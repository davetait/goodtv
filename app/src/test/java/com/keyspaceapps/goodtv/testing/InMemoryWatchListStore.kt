package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.watchlist.WatchListItem
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.data.watchlist.WatchListStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Every saved ref is "in the catalog", titled by its key. */
class InMemoryWatchListStore : WatchListStore {
    private val saved = MutableStateFlow<Map<WatchListRef, Long>>(emptyMap())

    override fun observeSaved(): Flow<Set<WatchListRef>> = saved.map { it.keys }

    override fun observeRow(): Flow<List<WatchListItem>> = saved.map { m ->
        m.entries.sortedByDescending { it.value }.map { (ref, at) -> WatchListItem(ref, ref.key, null, null, ref.key, at) }
    }

    override suspend fun add(ref: WatchListRef, savedAt: Long) = saved.update { it + (ref to savedAt) }

    override suspend fun remove(ref: WatchListRef) = saved.update { it - ref }
}
