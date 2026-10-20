package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PopularRepositoryTest {
    private val server = MockWebServer()
    private val store = Store()
    private var now = 1_792_454_400_000L // 2026-10-20T00:00Z
    private val pages = mutableMapOf<Int, String>()
    @Volatile private var failWith: Int? = null

    private class Store : PopularStore {
        val lists = mutableMapOf<String, Pair<List<Long>, Long>>()
        override suspend fun fetchedAt(listKey: String) = lists[listKey]?.second
        override suspend fun replace(listKey: String, tmdbIds: List<Long>, fetchedAt: Long) { lists.clear(); lists[listKey] = tmdbIds to fetchedAt }
        override fun observeMovies(listKey: String, limit: Int): Flow<List<Movie>> = flowOf(emptyList())
        override fun observeSeries(listKey: String, limit: Int): Flow<List<Series>> = flowOf(emptyList())
    }

    private fun page(totalPages: Int, vararg ids: Long) =
        """{"total_pages":$totalPages,"results":[${ids.joinToString(",") { """{"id":$it,"title":"T$it"}""" }}]}"""

    private fun repo(query: PopularQuery = PopularMoviesQuery) = PopularRepository(
        TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("k"), baseUrl = server.url("/3/"), maxAttempts = 1),
        store, query, language = { "en-US" }, clock = { now },
    )

    private val ids get() = store.lists[PopularMoviesQuery.listKey]?.first

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                failWith?.let { return MockResponse(code = it) }
                val p = request.url.queryParameter("page")!!.toInt()
                return MockResponse(code = 200, body = pages[p] ?: page(1))
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    @Test fun fetchesTheRankingAcrossPagesInOrderWithoutDuplicates() = runTest {
        pages[1] = page(3, 10, 11); pages[2] = page(3, 11, 12); pages[3] = page(3, 13)
        assertTrue(repo().refreshIfStale().isSuccess)
        assertEquals(listOf(10L, 11L, 12L, 13L), ids)
        assertEquals("stops at the last page", 3, server.requestCount)
        val first = server.takeRequest().url
        assertEquals("US", first.queryParameter("watch_region"))
        assertEquals("/3/discover/movie", first.encodedPath)
        assertEquals("8|9|337|350|1899|15", first.queryParameter("with_watch_providers"))
        assertEquals("2025-10-20", first.queryParameter("primary_release_date.gte"))
        assertEquals("2026-10-20", first.queryParameter("primary_release_date.lte"))
        assertEquals("50", first.queryParameter("vote_count.gte"))
    }

    @Test fun showsAreCurrentStreamingSeriesWithoutSoapsTalkNewsOrReality() = runTest {
        pages[1] = page(1, 136315, 108978)
        assertTrue(repo(PopularShowsQuery).refreshIfStale().isSuccess)
        assertEquals(listOf(136315L, 108978L), store.lists[PopularShowsQuery.listKey]?.first)
        val url = server.takeRequest().url
        assertEquals("/3/discover/tv", url.encodedPath)
        assertEquals("US", url.queryParameter("watch_region"))
        assertEquals("8|9|337|350|1899|15", url.queryParameter("with_watch_providers"))
        assertEquals("aired an episode in the last year", "2025-10-20", url.queryParameter("air_date.gte"))
        assertEquals("2026-10-20", url.queryParameter("air_date.lte"))
        assertEquals("premiered in the last five years", "2021-10-20", url.queryParameter("first_air_date.gte"))
        assertEquals("10763,10764,10766,10767", url.queryParameter("without_genres"))
        assertEquals("50", url.queryParameter("vote_count.gte"))
    }

    @Test fun asksForAtMostFivePages() = runTest {
        (1..9).forEach { pages[it] = page(53, it.toLong()) }
        repo().refreshIfStale()
        assertEquals(PopularRepository.PAGES, server.requestCount)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids)
    }

    @Test fun refreshesOncePerDay() = runTest {
        pages[1] = page(1, 10)
        val repo = repo()
        repo.refreshIfStale()
        pages[1] = page(1, 20)
        now += PopularRepository.REFRESH_MS - 1
        repo.refreshIfStale()
        assertEquals("still fresh", listOf(10L), ids)
        assertEquals(1, server.requestCount)
        now += 1
        repo.refreshIfStale()
        assertEquals(listOf(20L), ids)
    }

    @Test fun aFailedRefreshKeepsTheOldListAndBacksOff() = runTest {
        pages[1] = page(1, 10)
        val repo = repo()
        repo.refreshIfStale()
        now += PopularRepository.REFRESH_MS
        failWith = 503
        assertTrue(repo.refreshIfStale().isFailure)
        assertEquals(listOf(10L), ids)
        val asked = server.requestCount
        now += PopularRepository.FAILURE_BACKOFF_MS - 1
        repo.refreshIfStale()
        assertEquals("not asked again during the back-off", asked, server.requestCount)
        failWith = null
        pages[1] = page(1, 30)
        now += 1
        assertTrue(repo.refreshIfStale().isSuccess)
        assertEquals(listOf(30L), ids)
    }

    @Test fun withoutTmdbNothingIsFetched() = runTest {
        assertTrue(PopularRepository(null, store, PopularMoviesQuery, { "en-US" }).refreshIfStale().isSuccess)
        assertNull(ids)
    }
}
