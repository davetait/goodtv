package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.WatchProgressStore
import com.keyspaceapps.goodtv.data.progress.WatchRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryWatchProgressStore : WatchProgressStore {
    private fun key(r: WatchRef) = Triple(r.sourceId, r.kind, r.itemId)
    val rows = MutableStateFlow<Map<Triple<Long, WatchKind, String>, WatchProgress>>(emptyMap())
    override fun observe(ref: WatchRef): Flow<WatchProgress?> = rows.map { it[key(ref)] }
    override fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgress>> = rows.map { m ->
        m.values.filter { it.ref.sourceId == sourceId && it.ref.kind == WatchKind.EPISODE && it.ref.seriesId == seriesId }.sortedByDescending { it.updatedAt }
    }
    override suspend fun upsert(progress: WatchProgress) { rows.value = rows.value + (key(progress.ref) to progress) }
    override suspend fun delete(ref: WatchRef) { rows.value = rows.value - key(ref) }
}
