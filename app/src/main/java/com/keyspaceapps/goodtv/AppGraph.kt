package com.keyspaceapps.goodtv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.settings.BuildDefaults
import com.keyspaceapps.goodtv.data.settings.Integrations
import com.keyspaceapps.goodtv.data.settings.KeyStatus
import com.keyspaceapps.goodtv.data.settings.OpenSubtitlesAccess
import com.keyspaceapps.goodtv.data.settings.integratedOpenSubtitlesApi
import com.keyspaceapps.goodtv.data.settings.integratedTmdbApi
import com.keyspaceapps.goodtv.data.db.GoodTvDatabase
import com.keyspaceapps.goodtv.data.db.RoomLibraryStore
import com.keyspaceapps.goodtv.data.db.RoomPopularStore
import com.keyspaceapps.goodtv.data.db.RoomPeopleCache
import com.keyspaceapps.goodtv.data.db.RoomTmdbCache
import com.keyspaceapps.goodtv.data.db.RoomTmdbGenreStore
import com.keyspaceapps.goodtv.data.db.RoomGuideStore
import com.keyspaceapps.goodtv.data.db.RoomLineupStore
import com.keyspaceapps.goodtv.data.guide.LineupKeeper
import com.keyspaceapps.goodtv.data.guide.LineupStore
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
import com.keyspaceapps.goodtv.data.tmdb.PeopleRepository
import com.keyspaceapps.goodtv.data.tmdb.PopularMoviesQuery
import com.keyspaceapps.goodtv.data.tmdb.PopularRepository
import com.keyspaceapps.goodtv.data.tmdb.PopularShowsQuery
import com.keyspaceapps.goodtv.data.tmdb.PopularStore
import com.keyspaceapps.goodtv.data.tmdb.TmdbGenreFetcher
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.data.xtream.XtreamApi
import com.keyspaceapps.goodtv.data.xtream.XtreamChannelSource
import com.keyspaceapps.goodtv.data.xtream.XtreamJson
import com.keyspaceapps.goodtv.ui.settings.IntegrationChecks
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

const val USER_AGENT = "GoodTV/0.2"

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
// A corrupt file would otherwise make every Save throw; starting empty falls back to the build defaults.
private val Context.integrationsDataStore by preferencesDataStore(
    name = "integrations",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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

    /** The build's keys; a service switched off for tests gets none. */
    private val buildDefaults: BuildDefaults by lazy {
        val os = !GoodTvApp.disableOpenSubtitles
        BuildDefaults(
            tmdbKey = if (GoodTvApp.disableTmdb) "" else BuildConfig.TMDB_API_KEY,
            osKey = if (os) BuildConfig.OPENSUBTITLES_API_KEY else "",
            osUsername = if (os) BuildConfig.OPENSUBTITLES_USERNAME else "",
            osPassword = if (os) BuildConfig.OPENSUBTITLES_PASSWORD else "",
        )
    }

    /** Keys typed in Settings over the build's defaults. */
    val integrations: Integrations by lazy { Integrations(context.integrationsDataStore, buildDefaults, appScope) }

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
     * TMDB ratings and summaries on the key in [integrations]; a no-op while none is set or
     * [GoodTvApp.disableTmdb] is (instrumented tests, which must not call the real API). Language follows the device.
     */
    private val tmdbApi: TmdbApi by lazy { integratedTmdbApi(okHttp, integrations, appScope, disabled = { GoodTvApp.disableTmdb }) }
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
    val lineupStore: LineupStore by lazy { RoomLineupStore(db) }

    /** Rebuilds and saves the guide lineup in the background whenever the library changes; started on first use, once per process. */
    val lineupKeeper: LineupKeeper by lazy {
        // Started as it's created, so whoever gets it (the guide included) sees it building before its first check.
        LineupKeeper(store, lineupStore, Dispatchers.Default, appVersion = installedAt()).also { it.start(appScope) }
    }

    /** When this install was last installed or updated: a saved lineup from another install rebuilds. */
    private fun installedAt(): Long {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        }
        return info.lastUpdateTime
    }

    /** True while the player is on screen; the guide's provider download waits for it to end. */
    val playback = PlaybackState()

    /**
     * Downloads the guide once a day: epgshare01 first, the provider's own for what it lacks. Under
     * [GoodTvApp.disableGuideDownloads] epgshare01 is a closed local port, so its refresh fails at once.
     */
    val guideRefresher: GuideRefresher by lazy {
        val epgShare = if (GoodTvApp.disableGuideDownloads) EpgShareSource(okHttp, "http://127.0.0.1:9/".toHttpUrl()) else EpgShareSource(okHttp)
        GuideRefresher(
            guideStore, store, sourceRegistry, epgShare, playback,
            System::currentTimeMillis, Dispatchers.IO, File(context.cacheDir, "guide"), early = settings, failures = settings,
        )
    }

    /** Home's popular rows: TMDB's popular streaming titles the library has, rankings refreshed daily. */
    val popularStore: PopularStore by lazy { RoomPopularStore(db.popular()) }
    val popularMovies: PopularRepository by lazy { PopularRepository(tmdbApi, popularStore, PopularMoviesQuery, tmdbLanguage) }
    val popularShows: PopularRepository by lazy { PopularRepository(tmdbApi, popularStore, PopularShowsQuery, tmdbLanguage) }

    private val openSubtitlesApi: OpenSubtitlesApi by lazy {
        integratedOpenSubtitlesApi(okHttp, integrations, appScope, disabled = { GoodTvApp.disableOpenSubtitles })
    }
    private val onlineSubtitlesInstance: OnlineSubtitles by lazy { OnlineSubtitles(openSubtitlesApi, File(context.filesDir, "subtitles")) }

    /**
     * English subtitles from OpenSubtitles for titles without their own, on the key (and login, when
     * set) in [integrations]; null while none is set or [GoodTvApp.disableOpenSubtitles] is.
     */
    val onlineSubtitles: OnlineSubtitles? get() = if (openSubtitlesApi.isConfigured) onlineSubtitlesInstance else null

    /** The Sports tab: today's NHL/NBA/MLB/NFL/WNBA games from ESPN, PWHL from HockeyTech, matched to live channels. */
    val sports: SportsRepository by lazy {
        val espn = EspnScoreboard(okHttp)
        val sources: Map<League, ScheduleSource> = League.entries.associateWith { if (it == League.PWHL) PwhlScorebar(okHttp) else espn }
        SportsRepository(sources, store, liveRefresher)
    }

    /** Re-downloads the live lists of stale sources; shared by Sports and the guide's event slots. */
    private val liveRefresher: LiveRefresher by lazy { LiveRefresher(store, sourceRegistry, { syncManager.state.value is SyncState.Syncing }) }

    /** The Live TV guide: the lineup with its programmes, favourites and search. */
    val guide: GuideRepository by lazy { GuideRepository(store, guideStore, guideRefresher, liveRefresher, System::currentTimeMillis, Dispatchers.Default, lineups = lineupStore, lineupBuilding = lineupKeeper.building) }

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
     * When the TMDB key in effect changes (typed, removed, or the saved one replacing the build
     * default at launch), drops what was remembered under the old one (memos, the popular rows'
     * failure back-off) and restarts the genre fill.
     * Call once per process.
     */
    fun startFollowingTmdbKey() {
        appScope.launch {
            // Compared with the build default (what the flow holds before loading) rather than dropping
            // the first value, so a saved key that loads before this subscribes still counts as a change.
            var last = buildDefaults.tmdbKey.takeIf { it.isNotBlank() }
            integrations.tmdb.map { it.key }.distinctUntilChanged().filter { it != last }.collect { key ->
                last = key
                tmdb.forgetMemo()
                people.forgetMemo()
                popularMovies.forgetFailure()
                popularShows.forgetFailure()
                genreFetcher.start()
            }
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

    /** Save's checks on the Integrations screen: the candidate value, not the one in effect. */
    private val defaultIntegrationChecks: IntegrationChecks = object : IntegrationChecks {
        override suspend fun tmdb(key: String): Boolean = TmdbCredential.from(key)?.let { tmdbApi.check(it) } ?: false
        override suspend fun openSubtitles(access: OpenSubtitlesAccess): KeyStatus = openSubtitlesApi.check(access)
    }

    /** Instrumented tests answer the Integrations screen's checks without a network. */
    @VisibleForTesting var integrationChecksOverride: IntegrationChecks? = null
    val integrationChecks: IntegrationChecks get() = integrationChecksOverride ?: defaultIntegrationChecks

    /** Instrumented tests point this at a mock TMDB server. */
    @VisibleForTesting var tmdbOverride: TmdbRepository? = null
    val tmdb: TmdbRepository get() = tmdbOverride ?: defaultTmdb

    private val defaultPeople: PeopleRepository by lazy { PeopleRepository(api = tmdbApi, cache = RoomPeopleCache(db.people()), language = tmdbLanguage) }

    /** Instrumented tests point this at a mock TMDB server. */
    @VisibleForTesting var peopleOverride: PeopleRepository? = null
    val people: PeopleRepository get() = peopleOverride ?: defaultPeople
}
