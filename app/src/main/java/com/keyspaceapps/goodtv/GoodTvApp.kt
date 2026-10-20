package com.keyspaceapps.goodtv

import android.app.Application
import android.content.Context
import com.keyspaceapps.goodtv.data.debug.DebugSource
import kotlinx.coroutines.launch

class GoodTvApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.startGenreFillAfterSyncs()
        graph.appScope.launch {
            graph.repository.ensureDebugSource(
                DebugSource.fromBuildConfig(BuildConfig.XTREAM_SERVER, BuildConfig.XTREAM_USERNAME, BuildConfig.XTREAM_PASSWORD, skipDebugSource),
            )
        }
    }

    companion object {
        /** Set by the instrumented test runner before the Application is created. */
        @JvmStatic
        var skipDebugSource: Boolean = false
        /** Set by the instrumented test runner so tests never reach the real TMDB API. */
        @Volatile var disableTmdb: Boolean = false
    }
}

fun Context.appGraph(): AppGraph = (applicationContext as GoodTvApp).graph
