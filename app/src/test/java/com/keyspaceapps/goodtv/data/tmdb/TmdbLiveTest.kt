package com.keyspaceapps.goodtv.data.tmdb

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Against the real TMDB API. Skipped unless run with `./gradlew :app:testDebugUnitTest -PtmdbLive
 * --tests '*TmdbLiveTest'` and GOODTV_TMDB_API_KEY set in local.properties. Optional
 * `-PtmdbSample=<file>` (lines "MOVIE|TV<TAB>tmdbId-or-empty<TAB>catalog name") writes a match
 * report to build/tmdb-live-report.tsv.
 */
class TmdbLiveTest {
    private lateinit var repo: TmdbRepository
    private lateinit var api: TmdbApi

    @Before
    fun setUp() {
        val key = System.getProperty("goodtv.tmdbLiveKey").orEmpty()
        assumeTrue("TMDB live test needs -PtmdbLive and a key", key.isNotBlank())
        api = TmdbApi(OkHttpClient(), TmdbCredential.from(key)!!)
        repo = TmdbRepository(api, InMemoryTmdbCache(), language = { "en-US" })
    }

    @Test
    fun movieByProviderId() = runBlocking {
        val m = repo.lookup(TmdbKind.MOVIE, 603, "4K-AR - The Matrix (1999)").getOrThrow()!!
        assertEquals("The Matrix", m.title)
        assertEquals(1999, m.year)
        assertTrue("rating ${m.rating}", m.rating!! in 7.0..9.5)
        assertTrue(m.voteCount > 10_000)
        assertTrue(m.overview!!.isNotBlank())
        assertTrue(m.genres.contains("Science Fiction"))
        assertTrue(m.posterUrl!!.startsWith("https://image.tmdb.org/t/p/w500/"))
    }

    @Test
    fun tvByProviderId() = runBlocking {
        val t = repo.lookup(TmdbKind.TV, 1399, "EN - Game of Thrones (2011)").getOrThrow()!!
        assertEquals("Game of Thrones", t.title)
        assertEquals(2011, t.year)
        assertNotNull(t.rating)
    }

    @Test
    fun titleSearchFallbackFindsMoviesAndShows() = runBlocking {
        assertEquals(603L, repo.lookup(TmdbKind.MOVIE, null, "EN - The Matrix (1999)").getOrThrow()?.tmdbId)
        assertEquals(1399L, repo.lookup(TmdbKind.TV, null, "AR-SUBS - Game of Thrones (2011) (US)").getOrThrow()?.tmdbId)
        assertEquals("Broadchurch", repo.lookup(TmdbKind.TV, null, "AR-SUBS - Broadchurch (2013) (GB)").getOrThrow()?.title)
    }

    @Test
    fun imageUrlActuallyServes() {
        val url = runBlocking { repo.lookup(TmdbKind.MOVIE, 603, "x").getOrThrow()!!.posterUrl!! }
        OkHttpClient().newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { r ->
            assertEquals(200, r.code)
            assertTrue(r.header("Content-Type").orEmpty().startsWith("image/"))
        }
    }

    @Test
    fun unknownIdIsNullAndBadKeyIsAuth() = runBlocking {
        assertNull(api.movie(999_999_999, "en-US"))
        val bad = TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("0".repeat(32)))
        val e = runCatching { bad.movie(603, "en-US") }.exceptionOrNull()
        assertEquals(TmdbException.Reason.AUTH, (e as TmdbException).reason)
    }

    @Test
    fun sampleReport() = runBlocking {
        val path = System.getProperty("goodtv.tmdbSample")
        assumeTrue("no sample file", !path.isNullOrBlank())
        val out = StringBuilder("kind\tproviderId\tcatalogName\tcleanedQuery\tresult\ttmdbTitle\ttmdbYear\trating\tvotes\n")
        File(path!!).readLines().filter { it.isNotBlank() }.forEach { line ->
            val (kind, id, name) = line.split('\t', limit = 3)
            val clean = TitleCleaner.clean(name)
            val result = repo.lookup(TmdbKind.valueOf(kind), id.toLongOrNull(), name)
            val m = result.getOrNull()
            val status = when {
                result.isFailure -> "ERROR ${result.exceptionOrNull()?.message}"
                m == null -> if (clean.searchable) "no match" else "skipped"
                else -> "ok"
            }
            out.append("$kind\t$id\t$name\t${clean.title}|${clean.year ?: ""}\t$status\t${m?.title ?: ""}\t${m?.year ?: ""}\t${m?.rating ?: ""}\t${m?.voteCount ?: ""}\n")
        }
        File("build/tmdb-live-report.tsv").writeText(out.toString())
    }
}
