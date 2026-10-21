package com.keyspaceapps.goodtv.data.subtitles

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenSubtitlesApiTest {
    private val server = MockWebServer()

    private fun ok(body: String) = MockResponse(code = 200, body = body)
    private fun api(login: OpenSubtitlesLogin? = null) =
        OpenSubtitlesApi(OkHttpClient(), "k3y", login, baseUrl = server.url("/api/v1/"))

    private suspend fun expectFailure(reason: OpenSubtitlesException.Reason, block: suspend () -> Unit): OpenSubtitlesException {
        try { block(); fail("expected $reason"); throw AssertionError() } catch (e: OpenSubtitlesException) { assertEquals(reason, e.reason); return e }
    }

    private val searchFixture = """
        {"total_count":1,"data":[{"id":"9213502","type":"subtitle","attributes":{
          "language":"en","download_count":4724,"hearing_impaired":false,"from_trusted":false,
          "ai_translated":false,"machine_translated":false,"release":"The Bear.S01E02",
          "files":[{"file_id":9213502,"file_name":"The Bear.S01E02"}]}}]}
    """.trimIndent()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    @Test fun movieSearchSendsKeyAgentAndTmdbId() = runTest {
        server.enqueue(ok("""{"total_count":0,"data":[]}"""))
        api().search(SubtitleQuery(603))
        val req = server.takeRequest()
        assertEquals("/api/v1/subtitles", req.url.encodedPath)
        assertEquals("k3y", req.headers["Api-Key"])
        assertEquals("GoodTV v0.1", req.headers["User-Agent"])
        assertEquals("en", req.url.queryParameter("languages"))
        assertEquals("603", req.url.queryParameter("tmdb_id"))
        assertNull(req.url.queryParameter("parent_tmdb_id"))
    }

    @Test fun episodeSearchUsesParentIdSeasonAndEpisode() = runTest {
        server.enqueue(ok("""{"total_count":0,"data":[]}"""))
        api().search(SubtitleQuery(136315, season = 1, episode = 2))
        val url = server.takeRequest().url
        assertEquals("136315", url.queryParameter("parent_tmdb_id"))
        assertEquals("1", url.queryParameter("season_number"))
        assertEquals("2", url.queryParameter("episode_number"))
        assertNull(url.queryParameter("tmdb_id"))
    }

    @Test fun searchMapsAttributes() = runTest {
        server.enqueue(ok(searchFixture))
        val found = api().search(SubtitleQuery(136315, 1, 2))
        assertEquals(
            listOf(OsSubtitle(9213502, "The Bear.S01E02", "The Bear.S01E02", "en", hearingImpaired = false, fromTrusted = false, aiTranslated = false, machineTranslated = false, downloadCount = 4724)),
            found,
        )
    }

    @Test fun downloadPostsFileIdAsSrtAndReturnsLink() = runTest {
        server.enqueue(ok("""{"link":"https://dl.example/x.srt","file_name":"x.srt","requests":1,"remaining":19}"""))
        assertEquals("https://dl.example/x.srt", api().downloadLink(9213502))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/download", req.url.encodedPath)
        val body = req.body!!.utf8()
        assertTrue(body, body.contains("\"file_id\":9213502") && body.contains("\"sub_format\":\"srt\""))
    }

    @Test fun quotaExhaustedIsQuotaWithResetTime() = runTest {
        server.enqueue(MockResponse(code = 406, body = """{"requests":21,"remaining":-1,"message":"You have downloaded your allowed 20 subtitles for 24h.","reset_time":"11 hours","reset_time_utc":"2026-10-22T00:00:00.000Z"}"""))
        val e = expectFailure(OpenSubtitlesException.Reason.QUOTA) { api().downloadLink(1) }
        assertEquals("2026-10-22T00:00:00.000Z", e.resetUtc)
    }

    @Test fun badKeyIsAuth() = runTest {
        server.enqueue(MockResponse(code = 401, body = "{}"))
        expectFailure(OpenSubtitlesException.Reason.AUTH) { api().search(SubtitleQuery(1)) }
        server.enqueue(MockResponse(code = 429, body = "{}"))
        expectFailure(OpenSubtitlesException.Reason.RATE_LIMITED) { api().search(SubtitleQuery(1)) }
        server.enqueue(MockResponse(code = 503, body = "{}"))
        expectFailure(OpenSubtitlesException.Reason.SERVER) { api().search(SubtitleQuery(1)) }
    }

    @Test fun loginOnceThenBearerToReturnedBaseUrl() = runTest {
        val host = "${server.hostName}:${server.port}"
        server.enqueue(ok("""{"token":"t0k","base_url":"$host","user":{"allowed_downloads":20}}"""))
        server.enqueue(ok("""{"total_count":0,"data":[]}"""))
        server.enqueue(ok("""{"total_count":0,"data":[]}"""))
        val api = OpenSubtitlesApi(OkHttpClient(), "k3y", OpenSubtitlesLogin("u", "p"), baseUrl = server.url("/api/v1/"), loginScheme = "http")
        api.search(SubtitleQuery(1))
        api.search(SubtitleQuery(2))
        val login = server.takeRequest()
        assertEquals("/api/v1/login", login.url.encodedPath)
        assertEquals("POST", login.method)
        assertTrue(login.body!!.utf8().contains("\"username\":\"u\""))
        val first = server.takeRequest()
        assertEquals("Bearer t0k", first.headers["Authorization"])
        assertEquals("/api/v1/subtitles", first.url.encodedPath)
        assertEquals("Bearer t0k", server.takeRequest().headers["Authorization"])
        assertEquals("logged in once", 3, server.requestCount)
    }

    @Test fun credentialsNeverPrinted() = assertEquals("OpenSubtitlesLogin(***)", OpenSubtitlesLogin("u", "p").toString())

    @Test fun downloadLinksMustBeHttps() = runTest {
        expectFailure(OpenSubtitlesException.Reason.BAD_RESPONSE) { api().fetchText("http://192.168.1.1/admin") }
        assertEquals("nothing was requested", 0, server.requestCount)
    }

    @Test fun oversizedResponsesAreRefused() = runTest {
        server.enqueue(ok("x".repeat(OpenSubtitlesApi.MAX_BYTES + 1)))
        val insecureOk = OpenSubtitlesApi(OkHttpClient(), "k3y", baseUrl = server.url("/api/v1/"), allowInsecureLinks = true)
        expectFailure(OpenSubtitlesException.Reason.BAD_RESPONSE) { insecureOk.fetchText(server.url("/big.srt").toString()) }
    }

    /** OkHttp runs network interceptors on every hop, so an https link that redirects to http is refused too. */
    @Test fun everyHopOfADownloadMustBeHttps() {
        server.enqueue(ok("secret"))
        val client = OkHttpClient.Builder().addNetworkInterceptor(HttpsOnly).build()
        try {
            client.newCall(okhttp3.Request.Builder().url(server.url("/lan")).build()).execute().close()
            fail("expected the plain-http hop to be refused")
        } catch (e: InsecureLinkException) {
            assertEquals(0, server.requestCount)
        }
    }
}

