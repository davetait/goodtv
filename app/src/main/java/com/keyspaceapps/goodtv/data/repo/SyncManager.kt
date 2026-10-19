package com.keyspaceapps.goodtv.data.repo

import com.keyspaceapps.goodtv.data.source.SourceException
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Refreshes sources into the [LibraryStore]. One in-flight refresh per source; sources refresh
 * independently so one failing never blocks another. A failed refresh keeps the old rows.
 */
class SyncManager(
    private val store: LibraryStore,
    private val registry: SourceRegistry,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = 43_200_000L,
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val lock = Any()
    private val jobs = HashMap<Long, Job>()
    private val results = HashMap<Long, Result<Unit>>()
    private var lastError: SyncState.Error? = null

    fun refresh(sourceId: Long): Job = synchronized(lock) {
        jobs[sourceId]?.takeIf { it.isActive }?.let { return it }
        lastError = null
        val job = scope.launch {
            val result = doRefresh(sourceId)
            synchronized(lock) {
                jobs.remove(sourceId)
                results[sourceId] = result
                result.exceptionOrNull()?.let { lastError = SyncState.Error(sourceId, it.message ?: "Unknown error") }
                publish()
            }
        }
        jobs[sourceId] = job
        publish()
        job
    }

    /** Enabled sources never synced, or synced longer than [staleAfterMs] ago. */
    fun refreshStale(): Job = scope.launch {
        val now = clock()
        store.getSources()
            .filter { it.enabled && (it.lastSyncAt == null || now - it.lastSyncAt > staleAfterMs) }
            .map { refresh(it.id) }
            .joinAll()
    }

    fun refreshAll(): Job = scope.launch {
        store.getSources().filter { it.enabled }.map { refresh(it.id) }.joinAll()
    }

    suspend fun refreshNow(sourceId: Long): Result<Unit> {
        refresh(sourceId).join()
        return synchronized(lock) { results[sourceId] } ?: Result.failure(SourceException("Refresh did not run"))
    }

    private suspend fun doRefresh(sourceId: Long): Result<Unit> {
        val record = store.getSource(sourceId) ?: return Result.failure(SourceException("Source not found"))
        val source = registry.forType(record.type)
        return source.fetchCatalog(sourceId, record.config).fold(
            onSuccess = { catalog ->
                store.replaceCatalog(sourceId, catalog)
                store.markSync(sourceId, clock(), null)
                Result.success(Unit)
            },
            onFailure = { e ->
                store.markSync(sourceId, null, e.message ?: "Unknown error")
                Result.failure(e)
            },
        )
    }

    private fun publish() {
        val active = jobs.filterValues { it.isActive }.keys
        _state.value = when {
            active.isNotEmpty() -> SyncState.Syncing(active.toSet())
            else -> lastError ?: SyncState.Idle
        }
    }
}
