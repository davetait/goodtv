package com.keyspaceapps.goodtv.data.sports

import com.keyspaceapps.goodtv.data.source.LiveCatalog
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRefresherTest {
    private val store = FakeLibraryStore()
    private val source = FakeChannelSource()
    private var now = 10_000_000L
    private var syncing = false
    private val refresher = LiveRefresher(store, SourceRegistry(listOf(source)), { syncing }, { now })

    private suspend fun add(name: String, lastSyncAgoMs: Long, enabled: Boolean = true): Long {
        val id = store.insertSource(SourceRecord(0, SourceType.XTREAM, name, SourceConfig.Xtream("http://$name", "u", "p"), enabled, now - lastSyncAgoMs, null))
        source.liveResults[id] = { Result.success(LiveCatalog(listOf(Category(id, ContentKind.LIVE, "c", "US| NHL PPV")), listOf(LiveChannel(id, "1", "c", "LIVE | NHL X - Y", null, 1)))) }
        return id
    }

    @Test fun refreshesOnlyStaleEnabledSources() = runTest {
        val fresh = add("fresh", 10 * 60_000L)
        val stale = add("stale", 40 * 60_000L)
        add("off", 40 * 60_000L, enabled = false)
        refresher.refreshIfStale()
        assertEquals(listOf(stale), source.fetchLiveCalls)
        assertTrue(fresh !in source.fetchLiveCalls)
    }

    @Test fun twiceInARowFetchesOnce() = runTest {
        add("stale", 40 * 60_000L)
        refresher.refreshIfStale(); refresher.refreshIfStale()
        assertEquals(1, source.fetchLiveCalls.size)
        now += LiveRefresher.MAX_AGE_MS
        refresher.refreshIfStale()
        assertEquals(2, source.fetchLiveCalls.size)
    }

    @Test fun skippedWhileAFullSyncRuns() = runTest {
        add("stale", 40 * 60_000L)
        syncing = true
        refresher.refreshIfStale()
        assertEquals(emptyList<Long>(), source.fetchLiveCalls)
    }

    @Test fun replaceLiveKeepsMoviesSeriesAndHidden() = runTest {
        val id = add("stale", 40 * 60_000L)
        store.replaceCatalog(id, Catalog(
            listOf(Category(id, ContentKind.LIVE, "c", "US| NHL PPV"), Category(id, ContentKind.MOVIE, "m", "Films")),
            listOf(LiveChannel(id, "old", "c", "old name", null, 1)),
            listOf(Movie(id, "mv", "m", "Heat", null, "mkv", null, null)), emptyList(),
        ))
        store.setCategoriesHidden(id, ContentKind.LIVE, listOf("c"), hidden = true)
        refresher.refreshIfStale()
        val c = store.catalogs.value.getValue(id)
        assertEquals(listOf("LIVE | NHL X - Y"), c.live.map { it.name })
        assertEquals(listOf("Heat"), c.movies.map { it.name })
        assertEquals(setOf(ContentKind.LIVE to "c"), store.observeHiddenCategories(id).first())
    }
}
