package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.progress.PlaybackInfo
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgressRepository
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WatchProgressStoreTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var store: RoomLibraryStore
    private lateinit var progress: WatchProgressRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        store = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
        progress = WatchProgressRepository(RoomWatchProgressStore(db.watchProgress())) { 1L }
    }

    @After fun tearDown() = db.close()

    @Test
    fun progressSurvivesAResyncAndGoesWithItsSource() = runBlocking {
        val sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
        val movie = WatchRef(sid, WatchKind.MOVIE, "m1")
        val episode = WatchRef(sid, WatchKind.EPISODE, "e1", "s1")
        progress.record(movie, 600_000, 7_200_000)
        progress.record(episode, 300_000, 2_700_000)

        store.replaceCatalog(sid, Catalog(emptyList(), emptyList(), emptyList(), emptyList()))
        assertEquals(600_000L, progress.observe(movie).first()!!.positionMs)
        assertEquals(listOf("e1"), progress.observeSeries(sid, "s1").first().map { it.ref.itemId })

        store.deleteSource(sid)
        assertNull(progress.observe(movie).first())
    }

    /** Continue Watching joins the catalog: one card per series on its latest episode, finished movies and disabled sources left out. */
    @Test
    fun continueWatchingJoinsTheCatalog() = runBlocking {
        var now = 1L
        progress = WatchProgressRepository(RoomWatchProgressStore(db.watchProgress())) { now }
        val sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
        val off = store.insertSource(SourceRecord(0, SourceType.XTREAM, "B", SourceConfig.Xtream("http://t", "u", "p"), false, null, null))
        val movies = listOf(Movie(sid, "m1", null, "EN - Heat (1995)", "http://p/heat", "mkv", null, null, title = "Heat (1995)", language = "EN"), Movie(sid, "m2", null, "Done", null, "mkv", null, null))
        store.replaceCatalog(sid, Catalog(emptyList(), emptyList(), movies, listOf(Series(sid, "s1", null, "Dark", "http://p/dark", null, null))))
        store.replaceCatalog(off, Catalog(emptyList(), emptyList(), listOf(Movie(off, "m9", null, "Hidden", null, "mkv", null, null)), emptyList()))

        progress.record(WatchRef(sid, WatchKind.MOVIE, "m1"), 600_000, 7_200_000, PlaybackInfo("Heat", "mkv")); now = 2
        progress.record(WatchRef(sid, WatchKind.MOVIE, "m2"), 7_100_000, 7_200_000); now = 3 // finished
        progress.record(WatchRef(off, WatchKind.MOVIE, "m9"), 600_000, 7_200_000); now = 4 // source disabled
        progress.record(WatchRef(sid, WatchKind.MOVIE, "gone"), 600_000, 7_200_000); now = 5 // not in the catalog
        progress.record(WatchRef(sid, WatchKind.EPISODE, "e1", "s1"), 600_000, 3_000_000); now = 6
        progress.record(WatchRef(sid, WatchKind.EPISODE, "e2", "s1"), 300_000, 3_000_000, PlaybackInfo("Dark S1 E2", "mp4", 1, 2))

        val items = progress.observeContinueWatching().first()
        assertEquals(listOf("Dark", "Heat (1995) (EN)"), items.map { it.title })
        assertEquals("e2", items[0].progress.ref.itemId)
        assertEquals(PlaybackInfo("Dark S1 E2", "mp4", 1, 2), items[0].progress.info)
        assertEquals("http://p/dark", items[0].imageUrl)
        assertEquals("EN - Heat (1995)", items[1].lookupName)

        progress.remove(items[0])
        assertEquals(emptyList<Any>(), progress.observeSeries(sid, "s1").first())
        assertEquals(listOf("m1"), progress.observeContinueWatching().first().map { it.itemId })
    }
}
