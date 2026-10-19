package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.PlayableRef
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

class XtreamChannelSourceTest {
    private val server = MockWebServer()
    private lateinit var source: XtreamChannelSource
    private lateinit var cfg: SourceConfig.Xtream

    private fun fixture(name: String) = javaClass.getResource("/xtream/$name")!!.readText()
    private fun json(body: String, code: Int = 200) = MockResponse(code = code, body = body)

    @Before
    fun setUp() {
        server.start()
        cfg = SourceConfig.Xtream(server = server.url("/").toString(), username = "u", password = "p")
        source = XtreamChannelSource(XtreamApi(OkHttpClient(), XtreamJson.instance))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun typeIsXtream() = assertEquals(SourceType.XTREAM, source.type)

    @Test
    fun validateOkReturnsInfo() = runTest {
        server.enqueue(json(fixture("auth_ok.json")))
        val info = source.validate(cfg).getOrThrow()
        assertEquals(listOf("ts", "m3u8"), info.allowedOutputFormats)
        assertEquals("Active", info.status)
        assertEquals(1735689600L * 1000, info.expiresAt)
        assertEquals(2, info.maxConnections)
        val req = server.takeRequest()
        assertTrue(req.url.toString(), req.url.toString().contains("username=u&password=p"))
        assertEquals("GoodTV/0.2", req.headers["User-Agent"])
    }

    @Test
    fun validateAuthZeroIsInvalidCredentials() = runTest {
        server.enqueue(json(fixture("auth_bad.json")))
        val err = source.validate(cfg).exceptionOrNull()
        assertTrue(err is SourceException)
        assertEquals("Invalid username or password", err!!.message)
    }

    @Test
    fun validateHtmlBodyIsUnexpectedResponse() = runTest {
        server.enqueue(json("<html>503 Service Unavailable</html>"))
        assertEquals("Server returned an unexpected response", source.validate(cfg).exceptionOrNull()!!.message)
    }

    @Test
    fun validateHttp503() = runTest {
        server.enqueue(json("", code = 503))
        assertEquals("Server returned HTTP 503", source.validate(cfg).exceptionOrNull()!!.message)
    }

    @Test
    fun validateUnreachableServer() = runTest {
        server.close()
        val msg = source.validate(cfg).exceptionOrNull()!!.message!!
        assertTrue(msg, msg.startsWith("Could not reach server"))
    }

    private fun catalogDispatcher(failAction: String? = null) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val action = request.url.queryParameter("action")
            if (action == failAction) return json("boom", 500)
            return when (action) {
                "get_live_categories" -> json(fixture("live_categories.json"))
                "get_vod_categories" -> json("""[{"category_id":"10","category_name":"Action"}]""")
                "get_series_categories" -> json("""[{"category_id":"20","category_name":"Drama"}]""")
                "get_live_streams" -> json(fixture("live_streams_mixed.json"))
                "get_vod_streams" -> json(fixture("vod_streams_mixed.json"))
                "get_series" -> json(fixture("series.json"))
                "get_vod_info" -> json(fixture("vod_info.json"))
                "get_series_info" -> json(fixture("series_info_map.json"))
                null -> json(fixture("auth_ok.json"))
                else -> json("[]", 404)
            }
        }
    }

    @Test
    fun fetchCatalogMapsAllSix() = runTest {
        server.dispatcher = catalogDispatcher()
        val catalog = source.fetchCatalog(7, cfg).getOrThrow()
        assertEquals(4, catalog.categories.size)
        assertEquals(2, catalog.categories.count { it.kind == ContentKind.LIVE })
        assertEquals(1, catalog.categories.count { it.kind == ContentKind.MOVIE })
        assertEquals(1, catalog.categories.count { it.kind == ContentKind.SERIES })
        val ch = catalog.live.single { it.id == "124" }
        assertEquals(7L, ch.sourceId)
        assertEquals("2", ch.categoryId)
        assertEquals(7, ch.number)
        assertEquals("mkv", catalog.movies.single { it.id == "456" }.containerExtension)
        assertEquals(listOf("789", "790"), catalog.series.map { it.id })
        assertTrue(catalog.categories.all { it.sourceId == 7L })
    }

    @Test
    fun fetchCatalogFailsIfAnyCallFails() = runTest {
        server.dispatcher = catalogDispatcher(failAction = "get_vod_streams")
        val result = source.fetchCatalog(7, cfg)
        assertTrue(result.isFailure)
        assertEquals("Server returned HTTP 500", result.exceptionOrNull()!!.message)
    }

    @Test
    fun fetchMovieDetailMaps() = runTest {
        server.dispatcher = catalogDispatcher()
        val d = source.fetchMovieDetail(7, cfg, "456").getOrThrow()
        assertEquals("456", d.movie.id)
        assertEquals("Movie A", d.movie.name)
        assertEquals("mkv", d.movie.containerExtension)
        assertEquals(7200, d.durationSecs)
        assertEquals("http://x/backdrop.jpg", d.backdropUrl)
        assertEquals("Action, Adventure", d.genre)
    }

    @Test
    fun fetchSeriesDetailMaps() = runTest {
        server.dispatcher = catalogDispatcher()
        val d = source.fetchSeriesDetail(7, cfg, "789").getOrThrow()
        assertEquals("789", d.series.id)
        assertEquals("Series Title", d.series.name)
        assertEquals(listOf(1, 2), d.seasons.map { it.number })
        assertEquals(3, d.episodes.size)
        val e = d.episodes.single { it.id == "2001" }
        assertEquals(2, e.season)
        assertEquals("789", e.seriesId)
        assertEquals(7L, e.sourceId)
        assertEquals("mkv", e.containerExtension)
    }

    @Test
    fun streamUrlDelegatesToXtreamUrls() {
        assertEquals(XtreamUrls.stream(cfg, PlayableRef.Live("5")), source.streamUrl(cfg, PlayableRef.Live("5")))
    }
}
