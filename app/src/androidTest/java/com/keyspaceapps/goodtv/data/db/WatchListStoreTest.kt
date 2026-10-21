package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.data.watchlist.WatchListRepository
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WatchListStoreTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var store: RoomLibraryStore
    private lateinit var list: WatchListRepository
    private var now = 1L
    private var sid = 0L

    private val heat get() = Movie(sid, "m1", "a", "EN - Heat (1995)", "http://p/heat.jpg", "mkv", null, null, title = "Heat (1995)", language = "EN")
    private val severance get() = Series(sid, "s1", "d", "Severance", "http://p/sev.jpg", null, null)
    private fun catalog(movies: List<Movie> = listOf(heat)) = Catalog(
        listOf(Category(sid, ContentKind.MOVIE, "a", "Action"), Category(sid, ContentKind.SERIES, "d", "Drama")),
        emptyList(), movies, listOf(severance),
    )
    private val movie get() = WatchListRef(sid, WatchListKind.MOVIE, "m1")
    private val series get() = WatchListRef(sid, WatchListKind.SERIES, "s1")

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        store = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
        list = WatchListRepository(RoomWatchListStore(db.watchList())) { now }
        sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
        store.replaceCatalog(sid, catalog())
    }

    @After fun tearDown() = db.close()

    @Test
    fun addedTitlesShowNewestFirst() = runBlocking {
        now = 1; list.add(movie)
        now = 2; list.add(series)
        val row = list.row().first()
        assertEquals(listOf("SERIES:$sid:s1", "MOVIE:$sid:m1"), row.map { it.ref.key })
        assertEquals("Heat (1995) (EN)", row[1].title)
        assertEquals("http://p/heat.jpg", row[1].imageUrl)
        assertEquals("http://p/sev.jpg", row[0].imageUrl)
    }

    /** A re-sync without the title hides it; it comes back with the title. */
    @Test
    fun missingFromCatalogIsHiddenNotDeleted() = runBlocking {
        list.add(movie)
        store.replaceCatalog(sid, catalog(movies = emptyList()))
        assertEquals(emptyList<String>(), list.row().first().map { it.ref.key })
        assertTrue(movie in list.saved().first())
        store.replaceCatalog(sid, catalog())
        assertEquals(listOf(movie), list.row().first().map { it.ref })
    }

    @Test
    fun disabledSourceAndHiddenCategoryHide() = runBlocking {
        list.add(movie); list.add(series)
        val source = store.getSource(sid)!!
        store.updateSource(source.copy(enabled = false))
        assertEquals(emptyList<WatchListRef>(), list.row().first().map { it.ref })
        store.updateSource(source.copy(enabled = true))
        store.setCategoriesHidden(sid, ContentKind.MOVIE, listOf("a"), hidden = true)
        assertEquals(listOf(series), list.row().first().map { it.ref })
        assertEquals(setOf(movie, series), list.saved().first())
    }

    @Test
    fun deletingTheSourceDeletesItsRows() = runBlocking {
        list.add(movie)
        store.deleteSource(sid)
        assertEquals(emptySet<WatchListRef>(), list.saved().first())
    }

    @Test
    fun addTwiceKeepsOneRowWithTheNewTime() = runBlocking {
        now = 1; list.add(movie)
        now = 5; list.add(movie)
        val row = list.row().first()
        assertEquals(1, row.size)
        assertEquals(5L, row.single().savedAt)
    }

    @Test
    fun removeTakesItOff() = runBlocking {
        list.add(movie)
        list.remove(movie)
        assertEquals(emptySet<WatchListRef>(), list.saved().first())
    }
}
