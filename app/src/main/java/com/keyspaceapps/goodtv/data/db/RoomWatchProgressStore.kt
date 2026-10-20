package com.keyspaceapps.goodtv.data.db

import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.WatchProgressStore
import com.keyspaceapps.goodtv.data.progress.WatchRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomWatchProgressStore(private val dao: WatchProgressDao) : WatchProgressStore {
    override fun observe(ref: WatchRef): Flow<WatchProgress?> = dao.observe(ref.sourceId, ref.kind.name, ref.itemId).map { it?.toDomain() }

    override fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgress>> =
        dao.observeSeries(sourceId, seriesId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsert(progress: WatchProgress) = dao.upsert(
        with(progress) { WatchProgressEntity(ref.sourceId, ref.kind.name, ref.itemId, ref.seriesId, positionMs, durationMs, finished, updatedAt) },
    )

    override suspend fun delete(ref: WatchRef) = dao.delete(ref.sourceId, ref.kind.name, ref.itemId)
}

private fun WatchProgressEntity.toDomain() =
    WatchProgress(WatchRef(sourceId, WatchKind.valueOf(kind), itemId, seriesId), positionMs, durationMs, finished, updatedAt)
