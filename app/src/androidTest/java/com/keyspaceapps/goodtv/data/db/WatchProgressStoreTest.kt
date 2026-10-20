package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgressRepository
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
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
}
