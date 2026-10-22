package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.testing.InMemoryPeopleCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PeopleRepositoryTest {
    private val server = MockWebServer()
    private var now = 1_000_000_000_000L
    private val requests = mutableListOf<String>()
    private val routes = mutableMapOf<String, Pair<Int, String>>()

    private fun movie(id: String, tmdbId: Long) = Movie(1, id, null, "Movie $tmdbId", null, "mkv", null, null, tmdbId = tmdbId)
    private val cache = InMemoryPeopleCache(movies = listOf(361743L, 2006L, 2009L, 2002L).map { movie("m$it", it) })

    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()
    private fun repo(enabled: Boolean = true) = PeopleRepository(
        api = if (enabled) TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("k"), baseUrl = server.url("/3/"), sleep = {}) else null,
        cache = cache, language = { "en-US" }, clock = { now },
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val key = url.encodedPath + (url.queryParameter("query")?.let { "?$it" } ?: "")
                synchronized(requests) { requests += key }
                val (code, body) = routes[key] ?: (404 to "{}")
                return MockResponse(code = code, body = body)
            }
        }
        server.start()
        routes["/3/movie/361743/credits"] = 200 to fixture("movie_credits_361743.json")
        routes["/3/tv/1396"] = 200 to fixture("tv_people_1396.json")
        routes["/3/person/500/combined_credits"] = 200 to fixture("person_credits_500.json")
        routes["/3/search/person?Tom Cruise"] = 200 to fixture("search_person_tom_cruise.json")
        routes["/3/search/person?tom cru"] = 200 to fixture("search_person_tom_cruise.json")
    }

    @After fun tearDown() = server.close()

    @Test
    fun titlePeopleAreFetchedOnceThenCached() = runTest {
        val first = repo().titlePeople(TmdbKind.MOVIE, 361743).getOrThrow()
        assertEquals(10, first.size)
        assertEquals(first, repo().titlePeople(TmdbKind.MOVIE, 361743).getOrThrow())
        assertEquals(listOf("/3/movie/361743/credits"), requests)
        assertEquals("Vince Gilligan", repo().titlePeople(TmdbKind.TV, 1396).getOrThrow().first().name)
    }

    @Test
    fun anUnknownTitleCachesNobody() = runTest {
        assertEquals(emptyList<TmdbPerson>(), repo().titlePeople(TmdbKind.MOVIE, 9).getOrThrow())
        repo().titlePeople(TmdbKind.MOVIE, 9)
        assertEquals(1, requests.size)
    }

    @Test
    fun titlePeopleRefetchAfterTtl() = runTest {
        repo().titlePeople(TmdbKind.MOVIE, 361743)
        now += PeopleRepository.TITLE_PEOPLE_TTL_MS - 1
        repo().titlePeople(TmdbKind.MOVIE, 361743)
        assertEquals(1, requests.size)
        now += 2
        repo().titlePeople(TmdbKind.MOVIE, 361743)
        assertEquals(2, requests.size)
    }

    @Test
    fun titlePeopleFailureIsNotCached() = runTest {
        routes["/3/movie/361743/credits"] = 500 to "{}"
        assertTrue(repo().titlePeople(TmdbKind.MOVIE, 361743).isFailure)
        routes["/3/movie/361743/credits"] = 200 to fixture("movie_credits_361743.json")
        assertEquals(10, repo().titlePeople(TmdbKind.MOVIE, 361743).getOrThrow().size)
    }

    @Test
    fun ensureCreditsStoresOnlyCountedCredits() = runTest {
        assertTrue(repo().ensureCredits(500, "Tom Cruise").isSuccess)
        val titles = cache.observeTitles(500).first()
        // Library holds 361743, 2006, 2009 and 2002; the fixture counts the first three, not the guest cameo 2002.
        assertEquals(listOf(361743L, 2006L, 2009L).toSet(), titles.map { (it as PersonTitle.OfMovie).movie.tmdbId }.toSet())
    }

    @Test
    fun freshCreditsSkipTheNetwork() = runTest {
        repo().ensureCredits(500, "Tom Cruise")
        now += PeopleRepository.CREDITS_TTL_MS - 1
        assertTrue(repo().ensureCredits(500, "Tom Cruise").isSuccess)
        assertEquals(1, requests.size)
    }

    @Test
    fun staleCreditsAreUsedWhenTmdbFails() = runTest {
        repo().ensureCredits(500, "Tom Cruise")
        now += PeopleRepository.CREDITS_TTL_MS + 1
        routes["/3/person/500/combined_credits"] = 500 to "{}"
        assertTrue(repo().ensureCredits(500, "Tom Cruise").isSuccess)
        assertEquals(3, repo().observeTitles(500).first().size)
    }

    @Test
    fun noCreditsAndNoNetworkFails() = runTest {
        routes["/3/person/500/combined_credits"] = 500 to "{}"
        assertTrue(repo().ensureCredits(500, "Tom Cruise").isFailure)
    }

    @Test
    fun findPeopleUsesRulesAndMemo() = runTest {
        assertEquals(listOf(PersonMatch(500, "Tom Cruise")), repo().findPeople("Tom Cruise").getOrThrow())
        val r = repo()
        r.findPeople("Tom Cruise")
        assertEquals(listOf(PersonMatch(500, "Tom Cruise")), r.findPeople("Tom  Cruise").getOrThrow())
        assertEquals(2, requests.size)
        assertEquals(emptyList<PersonMatch>(), r.findPeople("tom cru").getOrThrow())
    }

    @Test
    fun disabledRepositoryReturnsEmptySuccess() = runTest {
        val r = repo(enabled = false)
        assertEquals(false, r.isEnabled)
        assertEquals(emptyList<TmdbPerson>(), r.titlePeople(TmdbKind.MOVIE, 1).getOrThrow())
        assertEquals(emptyList<PersonMatch>(), r.findPeople("Tom Cruise").getOrThrow())
        assertTrue(requests.isEmpty())
    }
}
