package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.tmdb.PopularMoviesQuery
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Home's Popular Movies: the stored TMDB ranking shown as the library's preferred copies. */
@OptIn(ExperimentalTestApi::class)
class PopularMoviesUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    /** A ranking stored "now", so nothing is fetched (TMDB is off in tests anyway). */
    @Before
    fun seedAndLaunch() {
        runBlocking {
            val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.db.clearAllTables()
            graph.settings.setLastTab("HOME")
            val sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
            fun movie(id: String, tmdbId: Long, title: String, language: String?) =
                Movie(sid, id, null, "$language - $title", null, "mkv", null, null, tmdbId, title = title, language = language)
            graph.store.replaceCatalog(
                sid,
                Catalog(
                    emptyList(), emptyList(),
                    listOf(movie("1", 10, "Heat (1995)", "DE"), movie("2", 10, "Heat (1995)", "EN"), movie("3", 20, "Ronin (1998)", "FR"), movie("4", 30, "Collateral (2004)", null)),
                    emptyList(),
                ),
            )
            graph.db.popular().replace(PopularMoviesQuery.listKey, listOf(10, 20, 30), System.currentTimeMillis())
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() { scenario?.close() }

    private fun waitFor(text: String, substring: Boolean = false) {
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
        }
    }

    private fun waitFocused(node: () -> SemanticsNodeInteraction) =
        composeRule.waitUntil(5_000) { runCatching { node().assertIsFocused() }.isSuccess }

    private fun pressBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun showsOneCopyPerFilmInRankOrderAndBackReturnsToTheCard() {
        waitFor("Popular Movies")
        waitFocused { composeRule.onNodeWithText("Heat (1995) (EN)") }
        composeRule.onAllNodes(hasText("Heat (1995) (DE)")).assertCountEquals(0)

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("Ronin (1998) (FR)").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("Collateral (2004)").assertIsFocused()

        // Opens the movie's page (its details can't load from the fake server), then Back lands on the same card.
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Could not reach server", substring = true)
        pressBack()
        waitFor("Popular Movies")
        waitFocused { composeRule.onNodeWithText("Collateral (2004)") }

        // DOWN goes to the Popular Shows card underneath (whichever lines up), not back to the top.
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNode(isFocused() and hasText("Show ", substring = true)).assertExists()
    }

    /** On a fresh install the ranking arrives after Home is showing; the new row comes into view with focus. */
    @Test
    fun aRankingThatArrivesLateTakesFocusFromThePlaceholders() {
        scenario?.close()
        runBlocking { graph().db.popular().replace("none", emptyList(), System.currentTimeMillis()) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Show 1")
        waitFocused { composeRule.onNodeWithText("Show 1") }
        composeRule.onAllNodes(hasText("Popular Movies")).assertCountEquals(0)

        runBlocking { graph().db.popular().replace(PopularMoviesQuery.listKey, listOf(20), System.currentTimeMillis()) }
        waitFor("Popular Movies")
        waitFocused { composeRule.onNodeWithText("Ronin (1998) (FR)") }
        composeRule.onNodeWithText("Popular Movies").assertIsDisplayed()
    }

    private fun graph() = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
}

