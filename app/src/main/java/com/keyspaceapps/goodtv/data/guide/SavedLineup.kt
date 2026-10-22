package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.domain.LiveChannel
import kotlinx.coroutines.flow.Flow

/** One provider channel inside a saved row, with the name of its category (what the guide shows as its source). */
data class SavedFeed(val channel: LiveChannel, val categoryName: String)

/** One saved guide row: its identity fields as the lineup builder computed them, and its [feeds] in play order. */
data class SavedRow(
    val key: String,
    val position: Int,
    val name: String,
    val genre: LiveGenre,
    val network: Network?,
    val callSign: String?,
    val market: String?,
    val loopShow: String?,
    val providerLogo: String?,
    val sortKey: String,
    val loopKey: String?,
    val appCopy: Boolean,
    val feeds: List<SavedFeed>,
)

/**
 * The guide lineup as last built, so opening Live TV reads it instead of recomputing ~12.6k identities.
 * [generation] is the library generation it was built from; [rulesVersion] the identity rules that built it.
 */
data class SavedLineup(val rows: List<SavedRow>, val generation: Long, val rulesVersion: Int)

/** What the user lets the guide show: the enabled sources and the hidden Live categories as (sourceId, categoryId). */
data class Visibility(val enabledSources: Set<Long>, val hiddenLiveCategories: Set<Pair<Long, String>>)

/** Storage for the saved lineup, the library generation and the visibility inputs; Room in the app, in-memory in tests. */
interface LineupStore {
    suspend fun saved(): SavedLineup?
    fun observeSaved(): Flow<SavedLineup?>
    /** Replaces the whole saved lineup in one transaction. */
    suspend fun save(lineup: SavedLineup)
    /** Counts library writes (sources, catalogs, hidden categories); 0 before the first. */
    suspend fun generation(): Long
    fun observeGeneration(): Flow<Long>
    fun observeVisibility(): Flow<Visibility>
}
