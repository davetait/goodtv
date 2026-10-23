package com.keyspaceapps.goodtv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.MessageDigest

enum class KeyStatus { NOT_SET, UNCHECKED, CONNECTED, REJECTED_KEY, REJECTED_LOGIN }

/** An OpenSubtitles key and optional login. [toString] never shows the values. */
data class OpenSubtitlesAccess(val apiKey: String, val login: OpenSubtitlesLogin?) {
    override fun toString() = "OpenSubtitlesAccess(***)"
}

/** Keys compiled into the build (debug builds only; release builds carry none). [toString] never shows the values. */
data class BuildDefaults(val tmdbKey: String, val osKey: String, val osUsername: String, val osPassword: String) {
    override fun toString() = "BuildDefaults(***)"
}

/** [key] null means TMDB is off. */
data class TmdbState(val key: String?, val fromBuild: Boolean, val status: KeyStatus) {
    override fun toString() = "TmdbState(set=${key != null}, fromBuild=$fromBuild, status=$status)"
}

data class OpenSubtitlesState(val access: OpenSubtitlesAccess?, val fromBuild: Boolean, val status: KeyStatus) {
    override fun toString() = "OpenSubtitlesState(set=${access != null}, fromBuild=$fromBuild, status=$status)"
}

/**
 * The TMDB and OpenSubtitles values in effect: what the user typed, else the build defaults.
 * Each value's last check result is stored with a fingerprint (a hash prefix, never the value) of
 * the value it belongs to, so a changed value reads [KeyStatus.UNCHECKED].
 */
class Integrations(
    private val dataStore: DataStore<Preferences>,
    private val defaults: BuildDefaults,
    scope: CoroutineScope,
) {
    private val tmdbKey = stringPreferencesKey("tmdb_key")
    private val tmdbStatus = stringPreferencesKey("tmdb_status")
    private val tmdbFingerprint = stringPreferencesKey("tmdb_fingerprint")
    private val osKey = stringPreferencesKey("os_key")
    private val osUsername = stringPreferencesKey("os_username")
    private val osPassword = stringPreferencesKey("os_password")
    private val osStatus = stringPreferencesKey("os_status")
    private val osFingerprint = stringPreferencesKey("os_fingerprint")

    private class TmdbValue(val key: String, val fromBuild: Boolean)
    private class OsValue(val access: OpenSubtitlesAccess, val fromBuild: Boolean)

    private fun effectiveTmdb(p: Preferences): TmdbValue? =
        p[tmdbKey]?.takeIf { it.isNotBlank() }?.let { TmdbValue(it, false) }
            ?: defaults.tmdbKey.takeIf { it.isNotBlank() }?.let { TmdbValue(it, true) }

    private fun effectiveOs(p: Preferences): OsValue? =
        p[osKey]?.takeIf { it.isNotBlank() }?.let { OsValue(access(it, p[osUsername], p[osPassword]), false) }
            ?: defaults.osKey.takeIf { it.isNotBlank() }?.let { OsValue(access(it, defaults.osUsername, defaults.osPassword), true) }

    private fun access(key: String, username: String?, password: String?): OpenSubtitlesAccess =
        OpenSubtitlesAccess(key, login(username, password))

    private fun login(username: String?, password: String?): OpenSubtitlesLogin? =
        if (username.isNullOrBlank() || password.isNullOrBlank()) null else OpenSubtitlesLogin(username, password)

    private fun storedStatus(p: Preferences, statusKey: Preferences.Key<String>, fingerprintKey: Preferences.Key<String>, fingerprint: String): KeyStatus {
        if (p[fingerprintKey] != fingerprint) return KeyStatus.UNCHECKED
        return KeyStatus.entries.firstOrNull { it.name == p[statusKey] && it != KeyStatus.NOT_SET } ?: KeyStatus.UNCHECKED
    }

    private fun tmdbState(p: Preferences): TmdbState {
        val v = effectiveTmdb(p) ?: return TmdbState(null, false, KeyStatus.NOT_SET)
        return TmdbState(v.key, v.fromBuild, storedStatus(p, tmdbStatus, tmdbFingerprint, fingerprint(v.key)))
    }

    private fun osState(p: Preferences): OpenSubtitlesState {
        val v = effectiveOs(p) ?: return OpenSubtitlesState(null, false, KeyStatus.NOT_SET)
        return OpenSubtitlesState(v.access, v.fromBuild, storedStatus(p, osStatus, osFingerprint, fingerprint(v.access)))
    }

    val tmdb: StateFlow<TmdbState> = dataStore.data.map(::tmdbState)
        .stateIn(scope, SharingStarted.Eagerly, tmdbState(androidx.datastore.preferences.core.emptyPreferences()))

    val openSubtitles: StateFlow<OpenSubtitlesState> = dataStore.data.map(::osState)
        .stateIn(scope, SharingStarted.Eagerly, osState(androidx.datastore.preferences.core.emptyPreferences()))

    suspend fun saveTmdb(key: String, status: KeyStatus) {
        if (key.isBlank()) return removeTmdb()
        dataStore.edit {
            it[tmdbKey] = key
            it[tmdbStatus] = status.name
            it[tmdbFingerprint] = fingerprint(key)
        }
    }

    /** Drops the typed value; the build default (if any) applies again. */
    suspend fun removeTmdb() {
        dataStore.edit {
            it.remove(tmdbKey)
            it.remove(tmdbStatus)
            it.remove(tmdbFingerprint)
        }
    }

    suspend fun saveOpenSubtitles(access: OpenSubtitlesAccess, status: KeyStatus) {
        if (access.apiKey.isBlank()) return removeOpenSubtitles()
        val normalized = access(access.apiKey, access.login?.username, access.login?.password)
        dataStore.edit {
            it[osKey] = normalized.apiKey
            if (normalized.login != null) {
                it[osUsername] = normalized.login.username
                it[osPassword] = normalized.login.password
            } else {
                it.remove(osUsername)
                it.remove(osPassword)
            }
            it[osStatus] = status.name
            it[osFingerprint] = fingerprint(normalized)
        }
    }

    suspend fun removeOpenSubtitles() {
        dataStore.edit {
            it.remove(osKey)
            it.remove(osUsername)
            it.remove(osPassword)
            it.remove(osStatus)
            it.remove(osFingerprint)
        }
    }

    /** Records a result for the value currently in effect; ignored if [forKey] is no longer the effective value. */
    suspend fun recordTmdbStatus(forKey: String, status: KeyStatus) {
        dataStore.edit {
            if (effectiveTmdb(it)?.key != forKey) return@edit
            it[tmdbStatus] = status.name
            it[tmdbFingerprint] = fingerprint(forKey)
        }
    }

    suspend fun recordOpenSubtitlesStatus(forAccess: OpenSubtitlesAccess, status: KeyStatus) {
        val wanted = access(forAccess.apiKey, forAccess.login?.username, forAccess.login?.password)
        dataStore.edit {
            if (effectiveOs(it)?.access != wanted) return@edit
            it[osStatus] = status.name
            it[osFingerprint] = fingerprint(wanted)
        }
    }

    private fun fingerprint(key: String): String = hash(key)

    private fun fingerprint(access: OpenSubtitlesAccess): String =
        hash("${access.apiKey}\u0000${access.login?.username.orEmpty()}\u0000${access.login?.password.orEmpty()}")

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
