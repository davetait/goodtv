package com.keyspaceapps.goodtv.data.tmdb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * TMDB people for the library: who is on a title, which library titles a person counts for, and
 * name search. Every TMDB result is cached (see [PeopleCache]); temporary failures are returned as
 * failures and never cached. With no API configured ([api] null) the lists are empty successes.
 */
class PeopleRepository(
    private val api: TmdbApi?,
    private val cache: PeopleCache,
    private val language: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val isEnabled: Boolean get() = api != null

    private class Memo(val matches: List<PersonMatch>, val at: Long)
    private val memo = object : LinkedHashMap<String, Memo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Memo>?) = size > MEMO_SIZE
    }

    /**
     * success(empty) = TMDB lists nobody or no api; failure = temporary (never cached) with nothing
     * cached. A failed refresh of an expired entry returns the stale people.
     */
    suspend fun titlePeople(kind: TmdbKind, tmdbId: Long): Result<List<TmdbPerson>> {
        val api = api ?: return Result.success(emptyList())
        val cached = cache.titlePeople(kind, tmdbId)
        if (cached != null && clock() - cached.second < TITLE_PEOPLE_TTL_MS) return Result.success(cached.first)
        val fetched = call {
            val lang = language()
            val people = when (kind) {
                TmdbKind.MOVIE -> api.movieCredits(tmdbId, lang)?.let(PeopleRules::moviePeople)
                TmdbKind.TV -> api.tvPeople(tmdbId, lang)?.let(PeopleRules::tvPeople)
            }.orEmpty()
            cache.putTitlePeople(kind, tmdbId, people, clock())
            people
        }
        return if (fetched.isFailure && cached != null) Result.success(cached.first) else fetched
    }

    /**
     * Makes the person's counted credits available: fresh cache → success; else fetch; fetch fails
     * with any cache → success (stale); none → failure.
     */
    suspend fun ensureCredits(personId: Long, name: String): Result<Unit> {
        val cachedAt = cache.creditsFetchedAt(personId)
        if (cachedAt != null && clock() - cachedAt < CREDITS_TTL_MS) return Result.success(Unit)
        val fetched = api?.let { api ->
            call {
                val credits = api.personCredits(personId, language())?.let(PeopleRules::countedCredits).orEmpty()
                cache.putCredits(personId, name, credits, clock())
                cache.prune(clock() - TmdbRepository.MAX_CACHE_AGE_MS)
            }
        } ?: Result.failure(IllegalStateException("TMDB is not configured"))
        return if (fetched.isSuccess || cachedAt != null) Result.success(Unit) else Result.failure(fetched.exceptionOrNull()!!)
    }

    /** Confident matches for a typed name; failure when TMDB is unreachable. Memoised per normalized query. */
    suspend fun findPeople(query: String): Result<List<PersonMatch>> {
        val api = api ?: return Result.success(emptyList())
        val lang = language()
        val key = "$lang|${TitleCleaner.normalize(query)}"
        synchronized(memo) { memo[key] }?.takeIf { clock() - it.at < MEMO_TTL_MS }?.let { return Result.success(it.matches) }
        return call { PeopleRules.confidentPeople(query, api.searchPerson(query.trim(), lang)) }
            .onSuccess { matches -> synchronized(memo) { memo[key] = Memo(matches, clock()) } }
    }

    fun observeTitles(personId: Long): Flow<List<PersonTitle>> = cache.observeTitles(personId)

    private suspend inline fun <T> call(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: TmdbException) {
        Result.failure(e)
    }

    companion object {
        const val TITLE_PEOPLE_TTL_MS = 30L * 24 * 3_600_000
        const val CREDITS_TTL_MS = 7L * 24 * 3_600_000
        const val MEMO_TTL_MS = 3_600_000L
        private const val MEMO_SIZE = 200
    }
}
