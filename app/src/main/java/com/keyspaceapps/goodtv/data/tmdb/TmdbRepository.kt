package com.keyspaceapps.goodtv.data.tmdb

import kotlinx.coroutines.CancellationException

enum class TmdbKind { MOVIE, TV }

/** What we keep from TMDB for one title. [rating] is TMDB's 0–10 vote average; weigh it by [voteCount]. */
data class TmdbMeta(
    val kind: TmdbKind,
    val tmdbId: Long,
    val title: String,
    val overview: String?,
    val rating: Double?,
    val voteCount: Int,
    val year: Int?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val language: String,
    val fetchedAt: Long,
) {
    /** [rating] only when enough people voted for it to mean something; a lone 10/10 is noise. */
    val reliableRating: Double? get() = rating?.takeIf { voteCount >= MIN_VOTES }

    companion object {
        const val MIN_VOTES = 10
    }
}

/** Storage for [TmdbRepository]; Room in the app, in-memory in tests. */
interface TmdbCache {
    suspend fun meta(kind: TmdbKind, tmdbId: Long): TmdbMeta?
    suspend fun putMeta(meta: TmdbMeta)
    /** null = never searched; [TitleMatch.tmdbId] null = searched, no confident match. */
    suspend fun titleMatch(kind: TmdbKind, queryKey: String): TitleMatch?
    suspend fun putTitleMatch(kind: TmdbKind, queryKey: String, match: TitleMatch)
    suspend fun prune(before: Long)
}

data class TitleMatch(val tmdbId: Long?, val fetchedAt: Long)

/**
 * TMDB metadata for catalog titles, cached. Lookup order: fresh cache, then the provider's TMDB id,
 * then a title search (only when the name looks like a film or show). A search result is accepted
 * only when it clearly matches; a wrong rating is worse than none.
 *
 * [lookup] returns success(null) for "TMDB doesn't know this title" (cached, not retried for
 * [NO_MATCH_TTL_MS]) and failure for anything temporary (network, rate limit, bad key), which is
 * never cached. With no API configured ([api] null) every lookup is success(null).
 */
class TmdbRepository(
    private val api: TmdbApi?,
    private val cache: TmdbCache,
    private val language: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val isEnabled: Boolean get() = api != null

    // Process-wide memo over the Room cache so posters scrolled past again resolve instantly.
    // Failures are remembered briefly so a dead network isn't asked again by every poster.
    private class Memo(val result: Result<TmdbMeta?>, val at: Long)
    private val memo = object : LinkedHashMap<String, Memo>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Memo>?) = size > MEMO_SIZE
    }

    private fun memoKey(kind: TmdbKind, tmdbId: Long?, rawTitle: String) =
        "${language()}|$kind|${tmdbId?.takeIf { it > 0 } ?: ("t:" + rawTitle)}"

    /** The remembered answer if this lookup already ran in this process: null = unknown, else the result. */
    fun peek(kind: TmdbKind, tmdbId: Long?, rawTitle: String): Result<TmdbMeta?>? = synchronized(memo) {
        memo[memoKey(kind, tmdbId, rawTitle)]?.takeIf { clock() - it.at < if (it.result.isSuccess) MEMO_TTL_MS else FAILURE_BACKOFF_MS }?.result
    }

    suspend fun lookup(kind: TmdbKind, tmdbId: Long?, rawTitle: String): Result<TmdbMeta?> {
        peek(kind, tmdbId, rawTitle)?.let { return it }
        val result = lookupUncached(kind, tmdbId, rawTitle)
        synchronized(memo) { memo[memoKey(kind, tmdbId, rawTitle)] = Memo(result, clock()) }
        return result
    }

    private suspend fun lookupUncached(kind: TmdbKind, tmdbId: Long?, rawTitle: String): Result<TmdbMeta?> {
        val api = api ?: return Result.success(null)
        return try {
            val lang = language()
            val id = tmdbId?.takeIf { it > 0 } ?: findId(api, kind, rawTitle, lang) ?: return Result.success(null)
            Result.success(details(api, kind, id, lang))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TmdbException) {
            Result.failure(e)
        }
    }

    /** Drops cache rows older than TMDB allows; call occasionally (e.g. after a sync). */
    suspend fun prune() = cache.prune(clock() - MAX_CACHE_AGE_MS)

    private suspend fun details(api: TmdbApi, kind: TmdbKind, id: Long, lang: String): TmdbMeta? {
        cache.meta(kind, id)?.takeIf { it.language == lang && clock() - it.fetchedAt < META_TTL_MS }?.let { return it }
        val now = clock()
        val meta = when (kind) {
            TmdbKind.MOVIE -> api.movie(id, lang)?.let { m ->
                TmdbMeta(
                    kind, m.id, m.title.ifBlank { m.original_title.orEmpty() }, m.overview?.takeIf { it.isNotBlank() },
                    m.vote_average?.takeIf { m.vote_count > 0 }, m.vote_count, m.release_date.tmdbYear(), m.genres.map { it.name },
                    m.runtime?.takeIf { it > 0 }, TmdbImages.url(m.poster_path, TmdbImages.POSTER), TmdbImages.url(m.backdrop_path, TmdbImages.BACKDROP),
                    lang, now,
                )
            }
            TmdbKind.TV -> api.tv(id, lang)?.let { t ->
                TmdbMeta(
                    kind, t.id, t.name.ifBlank { t.original_name.orEmpty() }, t.overview?.takeIf { it.isNotBlank() },
                    t.vote_average?.takeIf { t.vote_count > 0 }, t.vote_count, t.first_air_date.tmdbYear(), t.genres.map { it.name },
                    t.episode_run_time.firstOrNull()?.takeIf { it > 0 }, TmdbImages.url(t.poster_path, TmdbImages.POSTER),
                    TmdbImages.url(t.backdrop_path, TmdbImages.BACKDROP), lang, now,
                )
            }
        }
        meta?.let { cache.putMeta(it) }
        return meta
    }

    private suspend fun findId(api: TmdbApi, kind: TmdbKind, rawTitle: String, lang: String): Long? {
        val clean = TitleCleaner.clean(rawTitle)
        if (!clean.searchable) return null
        val key = "${TitleCleaner.normalize(clean.title)}|${clean.year ?: ""}"
        cache.titleMatch(kind, key)?.let { m ->
            val ttl = if (m.tmdbId == null) NO_MATCH_TTL_MS else META_TTL_MS
            if (clock() - m.fetchedAt < ttl) return m.tmdbId
        }
        suspend fun search(year: Int?): List<Candidate> = when (kind) {
            TmdbKind.MOVIE -> api.searchMovie(clean.title, year, lang).map { Candidate(it.id, listOfNotNull(it.title, it.original_title), it.release_date.tmdbYear()) }
            TmdbKind.TV -> api.searchTv(clean.title, year, lang).map { Candidate(it.id, listOfNotNull(it.name, it.original_name), it.first_air_date.tmdbYear()) }
        }
        // TMDB's year filter is exact; a provider year off by one returns nothing, so widen once.
        val candidates = search(clean.year).ifEmpty { if (clean.year != null) search(null) else emptyList() }
        val id = pickMatch(clean, candidates)
        cache.putTitleMatch(kind, key, TitleMatch(id, clock()))
        return id
    }

    internal data class Candidate(val id: Long, val titles: List<String>, val year: Int?)

    companion object {
        /** Refresh cached details monthly; ratings drift slowly. */
        const val META_TTL_MS = 30L * 24 * 3_600_000
        /** Retry titles TMDB didn't know after a week (new releases get added). */
        const val NO_MATCH_TTL_MS = 7L * 24 * 3_600_000
        /** TMDB's terms: no caching beyond 6 months. */
        const val MAX_CACHE_AGE_MS = 180L * 24 * 3_600_000
        /** After a failed lookup, wait this long before trying the same title again. */
        const val FAILURE_BACKOFF_MS = 2L * 60_000
        /** In-memory answers are re-read from the Room cache after this; the TV process can live for days. */
        const val MEMO_TTL_MS = 3_600_000L
        private const val MEMO_SIZE = 5_000

        /**
         * With a year: the first result (TMDB orders by relevance) released within a year of it, if
         * one of the top results' titles matches exactly; else the first within a year at all.
         * Without a year: only an exact title match, and only when it is the sole one; a bare "Domino"
         * has several, and guessing would show the wrong rating. Otherwise no match.
         */
        internal fun pickMatch(clean: CleanTitle, candidates: List<Candidate>): Long? {
            val want = TitleCleaner.normalize(clean.title)
            val top = candidates.take(10)
            fun exact(c: Candidate) = c.titles.any { TitleCleaner.normalize(it) == want }
            fun yearOk(c: Candidate) = clean.year != null && c.year != null && kotlin.math.abs(c.year - clean.year) <= 1
            return if (clean.year != null) {
                (top.firstOrNull { exact(it) && yearOk(it) } ?: top.firstOrNull { yearOk(it) })?.id
            } else {
                top.filter { exact(it) }.singleOrNull()?.id
            }
        }
    }
}
