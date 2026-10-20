package com.keyspaceapps.goodtv.ui

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import com.keyspaceapps.goodtv.testing.InMemoryTmdbCache
import com.keyspaceapps.goodtv.ui.movies.MovieDetailViewModel
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.series.SeriesDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Detail pages get TMDB data keyed by the catalog row's TMDB id, which the provider's detail call lacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailTmdbTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeLibraryStore()
    private val source = FakeChannelSource()
    private val server = MockWebServer()
    private val paths = mutableListOf<String>()
    private lateinit var repo: LibraryRepository
    private lateinit var tmdb: TmdbRepository
    private var sid = 0L

    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(paths) { paths += request.url.encodedPath }
                return when (request.url.encodedPath) {
                    "/3/movie/603" -> MockResponse(code = 200, body = fixture("movie_603.json"))
                    "/3/tv/1399" -> MockResponse(code = 200, body = fixture("tv_1399.json"))
                    else -> MockResponse(code = 404, body = "{}")
                }
            }
        }
        server.start()
        val registry = SourceRegistry(listOf(source))
        repo = LibraryRepository(store, registry, SyncManager(store, registry, CoroutineScope(dispatcher)), AppSettings(InMemoryDataStore()))
        tmdb = TmdbRepository(
            TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("k"), baseUrl = server.url("/3/"), io = dispatcher, sleep = {}),
            InMemoryTmdbCache(), language = { "en-US" },
        )
        sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
        store.replaceCatalog(
            sid,
            Catalog(
                categories = emptyList(), live = emptyList(),
                movies = listOf(Movie(sid, "m1", null, "4K-DE - Matrix (1999)", null, "mkv", 7.0, null, tmdbId = 603)),
                series = listOf(Series(sid, "s1", null, "EN - Game of Thrones", null, null, 8.0, tmdbId = 1399)),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
    }

    @Test
    fun movieDetailGetsTmdbRatingAndSummaryFromTheCatalogId() = runTest(dispatcher) {
        // The provider's detail has no TMDB id and a German plot.
        source.movieDetail = MovieDetail(Movie(sid, "m1", null, "Matrix", null, "mkv", 7.0, null), "Der Hacker Neo…", null, null, null, null, null, null)
        val vm = MovieDetailViewModel(repo, MovieDetailKey(sid, "m1"), tmdb)
        advanceUntilIdle()
        assertEquals(8.2, vm.tmdb.value!!.reliableRating!!, 0.0)
        assertEquals("Set in the 22nd century", vm.tmdb.value!!.overview!!.take(23))
        assertEquals(listOf("/3/movie/603"), paths)
    }

    @Test
    fun seriesDetailGetsTmdbData() = runTest(dispatcher) {
        source.seriesDetail = SeriesDetail(Series(sid, "s1", null, "GoT", null, null, null), null, null, null, null, null, emptyList(), emptyList())
        val vm = SeriesDetailViewModel(repo, SeriesDetailKey(sid, "s1"), tmdb)
        advanceUntilIdle()
        assertEquals("Game of Thrones", vm.tmdb.value!!.title)
        assertEquals(listOf("/3/tv/1399"), paths)
    }

    @Test
    fun withoutTmdbOrACatalogRowThereIsNoTmdbData() = runTest(dispatcher) {
        source.movieDetail = MovieDetail(Movie(sid, "m1", null, "Matrix", null, "mkv", 7.0, null), null, null, null, null, null, null, null)
        val off = MovieDetailViewModel(repo, MovieDetailKey(sid, "m1"), tmdbRepo = null)
        val unknown = MovieDetailViewModel(repo, MovieDetailKey(sid, "not-in-catalog"), tmdb)
        advanceUntilIdle()
        assertNull(off.tmdb.value)
        assertNull(unknown.tmdb.value)
        assertEquals(emptyList<String>(), paths)
    }
}
