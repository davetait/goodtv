package com.keyspaceapps.goodtv.data.settings

import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/*
 * How the app's API clients follow [Integrations]: each request waits for the saved values to load
 * (until then the flows hold the build defaults), uses the value in effect, and records whether the
 * service accepted it. [disabled] (the instrumented tests' switch) keeps a service off whatever is typed.
 */

/** TMDB on the key in effect; a 2xx records [KeyStatus.CONNECTED], a 401 [KeyStatus.REJECTED_KEY]. */
fun integratedTmdbApi(
    client: OkHttpClient,
    integrations: Integrations,
    scope: CoroutineScope,
    disabled: () -> Boolean,
    baseUrl: HttpUrl = TmdbApi.DEFAULT_BASE_URL.toHttpUrl(),
    io: CoroutineDispatcher = Dispatchers.IO,
): TmdbApi = TmdbApi(
    client,
    credential = {
        if (disabled()) null else {
            integrations.awaitLoaded()
            integrations.tmdb.value.key?.let(TmdbCredential::from)
        }
    },
    // Before the saved values load the answer isn't known: say yes and let the request's wait decide.
    configured = { !disabled() && (!integrations.loaded.value || integrations.tmdb.value.key?.let(TmdbCredential::from) != null) },
    onCredentialResult = { credential, accepted ->
        val status = if (accepted) KeyStatus.CONNECTED else KeyStatus.REJECTED_KEY
        val state = integrations.tmdb.value
        val key = state.key
        // Only a change is written: every poster lookup succeeds, and each would otherwise be a disk write.
        if (key != null && state.status != status && TmdbCredential.from(key) == credential) {
            scope.launch { integrations.recordTmdbStatus(key, status) }
        }
    },
    baseUrl = baseUrl,
    io = io,
)

/** OpenSubtitles on the access in effect; records CONNECTED, REJECTED_KEY or REJECTED_LOGIN as calls report them. */
fun integratedOpenSubtitlesApi(
    client: OkHttpClient,
    integrations: Integrations,
    scope: CoroutineScope,
    disabled: () -> Boolean,
    baseUrl: HttpUrl = OpenSubtitlesApi.DEFAULT_BASE_URL.toHttpUrl(),
    io: CoroutineDispatcher = Dispatchers.IO,
    loginScheme: String = "https",
): OpenSubtitlesApi = OpenSubtitlesApi(
    client,
    access = {
        if (disabled()) null else {
            integrations.awaitLoaded()
            integrations.openSubtitles.value.access
        }
    },
    configured = { !disabled() && (!integrations.loaded.value || integrations.openSubtitles.value.access != null) },
    onResult = { access, status ->
        val state = integrations.openSubtitles.value
        // A key Save's check found bad stays REJECTED_KEY: a login refused with that key says nothing new.
        val keepsRejectedKey = state.status == KeyStatus.REJECTED_KEY && status == KeyStatus.REJECTED_LOGIN
        if (state.access == access && state.status != status && !keepsRejectedKey) {
            scope.launch { integrations.recordOpenSubtitlesStatus(access, status) }
        }
    },
    baseUrl = baseUrl,
    io = io,
    loginScheme = loginScheme,
)
