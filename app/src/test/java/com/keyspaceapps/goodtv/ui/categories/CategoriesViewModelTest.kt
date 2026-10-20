package com.keyspaceapps.goodtv.ui.categories

import androidx.compose.ui.state.ToggleableState
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeLibraryStore()
    private lateinit var repo: LibraryRepository
    private var sid = 0L

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = SourceRegistry(listOf(FakeChannelSource()))
        repo = LibraryRepository(store, registry, SyncManager(store, registry, CoroutineScope(dispatcher)), AppSettings(InMemoryDataStore()))
        sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "Home", SourceConfig.Xtream("http://s", "u", "p"), true, 5L, null))
        store.replaceCatalog(
            sid,
            Catalog(
                categories = listOf(
                    Category(sid, ContentKind.LIVE, "1", "AR| Sports"), Category(sid, ContentKind.LIVE, "2", "AR| News"), Category(sid, ContentKind.LIVE, "3", "AR| Kids"),
                    Category(sid, ContentKind.LIVE, "4", "UK| BBC"), Category(sid, ContentKind.LIVE, "5", "UK| ITV"),
                    Category(sid, ContentKind.MOVIE, "m1", "FR - Action"), Category(sid, ContentKind.MOVIE, "m2", "FR - Drama"),
                ),
                live = emptyList(), movies = emptyList(), series = emptyList(),
            ),
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** StateFlows built with WhileSubscribed need a collector to run. */
    private fun TestScope.subscribe(vm: CategoriesViewModel): List<Job> =
        listOf(vm.groups.launchIn(this), vm.tabs.launchIn(this), vm.nickname.launchIn(this), vm.synced.launchIn(this))

    @Test
    fun allShownByDefaultAndCountsMatch() = runTest(dispatcher) {
        val vm = CategoriesViewModel(repo, sid)
        val jobs = subscribe(vm); advanceUntilIdle()

        assertEquals("Home", vm.nickname.value)
        assertEquals(true, vm.synced.value)
        assertEquals(listOf(CategoriesViewModel.TabState(ContentKind.LIVE, 5, 5), CategoriesViewModel.TabState(ContentKind.MOVIE, 2, 2), CategoriesViewModel.TabState(ContentKind.SERIES, 0, 0)), vm.tabs.value)
        assertEquals(listOf("AR", "UK"), vm.groups.value.map { it.prefix })
        assertEquals(listOf(ToggleableState.On, ToggleableState.On), vm.groups.value.map { it.state })
        jobs.forEach { it.cancel() }
    }

    @Test
    fun toggleHidesAndShowsAgain() = runTest(dispatcher) {
        val vm = CategoriesViewModel(repo, sid)
        val jobs = subscribe(vm); advanceUntilIdle()

        vm.toggle("1"); advanceUntilIdle()
        assertEquals(setOf("1"), vm.groups.value.first { it.prefix == "AR" }.hiddenIds)
        assertEquals(4, vm.tabs.value.first { it.kind == ContentKind.LIVE }.shown)

        vm.toggle("1"); advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.groups.value.first { it.prefix == "AR" }.hiddenIds)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun toggleGroupOnMixedBlockHidesWholeBlockThenShowsAll() = runTest(dispatcher) {
        val vm = CategoriesViewModel(repo, sid)
        val jobs = subscribe(vm); advanceUntilIdle()
        vm.toggle("2"); advanceUntilIdle()
        val mixed = vm.groups.value.first { it.prefix == "AR" }
        assertEquals(ToggleableState.Indeterminate, mixed.state)

        vm.toggleGroup(mixed); advanceUntilIdle()
        assertEquals(ToggleableState.Off, vm.groups.value.first { it.prefix == "AR" }.state)
        assertEquals(2, vm.tabs.value.first { it.kind == ContentKind.LIVE }.shown)

        vm.toggleGroup(vm.groups.value.first { it.prefix == "AR" }); advanceUntilIdle()
        assertEquals(ToggleableState.On, vm.groups.value.first { it.prefix == "AR" }.state)
        assertEquals(5, vm.tabs.value.first { it.kind == ContentKind.LIVE }.shown)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun clearAllThenSelectAllAffectsOnlyCurrentKind() = runTest(dispatcher) {
        val vm = CategoriesViewModel(repo, sid)
        val jobs = subscribe(vm); advanceUntilIdle()

        vm.clearAll(); advanceUntilIdle()
        assertEquals(0, vm.tabs.value.first { it.kind == ContentKind.LIVE }.shown)
        assertEquals(2, vm.tabs.value.first { it.kind == ContentKind.MOVIE }.shown)

        vm.selectAll(); advanceUntilIdle()
        assertEquals(5, vm.tabs.value.first { it.kind == ContentKind.LIVE }.shown)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun switchingKindChangesGroups() = runTest(dispatcher) {
        val vm = CategoriesViewModel(repo, sid)
        val jobs = subscribe(vm); advanceUntilIdle()
        vm.selectKind(ContentKind.MOVIE); advanceUntilIdle()
        assertEquals(ContentKind.MOVIE, vm.kind.value)
        assertEquals(listOf("FR"), vm.groups.value.map { it.prefix })
        jobs.forEach { it.cancel() }
    }
}
