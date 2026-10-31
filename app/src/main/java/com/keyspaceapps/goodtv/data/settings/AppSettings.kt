package com.keyspaceapps.goodtv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.keyspaceapps.goodtv.data.sports.League
import com.keyspaceapps.goodtv.data.guide.EarlyRefreshLog
import com.keyspaceapps.goodtv.data.guide.GuideFailureLog
import com.keyspaceapps.goodtv.data.guide.GuideOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AppSettings(private val dataStore: DataStore<Preferences>) : EarlyRefreshLog, GuideFailureLog {
    private val debugSourceInsertedKey = booleanPreferencesKey("debug_source_inserted")
    private val lastTabKey = stringPreferencesKey("last_tab")

    val debugSourceInserted: Flow<Boolean> = dataStore.data.map { it[debugSourceInsertedKey] ?: false }
    suspend fun setDebugSourceInserted(value: Boolean) { dataStore.edit { it[debugSourceInsertedKey] = value } }

    val lastTab: Flow<String?> = dataStore.data.map { it[lastTabKey] }
    suspend fun setLastTab(name: String) { dataStore.edit { it[lastTabKey] = name } }

    private val livePreviewsKey = booleanPreferencesKey("live_previews")

    /** Whether the guide plays the highlighted channel; off until turned on. */
    val livePreviews: Flow<Boolean> = dataStore.data.map { it[livePreviewsKey] ?: false }
    suspend fun setLivePreviews(on: Boolean) { dataStore.edit { it[livePreviewsKey] = on } }

    private val sportsLeaguesKey = stringSetPreferencesKey("sports_leagues")
    private val defaultLeagues: Set<League> = League.entries.filter { it.onByDefault }.toSet()

    private fun Preferences.leagues(): Set<League> =
        this[sportsLeaguesKey]?.let { names -> League.entries.filter { it.name in names }.toSet() } ?: defaultLeagues

    /** The leagues the Sports screen shows: the original six until a switch is changed. Stored names that are no longer leagues are ignored. */
    val sportsLeagues: Flow<Set<League>> = dataStore.data.map { it.leagues() }

    /** Switches one league on or off; the whole set is stored, starting from the defaults when nothing is. */
    suspend fun setLeagueOn(league: League, on: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs.leagues()
            prefs[sportsLeaguesKey] = (if (on) current + league else current - league).map { it.name }.toSet()
        }
    }

    private fun earlyRefreshKey(sourceId: Long) = longPreferencesKey("guide_early_refresh_$sourceId")
    override suspend fun lastEarlyRefresh(sourceId: Long): Long? = dataStore.data.first()[earlyRefreshKey(sourceId)]
    override suspend fun recordEarlyRefresh(sourceId: Long, atMs: Long) { dataStore.edit { it[earlyRefreshKey(sourceId)] = atMs } }

    private fun failureKey(origin: GuideOrigin) = longPreferencesKey("guide_failed_${origin.name}")
    override suspend fun lastFailure(origin: GuideOrigin): Long? = dataStore.data.first()[failureKey(origin)]
    override suspend fun recordFailure(origin: GuideOrigin, atMs: Long) { dataStore.edit { it[failureKey(origin)] = atMs } }
}
