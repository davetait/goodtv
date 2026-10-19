package com.keyspaceapps.goodtv.data.repo

import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.domain.PlayableRef
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRepositoryTest {
    private val store = FakeLibraryStore()
    private val source = FakeChannelSource()
    private val registry = SourceRegistry(listOf(source))
    private val settings = AppSettings(InMemoryDataStore())
    private val cfg = SourceConfig.Xtream("http://s", "u", "p")

    private fun repo(scope: kotlinx.coroutines.CoroutineScope) =
        LibraryRepository(store, registry, SyncManager(store, registry, scope, { 42L }), settings)

    @Test
    fun addSourceInsertsThenSyncs() = runTest {
        source.catalogResults[1L] = { Result.success(FakeChannelSource.catalog(1L, "A")) }
        val repo = repo(this)

        val id = repo.addSource("Home", cfg)
        advanceUntilIdle()

        assertEquals(1L, id)
        assertEquals("Home", store.getSource(id)!!.nickname)
        assertEquals(FakeChannelSource.catalog(1L, "A"), store.catalogs.value[id])
        assertEquals(42L, store.getSource(id)!!.lastSyncAt)
        assertEquals(1, repo.enabledSourceCount().first())
    }

    @Test
    fun movieDetailUsesCacheSecondTime() = runTest {
        val id = store.insertSource(SourceRecord(0, SourceType.XTREAM, "Home", cfg, true, null, null))
        val detail = MovieDetail(Movie(id, "m1", "mc", "Movie", null, "mkv", null, null), "plot", null, null, null, null, null, null)
        source.movieDetail = detail
        val repo = repo(this)

        assertEquals(detail, repo.movieDetail(id, "m1").getOrThrow())
        assertEquals(detail, repo.movieDetail(id, "m1").getOrThrow())

        assertEquals(listOf("m1"), source.movieDetailCalls)
    }

    @Test
    fun ensureDebugSourceOnlyOnce() = runTest {
        source.catalogResults[1L] = { Result.success(FakeChannelSource.catalog(1L, "Dev")) }
        val repo = repo(this)
        val record = SourceRecord(0, SourceType.XTREAM, "Dev", cfg, true, null, null)

        repo.ensureDebugSource(record)
        repo.ensureDebugSource(record)
        assertEquals(1, store.sources.value.size)

        store.deleteSource(1L)
        repo.ensureDebugSource(record)
        assertEquals(0, store.sources.value.size)
        repo.ensureDebugSource(null)
        assertEquals(0, store.sources.value.size)
    }

    @Test
    fun ensureDebugSourceSkipsWhenSourcesExist() = runTest {
        store.insertSource(SourceRecord(0, SourceType.XTREAM, "Mine", cfg, true, null, null))
        val repo = repo(this)
        repo.ensureDebugSource(SourceRecord(0, SourceType.XTREAM, "Dev", cfg, true, null, null))
        assertEquals(listOf("Mine"), store.sources.value.map { it.nickname })
    }

    @Test
    fun playableBuildsUrlFromSource() = runTest {
        val id = store.insertSource(SourceRecord(0, SourceType.XTREAM, "Home", cfg, true, null, null))
        val repo = repo(this)
        assertEquals(PlayableItem("Ch", "fake://live/5", isLive = true), repo.playable(id, PlayableRef.Live("5"), "Ch"))
        assertEquals(PlayableItem("Mv", "fake://movie/9.mkv", isLive = false), repo.playable(id, PlayableRef.Movie("9", "mkv"), "Mv"))
    }

    @Test
    fun removeSourceDeletesIt() = runTest {
        val id = store.insertSource(SourceRecord(0, SourceType.XTREAM, "Home", cfg, true, null, null))
        repo(this).removeSource(id)
        assertEquals(0, store.sources.value.size)
    }

    @Test
    fun addSourceReturnsBeforeSyncCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.catalogResults[1L] = { gate.await(); Result.success(FakeChannelSource.catalog(1L, "A")) }
        val repo = repo(this)

        val id = repo.addSource("Home", cfg)

        assertEquals(1L, id)
        assertEquals(null, store.catalogs.value[id])
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(FakeChannelSource.catalog(1L, "A"), store.catalogs.value[id])
    }

    @Test
    fun removeSourceCancelsInFlightRefresh() = runTest {
        val id = store.insertSource(SourceRecord(0, SourceType.XTREAM, "Home", cfg, true, null, null))
        val gate = CompletableDeferred<Unit>()
        source.catalogResults[id] = { gate.await(); Result.success(FakeChannelSource.catalog(id, "A")) }
        store.replaceCatalogError = IllegalStateException("FOREIGN KEY constraint failed")
        val repo = repo(this)
        repo.refresh(id)
        advanceUntilIdle()

        repo.removeSource(id)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, store.sources.value.size)
        assertEquals(SyncState.Idle, repo.syncState.value)
    }

    @Test
    fun updateSourceCancelsOldRefreshAndSyncsNewConfig() = runTest {
        val id = store.insertSource(SourceRecord(0, SourceType.XTREAM, "Home", cfg, true, null, null))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        source.catalogResults[id] = {
            calls++
            if (calls == 1) { gate.await(); Result.failure(com.keyspaceapps.goodtv.data.source.SourceException("old config failed")) }
            else Result.success(FakeChannelSource.catalog(id, "new"))
        }
        val repo = repo(this)
        repo.refresh(id)
        advanceUntilIdle()

        repo.updateSource(id, "Home", cfg.copy(server = "http://fixed"))
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("http://fixed", (store.getSource(id)!!.config as SourceConfig.Xtream).server)
        assertEquals(FakeChannelSource.catalog(id, "new"), store.catalogs.value[id])
        assertEquals(null, store.getSource(id)!!.lastError)
    }
}
