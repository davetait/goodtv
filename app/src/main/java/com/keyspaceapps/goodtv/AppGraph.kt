package com.keyspaceapps.goodtv

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.db.GoodTvDatabase
import com.keyspaceapps.goodtv.data.db.RoomLibraryStore
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.xtream.XtreamApi
import com.keyspaceapps.goodtv.data.xtream.XtreamChannelSource
import com.keyspaceapps.goodtv.data.xtream.XtreamJson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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
}
