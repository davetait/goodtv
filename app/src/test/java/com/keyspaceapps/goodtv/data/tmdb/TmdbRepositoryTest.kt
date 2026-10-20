package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.testing.InMemoryTmdbCache
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

class TmdbRepositoryTest {
    private val server = MockWebServer()
    private val cache = InMemoryTmdbCache()
    private var now = 1_000_000_000_000L
    private var lang = "en-US"
    private val requests = mutableListOf<String>()
    /** path → (code, body); search paths keyed with their query, e.g. "/3/search/movie?Heat|1995". */
    private val routes = mutableMapOf<String, Pair<Int, String>>()

    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()
    private fun repo(enabled: Boolean = true) = TmdbRepository(
        api = if (enabled) TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("k"), baseUrl = server.url("/3/"), sleep = {}) else null,
        cache = cache, language = { lang }, clock = { now },
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val key = url.encodedPath + (url.queryParameter("query")?.let { "?$it|${url.queryParameter("year") ?: url.queryParameter("first_air_date_year") ?: ""}" } ?: "")
                synchronized(requests) { requests += key }
                val (code, body) = routes[key] ?: (404 to "{}")
                return MockResponse(code = code, body = body)
            }
        }
        server.start()
        routes["/3/movie/603"] = 200 to fixture("movie_603.json")
        routes["/3/tv/1399"] = 200 to fixture("tv_1399.json")
    }

    @After fun tearDown() = server.close()

    @Test
    fun providerIdGoesStraightToDetailsAndIsCached() = runTest {
        val meta = repo().lookup(TmdbKind.MOVIE, 603, "4K-AR - The Matrix (1999)").getOrThrow()!!
        assertEquals("The Matrix", meta.title)
        assertEquals(8.2, meta.rating!!, 0.0)
        assertEquals(26280, meta.voteCount)
        assertEquals(1999, meta.year)
        assertEquals(136, meta.runtimeMinutes)
        assertEquals("https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg", meta.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/ncEsesgOJDNrTUED89hYbA117wo.jpg", meta.backdropUrl)

        repo().lookup(TmdbKind.MOVIE, 603, "anything")
        assertEquals(listOf("/3/movie/603"), requests)
    }

    @Test
    fun staleOrOtherLanguageCacheIsRefetched() = runTest {
        repo().lookup(TmdbKind.TV, 1399, "x")
        now += TmdbRepository.META_TTL_MS + 1
        repo().lookup(TmdbKind.TV, 1399, "x")
        lang = "de-DE"
        repo().lookup(TmdbKind.TV, 1399, "x")
        assertEquals(3, requests.size)
    }

    @Test
    fun tvRuntimeIsNullWhenTmdbListsNone() = runTest {
        val meta = repo().lookup(TmdbKind.TV, 1399, "x").getOrThrow()!!
        assertNull(meta.runtimeMinutes)
        assertEquals(listOf("Sci-Fi & Fantasy", "Drama"), meta.genres)
    }

    @Test
    fun withoutIdTheCleanedTitleIsSearchedAndTheExactYearMatchWins() = runTest {
        routes["/3/search/movie?The Matrix|1999"] = 200 to fixture("search_movie_matrix.json")
        val meta = repo().lookup(TmdbKind.MOVIE, null, "4K-AR - The Matrix (1999)").getOrThrow()!!
        assertEquals(603L, meta.tmdbId)

        repo().lookup(TmdbKind.MOVIE, null, "EN - The Matrix (1999)")
        assertEquals(listOf("/3/search/movie?The Matrix|1999", "/3/movie/603"), requests)
    }

    @Test
    fun yearOffByOneWidensTheSearchOnce() = runTest {
        routes["/3/search/movie?The Matrix|2000"] = 200 to """{"results":[]}"""
        routes["/3/search/movie?The Matrix|"] = 200 to fixture("search_movie_matrix.json")
        assertEquals(603L, repo().lookup(TmdbKind.MOVIE, null, "The Matrix (2000)").getOrThrow()!!.tmdbId)
    }

    @Test
    fun noConfidentMatchIsCachedForAWeek() = runTest {
        routes["/3/search/movie?Obscure Thing|"] = 200 to """{"results":[{"id":9,"title":"Something Else","release_date":"2001-01-01"}]}"""
        assertNull(repo().lookup(TmdbKind.MOVIE, null, "Obscure Thing").getOrThrow())
        assertNull(repo().lookup(TmdbKind.MOVIE, null, "Obscure Thing").getOrThrow())
        assertEquals(1, requests.size)
        now += TmdbRepository.NO_MATCH_TTL_MS + 1
        repo().lookup(TmdbKind.MOVIE, null, "Obscure Thing")
        assertEquals(2, requests.size)
    }

    @Test
    fun failuresAreReturnedAndNotCached() = runTest {
        routes["/3/movie/603"] = 503 to ""
        val result = repo().lookup(TmdbKind.MOVIE, 603, "x")
        assertTrue(result.exceptionOrNull() is TmdbException)
        routes["/3/movie/603"] = 200 to fixture("movie_603.json")
        val r = repo()
        assertTrue(r.lookup(TmdbKind.MOVIE, 603, "x").isSuccess) // fresh repository: nothing remembered
        assertEquals(603L, r.lookup(TmdbKind.MOVIE, 603, "x").getOrThrow()!!.tmdbId)
    }

    @Test
    fun oneRepositoryRemembersAnswersAndBacksOffAfterAFailure() = runTest {
        val r = repo()
        r.lookup(TmdbKind.MOVIE, 603, "x")
        assertEquals(603L, r.peek(TmdbKind.MOVIE, 603, "x")!!.getOrThrow()!!.tmdbId)
        r.lookup(TmdbKind.MOVIE, 603, "x")
        assertEquals(1, requests.size)

        routes["/3/tv/1399"] = 503 to ""
        assertTrue(r.lookup(TmdbKind.TV, 1399, "x").isFailure)
        routes["/3/tv/1399"] = 200 to fixture("tv_1399.json")
        assertTrue("still backing off", r.lookup(TmdbKind.TV, 1399, "x").isFailure)
        assertEquals(2, requests.size)
        now += TmdbRepository.FAILURE_BACKOFF_MS + 1
        assertEquals(1399L, r.lookup(TmdbKind.TV, 1399, "x").getOrThrow()!!.tmdbId)
        assertNull(r.peek(TmdbKind.MOVIE, 1, "never asked"))
    }

    @Test
    fun reliableRatingNeedsEnoughVotes() = runTest {
        val m = repo().lookup(TmdbKind.MOVIE, 603, "x").getOrThrow()!!
        assertEquals(8.2, m.reliableRating!!, 0.0)
        assertNull(m.copy(voteCount = TmdbMeta.MIN_VOTES - 1).reliableRating)
    }

    @Test
    fun sportsEventsAndDisabledTmdbMakeNoRequests() = runTest {
        assertNull(repo().lookup(TmdbKind.MOVIE, null, "SOC - Fulham - Arsenal 08.12.2024").getOrThrow())
        assertNull(repo(enabled = false).lookup(TmdbKind.MOVIE, 603, "The Matrix").getOrThrow())
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun pruneDropsRowsPastTmdbsSixMonthLimit() = runTest {
        repo().lookup(TmdbKind.MOVIE, 603, "x")
        now += TmdbRepository.MAX_CACHE_AGE_MS + 1
        repo().prune()
        assertNull(cache.meta(TmdbKind.MOVIE, 603))
    }

    @Test
    fun pickMatchRules() {
        val c = { id: Long, title: String, year: Int? -> TmdbRepository.Candidate(id, listOf(title), year) }
        val list = listOf(c(1, "Heat Wave", 1995), c(2, "Heat", 1995), c(3, "Heat", 1986))
        assertEquals(2L, TmdbRepository.pickMatch(CleanTitle("Heat", 1995, true), list))
        assertEquals(1L, TmdbRepository.pickMatch(CleanTitle("Heat", 1996, true), listOf(c(1, "Heat Wave", 1995))))
        assertNull(TmdbRepository.pickMatch(CleanTitle("Heat", 2010, true), list))
        // Without a year an exact title must be the only one: "Heat" appears twice, so no guess.
        assertNull(TmdbRepository.pickMatch(CleanTitle("heat", null, true), list))
        assertEquals(2L, TmdbRepository.pickMatch(CleanTitle("heat", null, true), list.take(2)))
        assertNull(TmdbRepository.pickMatch(CleanTitle("Heat 2", null, true), list))
    }
}
