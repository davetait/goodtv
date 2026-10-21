package com.keyspaceapps.goodtv.data.db

import com.keyspaceapps.goodtv.data.watchlist.WatchListItem
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.data.watchlist.WatchListStore
import com.keyspaceapps.goodtv.domain.displayLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomWatchListStore(private val dao: WatchListDao) : WatchListStore {
    override fun observeSaved(): Flow<Set<WatchListRef>> = dao.observeAll().map { rows -> rows.map { it.ref() }.toSet() }

    override fun observeRow(): Flow<List<WatchListItem>> = dao.observeRow().map { rows ->
        rows.map { WatchListItem(it.saved.ref(), displayLabel(it.itemTitle, it.itemLanguage, it.itemQuality), it.imageUrl, it.itemTmdbId, it.rawName, it.saved.savedAt) }
    }

    override suspend fun add(ref: WatchListRef, savedAt: Long) = dao.upsert(WatchListEntity(ref.sourceId, ref.kind.name, ref.itemId, savedAt))

    override suspend fun remove(ref: WatchListRef) = dao.delete(ref.sourceId, ref.kind.name, ref.itemId)
}

private fun WatchListEntity.ref() = WatchListRef(sourceId, WatchListKind.valueOf(kind), itemId)
