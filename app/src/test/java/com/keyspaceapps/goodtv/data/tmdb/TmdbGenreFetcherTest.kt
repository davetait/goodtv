package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.testing.InMemoryTmdbCache
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TmdbGenreFetcherTest {
    private val server = MockWebServer()
    private val requested = mutableListOf<Long>()
    private val responses = mutableMapOf<Long, Pair<Int, String>>()
    private val store = FakeGenreStore()
    private val cache = InMemoryTmdbCache()
    private val dispatcher = StandardTestDispatcher()

    private fun movie(id: Long, vararg genreIds: Int) =
        200 to """{"id":$id,"title":"M$id","vote_average":7.0,"vote_count":100,"genres":[${genreIds.joinToString(",") { """{"id":$it,"name":"x"}""" }}]}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = request.url.pathSegments.last().toLong()
                synchronized(requested) { requested += id }
                val (code, body) = responses[id] ?: (404 to "{}")
                return MockResponse(code = code, body = body)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun TestScope.fetcher(batchSize: Int = 100) = TmdbGenreFetcher(
        TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("k"), baseUrl = server.url("/3/"), io = dispatcher, maxAttempts = 1, sleep = {}),
        // The test scope itself, not backgroundScope: advanceUntilIdle() doesn't run background work.
        store, cache, this, language = { "en-US" }, clock = { 1_000L }, minIntervalMs = 0, workers = 2, batchSize = batchSize, sleep = {},
    )

    @Test
    fun fillsGenresForPendingIdsAndWarmsTheRatingCache() = runTest(dispatcher) {
        store.ids = listOf(603L, 604L, 605L)
        responses[603] = movie(603, 28, 878)
        responses[604] = movie(604, 18)
        // 605: unknown to TMDB (404) → recorded with no genres so it isn't asked again.
        val f = fetcher()
        f.start(); advanceUntilIdle()
        assertEquals(mapOf(603L to setOf(Genre.ACTION, Genre.SCIFI), 604L to setOf(Genre.DRAMA), 605L to emptySet<Genre>()), store.saved)
        assertEquals(TmdbGenreFetcher.State.Idle, f.state.value)
        assertNotNull(cache.meta(TmdbKind.MOVIE, 603))

        requested.clear()
        f.start(); advanceUntilIdle()
        assertEquals("nothing left to fetch", emptyList<Long>(), requested)
    }

    @Test
    fun savesInBatchesAndReportsProgress() = runTest(dispatcher) {
        store.ids = (1L..5L).toList()
        store.alreadyDone = 5
        (1L..5L).forEach { responses[it] = movie(it, 35) }
        val f = fetcher(batchSize = 2)
        f.start(); advanceUntilIdle()
        assertTrue("saved in several batches: ${store.saveCalls}", store.saveCalls >= 3)
        assertEquals(5, store.saved.size)
    }

    @Test
    fun rejectedKeyStopsTheRun() = runTest(dispatcher) {
        store.ids = listOf(1L, 2L, 3L)
        (1L..3L).forEach { responses[it] = 401 to "{}" }
        val f = fetcher()
        f.start(); advanceUntilIdle()
        val state = f.state.value
        assertTrue("$state", state is TmdbGenreFetcher.State.Stopped)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun serverTroubleEndsQuietlyAndTheNextStartContinues() = runTest(dispatcher) {
        store.ids = listOf(1L)
        responses[1] = 503 to ""
        val f = fetcher()
        f.start(); advanceUntilIdle()
        assertEquals(TmdbGenreFetcher.State.Idle, f.state.value)
        assertTrue(store.saved.isEmpty())

        responses[1] = movie(1, 27)
        f.start(); advanceUntilIdle()
        assertEquals(mapOf(1L to setOf(Genre.HORROR)), store.saved)
    }

    @Test
    fun withoutTmdbNothingHappens() = runTest(dispatcher) {
        store.ids = listOf(1L)
        val f = TmdbGenreFetcher(null, store, cache, this, language = { "en-US" })
        f.start(); advanceUntilIdle()
        assertTrue(requested.isEmpty())
        assertEquals(TmdbGenreFetcher.State.Idle, f.state.value)
    }
}

private class FakeGenreStore : TmdbGenreStore {
    var ids: List<Long> = emptyList()
    var alreadyDone = 0
    val saved = mutableMapOf<Long, Set<Genre>>()
    var saveCalls = 0
    override suspend fun pendingMovieTmdbIds(before: Long) = ids.filterNot { it in saved }
    override suspend fun movieTmdbIdCount() = ids.size + alreadyDone
    override suspend fun saveMovieGenres(genres: Map<Long, Set<Genre>>, fetchedAt: Long) { saveCalls++; saved += genres }
}
