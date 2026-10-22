package com.keyspaceapps.goodtv.ui.movies

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.tmdb.PeopleRepository
import com.keyspaceapps.goodtv.data.tmdb.PersonJob
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import com.keyspaceapps.goodtv.data.tmdb.TmdbPerson
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import com.keyspaceapps.goodtv.testing.InMemoryPeopleCache
import com.keyspaceapps.goodtv.testing.InMemoryTmdbCache
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeLibraryStore()
    private val source = FakeChannelSource()
    private lateinit var repo: LibraryRepository
    private var sourceId = 0L

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = SourceRegistry(listOf(source))
        repo = LibraryRepository(store, registry, SyncManager(store, registry, CoroutineScope(dispatcher)), AppSettings(InMemoryDataStore()))
        sourceId = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server?.close()
    }

    private var server: MockWebServer? = null

    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()

    /** A fake TMDB serving The Matrix (603): search, details and credits. */
    private fun tmdbRepos(): Pair<TmdbRepository, PeopleRepository> {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/3/search/movie" -> MockResponse(code = 200, body = fixture("search_movie_matrix.json"))
                "/3/movie/603" -> MockResponse(code = 200, body = fixture("movie_603.json"))
                "/3/movie/603/credits" -> MockResponse(code = 200, body = fixture("movie_credits_361743.json"))
                else -> MockResponse(code = 404, body = "{}")
            }
        }
        s.start()
        server = s
        val api = TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("k"), baseUrl = s.url("/3/"), io = dispatcher, sleep = {})
        return TmdbRepository(api, InMemoryTmdbCache(), language = { "en-US" }) to
            PeopleRepository(api, InMemoryPeopleCache(), language = { "en-US" })
    }

    private suspend fun catalogMovie(name: String, tmdbId: Long?) = store.replaceCatalog(
        sourceId,
        Catalog(categories = emptyList(), live = emptyList(), movies = listOf(Movie(sourceId, "m1", null, name, null, "mkv", 7.0, null, tmdbId = tmdbId)), series = emptyList()),
    )

    private val kosinski = TmdbPerson(10, "Joseph Kosinski", null, PersonJob.DIRECTOR)

    @Test
    fun peopleArriveWithTmdbMeta() = runTest(dispatcher) {
        catalogMovie("4K-DE - Matrix (1999)", 603)
        source.movieDetail = MovieDetail(Movie(sourceId, "m1", null, "Matrix", null, "mkv", 7.0, null), null, null, "A, B", null, null, null, null)
        val (tmdb, people) = tmdbRepos()
        val vm = MovieDetailViewModel(repo, MovieDetailKey(sourceId, "m1"), tmdb, peopleRepo = people)
        assertEquals(emptyList<TmdbPerson>(), vm.people.value)
        // Whenever the TMDB meta is visible, the people are too (they land together).
        var sawMetaWithoutPeople = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.tmdb.collect { if (it != null && vm.people.value.isEmpty()) sawMetaWithoutPeople = true } }
        advanceUntilIdle()
        assertNotNull(vm.tmdb.value)
        assertEquals(kosinski, vm.people.value.first())
        assertEquals(10, vm.people.value.size)
        assertEquals(false, sawMetaWithoutPeople)
    }

    @Test
    fun peopleUseLookupIdWhenProviderHasNone() = runTest(dispatcher) {
        catalogMovie("The Matrix (1999)", null)
        source.movieDetail = MovieDetail(Movie(sourceId, "m1", null, "Matrix", null, "mkv", 7.0, null), null, null, null, null, null, null, null)
        val (tmdb, people) = tmdbRepos()
        val vm = MovieDetailViewModel(repo, MovieDetailKey(sourceId, "m1"), tmdb, peopleRepo = people)
        advanceUntilIdle()
        assertEquals(603L, vm.tmdb.value!!.tmdbId)
        assertEquals(kosinski, vm.people.value.first())
    }

    @Test
    fun readyAfterLoad() = runTest(dispatcher) {
        val detail = MovieDetail(Movie(sourceId, "m1", "c", "Movie", null, "mkv", 7.5, null), "plot", "Action", "cast", "dir", "2024-05-01", 5400, null)
        source.movieDetail = detail
        val vm = MovieDetailViewModel(repo, MovieDetailKey(sourceId, "m1"))
        assertEquals(MovieDetailViewModel.State.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(MovieDetailViewModel.State.Ready(detail), vm.state.value)
    }

    @Test
    fun errorMessageSurfaced() = runTest(dispatcher) {
        source.movieDetail = null
        val vm = MovieDetailViewModel(repo, MovieDetailKey(sourceId, "m1"))
        advanceUntilIdle()
        assertEquals(MovieDetailViewModel.State.Error("No detail"), vm.state.value)
    }
}
