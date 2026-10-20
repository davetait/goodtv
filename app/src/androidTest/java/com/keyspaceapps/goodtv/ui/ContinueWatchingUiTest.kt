package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.progress.PlaybackInfo
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Home's Continue Watching row: filled from saved progress; removing a card resets its progress. */
@OptIn(ExperimentalTestApi::class)
class ContinueWatchingUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private var sid = 0L
    private val movie get() = WatchRef(sid, WatchKind.MOVIE, "m1")

    /** Seeds a part-watched movie, then a part-watched episode (newer), before launching on Home. */
    @Before
    fun seedAndLaunch() {
        runBlocking {
            graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.db.clearAllTables()
            graph.settings.setLastTab("HOME")
            sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
            graph.store.replaceCatalog(
                sid,
                Catalog(emptyList(), emptyList(), listOf(Movie(sid, "m1", null, "Heat", null, "mkv", null, null)), listOf(Series(sid, "s1", null, "Dark", null, null, null))),
            )
            graph.watchProgress.record(movie, 42 * 60_000L, 170 * 60_000L, PlaybackInfo("Heat", "mkv"))
            Thread.sleep(5)
            graph.watchProgress.record(WatchRef(sid, WatchKind.EPISODE, "e1", "s1"), 10 * 60_000L, 50 * 60_000L, PlaybackInfo("Dark S1 E1", "mp4", 1, 1))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() { scenario?.close() }

    private fun waitFor(text: String) {
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
        }
    }

    private fun waitFocused(node: () -> SemanticsNodeInteraction) =
        composeRule.waitUntil(5_000) { runCatching { node().assertIsFocused() }.isSuccess }

    /** Menu on the focused card opens the remove dialog (its own window); Select confirms on the focused button. */
    private fun openDialog(): SemanticsNodeInteraction {
        composeRule.onRoot().performKeyInput { pressKey(Key.Menu) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        val dialog = composeRule.onAllNodes(isRoot())[1]
        waitFocused { composeRule.onNodeWithText("Remove from Continue Watching") }
        return dialog
    }

    @Test
    fun newestFirstAndRemovingResetsProgressAndMovesFocus() {
        waitFor("Continue Watching")
        composeRule.onNodeWithText("S1 E1 · 40 min left").assertIsDisplayed()
        composeRule.onNodeWithText("128 min left").assertIsDisplayed()
        waitFocused { composeRule.onNodeWithText("Dark") }

        openDialog().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Dark")).fetchSemanticsNodes().isEmpty() }
        assertTrue("every episode's progress is gone", runBlocking { graph.watchProgress.observeSeries(sid, "s1").first() }.isEmpty())
        waitFocused { composeRule.onNodeWithText("Heat") }

        // Removing the last card hides the row and hands focus to the next row.
        openDialog().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Continue Watching")).fetchSemanticsNodes().isEmpty() }
        assertNull(runBlocking { graph.watchProgress.observe(movie).first() })
        waitFocused { composeRule.onNodeWithText("Show 1") }
    }

    @Test
    fun cancelKeepsTheCardAndItsFocus() {
        waitFor("Continue Watching")
        waitFocused { composeRule.onNodeWithText("Dark") }
        openDialog().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
        composeRule.onAllNodes(hasText("Dark")).assertCountEquals(1)
        waitFocused { composeRule.onNodeWithText("Dark") }
    }
}
