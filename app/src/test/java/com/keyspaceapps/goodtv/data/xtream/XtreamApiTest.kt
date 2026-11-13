package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.data.source.SourceConfig
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
    fun cancellingAListCallStopsItsDownload() = runBlocking {
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
                api.vodStreams(cfg)
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
}
