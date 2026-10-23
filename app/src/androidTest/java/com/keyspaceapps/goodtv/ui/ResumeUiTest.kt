package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Season
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.movies.MovieDetailScreen
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.series.SeriesDetailScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Detail pages offer Resume / Start over from saved progress. */
@OptIn(ExperimentalTestApi::class)
class ResumeUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private var sid = 0L

    @Before
    fun seed() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
        graph.clearDatabaseForTest()
        sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null))
        val movie = Movie(sid, "m1", null, "EN - Heat (1995)", null, "mkv", null, null)
        val fresh = Movie(sid, "m2", null, "Fresh", null, "mkv", null, null)
        graph.store.replaceCatalog(sid, Catalog(emptyList(), emptyList(), listOf(movie, fresh), listOf(Series(sid, "s1", null, "Dark", null, null, null))))
        graph.store.saveMovieDetail(MovieDetail(movie, null, null, null, null, null, 10_200, null))
        graph.store.saveMovieDetail(MovieDetail(fresh, null, null, null, null, null, null, null))
        val eps = listOf(Episode(sid, "s1", "e1", 1, 1, "Secrets", "mp4", 3000, null, null), Episode(sid, "s1", "e2", 1, 2, "Lies", "mp4", 3000, null, null))
        graph.store.saveSeriesDetail(SeriesDetail(Series(sid, "s1", null, "Dark", null, null, null), null, null, null, null, null, listOf(Season(1, "Season 1", null)), eps))
        graph.watchProgress.record(WatchRef(sid, WatchKind.MOVIE, "m1"), 42 * 60_000L + 10_000, 10_200_000)
        graph.watchProgress.record(WatchRef(sid, WatchKind.EPISODE, "e1", "s1"), 3_000_000, 3_000_000) // finished
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun partWatchedMovieOffersResumeFocusedThenStartOver() {
        var played: PlayerKey? = null
        composeRule.setContent { GoodTvTheme { MovieDetailScreen(MovieDetailKey(sid, "m1"), onPlay = { played = it }) } }
        waitFor("Resume from 42:10")
        composeRule.onNodeWithText("Resume from 42:10").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(42 * 60_000L + 10_000, played!!.startPositionMs)

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals("Start over", 0L, played!!.startPositionMs)
    }

    @Test
    fun unwatchedMovieJustOffersPlay() {
        composeRule.setContent { GoodTvTheme { MovieDetailScreen(MovieDetailKey(sid, "m2"), onPlay = {}) } }
        waitFor("Play")
        composeRule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun afterAFinishedEpisodeTheSeriesOffersTheNextOne() {
        var played: PlayerKey? = null
        composeRule.setContent { GoodTvTheme { SeriesDetailScreen(SeriesDetailKey(sid, "s1"), onPlay = { played = it }) } }
        waitFor("Play S1 E2")
        composeRule.onNodeWithText("E1 · Secrets · 50m · Watched").assertIsDisplayed()
        composeRule.onNodeWithText("Play S1 E2").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals("e2", played!!.refId)
        assertEquals("s1", played!!.seriesId)
        assertEquals(0L, played!!.startPositionMs)
    }
}
