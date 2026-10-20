package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.requestFocus
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.data.tmdb.PopularMoviesQuery
import com.keyspaceapps.goodtv.data.tmdb.PopularShowsQuery
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** The Home tab's layout skeleton: search bar on top, the rails (popular rows from stored rankings), focus on the first tile, search the D-pad can pass through. */
@OptIn(ExperimentalTestApi::class)
class HomeTabScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() { scenario?.close() }

    /** Seed before launching so HomeViewModel resolves the initial tab against real data. An unknown tab name means "none saved". */
    private fun seedAndLaunch(lastTab: String, ranked: Boolean = true) {
        runBlocking {
            val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.db.clearAllTables()
            graph.settings.setLastTab(lastTab)
            val id = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
            graph.store.replaceCatalog(
                id,
                Catalog(
                    categories = listOf(Category(id, ContentKind.LIVE, "c1", "News"), Category(id, ContentKind.MOVIE, "m1", "Action")),
                    live = listOf(LiveChannel(id, "l1", "c1", "Matrix News", null, 1)),
                    movies = listOf(Movie(id, "mv1", "m1", "The Matrix", null, "mp4", null, null), Movie(id, "mv2", "m1", "Heat", null, "mp4", null, null, tmdbId = 949)),
                    series = listOf(Series(id, "sr1", null, "Dark", null, null, null, tmdbId = 70523)),
                ),
            )
            // Rankings stored "now", so nothing is fetched (TMDB is off in tests anyway).
            if (ranked) {
                graph.db.popular().replace(PopularMoviesQuery.listKey, listOf(949), System.currentTimeMillis())
                graph.db.popular().replace(PopularShowsQuery.listKey, listOf(70523), System.currentTimeMillis())
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
        }
    }

    @Test
    fun showsSearchAndRowsWithFocusOnFirstTile() {
        seedAndLaunch("HOME")
        waitFor("Popular Shows")
        composeRule.onNodeWithText("Search movies, shows and channels").assertIsDisplayed()
        // Nothing watched yet, so no Continue Watching row; the popular rows show their ranked titles.
        composeRule.onAllNodes(hasText("Continue Watching")).assertCountEquals(0)
        composeRule.onNodeWithText("Popular Movies").assertIsDisplayed()
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Heat").assertIsFocused() }.isSuccess }
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Dark").assertIsFocused()
    }

    /** No rankings and nothing watched (e.g. TMDB not set up): a hint, and the scope dropdown takes focus. */
    @Test
    fun withNoRowsAHintShowsAndScopeTakesFocus() {
        seedAndLaunch("HOME", ranked = false)
        waitFor("Popular movies and shows appear here", substring = true)
        composeRule.onAllNodes(hasText("Popular Movies")).assertCountEquals(0)
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("All").assertIsFocused() }.isSuccess }
    }

    @Test
    fun upFromFirstTileFocusesScopeThenRightFocusesSearch() {
        seedAndLaunch("HOME")
        waitFor("Heat")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Heat").assertIsFocused() }.isSuccess }

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("All").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        // The bar takes focus, not the text field inside it (that would open the keyboard).
        composeRule.onNode(isFocused() and hasAnyDescendant(hasSetTextAction())).assertExists()
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    /** The field only takes focus after Select; DOWN leaves it again so the D-pad is never trapped. */
    @Test
    fun selectOnSearchFocusesTheFieldAndDownLeavesIt() {
        seedAndLaunch("HOME")
        waitFor("Heat")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Heat").assertIsFocused() }.isSuccess }
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasSetTextAction()).assertIsFocused() }.isSuccess }
        composeRule.onNode(hasSetTextAction()).performTextInput("matrix")

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        composeRule.onNodeWithText("matrix").assertIsDisplayed()
    }

    @Test
    fun homeIsTheDefaultTabWhenNoneIsSaved() {
        seedAndLaunch("")
        waitFor("Popular Shows")
        composeRule.onNodeWithText("Home").assertIsSelected()
    }

    @Test
    fun searchShowsMatchesOfEveryKindLabelledByKind() {
        seedAndLaunch("HOME")
        waitFor("Heat")
        composeRule.onNode(hasSetTextAction()).performTextInput("matrix")
        waitFor("The Matrix")
        composeRule.onNodeWithText("Matrix News").assertIsDisplayed()
        composeRule.onNodeWithText("Channel").assertIsDisplayed()
        composeRule.onNodeWithText("Movie").assertIsDisplayed()
        composeRule.onAllNodes(hasText("Popular Shows")).assertCountEquals(0)
    }

    @Test
    fun noMatchesSaysSo() {
        seedAndLaunch("HOME")
        waitFor("Heat")
        composeRule.onNode(hasSetTextAction()).performTextInput("zzz")
        waitFor("Nothing matches “zzz”")
    }

    /** The scope list opens in a popup with focus on the current scope; picking Movies narrows the results. */
    @Test
    fun scopeDropdownNarrowsToOneKindAndReturnsFocus() {
        seedAndLaunch("HOME")
        waitFor("Heat")
        composeRule.onNode(hasSetTextAction()).performTextInput("matrix")
        waitFor("Matrix News")

        composeRule.onNodeWithText("All").requestFocus()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        val popup = composeRule.onAllNodes(isRoot())[1]
        popup.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Matrix News")).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithText("The Matrix").assertIsDisplayed()
        composeRule.onAllNodes(hasText("Movie")).assertCountEquals(0)
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText("Movies") and hasClickAction() and isFocused()).assertExists() }.isSuccess }
    }
}
