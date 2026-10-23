package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Back from the player returns focus to the item the user left from (spec: "returns to the same position"). */
@OptIn(ExperimentalTestApi::class)
class FocusRestoreTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() { scenario?.close() }

    private fun seedAndLaunch(lastTab: String) {
        runBlocking {
            val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.clearDatabaseForTest()
            graph.settings.setLastTab(lastTab)
            val id = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
            graph.store.replaceCatalog(
                id,
                Catalog(
                    categories = listOf(Category(id, ContentKind.LIVE, "c1", "Sports"), Category(id, ContentKind.MOVIE, "m1", "Action")),
                    live = (1..3).map { LiveChannel(id, "l$it", "c1", "Channel $it", null, it) },
                    movies = (1..3).map { Movie(id, "mv$it", "m1", "Movie $it", null, "mp4", null, null) },
                    series = emptyList(),
                ),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun pressBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** In the guide, each channel here is one "No guide info" cell; the details panel names the focused row. */
    @Test
    fun backFromLivePlayerRefocusesTheChannelThatWasPlayed() {
        seedAndLaunch("LIVE")
        waitFor("Channel 1")
        waitFocusedIn("Channel 1")
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        waitFocusedIn("Channel 3")

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("LIVE")
        pressBack()

        waitFor("Channel 3")
        waitFocusedIn("Channel 3")
    }

    /** A "No guide info" cell has focus and the details panel (the second "Channel N") describes [channel]'s row. */
    private fun waitFocusedIn(channel: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodes(hasText("No guide info") and isFocused()).fetchSemanticsNodes().size == 1 &&
            composeRule.onAllNodes(hasText(channel)).fetchSemanticsNodes().size == 2
    }

    @Test
    fun backFromMovieDetailRefocusesThePoster() {
        seedAndLaunch("MOVIES")
        waitFor("Movie 1")
        composeRule.onNodeWithText("Movie 1").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("Movie 2").assertIsFocused()

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Could not reach server", substring = true)
        pressBack()

        waitFor("Movie 2")
        composeRule.onNodeWithText("Movie 2").assertIsFocused()
    }
}
