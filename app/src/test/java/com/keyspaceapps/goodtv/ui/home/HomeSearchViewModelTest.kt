package com.keyspaceapps.goodtv.ui.home

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeLibraryStore()
    private lateinit var repo: LibraryRepository
    private var sourceId = 0L

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = SourceRegistry(listOf(FakeChannelSource()))
        repo = LibraryRepository(store, registry, SyncManager(store, registry, CoroutineScope(dispatcher)), AppSettings(InMemoryDataStore()))
        sourceId = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
        store.replaceCatalog(
            sourceId,
            Catalog(
                categories = listOf(Category(sourceId, ContentKind.LIVE, "c1", "News"), Category(sourceId, ContentKind.MOVIE, "m1", "Action")),
                live = listOf(LiveChannel(sourceId, "l1", "c1", "Matrix News", null, 1)),
                movies = listOf(
                    Movie(sourceId, "m1", "m1", "The Matrix", "http://img/m1", "mkv", null, null),
                    Movie(sourceId, "m2", "m1", "Matrix Reloaded", null, "mkv", null, null),
                    Movie(sourceId, "m3", "m1", "Heat", null, "mkv", null, null),
                ),
                series = listOf(Series(sourceId, "s1", null, "Matrix Stories", "http://img/s1", null, null)),
            ),
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Keeps [HomeSearchViewModel.results] subscribed, as the screen does. */
    private fun kotlinx.coroutines.test.TestScope.collect(vm: HomeSearchViewModel) = backgroundScope.launch { vm.results.collect {} }

    @Test
    fun blankQueryHasNoResults() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("   ")
        advanceUntilIdle()
        assertNull(vm.results.value)
    }

    @Test
    fun allScopeGroupsLiveThenMoviesThenSeriesWithPrefixMatchesFirst() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery(" matrix ")
        advanceUntilIdle()
        val results = vm.results.value!!
        assertEquals(listOf("Matrix News", "Matrix Reloaded", "The Matrix", "Matrix Stories"), results.map { it.title })
        assertEquals(listOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.MOVIE, ContentKind.SERIES), results.map { it.kind })
        assertEquals("http://img/m1", results[2].imageUrl)
    }

    @Test
    fun scopeLimitsToOneKind() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("matrix")
        vm.setScope(SearchScope.SERIES)
        advanceUntilIdle()
        assertEquals(listOf("Matrix Stories"), vm.results.value!!.map { it.title })

        vm.setScope(SearchScope.LIVE)
        advanceUntilIdle()
        assertEquals(listOf("Matrix News"), vm.results.value!!.map { it.title })
    }

    @Test
    fun waitsForTypingToPauseBeforeSearching() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("heat")
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 50)
        runCurrent()
        assertNull(vm.results.value)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("Heat"), vm.results.value!!.map { it.title })
    }

    @Test
    fun noMatchGivesEmptyListNotNull() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("zzz")
        advanceUntilIdle()
        assertEquals(emptyList<SearchResult>(), vm.results.value)
    }

    @Test
    fun hiddenCategoriesAreLeftOut() = runTest(dispatcher) {
        store.setCategoriesHidden(sourceId, ContentKind.MOVIE, listOf("m1"), hidden = true)
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("matrix")
        vm.setScope(SearchScope.MOVIES)
        advanceUntilIdle()
        assertEquals(emptyList<SearchResult>(), vm.results.value)
    }
}
