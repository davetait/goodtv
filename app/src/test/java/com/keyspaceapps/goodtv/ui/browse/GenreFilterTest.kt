package com.keyspaceapps.goodtv.ui.browse

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import com.keyspaceapps.goodtv.ui.movies.MoviesViewModel
import com.keyspaceapps.goodtv.ui.series.SeriesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenreFilterTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeLibraryStore()
    private lateinit var repo: LibraryRepository
    private var sid = 0L

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = SourceRegistry(listOf(FakeChannelSource()))
        repo = LibraryRepository(store, registry, SyncManager(store, registry, CoroutineScope(dispatcher)), AppSettings(InMemoryDataStore()))
        sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
        store.replaceCatalog(
            sid,
            Catalog(
                categories = listOf(Category(sid, ContentKind.MOVIE, "m", "Movies"), Category(sid, ContentKind.SERIES, "s", "Shows")),
                live = emptyList(),
                movies = listOf(
                    Movie(sid, "1", "m", "EN - Heat (1995)", null, "mkv", null, 10, tmdbId = 949),
                    Movie(sid, "2", "m", "DE - Heat (1995)", null, "mkv", null, 20, tmdbId = 949),
                    Movie(sid, "3", "m", "Up", null, "mkv", null, 30, tmdbId = 14160),
                    Movie(sid, "4", "m", "No id", null, "mkv", null, 40),
                ),
                series = listOf(
                    Series(sid, "a", "s", "Dark", null, null, null, genres = setOf(Genre.DRAMA, Genre.MYSTERY)),
                    Series(sid, "b", "s", "Bluey", null, null, null, genres = setOf(Genre.KIDS)),
                ),
            ),
        )
        // What the background TMDB fill stores: genres per TMDB id, shared by every copy of the film.
        store.movieGenres.value = mapOf(949L to setOf(Genre.CRIME, Genre.THRILLER), 14160L to setOf(Genre.ANIMATION))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.watch(f: GenreFilter) = backgroundScope.launch { launch { f.counts.collect {} }; f.items.collect {} }

    @Test
    fun movieGenresComeFromTmdbIdsAndCoverEveryCopy() = runTest(dispatcher) {
        val vm = MoviesViewModel(repo)
        watch(vm.genres); advanceUntilIdle()
        assertEquals(mapOf(Genre.CRIME to 2, Genre.THRILLER to 2, Genre.ANIMATION to 1), vm.genres.counts.value)
        assertNull("no genre chosen: rows show", vm.genres.items.value)

        vm.genres.select(Genre.CRIME); advanceUntilIdle()
        assertEquals(listOf("DE - Heat (1995)", "EN - Heat (1995)"), vm.genres.items.value!!.map { it.title }) // newest first
        assertEquals(949L, vm.genres.items.value!!.first().tmdbId)

        vm.genres.select(null); advanceUntilIdle()
        assertNull(vm.genres.items.value)
    }

    @Test
    fun seriesGenresComeFromTheProvider() = runTest(dispatcher) {
        val vm = SeriesViewModel(repo)
        watch(vm.genres); advanceUntilIdle()
        assertEquals(mapOf(Genre.DRAMA to 1, Genre.MYSTERY to 1, Genre.KIDS to 1), vm.genres.counts.value)
        vm.genres.select(Genre.KIDS); advanceUntilIdle()
        assertEquals(listOf("Bluey"), vm.genres.items.value!!.map { it.title })
    }

    @Test
    fun hiddenCategoriesAreLeftOutAndAnEmptyGenreIsEmptyNotNull() = runTest(dispatcher) {
        store.setCategoriesHidden(sid, ContentKind.MOVIE, listOf("m"), hidden = true)
        val vm = MoviesViewModel(repo)
        watch(vm.genres)
        vm.genres.select(Genre.CRIME); advanceUntilIdle()
        assertEquals(emptyMap<Genre, Int>(), vm.genres.counts.value)
        assertEquals(emptyList<RowItem>(), vm.genres.items.value)
    }

    @Test
    fun choosingAnotherGenreForgetsTheFocusedResult() = runTest(dispatcher) {
        val vm = MoviesViewModel(repo)
        vm.genres.select(Genre.CRIME)
        vm.genres.focus.key = "1:1"
        vm.genres.select(Genre.CRIME)
        assertEquals("same genre keeps it", "1:1", vm.genres.focus.key)
        vm.genres.select(Genre.ANIMATION)
        assertNull(vm.genres.focus.key)
    }
}
