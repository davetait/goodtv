package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class XtreamApiTest {
    private val server = MockWebServer()
    private lateinit var cfg: SourceConfig.Xtream

    @Before
    fun setUp() {
        server.start()
        cfg = SourceConfig.Xtream(server = server.url("/").toString(), username = "u", password = "p")
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun cancellingAListCallStopsItsDownload() = assertCancellingStopsTheDownload { api -> api.vodStreams(cfg) }

    @Test
    fun cancellingAStreamedListStopsItsDownload() = assertCancellingStopsTheDownload { api ->
        api.streamList(XtreamUrls.api(cfg, "get_vod_streams"), XtVodStream.serializer(), 100) {}
    }

    private fun assertCancellingStopsTheDownload(read: suspend (XtreamApi) -> Unit) = runBlocking {
        val bytes = ("[" + (1..2_000).joinToString(",") { """{"stream_id":$it,"name":"Movie $it"}""" } + "]")
            .encodeToByteArray()
        val serverSawClose = CountDownLatch(1)
        // Wraps the throttled socket sink: a failed write means the client closed the connection.
        val body = object : MockResponseBody {
            override val contentLength = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                try {
                    sink.write(bytes)
                    sink.flush()
                } catch (e: IOException) {
                    serverSawClose.countDown()
                    throw e
                }
            }
        }
        // 512 bytes every 100 ms: the ~70 KB list takes over 10 s to stream.
        server.enqueue(MockResponse.Builder().body(body).throttleBody(512, 100, TimeUnit.MILLISECONDS).build())
        val api = XtreamApi(OkHttpClient(), XtreamJson.instance)
        assertTrue(bytes.size / 512 * 100 >= 10_000)

        var thrown: Throwable? = null
        val job = launch(Dispatchers.Default) {
            try {
                read(api)
            } catch (t: Throwable) {
                thrown = t
                throw t
            }
        }
        delay(200)
        val cancelledAt = System.nanoTime()
        job.cancel()
        job.join()
        val elapsedMs = (System.nanoTime() - cancelledAt) / 1_000_000

        assertTrue("took $elapsedMs ms after the cancel", elapsedMs < 2_000)
        assertTrue("threw $thrown", thrown is CancellationException)
        assertTrue("server never saw the connection close", serverSawClose.await(2, TimeUnit.SECONDS))
    }

    private fun moviesJson(count: Int): String = (1..count).joinToString(",", "[", "]") { """{"stream_id":$it,"name":"Movie $it"}""" }

    private fun streamMovies(body: String, chunk: Int = 5_000): Pair<List<List<XtVodStream>>, Throwable?> = runBlocking {
        server.enqueue(MockResponse(code = 200, body = body))
        val api = XtreamApi(OkHttpClient(), XtreamJson.instance)
        val pieces = mutableListOf<List<XtVodStream>>()
        val error = runCatching {
            api.streamList(XtreamUrls.api(cfg, "get_vod_streams"), XtVodStream.serializer(), chunk) { pieces += it }
        }.exceptionOrNull()
        pieces to error
    }

    @Test
    fun streamListHandsOnPiecesInOrder() {
        val (pieces, error) = streamMovies(moviesJson(12_345))

        assertEquals(null, error)
        assertEquals(listOf(5_000, 5_000, 2_345), pieces.map { it.size })
        assertEquals((1..12_345).map { "$it" }, pieces.flatten().map { it.stream_id })
        assertEquals("Movie 12345", pieces.last().last().name)
    }

    @Test
    fun streamListHandsOnAnEmptyListAsOneEmptyPiece() {
        val (pieces, error) = streamMovies("[]")

        assertEquals(null, error)
        assertEquals(listOf(0), pieces.map { it.size })
    }

    @Test
    fun streamListFailsAsSourceExceptionOnABadElementMidStream() {
        val (pieces, error) = streamMovies(moviesJson(6_000).dropLast(1) + ""","{"stream_id":6001,"name":]""")

        assertEquals("the first piece was handed on before the bad element", listOf(5_000), pieces.map { it.size })
        assertTrue("threw $error", error is SourceException)
        assertEquals("Server returned an unexpected response", error!!.message)
    }

    @Test
    fun streamListFailsAsSourceExceptionWhenTheBodyIsNotAList() {
        val (pieces, error) = streamMovies("""{"user_info":{"auth":0}}""")

        assertEquals(emptyList<Int>(), pieces.map { it.size })
        assertEquals("Server returned an unexpected response", error!!.message)
    }

    @Test
    fun streamListPassesTheCallersFailureThroughUnchanged() {
        server.enqueue(MockResponse(code = 200, body = moviesJson(10)))
        val api = XtreamApi(OkHttpClient(), XtreamJson.instance)
        val mine = IOException("writer failed")

        val error = runCatching {
            runBlocking { api.streamList(XtreamUrls.api(cfg, "get_vod_streams"), XtVodStream.serializer(), 5_000) { throw mine } }
        }.exceptionOrNull()

        // Not wrapped as a SourceException (coroutines may hand on a copy, so compare by type and message).
        assertTrue("threw $error", error is IOException && error !is SourceException && error.message == mine.message)
    }
}
