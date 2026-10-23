package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.Season
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.series.SeriesDetailScreen
import com.keyspaceapps.goodtv.ui.series.SeriesScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SeriesScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var sourceA = 0L

    @Before
    fun seed() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
        graph.clearDatabaseForTest()
        sourceA = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null))
        val s1 = Series(sourceA, "s1", "dra", "Series 1", null, "plot", 8.0)
        val s2 = Series(sourceA, "s2", "dra", "Series 2", null, null, null)
        graph.store.replaceCatalog(
            sourceA,
            Catalog(categories = listOf(Category(sourceA, ContentKind.SERIES, "dra", "Drama")), live = emptyList(), movies = emptyList(), series = listOf(s1, s2)),
        )
        graph.store.saveSeriesDetail(
            SeriesDetail(
                s1, "A plot", "Drama", null, "2024-01-01", null,
                seasons = listOf(Season(1, "Season 1", null), Season(2, "Season 2", null)),
                episodes = listOf(
                    Episode(sourceA, "s1", "1001", 1, 1, "One", "mp4", 2700, null, null),
                    Episode(sourceA, "s1", "1002", 1, 2, "Two", "mp4", 2700, null, null),
                    Episode(sourceA, "s1", "2001", 2, 1, "Three", "mkv", null, null, null),
                ),
            ),
        )
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun rowsRenderSeries() {
        var opened: SeriesDetailKey? = null
        composeRule.setContent { GoodTvTheme { SeriesScreen(onSeries = { opened = it }, onSeeAll = {}) } }
        waitFor("Series 1")
        composeRule.onNodeWithText("Drama").assertIsDisplayed()
        composeRule.onNodeWithText("Series 1").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(SeriesDetailKey(sourceA, "s1"), opened)
    }

    @Test
    fun detailShowsSeasonsAndEpisodes() {
        composeRule.setContent { GoodTvTheme { SeriesDetailScreen(key = SeriesDetailKey(sourceA, "s1"), onPlay = {}) } }
        waitFor("One", substring = true)
        try {
            composeRule.onNodeWithText("Season 1").assertIsDisplayed()
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
        }
        composeRule.onNodeWithText("Season 2").assertIsDisplayed()
        composeRule.onNodeWithText("E2 · Two · 45m").assertIsDisplayed()
        composeRule.onNodeWithText("Season 2").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Three", substring = true)
        composeRule.onNodeWithText("E1 · Three").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodes(hasText("E1 · One · 45m")).fetchSemanticsNodes().size)
    }

    @Test
    fun selectingEpisodeEmitsPlayerKey() {
        var played: PlayerKey? = null
        composeRule.setContent { GoodTvTheme { SeriesDetailScreen(key = SeriesDetailKey(sourceA, "s1"), onPlay = { played = it }) } }
        waitFor("One", substring = true)
        composeRule.onNodeWithText("E1 · One · 45m").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(PlayerKey(sourceA, PlayerKey.KIND_EPISODE, "1001", "mp4", "Series 1 · S1E1 · One", seriesId = "s1", season = 1, episode = 1), played)
    }
}
