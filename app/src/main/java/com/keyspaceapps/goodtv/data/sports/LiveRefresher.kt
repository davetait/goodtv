package com.keyspaceapps.goodtv.data.sports

import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps event channel names current for the Sports screen: event channels are renamed through the
 * day, but a full sync only runs every 12 hours. Re-downloads just the live list of each enabled
 * source whose live channels are more than [MAX_AGE_MS] old (by its last sync or last refresh here).
 * Skipped while a full sync runs; a failure leaves the channels the store already has.
 */
class LiveRefresher(
    private val store: LibraryStore,
    private val registry: SourceRegistry,
    private val syncing: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val refreshedAt = HashMap<Long, Long>()

    suspend fun refreshIfStale(): Unit = lock.withLock {
        if (syncing()) return@withLock
        val now = clock()
        for (source in store.getSources().filter { it.enabled }) {
            val last = maxOf(source.lastSyncAt ?: 0L, refreshedAt[source.id] ?: 0L)
            if (now - last < MAX_AGE_MS) continue
            try {
                registry.forType(source.type).fetchLive(source.id, source.config).onSuccess {
                    store.replaceLive(source.id, it)
                    refreshedAt[source.id] = now
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what the store has; the next opening tries again.
            }
        }
    }

    companion object {
        const val MAX_AGE_MS = 30 * 60 * 1000L
    }
}
