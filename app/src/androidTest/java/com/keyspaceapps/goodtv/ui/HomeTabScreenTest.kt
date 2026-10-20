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
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** The Home tab's layout skeleton: search bar on top, three placeholder rails, focus on the first tile, search the D-pad can pass through. */
@OptIn(ExperimentalTestApi::class)
class HomeTabScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() { scenario?.close() }

    /** Seed before launching so HomeViewModel resolves the initial tab against real data. An unknown tab name means "none saved". */
    private fun seedAndLaunch(lastTab: String) {
        runBlocking {
            val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.db.clearAllTables()
            graph.settings.setLastTab(lastTab)
            graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitFor(text: String) {
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
        }
    }

    @Test
    fun showsSearchAndRowsWithFocusOnFirstTile() {
        seedAndLaunch("HOME")
        waitFor("Continue Watching")
        composeRule.onNodeWithText("Search movies, shows and channels").assertIsDisplayed()
        composeRule.onNodeWithText("Continue Watching").assertIsDisplayed()
        composeRule.onNodeWithText("Popular Movies").assertIsDisplayed()
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Continue 1").assertIsFocused() }.isSuccess }

        // The third rail sits below the fold on a 1080p TV; DOWN walks the rails and scrolls it in.
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Movie 1").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Show 1").assertIsFocused()
        composeRule.onNodeWithText("Popular Shows").assertIsDisplayed()
    }

    @Test
    fun upFromFirstTileFocusesSearch() {
        seedAndLaunch("HOME")
        waitFor("Continue 1")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Continue 1").assertIsFocused() }.isSuccess }

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        // The bar takes focus, not the text field inside it (that would open the keyboard).
        composeRule.onNode(isFocused() and hasAnyDescendant(hasSetTextAction())).assertExists()
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    /** The field only takes focus after Select; DOWN leaves it again so the D-pad is never trapped. */
    @Test
    fun selectOnSearchFocusesTheFieldAndDownLeavesIt() {
        seedAndLaunch("HOME")
        waitFor("Continue 1")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Continue 1").assertIsFocused() }.isSuccess }
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
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
        waitFor("Continue Watching")
        composeRule.onNodeWithText("Home").assertIsSelected()
    }
}
