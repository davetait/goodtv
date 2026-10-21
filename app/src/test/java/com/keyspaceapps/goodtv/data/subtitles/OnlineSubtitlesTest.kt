package com.keyspaceapps.goodtv.data.subtitles

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    private val choice = OsSubtitle(42, "The Matrix", "m.srt", "en", false, false, false, false, 5)

    /** Review I1: two presses at once must spend one download, not two. */
    @Test fun concurrentFetchesSpendOneDownload() = runTest {
        repeat(2) {
            server.enqueue(ok("""{"link":"${server.url("/file/42.srt")}"}"""))
            server.enqueue(MockResponse.Builder().code(200).body(srt).headersDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        }
        val a = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val first = async { online.forQuery(matrix).fetch(choice) }
            val second = async { online.forQuery(matrix).fetch(choice) }
            listOf(first.await(), second.await())
        }
        assertTrue(a.all { it.getOrNull() == srt })
        assertEquals("one link request and one file", 2, server.requestCount)
    }

    /** Review I3: an unreadable "subtitle" (an HTML error page) is a failure and isn't kept. */
    @Test fun unreadableFileIsAFailureAndNotSaved() = runTest {
        server.enqueue(ok("""{"link":"${server.url("/file/42.srt")}"}"""))
        server.enqueue(ok("<html><body>Service unavailable</body></html>"))
        val e = online.forQuery(matrix).fetch(choice).exceptionOrNull() as OpenSubtitlesException
        assertEquals(OpenSubtitlesException.Reason.BAD_RESPONSE, e.reason)
        assertFalse(File(dir, "m603.srt").exists())
    }

    /** Review I3: leaving the player mid-download still keeps what the download paid for. */
    @Test fun cancelledFetchStillSavesTheDownload() = runTest {
        server.enqueue(ok("""{"link":"${server.url("/file/42.srt")}"}"""))
        server.enqueue(MockResponse.Builder().code(200).body(srt).headersDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { online.forQuery(matrix).fetch(choice) }
        Thread.sleep(200)
        job.cancel()
        val saved = File(dir, "m603.srt")
        val deadline = System.currentTimeMillis() + 3_000
        while (!saved.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(srt, saved.readText())
    }

    /** Review I3: a save that fails (e.g. disk full) still shows the subtitles it downloaded. */
    @Test fun saveFailureStillReturnsTheText() = runTest {
        val notADir = File(dir, "blocked").apply { writeText("x") }
        val blocked = OnlineSubtitles(OpenSubtitlesApi(OkHttpClient(), "k", baseUrl = server.url("/api/v1/"), allowInsecureLinks = true), notADir)
        server.enqueue(ok("""{"link":"${server.url("/file/42.srt")}"}"""))
        server.enqueue(ok(srt))
        assertEquals(srt, blocked.forQuery(matrix).fetch(choice).getOrThrow())
    }

    /** Review M2 (crash): a timing that can't be saved must not throw. */
    @Test fun offsetSaveFailureDoesNotThrow() = runTest {
        val notADir = File(dir, "blocked2").apply { writeText("x") }
        val blocked = OnlineSubtitles(OpenSubtitlesApi(OkHttpClient(), "k", baseUrl = server.url("/api/v1/")), notADir)
        blocked.forQuery(matrix).setOffsetMs(500)
        assertEquals(0L, blocked.forQuery(matrix).offsetMs())
    }
}

