package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The big US subscription streaming services, as TMDB watch-provider ids (they differ by region). */
object StreamingServices {
    const val REGION = "US"
    /** Netflix, Prime Video, Disney+, Apple TV+, Max, Hulu. */
    val PROVIDERS = listOf(8, 9, 337, 350, 1899, 15)
}

/** One of Home's popular rows: which TMDB Discover ranking to fetch. */
interface PopularQuery {
    /** Names the stored list; starts with the kind ("movie:", "tv:"). A changed query is a new key, so it's refetched. */
    val listKey: String
    /** One page (20) of ranked TMDB ids as of [today], and the number of pages TMDB has. */
    suspend fun page(api: TmdbApi, today: LocalDate, page: Int, language: String): Pair<List<Long>, Int>
}

/** Recent, well-voted films on the big streaming services. */
object PopularMoviesQuery : PopularQuery {
    const val RELEASE_WINDOW_DAYS = 365L
    /** Drops films a handful of people rated, which otherwise rank on raw popularity. */
    const val MIN_VOTES = 50

    override val listKey = "movie:${StreamingServices.REGION}:${StreamingServices.PROVIDERS.joinToString(",")}:d$RELEASE_WINDOW_DAYS:v$MIN_VOTES"

    override suspend fun page(api: TmdbApi, today: LocalDate, page: Int, language: String): Pair<List<Long>, Int> =
        api.discoverStreamingMovies(
            StreamingServices.REGION, StreamingServices.PROVIDERS, today.minusDays(RELEASE_WINDOW_DAYS).toString(), today.toString(), MIN_VOTES, page, language,
        ).let { r -> r.results.map { it.id } to r.total_pages }
}

/**
 * Current shows on the big streaming services: an episode in the last year and a premiere in the
 * last five, so new seasons of recent hits count but decades-old network procedurals (whose
 * syndication inflates their popularity) don't. Soaps, talk, news and reality are left out.
 */
object PopularShowsQuery : PopularQuery {
    const val AIRED_WINDOW_DAYS = 365L
    const val PREMIERED_WINDOW_YEARS = 5L
    const val MIN_VOTES = 50
    /** TMDB TV genres: News, Reality, Soap, Talk. */
    val EXCLUDED_GENRES = listOf(10763, 10764, 10766, 10767)

    override val listKey =
        "tv:${StreamingServices.REGION}:${StreamingServices.PROVIDERS.joinToString(",")}:a$AIRED_WINDOW_DAYS:p$PREMIERED_WINDOW_YEARS:v$MIN_VOTES:x${EXCLUDED_GENRES.joinToString(",")}"

    override suspend fun page(api: TmdbApi, today: LocalDate, page: Int, language: String): Pair<List<Long>, Int> =
        api.discoverStreamingTv(
            StreamingServices.REGION, StreamingServices.PROVIDERS,
            airedFrom = today.minusDays(AIRED_WINDOW_DAYS).toString(), airedTo = today.toString(),
            premieredFrom = today.minusYears(PREMIERED_WINDOW_YEARS).toString(),
            minVotes = MIN_VOTES, withoutGenres = EXCLUDED_GENRES, page = page, language = language,
        ).let { r -> r.results.map { it.id } to r.total_pages }
}

/** Storage for [PopularRepository]; Room in the app, in-memory in tests. */
interface PopularStore {
    /** When [listKey] was last stored, or null if never. */
    suspend fun fetchedAt(listKey: String): Long?
    /** Stores [tmdbIds] (most popular first) under [listKey], replacing every list of the same kind. */
    suspend fun replace(listKey: String, tmdbIds: List<Long>, fetchedAt: Long)
    /**
     * One library copy per stored id, in rank order: English, then untagged, then any language.
     * Ids with no copy in an enabled source and visible category are skipped.
     */
    fun observeMovies(listKey: String, limit: Int): Flow<List<Movie>>
    fun observeSeries(listKey: String, limit: Int): Flow<List<Series>>
}

/**
 * Keeps one popular ranking ([query]) fresh: fetched at most once per [REFRESH_MS], up to [PAGES]
 * pages so a row still fills when some titles aren't in the library or are hidden. The rows
 * themselves are a live join against the catalog ([PopularStore]), so syncs and hidden categories
 * apply immediately. A failed refresh keeps the previous list and isn't retried for
 * [FAILURE_BACKOFF_MS].
 */
class PopularRepository(
    private val api: TmdbApi?,
    private val store: PopularStore,
    val query: PopularQuery,
    private val language: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    @Volatile private var failedAt: Long? = null

    /** Fetches a new ranking when the stored one is a day old or missing; success(Unit) when nothing was needed. */
    suspend fun refreshIfStale(): Result<Unit> = lock.withLock {
        val api = api ?: return Result.success(Unit)
        val now = clock()
        store.fetchedAt(query.listKey)?.let { if (now - it < REFRESH_MS) return Result.success(Unit) }
        failedAt?.let { if (now - it < FAILURE_BACKOFF_MS) return Result.success(Unit) }
        try {
            store.replace(query.listKey, fetch(api, now), now)
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
        val ids = LinkedHashSet<Long>()
        for (page in 1..PAGES) {
            val (pageIds, totalPages) = query.page(api, today, page, language())
            ids += pageIds
            if (page >= totalPages) break
        }
        return ids.toList()
    }

    companion object {
        const val ROW_SIZE = 20
        /** 100 ranked titles. */
        const val PAGES = 5
        const val REFRESH_MS = 24 * 60 * 60 * 1000L
        const val FAILURE_BACKOFF_MS = 15 * 60 * 1000L
    }
}
