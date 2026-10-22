package com.keyspaceapps.goodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.keyspaceapps.goodtv.ui.nav.GoodTvNavigation
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GoodTvTheme { GoodTvNavigation() }
        }
    }

    override fun onStart() {
        super.onStart()
        // TV processes live for days; refresh stale sources every time the app comes forward, then
        // let the genre fill pick up any new movies (it resumes where it stopped) and the guide refresh if a day old.
        val graph = appGraph()
        graph.appScope.launch {
            graph.repository.refreshStale().join()
            graph.genreFetcher.start()
            graph.guideRefresher.refreshIfStale()
        }
    }
}
