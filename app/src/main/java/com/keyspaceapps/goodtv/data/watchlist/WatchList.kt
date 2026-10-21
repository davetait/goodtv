package com.keyspaceapps.goodtv.data.watchlist

import kotlinx.coroutines.flow.Flow

enum class WatchListKind { MOVIE, SERIES }

/** One exact copy as its source lists it; other copies of the same film are separate entries. */
data class WatchListRef(val sourceId: Long, val kind: WatchListKind, val itemId: String) {
    val key: String get() = "${kind.name}:$sourceId:$itemId"
}

/** A saved title as Home's Watch list row shows it; [title] and [imageUrl] come from the catalog. */
data class WatchListItem(
    val ref: WatchListRef,
    val title: String,
    val imageUrl: String?,
    val tmdbId: Long?,
    val lookupName: String,
    val savedAt: Long,
)

/** Storage for [WatchListRepository]; Room in the app, in-memory in tests. */
interface WatchListStore {
    /** Every saved ref, shown or not. */
    fun observeSaved(): Flow<Set<WatchListRef>>
    /** Saved titles of enabled sources, in visible categories and still in the catalog, newest first. */
    fun observeRow(): Flow<List<WatchListItem>>
    suspend fun add(ref: WatchListRef, savedAt: Long)
    suspend fun remove(ref: WatchListRef)
}

/**
 * The titles Dave saved to watch later. A title stays saved across re-syncs; while its source is
 * disabled, its category hidden or it's missing from the catalog, it's left out of [row] only.
 */
class WatchListRepository(private val store: WatchListStore, private val clock: () -> Long = System::currentTimeMillis) {
    fun saved(): Flow<Set<WatchListRef>> = store.observeSaved()

    fun row(): Flow<List<WatchListItem>> = store.observeRow()

    /** Saves [ref]; saving it again moves it to the front. */
    suspend fun add(ref: WatchListRef) = store.add(ref, clock())

    suspend fun remove(ref: WatchListRef) = store.remove(ref)
}
