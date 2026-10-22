package com.keyspaceapps.goodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.keyspaceapps.goodtv.data.guide.GuideFilter
import com.keyspaceapps.goodtv.ui.live.GuideTime
import com.keyspaceapps.goodtv.ui.nav.GoodTvNavigation
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GoodTvTheme { GoodTvNavigation() }
        }
        // Warm the guide once the first frame is up, so Live TV opened a moment later finds the lineup and
        // this half-hour's programmes ready (the guide keeps them five minutes after the last collector leaves).
        // A recreated activity finds them warm already.
        if (savedInstanceState == null) {
            val graph = appGraph()
            window.decorView.post {
                graph.appScope.launch {
                    withTimeoutOrNull(WARM_TIMEOUT_MS) {
                        graph.guide.board(GuideFilter.ALL, GuideTime.slotOf(System.currentTimeMillis())).first { it.programmesLoaded }
                    }
                }
            }
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

    private companion object {
        const val WARM_TIMEOUT_MS = 60_000L
    }
}
