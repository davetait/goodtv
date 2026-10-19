package com.keyspaceapps.goodtv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppSettings(private val dataStore: DataStore<Preferences>) {
    private val debugSourceInsertedKey = booleanPreferencesKey("debug_source_inserted")
    private val lastTabKey = stringPreferencesKey("last_tab")

    val debugSourceInserted: Flow<Boolean> = dataStore.data.map { it[debugSourceInsertedKey] ?: false }
    suspend fun setDebugSourceInserted(value: Boolean) { dataStore.edit { it[debugSourceInsertedKey] = value } }

    val lastTab: Flow<String?> = dataStore.data.map { it[lastTabKey] }
    suspend fun setLastTab(name: String) { dataStore.edit { it[lastTabKey] = name } }
}
