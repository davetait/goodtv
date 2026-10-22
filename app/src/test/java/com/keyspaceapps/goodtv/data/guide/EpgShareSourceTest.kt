package com.keyspaceapps.goodtv.data.guide

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class EpgShareSourceTest {
    private val server = MockWebServer()
    private lateinit var source: EpgShareSource

    @Before fun setUp() {
        server.start()
        source = EpgShareSource(OkHttpClient(), server.url("/epgshare01/"))
    }

    @After fun tearDown() = server.close()

    @Test fun openGunzipsAStream() = runTest {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("<tv>hello</tv>".toByteArray()) } }.toByteArray()
        server.enqueue(MockResponse.Builder().body(Buffer().write(gz)).build())
        assertEquals("<tv>hello</tv>", source.open(EpgShareFiles.CABLE).use { it.readBytes().decodeToString() })
        val request = server.takeRequest()
        assertEquals("/epgshare01/epg_ripper_US2.xml.gz", request.url.encodedPath)
        assertEquals("GoodTV/0.2", request.headers["User-Agent"])
    }

    @Test fun httpErrorIsAnIOException() = runTest {
        server.enqueue(MockResponse(code = 500))
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { source.open(EpgShareFiles.LOCALS) } }
    }
}
