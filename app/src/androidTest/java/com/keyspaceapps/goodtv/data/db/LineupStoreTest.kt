package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.guide.LiveGenre
import com.keyspaceapps.goodtv.data.guide.Network
import com.keyspaceapps.goodtv.data.guide.SavedFeed
import com.keyspaceapps.goodtv.data.guide.SavedLineup
import com.keyspaceapps.goodtv.data.guide.SavedRow
import com.keyspaceapps.goodtv.data.guide.Visibility
import com.keyspaceapps.goodtv.data.source.LiveCatalog
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
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

class LineupStoreTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var library: RoomLibraryStore
    private lateinit var lineup: RoomLineupStore

    private fun source(nickname: String = "A", enabled: Boolean = true) =
        SourceRecord(0, SourceType.XTREAM, nickname, SourceConfig.Xtream("http://s", "u", "p"), enabled, null, null)

    private fun feed(n: Int, rowKey: String) = SavedFeed(
        LiveChannel(7L + n, "$rowKey-$n", "cat$n", "Raw $rowKey $n", "http://logo/$n", n, "Title $n", "EN", "HD", "epg.$n"),
        "Category $n",
    )

    private fun row(key: String, position: Int, full: Boolean) = SavedRow(
        key, position, "Row $key", if (full) LiveGenre.NETWORK else LiveGenre.NEWS,
        if (full) Network.NBC else null, if (full) "WNBC" else null, if (full) "New York" else null,
        if (full) "Seinfeld" else null, if (full) "http://provider/logo" else null, "sort-$key", if (full) "loop-$key" else null, full,
        listOf(feed(0, key), feed(1, key), feed(2, key)),
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        library = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
        lineup = RoomLineupStore(db)
    }

    @After fun tearDown() = db.close()

    @Test
    fun saveThenReadRoundTripsRowsFeedsInOrder() = runBlocking {
        assertNull(lineup.saved())
        val saved = SavedLineup(listOf(row("b", 1, full = false), row("a", 0, full = true)), generation = 5, rulesVersion = 3)
        lineup.save(saved)
        // Rows come back by position, each with its feeds in their saved order and every field intact.
        assertEquals(SavedLineup(saved.rows.sortedBy { it.position }, 5, 3), lineup.saved())
        assertEquals(lineup.saved(), lineup.observeSaved().first())
    }

    @Test
    fun saveReplacesEverything() = runBlocking {
        lineup.save(SavedLineup(listOf(row("a", 0, true), row("b", 1, false)), 1, 1))
        val second = SavedLineup(listOf(row("c", 0, false)), 2, 2)
        lineup.save(second)
        assertEquals(second, lineup.saved())
        db.query("SELECT COUNT(*) FROM lineup_feeds", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
    }

    @Test
    fun saveHandlesALineupLargerThanOneBatch() = runBlocking {
        val rows = (0 until 1200).map { i -> row("r%04d".format(i), i, full = false).copy(feeds = listOf(feed(0, "r%04d".format(i)))) }
        lineup.save(SavedLineup(rows, 1, 1))
        assertEquals(rows, lineup.saved()!!.rows)
    }

    @Test
    fun writesBumpTheGeneration() = runBlocking {
        assertEquals(0L, lineup.generation())
        var last = 0L
        suspend fun assertBumped(what: String) {
            val now = lineup.generation()
            assertTrue("$what should bump the generation ($last -> $now)", now > last)
            last = now
        }
        val id = library.insertSource(source()); assertBumped("insertSource")
        library.updateSource(source("B").copy(id = id)); assertBumped("updateSource")
        library.replaceCatalog(id, Catalog(listOf(Category(id, ContentKind.LIVE, "c1", "News")), emptyList(), emptyList(), emptyList())); assertBumped("replaceCatalog")
        library.replaceLive(id, LiveCatalog(listOf(Category(id, ContentKind.LIVE, "c1", "News")), emptyList())); assertBumped("replaceLive")
        library.setCategoriesHidden(id, ContentKind.LIVE, listOf("c1"), true); assertBumped("setCategoriesHidden")
        library.deleteSource(id); assertBumped("deleteSource")
        assertEquals(last, lineup.observeGeneration().first())
    }

    @Test
    fun markSyncDoesNotBumpTheGeneration() = runBlocking {
        val id = library.insertSource(source())
        val before = lineup.generation()
        library.markSync(id, 5L, null)
        assertEquals(before, lineup.generation())
    }

    @Test
    fun visibilityReflectsDisabledSourcesAndHiddenCategories() = runBlocking {
        val a = library.insertSource(source("A"))
        val b = library.insertSource(source("B", enabled = false))
        library.replaceLive(a, LiveCatalog(listOf(Category(a, ContentKind.LIVE, "c1", "News"), Category(a, ContentKind.LIVE, "c2", "Sports")), emptyList()))
        assertEquals(Visibility(setOf(a), emptySet()), lineup.observeVisibility().first())

        library.setCategoriesHidden(a, ContentKind.LIVE, listOf("c2"), true)
        library.setCategoriesHidden(a, ContentKind.MOVIE, listOf("m1"), true) // other kinds never show up
        assertEquals(Visibility(setOf(a), setOf(a to "c2")), lineup.observeVisibility().first())

        library.updateSource(source("B").copy(id = b))
        assertEquals(Visibility(setOf(a, b), setOf(a to "c2")), lineup.observeVisibility().first())

        library.setCategoriesHidden(a, ContentKind.LIVE, listOf("c2"), false)
        assertEquals(Visibility(setOf(a, b), emptySet()), lineup.observeVisibility().first())
    }
}
