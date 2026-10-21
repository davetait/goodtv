package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.source.LiveCatalog
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SportsCandidatesTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var store: RoomLibraryStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        store = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
    }

    @After fun tearDown() = db.close()

    private fun record(n: String, enabled: Boolean = true) = SourceRecord(0, SourceType.XTREAM, n, SourceConfig.Xtream("http://$n", "u", "p"), enabled, 1L, null)

    @Test fun visibleEnabledLiveChannelsWithCategoryNames() = runBlocking {
        val a = store.insertSource(record("a"))
        val off = store.insertSource(record("off", enabled = false))
        store.replaceCatalog(a, Catalog(
            listOf(Category(a, ContentKind.LIVE, "nhl", "US| NHL PPV"), Category(a, ContentKind.LIVE, "x", "Hidden")),
            listOf(LiveChannel(a, "1", "nhl", "LIVE | NHL A - B", null, 1), LiveChannel(a, "2", "x", "LIVE | NHL C - D", null, 2)),
            emptyList(), emptyList(),
        ))
        store.replaceCatalog(off, Catalog(listOf(Category(off, ContentKind.LIVE, "nhl", "US| NHL PPV")), listOf(LiveChannel(off, "9", "nhl", "LIVE | NHL E - F", null, 9)), emptyList(), emptyList()))
        store.setCategoriesHidden(a, ContentKind.LIVE, listOf("x"), hidden = true)
        val got = store.observeSportsCandidates().first()
        assertEquals(listOf("1"), got.map { it.channel.id })
        assertEquals("US| NHL PPV", got.single().categoryName)
    }

    @Test fun replaceLiveTouchesOnlyLive() = runBlocking {
        val a = store.insertSource(record("a"))
        store.replaceCatalog(a, Catalog(
            listOf(Category(a, ContentKind.LIVE, "nhl", "US| NHL PPV"), Category(a, ContentKind.LIVE, "x", "Hidden"), Category(a, ContentKind.MOVIE, "m", "Films")),
            listOf(LiveChannel(a, "1", "nhl", "old", null, 1)),
            listOf(Movie(a, "mv", "m", "Heat", null, "mkv", null, null)), emptyList(),
        ))
        store.setCategoriesHidden(a, ContentKind.LIVE, listOf("x"), hidden = true)
        store.replaceLive(a, LiveCatalog(
            listOf(Category(a, ContentKind.LIVE, "nhl", "US| NHL PPV"), Category(a, ContentKind.LIVE, "x", "Hidden")),
            listOf(LiveChannel(a, "1", "nhl", "LIVE | NHL A - B", null, 1), LiveChannel(a, "2", "x", "hidden one", null, 2)),
        ))
        assertEquals(listOf("LIVE | NHL A - B"), store.observeSportsCandidates().first().map { it.channel.name })
        assertEquals(setOf(ContentKind.LIVE to "x"), store.observeHiddenCategories(a).first())
        assertEquals("Heat", store.getMovie(a, "mv")?.name)
        assertEquals(1L, store.getSource(a)?.lastSyncAt)
    }
}
