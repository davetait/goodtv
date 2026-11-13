package com.keyspaceapps.goodtv.data.repo

import com.keyspaceapps.goodtv.data.source.SourceException
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Refreshes sources into the [LibraryStore]. One in-flight refresh per source; sources refresh
 * independently so one failing never blocks another. A failed refresh keeps the old rows. Nothing
 * thrown inside a refresh escapes: every failure becomes a recorded error, never a crash.
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

    /**
     * Starts a refresh, or returns the one already in flight. A cancelled refresh is no longer active
     * but may still be running (a blocking download sees the cancel only when it returns), and its
     * clean-up clears the source's staging; so the new refresh first waits for it to finish. That keeps
     * one catalog writer per source at a time.
     *
     * The wait itself must not be cut short: if this refresh is cancelled too (two quick edits), a third
     * refresh waits only for this one, so this one has to outlive the older one. Hence the wait is
     * non-cancellable, and the job starts atomically so a cancel before it first runs still waits.
     */
    fun refresh(sourceId: Long): Job = synchronized(lock) {
        val previous = jobs[sourceId]
        previous?.takeIf { it.isActive }?.let { return it }
        lastError = null
        val job = scope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            val result = doRefresh(sourceId)
            synchronized(lock) {
                results[sourceId] = result
                result.exceptionOrNull()?.let { lastError = SyncState.Error(sourceId, it.message ?: "Unknown error") }
            }
        }
        jobs[sourceId] = job
        // Runs on normal completion, failure and cancellation alike, so the in-flight set stays honest.
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[sourceId] === job) jobs.remove(sourceId)
                publish()
            }
        }
        publish()
        job
    }

    /** Stops an in-flight refresh (used before a source is edited or removed). */
    fun cancel(sourceId: Long) {
        val job = synchronized(lock) { jobs[sourceId] }
        job?.cancel()
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

    /** Streams the catalog into a writer: commits only on success, abandons on failure or cancellation. */
    private suspend fun doRefresh(sourceId: Long): Result<Unit> {
        var writer: CatalogWriter? = null
        try {
            val record = store.getSource(sourceId) ?: return Result.failure(SourceException("Source not found"))
            val source = registry.forType(record.type)
            val w = store.beginCatalog(sourceId).also { writer = it }
            return source.streamCatalog(sourceId, record.config, w).fold(
                onSuccess = {
                    w.commit()
                    store.markSync(sourceId, clock(), null)
                    Result.success(Unit)
                },
                onFailure = { e ->
                    // A failed clean-up must not replace the provider's error; the next sync clears staging anyway.
                    runCatching { w.abandon() }
                    store.markSync(sourceId, null, e.message ?: "Unknown error")
                    Result.failure(e)
                },
            )
        } catch (e: CancellationException) {
            // The job is cancelled, so the clean-up must not be.
            writer?.let { w -> withContext(NonCancellable) { runCatching { w.abandon() } } }
            throw e
        } catch (e: Throwable) {
            // Errors too (OutOfMemoryError on a huge catalog): the user must see why nothing synced.
            runCatching { writer?.abandon() }
            val message = when (e) {
                is OutOfMemoryError -> "Out of memory while loading the catalog; the provider's list is too large"
                else -> e.message ?: e.javaClass.simpleName
            }
            runCatching { store.markSync(sourceId, null, message) }
            return Result.failure(SourceException(message, e))
        }
    }

    private fun publish() {
        val active = jobs.filterValues { it.isActive }.keys
        _state.value = when {
            active.isNotEmpty() -> SyncState.Syncing(active.toSet())
            else -> lastError ?: SyncState.Idle
        }
    }
}
