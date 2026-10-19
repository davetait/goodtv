package com.keyspaceapps.goodtv.ui.movies

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun tearDown() = Dispatchers.resetMain()

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
