package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The top menu shows where focus is and which page is open as two different marks: a bright pill on the
 * focused tab, and green on the open one. Read from the drawn pixels, since that's what the person sees.
 */
@OptIn(ExperimentalTestApi::class)
class MenuTabsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() { scenario?.close() }

    private fun seedAndLaunch() {
        runBlocking {
            val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.clearDatabaseForTest()
            graph.settings.setLastTab("SETTINGS")
            val id = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
            graph.store.replaceCatalog(
                id,
                Catalog(
                    categories = listOf(Category(id, ContentKind.MOVIE, "m1", "Action")),
                    live = emptyList(),
                    movies = listOf(Movie(id, "mv1", "m1", "Movie One", null, "mp4", null, null)),
                    series = emptyList(),
                ),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun image(label: String): ImageBitmap = composeRule.onNode(tab(label)).captureToImage()

    /** Share of the tab's pixels that are near-white: the pill fills most of it, text alone very little. */
    private fun ImageBitmap.brightShare(): Float {
        val map = toPixelMap()
        var bright = 0
        for (x in 0 until width) for (y in 0 until height) {
            val c = map[x, y]
            if (c.red > 0.85f && c.green > 0.85f && c.blue > 0.85f) bright++
        }
        return bright.toFloat() / (width * height)
    }

    /** Whether any pixel is the app's green (#22C55E), the open-page mark. */
    private fun ImageBitmap.hasGreen(): Boolean {
        val map = toPixelMap()
        for (x in 0 until width) for (y in 0 until height) {
            val c = map[x, y]
            if (c.green > 0.6f && c.red < 0.35f && c.blue < 0.55f) return true
        }
        return false
    }

    @Test
    fun theFocusedTabHasThePillAndTheOpenTabIsGreen() {
        seedAndLaunch()
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(tab("Movies")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(tab("Movies")).requestFocus()
        composeRule.onNode(tab("Movies")).assertIsFocused()
        composeRule.waitForIdle()

        val focused = image("Movies").brightShare()
        assertTrue("the focused tab sits on a pill (bright share $focused)", focused > 0.4f)
        val open = image("Settings")
        assertTrue("the open tab has no pill while focus is elsewhere (bright share ${open.brightShare()})", open.brightShare() < 0.2f)
        assertTrue("the open tab is marked green", open.hasGreen())
        assertTrue("other tabs have no pill", image("Home").brightShare() < 0.2f)
    }

    @Test
    fun downInThePageTheOpenTabStaysGreenAndNoTabHasThePill() {
        seedAndLaunch()
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(tab("Settings")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(tab("Settings")).requestFocus()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(tab("Settings") and isFocused()).fetchSemanticsNodes().isEmpty() }
        composeRule.waitForIdle()
        // Let the pill fade out.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        val open = image("Settings")
        assertTrue("the open tab keeps its green mark", open.hasGreen())
        assertTrue("no pill once focus is in the page (open tab ${open.brightShare()})", open.brightShare() < 0.2f)
        assertTrue("no pill on another tab", image("Series").brightShare() < 0.2f)
    }
}
