package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.domain.Movie
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Home's Popular Movies asks TMDB for: recent, well-voted films on the big US streaming services. */
object PopularMoviesQuery {
    const val REGION = "US"
    /** TMDB watch-provider ids for [REGION]: Netflix, Prime Video, Disney+, Apple TV+, Max, Hulu. */
    val PROVIDERS = listOf(8, 9, 337, 350, 1899, 15)
    const val RELEASE_WINDOW_DAYS = 365L
    /** Drops films a handful of people rated, which otherwise rank on raw popularity. */
    const val MIN_VOTES = 50
    /** 100 ranked films, so the row still fills when some aren't in the library or are hidden. */
    const val PAGES = 5

    /** Identifies the stored list; changing the query changes the key, so the old list is refetched. */
    val listKey: String = "movie:$REGION:${PROVIDERS.joinToString(",")}:d$RELEASE_WINDOW_DAYS:v$MIN_VOTES"
}

/** Storage for [PopularMoviesRepository]; Room in the app, in-memory in tests. */
interface PopularMoviesStore {
    /** When [listKey] was last stored, or null if never. */
    suspend fun fetchedAt(listKey: String): Long?
    /** Replaces every stored list with [tmdbIds] (most popular first) under [listKey]. */
    suspend fun replace(listKey: String, tmdbIds: List<Long>, fetchedAt: Long)
    /**
     * One library copy per stored id, in rank order: English, then untagged, then any language.
     * Ids with no copy in an enabled source and visible category are skipped.
     */
    fun observeMovies(listKey: String, limit: Int): Flow<List<Movie>>
}

/**
 * Home's Popular Movies: TMDB's most popular recent streaming films ([PopularMoviesQuery]) that the
 * library has. The ranked TMDB ids are fetched at most once per [REFRESH_MS]; the row itself is a
 * live join against the catalog, so syncs and hidden categories apply immediately. A failed refresh
 * keeps the previous list and isn't retried for [FAILURE_BACKOFF_MS].
 */
class PopularMoviesRepository(
    private val api: TmdbApi?,
    private val store: PopularMoviesStore,
    private val language: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    @Volatile private var failedAt: Long? = null

    fun observe(limit: Int = ROW_SIZE): Flow<List<Movie>> = store.observeMovies(PopularMoviesQuery.listKey, limit)

    /** Fetches a new list when the stored one is a day old or missing; success(Unit) when nothing was needed. */
    suspend fun refreshIfStale(): Result<Unit> = lock.withLock {
        val api = api ?: return Result.success(Unit)
        val now = clock()
        val key = PopularMoviesQuery.listKey
        store.fetchedAt(key)?.let { if (now - it < REFRESH_MS) return Result.success(Unit) }
        failedAt?.let { if (now - it < FAILURE_BACKOFF_MS) return Result.success(Unit) }
        try {
            store.replace(key, fetch(api, now), now)
            failedAt = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TmdbException) {
            failedAt = now
            Result.failure(e)
        }
    }

    private suspend fun fetch(api: TmdbApi, now: Long): List<Long> {
        val today = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate()
        val from = today.minusDays(PopularMoviesQuery.RELEASE_WINDOW_DAYS).toString()
        val ids = LinkedHashSet<Long>()
        for (page in 1..PopularMoviesQuery.PAGES) {
            val result = api.discoverStreamingMovies(
                PopularMoviesQuery.REGION, PopularMoviesQuery.PROVIDERS, from, today.toString(), PopularMoviesQuery.MIN_VOTES, page, language(),
            )
            result.results.forEach { ids += it.id }
            if (page >= result.total_pages) break
        }
        return ids.toList()
    }

    companion object {
        const val ROW_SIZE = 20
        const val REFRESH_MS = 24 * 60 * 60 * 1000L
        const val FAILURE_BACKOFF_MS = 15 * 60 * 1000L
    }
}
