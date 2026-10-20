package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.progress.ContinueItem
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.WatchProgressStore
import com.keyspaceapps.goodtv.data.progress.WatchRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory progress. [catalog] stands in for the movies/series tables Continue Watching joins:
 * (sourceId, movie or series id) → display title; rows without an entry are left out, like the SQL join.
 */
class InMemoryWatchProgressStore(var catalog: Map<Pair<Long, String>, String> = emptyMap()) : WatchProgressStore {
    private fun key(r: WatchRef) = Triple(r.sourceId, r.kind, r.itemId)
    val rows = MutableStateFlow<Map<Triple<Long, WatchKind, String>, WatchProgress>>(emptyMap())

    override fun observe(ref: WatchRef): Flow<WatchProgress?> = rows.map { it[key(ref)] }
    override fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgress>> = rows.map { m ->
        m.values.filter { it.ref.sourceId == sourceId && it.ref.kind == WatchKind.EPISODE && it.ref.seriesId == seriesId }.sortedByDescending { it.updatedAt }
    }
    override suspend fun upsert(progress: WatchProgress) { rows.value = rows.value + (key(progress.ref) to progress) }
    override suspend fun delete(ref: WatchRef) { rows.value = rows.value - key(ref) }
    override suspend fun deleteSeries(sourceId: Long, seriesId: String) {
        rows.value = rows.value.filterValues { !(it.ref.sourceId == sourceId && it.ref.kind == WatchKind.EPISODE && it.ref.seriesId == seriesId) }
    }

    private fun item(p: WatchProgress, id: String) = catalog[p.ref.sourceId to id]?.let { ContinueItem(p, it, null, null, it) }

    override fun observeContinueMovies(limit: Int): Flow<List<ContinueItem>> = rows.map { m ->
        m.values.filter { it.ref.kind == WatchKind.MOVIE && !it.finished }.sortedByDescending { it.updatedAt }
            .mapNotNull { item(it, it.ref.itemId) }.take(limit)
    }

    override fun observeContinueSeries(limit: Int): Flow<List<ContinueItem>> = rows.map { m ->
        m.values.filter { it.ref.kind == WatchKind.EPISODE && it.ref.seriesId != null }
            .groupBy { it.ref.sourceId to it.ref.seriesId!! }.values.map { eps -> eps.maxBy { it.updatedAt } }
            .sortedByDescending { it.updatedAt }.mapNotNull { item(it, it.ref.seriesId!!) }.take(limit)
    }
}
