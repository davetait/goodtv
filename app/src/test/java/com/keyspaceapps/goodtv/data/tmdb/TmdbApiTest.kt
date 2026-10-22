package com.keyspaceapps.goodtv.data.tmdb

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TmdbApiTest {
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()

    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()
    private fun ok(body: String) = MockResponse(code = 200, body = body)
    private fun api(credential: TmdbCredential = TmdbCredential.ApiKey("k3y"), maxAttempts: Int = 3) =
        TmdbApi(OkHttpClient(), credential, baseUrl = server.url("/3/"), maxAttempts = maxAttempts, sleep = { sleeps += it })

    private suspend fun expectFailure(reason: TmdbException.Reason, block: suspend () -> Unit) {
        try { block(); fail("expected $reason") } catch (e: TmdbException) { assertEquals(reason, e.reason) }
    }

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    @Test fun credentialKindIsDetectedAndNeverPrinted() {
        assertNull(TmdbCredential.from("  "))
        assertTrue(TmdbCredential.from("0123456789abcdef0123456789abcdef") is TmdbCredential.ApiKey)
        val jwt = TmdbCredential.from("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJl")
        assertTrue(jwt is TmdbCredential.Bearer)
        assertEquals("Bearer(***)", jwt.toString())
        assertEquals("ApiKey(***)", TmdbCredential.ApiKey("secret").toString())
    }

    @Test fun apiKeyGoesInTheQueryWithLanguage() = runTest {
        server.enqueue(ok(fixture("movie_603.json")))
        val m = api().movie(603, "de-DE")!!
        val req = server.takeRequest()
        assertEquals("/3/movie/603", req.url.encodedPath)
        assertEquals("k3y", req.url.queryParameter("api_key"))
        assertEquals("de-DE", req.url.queryParameter("language"))
        assertNull(req.headers["Authorization"])
        assertEquals("The Matrix", m.title)
        assertEquals(8.2, m.vote_average!!, 0.0)
        assertEquals(listOf("Action", "Science Fiction"), m.genres.map { it.name })
        assertEquals(136, m.runtime)
    }

    @Test fun readAccessTokenGoesInTheAuthorizationHeader() = runTest {
        server.enqueue(ok(fixture("tv_1399.json")))
        val t = api(TmdbCredential.Bearer("eyJ.a.b")).tv(1399, "en-US")!!
        val req = server.takeRequest()
        assertEquals("Bearer eyJ.a.b", req.headers["Authorization"])
        assertNull(req.url.queryParameter("api_key"))
        assertEquals("Game of Thrones", t.name)
        assertEquals(emptyList<Int>(), t.episode_run_time)
    }

    @Test fun searchSendsQueryAndYearAndToleratesNullsAndEmptyDates() = runTest {
        server.enqueue(ok(fixture("search_movie_matrix.json")))
        val results = api().searchMovie("The Matrix", 1999, "en-US")
        val req = server.takeRequest()
        assertEquals("/3/search/movie", req.url.encodedPath)
        assertEquals("The Matrix", req.url.queryParameter("query"))
        assertEquals("1999", req.url.queryParameter("year"))
        assertEquals("false", req.url.queryParameter("include_adult"))
        assertEquals(listOf(684731L, 603L), results.map { it.id })
        assertNull(results[0].release_date.tmdbYear())
        assertEquals(1999, results[1].release_date.tmdbYear())
    }

    @Test fun tvSearchUsesFirstAirDateYear() = runTest {
        server.enqueue(ok("""{"page":1,"results":[{"id":1399,"name":"Game of Thrones","first_air_date":"2011-04-17"}]}"""))
        api().searchTv("Game of Thrones", 2011, "en-US")
        val req = server.takeRequest()
        assertEquals("/3/search/tv", req.url.encodedPath)
        assertEquals("2011", req.url.queryParameter("first_air_date_year"))
        assertNull(req.url.queryParameter("year"))
    }

    @Test fun unknownIdIsNull() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"success":false,"status_code":34}"""))
        assertNull(api().movie(1, "en-US"))
    }

    @Test fun badKeyIsAnAuthError() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"success":false,"status_code":7}"""))
        expectFailure(TmdbException.Reason.AUTH) { api().movie(1, "en-US") }
    }

    @Test fun rateLimitIsRetriedAfterRetryAfter() = runTest {
        server.enqueue(MockResponse(code = 429, headers = Headers.headersOf("Retry-After", "2")))
        server.enqueue(MockResponse(code = 429))
        server.enqueue(ok(fixture("movie_603.json")))
        assertEquals(603L, api().movie(603, "en-US")!!.id)
        assertEquals(listOf(2_000L, 2_000L), sleeps)
        assertEquals(3, server.requestCount)
    }

    @Test fun rateLimitGivesUpAfterMaxAttempts() = runTest {
        repeat(2) { server.enqueue(MockResponse(code = 429)) }
        expectFailure(TmdbException.Reason.RATE_LIMITED) { api(maxAttempts = 2).movie(603, "en-US") }
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test fun serverErrorAndGarbageAreReported() = runTest {
        server.enqueue(MockResponse(code = 503))
        expectFailure(TmdbException.Reason.SERVER) { api().movie(1, "en-US") }
        server.enqueue(ok("<html>oops</html>"))
        expectFailure(TmdbException.Reason.BAD_RESPONSE) { api().movie(1, "en-US") }
    }

    @Test fun unreachableIsANetworkError() = runTest {
        val dead = api()
        server.close()
        expectFailure(TmdbException.Reason.NETWORK) { dead.movie(1, "en-US") }
    }

    @Test fun discoverAsksForSubscriptionStreamingByPopularity() = runTest {
        server.enqueue(ok("""{"page":2,"total_pages":53,"total_results":1057,"results":[{"id":1148365,"title":"Matchbox the Movie","release_date":"2026-10-05","popularity":311.2}]}"""))
        val page = api().discoverStreamingMovies("US", listOf(8, 9, 337), "2025-10-20", "2026-10-20", 50, 2, "en-US")
        val url = server.takeRequest().url
        assertEquals("/3/discover/movie", url.encodedPath)
        assertEquals("US", url.queryParameter("watch_region"))
        assertEquals("8|9|337", url.queryParameter("with_watch_providers"))
        assertEquals("flatrate", url.queryParameter("with_watch_monetization_types"))
        assertEquals("2025-10-20", url.queryParameter("primary_release_date.gte"))
        assertEquals("2026-10-20", url.queryParameter("primary_release_date.lte"))
        assertEquals("50", url.queryParameter("vote_count.gte"))
        assertEquals("popularity.desc", url.queryParameter("sort_by"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("false", url.queryParameter("include_adult"))
        assertEquals(53, page.total_pages)
        assertEquals(listOf(1148365L), page.results.map { it.id })
    }

    @Test fun discoverTvFiltersByAirDatePremiereAndGenre() = runTest {
        server.enqueue(ok("""{"page":1,"total_pages":17,"results":[{"id":136315,"name":"The Bear","first_air_date":"2022-06-23"}]}"""))
        val page = api().discoverStreamingTv("US", listOf(8, 1899), "2025-10-20", "2026-10-20", "2021-10-20", 50, listOf(10764, 10766), 1, "en-US")
        val url = server.takeRequest().url
        assertEquals("/3/discover/tv", url.encodedPath)
        assertEquals("8|1899", url.queryParameter("with_watch_providers"))
        assertEquals("flatrate", url.queryParameter("with_watch_monetization_types"))
        assertEquals("2025-10-20", url.queryParameter("air_date.gte"))
        assertEquals("2026-10-20", url.queryParameter("air_date.lte"))
        assertEquals("2021-10-20", url.queryParameter("first_air_date.gte"))
        assertEquals("10764,10766", url.queryParameter("without_genres"))
        assertEquals("popularity.desc", url.queryParameter("sort_by"))
        assertEquals(17, page.total_pages)
        assertEquals("The Bear", page.results.single().name)
    }

    @Test fun personCallsUseTheirPaths() = runTest {
        server.enqueue(ok(fixture("movie_credits_361743.json")))
        assertEquals(12, api().movieCredits(1, "en-US")!!.cast.size)
        assertEquals("/3/movie/1/credits", server.takeRequest().url.encodedPath)

        server.enqueue(ok(fixture("tv_people_1396.json")))
        val tv = api().tvPeople(2, "en-US")!!
        val tvUrl = server.takeRequest().url
        assertEquals("/3/tv/2", tvUrl.encodedPath)
        assertEquals("aggregate_credits", tvUrl.queryParameter("append_to_response"))
        assertEquals(listOf(66633L), tv.created_by.map { it.id })
        assertEquals("Walter White", tv.aggregate_credits!!.cast.first().roles.first().character)

        server.enqueue(ok(fixture("person_credits_500.json")))
        assertEquals(6, api().personCredits(3, "en-US")!!.cast.size)
        assertEquals("/3/person/3/combined_credits", server.takeRequest().url.encodedPath)

        server.enqueue(ok(fixture("search_person_tom_cruise.json")))
        val found = api().searchPerson("Tom Cruise", "en-US")
        val searchUrl = server.takeRequest().url
        assertEquals("/3/search/person", searchUrl.encodedPath)
        assertEquals("Tom Cruise", searchUrl.queryParameter("query"))
        assertEquals(listOf(500L, 777L), found.map { it.id })
    }

    @Test fun unknownPeopleDetailsAreNull() = runTest {
        repeat(3) { server.enqueue(MockResponse(code = 404, body = """{"success":false,"status_code":34}""")) }
        assertNull(api().movieCredits(1, "en-US"))
        assertNull(api().tvPeople(1, "en-US"))
        assertNull(api().personCredits(1, "en-US"))
    }
}
