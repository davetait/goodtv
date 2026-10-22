package com.keyspaceapps.goodtv

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.preferencesDataStore
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.db.GoodTvDatabase
import com.keyspaceapps.goodtv.data.db.RoomLibraryStore
import com.keyspaceapps.goodtv.data.db.RoomPopularStore
import com.keyspaceapps.goodtv.data.db.RoomTmdbCache
import com.keyspaceapps.goodtv.data.db.RoomTmdbGenreStore
import com.keyspaceapps.goodtv.data.db.RoomGuideStore
import com.keyspaceapps.goodtv.data.db.RoomWatchListStore
import com.keyspaceapps.goodtv.data.db.RoomWatchProgressStore
import com.keyspaceapps.goodtv.data.guide.EpgShareSource
import com.keyspaceapps.goodtv.data.guide.GuideRefresher
import com.keyspaceapps.goodtv.data.guide.GuideRepository
import com.keyspaceapps.goodtv.data.guide.GuideStore
import com.keyspaceapps.goodtv.data.guide.PlaybackState
import com.keyspaceapps.goodtv.data.progress.WatchProgressRepository
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.repo.SyncState
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.data.watchlist.WatchListRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import com.keyspaceapps.goodtv.data.sports.EspnScoreboard
import com.keyspaceapps.goodtv.data.sports.League
import com.keyspaceapps.goodtv.data.sports.LiveRefresher
import com.keyspaceapps.goodtv.data.sports.PwhlScorebar
import com.keyspaceapps.goodtv.data.sports.ScheduleSource
import com.keyspaceapps.goodtv.data.sports.SportsRepository
import com.keyspaceapps.goodtv.data.subtitles.OnlineSubtitles
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesApi
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesLogin
import com.keyspaceapps.goodtv.data.tmdb.PopularMoviesQuery
import com.keyspaceapps.goodtv.data.tmdb.PopularRepository
import com.keyspaceapps.goodtv.data.tmdb.PopularShowsQuery
import com.keyspaceapps.goodtv.data.tmdb.PopularStore
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
import java.io.File
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

    /** A saved movie watched to the credits leaves the watch list (that copy only). */
    val watchProgress: WatchProgressRepository by lazy {
        WatchProgressRepository(
            RoomWatchProgressStore(db.watchProgress()),
            onMovieFinished = { ref -> watchList.remove(WatchListRef(ref.sourceId, WatchListKind.MOVIE, ref.itemId)) },
        )
    }

    /** Titles saved to watch later, for the Watch list row, poster bookmarks and detail pages. */
    val watchList: WatchListRepository by lazy { WatchListRepository(RoomWatchListStore(db.watchList())) }

    /** Guide programmes, the channel-to-guide links and favourite channels. */
    val guideStore: GuideStore by lazy { RoomGuideStore(db) }

    /** True while the player is on screen; the guide's provider download waits for it to end. */
    val playback = PlaybackState()

    /** Downloads the guide once a day: epgshare01 first, the provider's own for what it lacks. */
    val guideRefresher: GuideRefresher by lazy {
        GuideRefresher(
            guideStore, store, sourceRegistry, EpgShareSource(okHttp), playback,
            System::currentTimeMillis, Dispatchers.IO, File(context.cacheDir, "guide"),
        )
    }

    /** Home's popular rows: TMDB's popular streaming titles the library has, rankings refreshed daily. */
    val popularStore: PopularStore by lazy { RoomPopularStore(db.popular()) }
    val popularMovies: PopularRepository by lazy { PopularRepository(tmdbApi, popularStore, PopularMoviesQuery, tmdbLanguage) }
    val popularShows: PopularRepository by lazy { PopularRepository(tmdbApi, popularStore, PopularShowsQuery, tmdbLanguage) }

    /**
     * English subtitles from OpenSubtitles for titles without their own; null when no key is
     * configured or [GoodTvApp.disableOpenSubtitles]. Logs in when a username and password are set.
     */
    val onlineSubtitles: OnlineSubtitles? by lazy {
        BuildConfig.OPENSUBTITLES_API_KEY.takeIf { it.isNotBlank() && !GoodTvApp.disableOpenSubtitles }?.let { key ->
            val login = OpenSubtitlesLogin(BuildConfig.OPENSUBTITLES_USERNAME, BuildConfig.OPENSUBTITLES_PASSWORD)
                .takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
            OnlineSubtitles(OpenSubtitlesApi(okHttp, key, login), File(context.filesDir, "subtitles"))
        }
    }

    /** The Sports tab: today's NHL/NBA/MLB/NFL/WNBA games from ESPN, PWHL from HockeyTech, matched to live channels. */
    val sports: SportsRepository by lazy {
        val espn = EspnScoreboard(okHttp)
        val sources: Map<League, ScheduleSource> = League.entries.associateWith { if (it == League.PWHL) PwhlScorebar(okHttp) else espn }
        SportsRepository(sources, store, liveRefresher)
    }

    /** Re-downloads the live lists of stale sources; shared by Sports and the guide's event slots. */
    private val liveRefresher: LiveRefresher by lazy { LiveRefresher(store, sourceRegistry, { syncManager.state.value is SyncState.Syncing }) }

    /** The Live TV guide: the lineup with its programmes, favourites and search. */
    val guide: GuideRepository by lazy { GuideRepository(store, guideStore, guideRefresher, liveRefresher, System::currentTimeMillis, Dispatchers.Default) }

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

    /**
     * Refreshes the guide whenever syncing finishes. A fresh guide is left alone, except that a source
     * with channels but no guide links yet (just added) gets the epgshare01 files matched to it. Call once per process.
     */
    fun startGuideRefreshAfterSyncs() {
        appScope.launch {
            syncManager.state.map { it is SyncState.Syncing }.distinctUntilChanged().drop(1).filter { syncing -> !syncing }
                .collect { guideRefresher.refreshIfStale() }
        }
    }

    /** Instrumented tests point this at a mock TMDB server. */
    @VisibleForTesting var tmdbOverride: TmdbRepository? = null
    val tmdb: TmdbRepository get() = tmdbOverride ?: defaultTmdb
}
