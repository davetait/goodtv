package com.keyspaceapps.goodtv.data.repo

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncManagerTest {
    private val store = FakeLibraryStore()
    private val source = FakeChannelSource()
    private val registry = SourceRegistry(listOf(source))
    private var now = 100L * 3_600_000L

    private suspend fun addSource(nick: String, lastSyncAt: Long? = null): Long = store.insertSource(
        SourceRecord(0, SourceType.XTREAM, nick, SourceConfig.Xtream("http://s", "u", "p"), true, lastSyncAt, null),
    )

    @Test
    fun successReplacesCatalogAndMarksSync() = runTest {
        val id = addSource("A")
        val catalog = FakeChannelSource.catalog(id, "A")
        source.catalogResults[id] = { Result.success(catalog) }
        val sync = SyncManager(store, registry, this, { now })

        val result = sync.refreshNow(id)

        assertTrue(result.isSuccess)
        assertEquals(catalog, store.catalogs.value[id])
        assertEquals(now, store.getSource(id)!!.lastSyncAt)
        assertEquals(SyncState.Idle, sync.state.value)
    }

    @Test
    fun failureKeepsOldRowsAndRecordsError() = runTest {
        val id = addSource("A", lastSyncAt = 5L)
        val old = FakeChannelSource.catalog(id, "old")
        store.replaceCatalog(id, old)
        source.catalogResults[id] = { Result.failure(SourceException("Could not reach server: boom")) }
        val sync = SyncManager(store, registry, this, { now })

        val result = sync.refreshNow(id)

        assertTrue(result.isFailure)
        assertEquals(old, store.catalogs.value[id])
        assertEquals("Could not reach server: boom", store.getSource(id)!!.lastError)
        assertEquals(5L, store.getSource(id)!!.lastSyncAt)
        assertEquals(SyncState.Error(id, "Could not reach server: boom"), sync.state.value)
    }

    @Test
    fun twoSourcesOneFailsOtherUpdates() = runTest {
        val a = addSource("A")
        val b = addSource("B")
        val oldB = FakeChannelSource.catalog(b, "oldB")
        store.replaceCatalog(b, oldB)
        val newA = FakeChannelSource.catalog(a, "A")
        source.catalogResults[a] = { Result.success(newA) }
        source.catalogResults[b] = { Result.failure(SourceException("Could not reach server: timeout")) }
        val sync = SyncManager(store, registry, this, { now })

        sync.refreshAll().join()

        assertEquals(newA, store.catalogs.value[a])
        assertEquals(oldB, store.catalogs.value[b])
        assertEquals(now, store.getSource(a)!!.lastSyncAt)
        assertEquals("Could not reach server: timeout", store.getSource(b)!!.lastError)
        assertEquals(SyncState.Error(b, "Could not reach server: timeout"), sync.state.value)
    }

    @Test
    fun refreshStaleSkipsFreshSources() = runTest {
        val fresh = addSource("Fresh", lastSyncAt = now - 1 * 3_600_000L)
        val stale = addSource("Stale", lastSyncAt = now - 13 * 3_600_000L)
        val never = addSource("Never", lastSyncAt = null)
        listOf(fresh, stale, never).forEach { id -> source.catalogResults[id] = { Result.success(FakeChannelSource.catalog(id, "x")) } }
        val sync = SyncManager(store, registry, this, { now })

        sync.refreshStale().join()

        assertEquals(setOf(stale, never), source.fetchCatalogCalls.toSet())
    }

    @Test
    fun duplicateRefreshIsNoop() = runTest {
        val id = addSource("A")
        val gate = CompletableDeferred<Unit>()
        source.catalogResults[id] = { gate.await(); Result.success(FakeChannelSource.catalog(id, "A")) }
        val sync = SyncManager(store, registry, this, { now })

        val first = sync.refresh(id)
        val second = sync.refresh(id)
        assertEquals(SyncState.Syncing(setOf(id)), sync.state.value.also { testScheduler.runCurrent() }.let { sync.state.value })
        gate.complete(Unit)
        first.join(); second.join()

        assertEquals(1, source.fetchCatalogCalls.size)
        assertEquals(SyncState.Idle, sync.state.value)
    }

    @Test
    fun disabledSourcesAreNotRefreshedByRefreshAll() = runTest {
        val a = addSource("A")
        val b = addSource("B")
        store.updateSource(store.getSource(b)!!.copy(enabled = false))
        listOf(a, b).forEach { id -> source.catalogResults[id] = { Result.success(FakeChannelSource.catalog(id, "x")) } }
        val sync = SyncManager(store, registry, this, { now })

        sync.refreshAll().join()

        assertEquals(listOf(a), source.fetchCatalogCalls)
    }

    @Test
    fun exceptionInsideRefreshBecomesFailureNotCrash() = runTest {
        val id = addSource("A")
        source.catalogResults[id] = { Result.success(FakeChannelSource.catalog(id, "A")) }
        store.replaceCatalogError = IllegalStateException("FOREIGN KEY constraint failed")
        val sync = SyncManager(store, registry, this, { now })

        val result = sync.refreshNow(id)

        assertTrue(result.isFailure)
        assertEquals("FOREIGN KEY constraint failed", store.getSource(id)!!.lastError)
        assertEquals(SyncState.Error(id, "FOREIGN KEY constraint failed"), sync.state.value)
    }

    @Test
    fun cancelStopsInFlightRefreshAndWritesNothing() = runTest {
        val id = addSource("A")
        val gate = CompletableDeferred<Unit>()
        source.catalogResults[id] = { gate.await(); Result.success(FakeChannelSource.catalog(id, "A")) }
        val sync = SyncManager(store, registry, this, { now })

        val job = sync.refresh(id)
        testScheduler.runCurrent()
        assertEquals(SyncState.Syncing(setOf(id)), sync.state.value)
        sync.cancel(id)
        gate.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(null, store.catalogs.value[id])
        assertEquals(SyncState.Idle, sync.state.value)
    }

    @Test
    fun outOfMemoryInsideRefreshIsRecordedAsAnError() = runTest {
        val id = addSource("A")
        source.catalogResults[id] = { Result.success(FakeChannelSource.catalog(id, "A")) }
        store.replaceCatalogError = OutOfMemoryError("Failed to allocate")
        val sync = SyncManager(store, registry, this, { now })

        val result = sync.refreshNow(id)

        assertTrue(result.isFailure)
        assertEquals("Out of memory while loading the catalog; the provider's list is too large", store.getSource(id)!!.lastError)
        assertEquals(SyncState.Error(id, "Out of memory while loading the catalog; the provider's list is too large"), sync.state.value)
    }

    @Test
    fun successfulSyncCommitsTheStreamedCatalog() = runTest {
        val id = addSource("A")
        val liveCats = listOf(Category(id, ContentKind.LIVE, "c1", "Sports"))
        val movieCats = listOf(Category(id, ContentKind.MOVIE, "mc", "Films"))
        val live = listOf(LiveChannel(id, "1", "c1", "Channel 1", null, 1))
        val movies = listOf(Movie(id, "m1", "mc", "Movie 1", null, "mkv", null, null))
        val series = listOf(Series(id, "s1", null, "Series 1", null, null, null))
        // Lists arrive in whatever order their downloads finish.
        source.streamScripts[id] = { w ->
            w.series(series)
            w.categories(ContentKind.MOVIE, movieCats)
            w.live(live)
            w.categories(ContentKind.SERIES, emptyList())
            w.movies(movies)
            w.categories(ContentKind.LIVE, liveCats)
            Result.success(Unit)
        }
        val sync = SyncManager(store, registry, this, { now })

        val result = sync.refreshNow(id)

        assertTrue(result.isSuccess)
        assertEquals(Catalog(liveCats + movieCats, live, movies, series), store.catalogs.value[id])
        assertEquals(1, store.commitCalls)
        assertEquals(now, store.getSource(id)!!.lastSyncAt)
        assertEquals(null, store.getSource(id)!!.lastError)
        assertEquals(SyncState.Idle, sync.state.value)
    }

    @Test
    fun failedSyncAbandonsAndKeepsTheLibrary() = runTest {
        val id = addSource("A", lastSyncAt = 5L)
        val old = FakeChannelSource.catalog(id, "old")
        store.replaceCatalog(id, old)
        source.streamScripts[id] = { w ->
            w.movies(listOf(Movie(id, "new", "mc", "New movie", null, "mkv", null, null)))
            Result.failure(SourceException("Server returned HTTP 500"))
        }
        val sync = SyncManager(store, registry, this, { now })

        val result = sync.refreshNow(id)

        assertTrue(result.isFailure)
        assertEquals(old, store.catalogs.value[id])
        assertEquals(0, store.commitCalls)
        assertEquals(1, store.abandonCalls)
        assertEquals("Server returned HTTP 500", store.getSource(id)!!.lastError)
        assertEquals(5L, store.getSource(id)!!.lastSyncAt)
        assertEquals(SyncState.Error(id, "Server returned HTTP 500"), sync.state.value)
    }

    @Test
    fun cancelledSyncAbandonsTheWriter() = runTest {
        val id = addSource("A", lastSyncAt = 5L)
        val old = FakeChannelSource.catalog(id, "old")
        store.replaceCatalog(id, old)
        val midStream = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        source.streamScripts[id] = { w ->
            w.movies(listOf(Movie(id, "new", "mc", "New movie", null, "mkv", null, null)))
            midStream.complete(Unit)
            never.await()
            Result.success(Unit)
        }
        val sync = SyncManager(store, registry, this, { now })

        val job = sync.refresh(id)
        midStream.await()
        sync.cancel(id)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(old, store.catalogs.value[id])
        assertEquals(0, store.commitCalls)
        assertEquals(1, store.abandonCalls)
        assertEquals(5L, store.getSource(id)!!.lastSyncAt)
        assertEquals(SyncState.Idle, sync.state.value)
    }

    @Test
    fun failedCleanUpStillRecordsTheProvidersError() = runTest {
        val id = addSource("A", lastSyncAt = 5L)
        val old = FakeChannelSource.catalog(id, "old")
        store.replaceCatalog(id, old)
        store.abandonError = IllegalStateException("disk I/O error")
        source.streamScripts[id] = { w ->
            w.movies(listOf(Movie(id, "new", "mc", "New movie", null, "mkv", null, null)))
            Result.failure(SourceException("Server returned HTTP 500"))
        }
        val sync = SyncManager(store, registry, this, { now })

        val result = sync.refreshNow(id)

        assertEquals("Server returned HTTP 500", result.exceptionOrNull()?.message)
        assertEquals(old, store.catalogs.value[id])
        assertEquals("Server returned HTTP 500", store.getSource(id)!!.lastError)
        assertEquals(5L, store.getSource(id)!!.lastSyncAt)
        assertEquals(SyncState.Error(id, "Server returned HTTP 500"), sync.state.value)
    }
}
