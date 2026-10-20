package com.keyspaceapps.goodtv.data.db

import com.keyspaceapps.goodtv.data.progress.ContinueItem
import com.keyspaceapps.goodtv.data.progress.PlaybackInfo
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.WatchProgressStore
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.domain.displayLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomWatchProgressStore(private val dao: WatchProgressDao) : WatchProgressStore {
    override fun observe(ref: WatchRef): Flow<WatchProgress?> = dao.observe(ref.sourceId, ref.kind.name, ref.itemId).map { it?.toDomain() }

    override fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgress>> =
        dao.observeSeries(sourceId, seriesId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsert(progress: WatchProgress) = dao.upsert(
        with(progress) {
            WatchProgressEntity(
                ref.sourceId, ref.kind.name, ref.itemId, ref.seriesId, positionMs, durationMs, finished, updatedAt,
                info?.title, info?.ext, info?.season, info?.episode,
            )
        },
    )

    override suspend fun delete(ref: WatchRef) = dao.delete(ref.sourceId, ref.kind.name, ref.itemId)

    override suspend fun deleteSeries(sourceId: Long, seriesId: String) = dao.deleteSeries(sourceId, seriesId)

    override fun observeContinueMovies(limit: Int): Flow<List<ContinueItem>> = dao.observeContinueMovies(limit).map { rows -> rows.map { it.toItem() } }

    override fun observeContinueSeries(limit: Int): Flow<List<ContinueItem>> = dao.observeContinueSeries(limit).map { rows -> rows.map { it.toItem() } }
}

private fun WatchProgressEntity.toDomain() = WatchProgress(
    WatchRef(sourceId, WatchKind.valueOf(kind), itemId, seriesId), positionMs, durationMs, finished, updatedAt,
    title?.let { PlaybackInfo(it, ext, season, episode) },
)

private fun ContinueRow.toItem() =
    ContinueItem(progress.toDomain(), displayLabel(itemTitle, itemLanguage, itemQuality), imageUrl, itemTmdbId, rawName)
