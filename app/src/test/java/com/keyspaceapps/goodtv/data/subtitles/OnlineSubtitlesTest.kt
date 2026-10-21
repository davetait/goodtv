package com.keyspaceapps.goodtv.data.subtitles

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnlineSubtitlesTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private lateinit var online: OnlineSubtitles
    private val matrix = SubtitleQuery(603)
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

    private fun ok(body: String) = MockResponse(code = 200, body = body)
    private val search = """{"data":[{"attributes":{"language":"en","download_count":5,"release":"The Matrix","files":[{"file_id":42,"file_name":"m.srt"}]}}]}"""

    @Before fun setUp() {
        server.start()
        dir = Files.createTempDirectory("subs").toFile()
        online = OnlineSubtitles(OpenSubtitlesApi(OkHttpClient(), "k", baseUrl = server.url("/api/v1/"), allowInsecureLinks = true), dir)
    }

    @After fun tearDown() { server.close(); dir.deleteRecursively() }

    private fun enqueueDownload() {
        server.enqueue(ok("""{"link":"${server.url("/file/42.srt")}"}"""))
        server.enqueue(ok(srt))
    }

    @Test fun findSearchesOnceAndPicks() = runTest {
        server.enqueue(ok(search))
        val source = online.forQuery(matrix)
        assertEquals(42L, source.find()?.fileId)
        assertEquals(42L, online.forQuery(matrix).find()?.fileId)
        assertEquals(1, server.requestCount)
    }

    @Test fun fetchDownloadsSavesAndReturnsText() = runTest {
        server.enqueue(ok(search)); enqueueDownload()
        val source = online.forQuery(matrix)
        assertEquals(srt, source.fetch(source.find()!!).getOrThrow())
        assertEquals("/api/v1/download", server.takeRequest().let { server.takeRequest().url.encodedPath })
        assertEquals(srt, File(dir, "m603.srt").readText())
    }

    @Test fun secondFetchUsesCacheWithoutNetwork() = runTest {
        server.enqueue(ok(search)); enqueueDownload()
        val source = online.forQuery(matrix)
        val choice = source.find()!!
        source.fetch(choice).getOrThrow()
        val before = server.requestCount
        assertEquals(srt, online.forQuery(matrix).fetch(choice).getOrThrow())
        assertEquals(before, server.requestCount)
    }

    @Test fun quotaFailureIsReturnedNotThrown() = runTest {
        server.enqueue(ok(search))
        server.enqueue(MockResponse(code = 406, body = """{"message":"quota","reset_time_utc":"2026-10-22T00:00:00.000Z"}"""))
        val source = online.forQuery(matrix)
        val result = source.fetch(source.find()!!)
        val e = result.exceptionOrNull() as OpenSubtitlesException
        assertEquals(OpenSubtitlesException.Reason.QUOTA, e.reason)
        assertEquals("2026-10-22T00:00:00.000Z", e.resetUtc)
        assertFalse(File(dir, "m603.srt").exists())
    }

    @Test fun offsetIsSavedPerTitle() = runTest {
        online.forQuery(matrix).setOffsetMs(-1500)
        assertEquals(-1500L, online.forQuery(matrix).offsetMs())
        assertEquals(0L, online.forQuery(SubtitleQuery(604)).offsetMs())
        assertTrue(File(dir, "m603.offset").exists())
    }
}
