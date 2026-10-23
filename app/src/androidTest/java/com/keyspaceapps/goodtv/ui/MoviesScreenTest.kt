package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.movies.MovieDetailScreen
import com.keyspaceapps.goodtv.ui.movies.MoviesScreen
import com.keyspaceapps.goodtv.ui.nav.CategoryGridKey
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MoviesScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var sourceA = 0L

    @Before
    fun seed() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
        graph.clearDatabaseForTest()
        sourceA = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null))
        val action = (1..31).map { Movie(sourceA, "act-%02d".format(it), "act", "Act %02d".format(it), null, "mkv", null, null) }
        val drama = (1..3).map { Movie(sourceA, "dra-$it", "dra", "Dra $it", null, "mp4", null, null) }
        graph.store.replaceCatalog(
            sourceA,
            Catalog(
                categories = listOf(Category(sourceA, ContentKind.MOVIE, "act", "Action"), Category(sourceA, ContentKind.MOVIE, "dra", "Drama")),
                live = emptyList(), movies = action + drama, series = emptyList(),
            ),
        )
        graph.store.saveMovieDetail(
            MovieDetail(action[0], "A plot", "Action", "Someone", "Director", "2024-05-01", 5400, null),
        )
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun rowsShowLabelAndSeeAllOnlyWhenMore() {
        composeRule.setContent { GoodTvTheme { MoviesScreen(onMovie = {}, onSeeAll = {}) } }
        waitFor("Action")
        composeRule.onNodeWithText("Action").assertIsDisplayed()
        composeRule.onNodeWithText("Drama").assertIsDisplayed()
        // "See all" is the 32nd tile of the Action row: scroll that row to it. Drama (3 items) must have none.
        composeRule.onNode(hasScrollToKeyAction() and hasAnyChild(hasText("Act 01"))).performScrollToKey("see-all")
        composeRule.onAllNodesWithText("See all").assertCountEquals(1)
    }

    @Test
    fun selectingPosterOpensDetail() {
        var opened: MovieDetailKey? = null
        var grid: CategoryGridKey? = null
        composeRule.setContent { GoodTvTheme { MoviesScreen(onMovie = { opened = it }, onSeeAll = { grid = it }) } }
        waitFor("Act 01")
        composeRule.onNodeWithText("Act 01").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(MovieDetailKey(sourceA, "act-01"), opened)
        assertEquals(null, grid)
    }

    @Test
    fun detailPlayButtonFocusedAndEmitsPlayerKey() {
        var played: PlayerKey? = null
        composeRule.setContent { GoodTvTheme { MovieDetailScreen(key = MovieDetailKey(sourceA, "act-01"), onPlay = { played = it }) } }
        waitFor("Play")
        composeRule.onNodeWithText("A plot").assertIsDisplayed()
        composeRule.onNodeWithText("Play").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(PlayerKey(sourceA, PlayerKey.KIND_MOVIE, "act-01", "mkv", "Act 01"), played)
    }

    @Test
    fun allHiddenShowsEverythingHiddenMessageNotNoSources() {
        runBlocking {
            ApplicationProvider.getApplicationContext<android.content.Context>().appGraph().store
                .setCategoriesHidden(sourceA, ContentKind.MOVIE, listOf("act", "dra"), true)
        }
        composeRule.setContent { GoodTvTheme { MoviesScreen(onMovie = {}, onSeeAll = {}) } }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Everything is hidden", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, composeRule.onAllNodes(hasText("No sources yet", substring = true)).fetchSemanticsNodes().size)
    }
}
