package com.keyspaceapps.goodtv.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.settings.Integrations
import com.keyspaceapps.goodtv.data.settings.KeyStatus
import com.keyspaceapps.goodtv.data.settings.OpenSubtitlesAccess
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Whether a service accepts a candidate value. Both throw when they can't tell (offline, server trouble). */
interface IntegrationChecks {
    /** True if TMDB accepts [key] (an API key or read access token), false if it rejects it. */
    suspend fun tmdb(key: String): Boolean

    /** [KeyStatus.CONNECTED], [KeyStatus.REJECTED_KEY] or [KeyStatus.REJECTED_LOGIN]. */
    suspend fun openSubtitles(access: OpenSubtitlesAccess): KeyStatus
}

enum class Badge { NOT_SET, CHECKING, CONNECTED, REJECTED, UNCHECKED }

/**
 * One integration card. [maskedKey] is the saved key as dots plus its last 4 characters (never the
 * key itself); [maskedPassword] says a password is saved. [saves] goes up each time Save stores
 * values, so the screen knows to clear what was typed.
 */
data class CardUi(
    val badge: Badge,
    @param:StringRes val message: Int?,
    val maskedKey: String?,
    val maskedPassword: Boolean,
    val username: String?,
    val fromBuild: Boolean,
    val busy: Boolean,
    val saves: Int = 0,
) {
    /** Remove is offered for a typed value only; the build's own value can't be removed. */
    val removable: Boolean get() = maskedKey != null && !fromBuild
}

/**
 * Whether every character is printable ASCII. OkHttp refuses anything else in a header (a pasted
 * zero-width space or newline), so no service could ever accept such a key. Only keys travel in
 * headers; an OpenSubtitles login goes in the JSON body, so any username or password is allowed.
 */
private fun String.sendable(): Boolean = all { it in ' '..'~' }

/** A saved key as "••••" plus its last 4 characters; a key under 8 characters is all dots. */
fun maskKey(key: String): String = if (key.length < 8) "•".repeat(key.length) else "••••" + key.takeLast(4)

/**
 * The Integrations screen: shows the TMDB and OpenSubtitles values in effect (masked) with their
 * status, and checks typed values with the service before saving them. Save runs in [appScope]
 * (the app's, not the screen's), so leaving the screen mid-check doesn't drop it.
 */
class IntegrationsViewModel(
    private val integrations: Integrations,
    private val checks: IntegrationChecks,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private companion object {
        /** How long a removal may take to show in [Integrations] before the build value's check is skipped. */
        const val SETTLE_MS = 5_000L
    }

    /** What the card's last action left behind: the badge always shows the saved value's status. */
    private data class Attempt(
        val busy: Boolean = false,
        @param:StringRes val message: Int? = null,
        val saves: Int = 0,
    )

    private val tmdbAttempt = MutableStateFlow(Attempt())
    private val osAttempt = MutableStateFlow(Attempt())

    private val loading = CardUi(Badge.NOT_SET, null, null, maskedPassword = false, username = null, fromBuild = false, busy = true)

    val tmdb: StateFlow<CardUi> = combine(integrations.loaded, integrations.tmdb, tmdbAttempt) { loaded, state, attempt ->
        if (!loaded) return@combine loading.copy(saves = attempt.saves)
        card(state.status, attempt, R.string.tmdb_rejected).copy(
            maskedKey = state.key?.let(::maskKey),
            fromBuild = state.fromBuild,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, loading)

    val openSubtitles: StateFlow<CardUi> = combine(integrations.loaded, integrations.openSubtitles, osAttempt) { loaded, state, attempt ->
        if (!loaded) return@combine loading.copy(saves = attempt.saves)
        val rejected = if (state.status == KeyStatus.REJECTED_LOGIN) R.string.os_login_rejected else R.string.os_key_rejected
        card(state.status, attempt, rejected).copy(
            maskedKey = state.access?.apiKey?.let(::maskKey),
            maskedPassword = state.access?.login != null,
            username = state.access?.login?.username,
            fromBuild = state.fromBuild,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, loading)

    private fun card(status: KeyStatus, attempt: Attempt, @StringRes rejected: Int): CardUi {
        val saved = when (status) {
            KeyStatus.NOT_SET -> Badge.NOT_SET
            KeyStatus.UNCHECKED -> Badge.UNCHECKED
            KeyStatus.CONNECTED -> Badge.CONNECTED
            KeyStatus.REJECTED_KEY, KeyStatus.REJECTED_LOGIN -> Badge.REJECTED
        }
        val badge = if (attempt.busy) Badge.CHECKING else saved
        // "Couldn't reach… Saved" comes only from the Save that couldn't check ([Attempt.message]).
        val message = when {
            attempt.busy -> null
            attempt.message != null -> attempt.message
            saved == Badge.REJECTED -> rejected
            else -> null
        }
        return CardUi(badge, message, null, maskedPassword = false, username = null, fromBuild = false, busy = attempt.busy, saves = attempt.saves)
    }

    init {
        viewModelScope.launch {
            integrations.awaitLoaded()
            checkBuildTmdbIfUnchecked()
        }
        viewModelScope.launch {
            integrations.awaitLoaded()
            checkBuildOpenSubtitlesIfUnchecked()
        }
    }

    /** A build value nobody has checked yet is checked in the background, and the result kept. */
    private suspend fun checkBuildTmdbIfUnchecked() {
        val state = integrations.tmdb.value
        val key = state.key ?: return
        if (!state.fromBuild || state.status != KeyStatus.UNCHECKED || tmdbAttempt.value.busy) return
        tmdbAttempt.update { it.copy(busy = true, message = null) }
        val status = decide { if (checks.tmdb(key)) KeyStatus.CONNECTED else KeyStatus.REJECTED_KEY }
        if (status != null) integrations.recordTmdbStatus(key, status)
        tmdbAttempt.update { it.copy(busy = false) }
    }

    private suspend fun checkBuildOpenSubtitlesIfUnchecked() {
        val state = integrations.openSubtitles.value
        val access = state.access ?: return
        if (!state.fromBuild || state.status != KeyStatus.UNCHECKED || osAttempt.value.busy) return
        osAttempt.update { it.copy(busy = true, message = null) }
        val status = decide { checks.openSubtitles(access) }
        if (status != null) integrations.recordOpenSubtitlesStatus(access, status)
        osAttempt.update { it.copy(busy = false) }
    }

    /** The check's answer, or null when it couldn't tell. */
    private suspend fun decide(check: suspend () -> KeyStatus): KeyStatus? =
        try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Checks [typed] with TMDB and saves it unless TMDB rejects it. Empty keeps the saved key. */
    fun saveTmdb(typed: String) {
        val key = typed.trim()
        if (key.isEmpty() || tmdbAttempt.value.busy) return
        if (!key.sendable()) {
            tmdbAttempt.update { it.copy(message = R.string.tmdb_rejected) }
            return
        }
        tmdbAttempt.update { it.copy(busy = true, message = null) }
        appScope.launch {
            when (decide { if (checks.tmdb(key)) KeyStatus.CONNECTED else KeyStatus.REJECTED_KEY }) {
                KeyStatus.CONNECTED -> {
                    integrations.saveTmdb(key, KeyStatus.CONNECTED)
                    tmdbAttempt.update { Attempt(saves = it.saves + 1) }
                }
                null -> {
                    integrations.saveTmdb(key, KeyStatus.UNCHECKED)
                    tmdbAttempt.update { Attempt(message = R.string.tmdb_unreachable, saves = it.saves + 1) }
                }
                else -> tmdbAttempt.update { Attempt(message = R.string.tmdb_rejected, saves = it.saves) }
            }
        }
    }

    fun removeTmdb() {
        if (tmdbAttempt.value.busy) return
        viewModelScope.launch {
            integrations.removeTmdb()
            tmdbAttempt.update { Attempt(saves = it.saves) }
            // Once the removal shows, a build value it falls back to may need its check.
            if (withTimeoutOrNull(SETTLE_MS) { integrations.tmdb.first { it.key == null || it.fromBuild } } != null) checkBuildTmdbIfUnchecked()
        }
    }

    /**
     * Checks the key, then the login if one is given, and saves them together only if all is
     * accepted. An empty field keeps its saved value (one half of a login is filled from the saved
     * login); with no saved login, a username needs a password and the reverse.
     */
    fun saveOpenSubtitles(key: String, username: String, password: String) {
        if (osAttempt.value.busy) return
        val typedKey = key.trim()
        val typedUser = username.trim()
        val typedPassword = password.trim()
        if (typedKey.isEmpty() && typedUser.isEmpty() && typedPassword.isEmpty()) return
        if (!typedKey.sendable()) {
            osAttempt.update { it.copy(message = R.string.os_key_rejected) }
            return
        }
        osAttempt.update { it.copy(busy = true, message = null) }
        appScope.launch {
            integrations.awaitLoaded()
            val saved = integrations.openSubtitles.value.access
            val login = when {
                typedUser.isEmpty() && typedPassword.isEmpty() -> saved?.login
                typedUser.isNotEmpty() && typedPassword.isNotEmpty() -> OpenSubtitlesLogin(typedUser, typedPassword)
                else -> saved?.login?.let { OpenSubtitlesLogin(typedUser.ifEmpty { it.username }, typedPassword.ifEmpty { it.password }) }
            }
            if (login == null && (typedUser.isNotEmpty() || typedPassword.isNotEmpty())) {
                osAttempt.update { it.copy(busy = false, message = R.string.os_need_both) }
                return@launch
            }
            val apiKey = typedKey.ifEmpty { saved?.apiKey }
            if (apiKey == null) {
                osAttempt.update { it.copy(busy = false, message = R.string.os_need_key) }
                return@launch
            }
            val access = OpenSubtitlesAccess(apiKey, login)
            when (val status = decide { checks.openSubtitles(access) }) {
                KeyStatus.CONNECTED -> {
                    integrations.saveOpenSubtitles(access, KeyStatus.CONNECTED)
                    osAttempt.update { Attempt(saves = it.saves + 1) }
                }
                null -> {
                    integrations.saveOpenSubtitles(access, KeyStatus.UNCHECKED)
                    osAttempt.update { Attempt(message = R.string.os_unreachable, saves = it.saves + 1) }
                }
                else -> {
                    val message = if (status == KeyStatus.REJECTED_LOGIN) R.string.os_login_rejected else R.string.os_key_rejected
                    osAttempt.update { Attempt(message = message, saves = it.saves) }
                }
            }
        }
    }

    fun removeOpenSubtitles() {
        if (osAttempt.value.busy) return
        viewModelScope.launch {
            integrations.removeOpenSubtitles()
            osAttempt.update { Attempt(saves = it.saves) }
            if (withTimeoutOrNull(SETTLE_MS) { integrations.openSubtitles.first { it.access == null || it.fromBuild } } != null) {
                checkBuildOpenSubtitlesIfUnchecked()
            }
        }
    }
}
