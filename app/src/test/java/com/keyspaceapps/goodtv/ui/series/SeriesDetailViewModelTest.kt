package com.keyspaceapps.goodtv.ui.series

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.Season
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
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
class SeriesDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeLibraryStore()
    private val source = FakeChannelSource()
    private lateinit var repo: LibraryRepository
    private var sid = 0L

    private fun detail(): SeriesDetail {
        val series = Series(sid, "s1", "dra", "Series 1", null, null, null)
        return SeriesDetail(
            series, "plot", "Drama", null, "2024-01-01", null,
            seasons = listOf(Season(2, "Season 2", null), Season(1, "Season 1", null)),
            episodes = listOf(
                Episode(sid, "s1", "1001", 1, 1, "One", "mp4", 2700, null, null),
                Episode(sid, "s1", "1002", 1, 2, "Two", "mp4", 2700, null, null),
                Episode(sid, "s1", "2001", 2, 1, "Three", "mkv", null, null, null),
            ),
        )
    }

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = SourceRegistry(listOf(source))
        repo = LibraryRepository(store, registry, SyncManager(store, registry, CoroutineScope(dispatcher)), AppSettings(InMemoryDataStore()))
        sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun defaultsToLowestSeason() = runTest(dispatcher) {
        source.seriesDetail = detail()
        val vm = SeriesDetailViewModel(repo, SeriesDetailKey(sid, "s1"))
        advanceUntilIdle()
        val ready = vm.state.value as SeriesDetailViewModel.State.Ready
        assertEquals(1, ready.selectedSeason)
        assertEquals(listOf("One", "Two"), ready.episodes().map { it.title })
    }

    @Test
    fun selectSeasonFiltersEpisodes() = runTest(dispatcher) {
        source.seriesDetail = detail()
        val vm = SeriesDetailViewModel(repo, SeriesDetailKey(sid, "s1"))
        advanceUntilIdle()
        vm.selectSeason(2)
        val ready = vm.state.value as SeriesDetailViewModel.State.Ready
        assertEquals(2, ready.selectedSeason)
        assertEquals(listOf("Three"), ready.episodes().map { it.title })
    }

    @Test
    fun errorSurfaced() = runTest(dispatcher) {
        source.seriesDetail = null
        val vm = SeriesDetailViewModel(repo, SeriesDetailKey(sid, "s1"))
        advanceUntilIdle()
        assertEquals(SeriesDetailViewModel.State.Error("No detail"), vm.state.value)
    }
}
