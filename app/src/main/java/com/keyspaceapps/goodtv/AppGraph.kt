package com.keyspaceapps.goodtv

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.preferencesDataStore
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.db.GoodTvDatabase
import com.keyspaceapps.goodtv.data.db.RoomLibraryStore
import com.keyspaceapps.goodtv.data.db.RoomTmdbCache
import com.keyspaceapps.goodtv.data.db.RoomTmdbGenreStore
import com.keyspaceapps.goodtv.data.db.RoomWatchProgressStore
import com.keyspaceapps.goodtv.data.progress.WatchProgressRepository
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.repo.SyncState
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import com.keyspaceapps.goodtv.data.tmdb.TmdbGenreFetcher
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.data.xtream.XtreamApi
import com.keyspaceapps.goodtv.data.xtream.XtreamChannelSource
import com.keyspaceapps.goodtv.data.xtream.XtreamJson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

const val USER_AGENT = "GoodTV/0.2"

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Manual dependency graph. One instance per process, owned by [GoodTvApp]. */
class AppGraph(val context: Context) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val settings: AppSettings by lazy { AppSettings(context.settingsDataStore) }

    val db: GoodTvDatabase by lazy { GoodTvDatabase.create(context) }
    val store: LibraryStore by lazy { RoomLibraryStore(db, json) }

    val sourceRegistry: SourceRegistry by lazy {
        SourceRegistry(listOf(XtreamChannelSource(XtreamApi(okHttp, XtreamJson.instance))))
    }

    /** Background work never takes the process down: log and carry on. */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("GoodTV", "Background task failed", e) },
    )
    val syncManager: SyncManager by lazy { SyncManager(store, sourceRegistry, appScope) }
    val repository: LibraryRepository by lazy { LibraryRepository(store, sourceRegistry, syncManager, settings) }

    /**
     * TMDB ratings and summaries; a no-op when no TMDB key is configured or [GoodTvApp.disableTmdb] is
     * set (instrumented tests, which must not call the real API). Language follows the device.
     */
    private val tmdbApi: TmdbApi? by lazy {
        TmdbCredential.from(BuildConfig.TMDB_API_KEY)?.takeUnless { GoodTvApp.disableTmdb }?.let { TmdbApi(okHttp, it) }
    }
    private val tmdbCache by lazy { RoomTmdbCache(db.tmdb()) }
    private val tmdbLanguage: () -> String = { Locale.getDefault().toLanguageTag() }

    private val defaultTmdb: TmdbRepository by lazy { TmdbRepository(api = tmdbApi, cache = tmdbCache, language = tmdbLanguage) }

    val watchProgress: WatchProgressRepository by lazy { WatchProgressRepository(RoomWatchProgressStore(db.watchProgress())) }

    /** Background TMDB genre fill for movies; started on launch after stale sources refresh. */
    val genreFetcher: TmdbGenreFetcher by lazy {
        TmdbGenreFetcher(tmdbApi, RoomTmdbGenreStore(db), tmdbCache, appScope, tmdbLanguage)
    }

    /**
     * Starts the movie genre fill whenever syncing finishes (a first sync, an added source, a manual
     * refresh), so new movies get genres without waiting for the next launch. Call once per process.
     */
    fun startGenreFillAfterSyncs() {
        appScope.launch {
            syncManager.state.map { it is SyncState.Syncing }.distinctUntilChanged().drop(1).filter { syncing -> !syncing }
                .collect { genreFetcher.start() }
        }
    }

    /** Instrumented tests point this at a mock TMDB server. */
    @VisibleForTesting var tmdbOverride: TmdbRepository? = null
    val tmdb: TmdbRepository get() = tmdbOverride ?: defaultTmdb
}
