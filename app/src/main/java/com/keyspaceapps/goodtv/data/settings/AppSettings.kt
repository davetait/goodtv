package com.keyspaceapps.goodtv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.keyspaceapps.goodtv.data.guide.EarlyRefreshLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AppSettings(private val dataStore: DataStore<Preferences>) : EarlyRefreshLog {
    private val debugSourceInsertedKey = booleanPreferencesKey("debug_source_inserted")
    private val lastTabKey = stringPreferencesKey("last_tab")

    val debugSourceInserted: Flow<Boolean> = dataStore.data.map { it[debugSourceInsertedKey] ?: false }
    suspend fun setDebugSourceInserted(value: Boolean) { dataStore.edit { it[debugSourceInsertedKey] = value } }

    val lastTab: Flow<String?> = dataStore.data.map { it[lastTabKey] }
    suspend fun setLastTab(name: String) { dataStore.edit { it[lastTabKey] = name } }

    private fun earlyRefreshKey(sourceId: Long) = longPreferencesKey("guide_early_refresh_$sourceId")
    override suspend fun lastEarlyRefresh(sourceId: Long): Long? = dataStore.data.first()[earlyRefreshKey(sourceId)]
    override suspend fun recordEarlyRefresh(sourceId: Long, atMs: Long) { dataStore.edit { it[earlyRefreshKey(sourceId)] = atMs } }
}
