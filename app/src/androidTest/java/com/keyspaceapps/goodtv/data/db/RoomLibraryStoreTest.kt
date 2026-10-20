package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.repo.CategoryKey
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Season
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoomLibraryStoreTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var store: RoomLibraryStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        store = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() = db.close()

    private fun record(nick: String, enabled: Boolean = true) = SourceRecord(
        id = 0, type = SourceType.XTREAM, nickname = nick,
        config = SourceConfig.Xtream("http://s", "u", "p", listOf("ts")), enabled = enabled, lastSyncAt = null, lastError = null,
    )

    private fun catalog(sid: Long, live: Int, movies: Int = 1, series: Int = 1) = Catalog(
        categories = listOf(
            Category(sid, ContentKind.LIVE, "c1", "Sports"),
            Category(sid, ContentKind.MOVIE, "m1", "Action"),
            Category(sid, ContentKind.SERIES, "s1", "Drama"),
        ),
        live = (1..live).map { LiveChannel(sid, "L$it", "c1", "Channel $it", null, it) },
        movies = (1..movies).map { Movie(sid, "M$it", "m1", "Movie $it", null, "mkv", 7.0, null) },
        series = (1..series).map { Series(sid, "S$it", "s1", "Series $it", null, null, null) },
    )

    @Test
    fun insertAndObserveSource() = runBlocking {
        val id = store.insertSource(record("Home"))
        val sources = store.observeSources().first()
        assertEquals(1, sources.size)
        assertEquals(id, sources[0].id)
        assertEquals("Home", sources[0].nickname)
        assertEquals(SourceConfig.Xtream("http://s", "u", "p", listOf("ts")), sources[0].config)
        assertEquals(sources[0], store.getSource(id))
    }

    @Test
    fun replaceCatalogSwapsRowsAtomically() = runBlocking {
        val id = store.insertSource(record("Home"))
        store.replaceCatalog(id, catalog(id, live = 2))
        assertEquals(listOf("L1", "L2"), store.observeLiveChannels(id, "c1").first().map { it.id })
        store.replaceCatalog(id, catalog(id, live = 1))
        assertEquals(listOf("L1"), store.observeLiveChannels(id, "c1").first().map { it.id })
        assertEquals(3, store.observeCategories(ContentKind.LIVE).first().size + store.observeCategories(ContentKind.MOVIE).first().size + store.observeCategories(ContentKind.SERIES).first().size)
    }

    @Test
    fun replaceCatalogLeavesOtherSourceAlone() = runBlocking {
        val a = store.insertSource(record("A"))
        val b = store.insertSource(record("B"))
        store.replaceCatalog(a, catalog(a, live = 2))
        store.replaceCatalog(b, catalog(b, live = 3))
        store.replaceCatalog(a, catalog(a, live = 1))
        assertEquals(1, store.observeLiveChannels(a, "c1").first().size)
        assertEquals(3, store.observeLiveChannels(b, "c1").first().size)
    }

    @Test
    fun deleteSourceCascades() = runBlocking {
        val id = store.insertSource(record("Home"))
        store.replaceCatalog(id, catalog(id, live = 2))
        store.deleteSource(id)
        assertTrue(store.observeCategories(ContentKind.LIVE).first().isEmpty())
        assertTrue(store.observeLiveChannels(id, "c1").first().isEmpty())
        assertTrue(store.observeSummaries().first().isEmpty())
        assertNull(store.getSource(id))
    }

    @Test
    fun summariesCountPerSource() = runBlocking {
        val id = store.insertSource(record("Home"))
        store.replaceCatalog(id, catalog(id, live = 2, movies = 1, series = 1))
        store.markSync(id, syncedAt = 1234L, error = null)
        val s = store.observeSummaries().first().single()
        assertEquals(2, s.liveCount)
        assertEquals(1, s.movieCount)
        assertEquals(1, s.seriesCount)
        assertEquals(1234L, s.lastSyncAt)
        assertNull(s.lastError)
        store.markSync(id, syncedAt = null, error = "boom")
        val s2 = store.observeSummaries().first().single()
        assertEquals(1234L, s2.lastSyncAt)
        assertEquals("boom", s2.lastError)
    }

    @Test
    fun summariesIncludeSourcesWithoutCatalog() = runBlocking {
        store.insertSource(record("Empty"))
        val s = store.observeSummaries().first().single()
        assertEquals(0, s.liveCount)
    }

    @Test
    fun categoriesCarryNicknameAndDisabledHidden() = runBlocking {
        val a = store.insertSource(record("A"))
        val b = store.insertSource(record("B", enabled = false))
        store.replaceCatalog(a, catalog(a, live = 1))
        store.replaceCatalog(b, catalog(b, live = 1))
        val cats = store.observeCategories(ContentKind.LIVE).first()
        assertEquals(1, cats.size)
        assertEquals("A", cats[0].sourceNickname)
        assertEquals("Sports", cats[0].category.name)
    }

    @Test
    fun moviesAndSeriesHonourLimit() = runBlocking {
        val id = store.insertSource(record("Home"))
        store.replaceCatalog(id, catalog(id, live = 0, movies = 5, series = 4))
        assertEquals(3, store.observeMovies(id, "m1", 3).first().size)
        assertEquals(4, store.observeSeries(id, "s1", 10).first().size)
        assertEquals("Movie 2", store.getMovie(id, "M2")!!.name)
        assertEquals("Series 3", store.getSeries(id, "S3")!!.name)
    }

    @Test
    fun seriesDetailRoundTrip() = runBlocking {
        val id = store.insertSource(record("Home"))
        store.replaceCatalog(id, catalog(id, live = 0))
        assertNull(store.getSeriesDetail(id, "S1"))
        val detail = SeriesDetail(
            series = Series(id, "S1", "s1", "Series 1", null, "plot", 8.0),
            plot = "plot", genre = "Drama", cast = "Cast", releaseDate = "2025-01-01", backdropUrl = "http://x/b.jpg",
            seasons = listOf(Season(1, "Season 1", null), Season(2, "Season 2", null)),
            episodes = listOf(
                Episode(id, "S1", "E1", 1, 1, "One", "mkv", 100, null, null),
                Episode(id, "S1", "E2", 1, 2, "Two", "mkv", 100, null, null),
                Episode(id, "S1", "E3", 2, 1, "Three", "mp4", null, "p", "http://x/e3.jpg"),
            ),
        )
        store.saveSeriesDetail(detail)
        val loaded = store.getSeriesDetail(id, "S1")!!
        assertEquals(detail.seasons, loaded.seasons)
        assertEquals(detail.episodes, loaded.episodes)
        assertEquals("Drama", loaded.genre)
        assertEquals("http://x/b.jpg", loaded.backdropUrl)
    }

    @Test
    fun movieDetailRoundTrip() = runBlocking {
        val id = store.insertSource(record("Home"))
        store.replaceCatalog(id, catalog(id, live = 0))
        assertNull(store.getMovieDetail(id, "M1"))
        val detail = MovieDetail(Movie(id, "M1", "m1", "Movie 1", "http://x/p.jpg", "mkv", 7.0, null), "plot", "Action", "cast", "dir", "2025-01-01", 7200, "http://x/b.jpg")
        store.saveMovieDetail(detail)
        assertEquals(detail, store.getMovieDetail(id, "M1"))
    }

    @Test
    fun updateSourceChangesConfigAndNickname() = runBlocking {
        val id = store.insertSource(record("Home"))
        val updated = store.getSource(id)!!.copy(nickname = "Renamed", config = SourceConfig.Xtream("http://t", "x", "y"))
        store.updateSource(updated)
        assertEquals(updated, store.getSource(id))
    }

    @Test
    fun moviesByCategoryLimitsPerCategoryNewestFirstAndSkipsDisabledSources() = runBlocking {
        val a = store.insertSource(record("A"))
        val b = store.insertSource(record("B", enabled = false))
        val movies = (1..5).map { Movie(a, "M$it", "m1", "Movie $it", null, "mkv", null, addedAt = it.toLong()) } +
            listOf(Movie(a, "X1", "m2", "Other 1", null, "mkv", null, null))
        store.replaceCatalog(a, catalog(a, live = 0).copy(movies = movies))
        store.replaceCatalog(b, catalog(b, live = 0))

        val grouped = store.observeMoviesByCategory(3).first()

        assertEquals(setOf(CategoryKey(a, "m1"), CategoryKey(a, "m2")), grouped.keys)
        assertEquals(listOf("M5", "M4", "M3"), grouped[CategoryKey(a, "m1")]!!.map { it.id })
        assertEquals(listOf("X1"), grouped[CategoryKey(a, "m2")]!!.map { it.id })
        assertEquals(1, store.observeSeriesByCategory(3).first()[CategoryKey(a, "s1")]!!.size)
    }

    @Test
    fun hiddenCategoriesAreExcludedFromBrowsingQueriesButNotFromAll() = runBlocking {
        val a = store.insertSource(record("A"))
        store.replaceCatalog(a, catalog(a, live = 1, movies = 2, series = 1))
        store.setCategoriesHidden(a, ContentKind.MOVIE, listOf("m1"), hidden = true)

        assertTrue(store.observeCategories(ContentKind.MOVIE).first().isEmpty())
        assertTrue(store.observeMoviesByCategory(10).first().isEmpty())
        assertEquals(listOf("m1"), store.observeAllCategories(a, ContentKind.MOVIE).first().map { it.id })
        assertEquals(setOf(ContentKind.MOVIE to "m1"), store.observeHiddenCategories(a).first())
        assertEquals(1, store.observeSummaries().first().single().hiddenCount)
        // other kinds untouched
        assertEquals(1, store.observeCategories(ContentKind.LIVE).first().size)
        assertEquals(1, store.observeSeriesByCategory(10).first().size)
    }

    @Test
    fun hiddenSetSurvivesReplaceCatalogAndEmptyKindDoesNotPrune() = runBlocking {
        val a = store.insertSource(record("A"))
        store.replaceCatalog(a, catalog(a, live = 1))
        store.setCategoriesHidden(a, ContentKind.LIVE, listOf("c1"), hidden = true)

        // A sync whose live category list is empty (panel hiccup) must keep the choice.
        val noLiveCats = catalog(a, live = 0).let { c -> c.copy(categories = c.categories.filter { it.kind != ContentKind.LIVE }) }
        store.replaceCatalog(a, noLiveCats)
        assertEquals(setOf(ContentKind.LIVE to "c1"), store.observeHiddenCategories(a).first())

        // A sync with live categories that no longer include c1 prunes it.
        val renamed = catalog(a, live = 1).let { c -> c.copy(categories = c.categories.map { if (it.id == "c1") it.copy(id = "c9", name = "Other") else it }) }
        store.replaceCatalog(a, renamed)
        assertTrue(store.observeHiddenCategories(a).first().isEmpty())
    }

    @Test
    fun deleteSourceRemovesHiddenRows() = runBlocking {
        val a = store.insertSource(record("A"))
        store.replaceCatalog(a, catalog(a, live = 1))
        store.setCategoriesHidden(a, ContentKind.LIVE, listOf("c1"), hidden = true)
        store.deleteSource(a)
        val again = store.insertSource(record("A"))
        store.replaceCatalog(again, catalog(again, live = 1))
        assertTrue(store.observeHiddenCategories(again).first().isEmpty())
        assertEquals(1, store.observeCategories(ContentKind.LIVE).first().size)
    }

    @Test
    fun setCategoriesHiddenFalseRemovesRows() = runBlocking {
        val a = store.insertSource(record("A"))
        store.replaceCatalog(a, catalog(a, live = 1))
        store.setCategoriesHidden(a, ContentKind.LIVE, listOf("c1"), hidden = true)
        store.setCategoriesHidden(a, ContentKind.LIVE, listOf("c1"), hidden = false)
        assertTrue(store.observeHiddenCategories(a).first().isEmpty())
        assertEquals(0, store.observeSummaries().first().single().hiddenCount)
    }
}
