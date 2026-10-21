package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import com.keyspaceapps.goodtv.data.tmdb.PopularMoviesQuery
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The Watch list on the real Home tab (tab bar included), where screen space is as on the device. */
@OptIn(ExperimentalTestApi::class)
class WatchListHomeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    private val graph: AppGraph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun seed() = runBlocking {
        graph.db.clearAllTables()
        graph.settings.setLastTab("HOME")
        val sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
        graph.store.replaceCatalog(
            sid,
            Catalog(
                listOf(Category(sid, ContentKind.MOVIE, "act", "Action")),
                emptyList(),
                listOf(
                    Movie(sid, "m2", "act", "Ronin", null, "mkv", null, null, tmdbId = 8195),
                    Movie(sid, "m3", "act", "Lady and the Tramp II Scamp's Adventure (GR AUDIO)", null, "mkv", null, null),
                ),
                emptyList(),
            ),
        )
        graph.watchProgress.record(WatchRef(sid, WatchKind.MOVIE, "m3"), 42 * 60_000L, 120 * 60_000L, PlaybackInfo("Lady", "mkv"))
        graph.db.popular().replace(PopularMoviesQuery.listKey, listOf(8195), System.currentTimeMillis())
    }

    @After
    fun tearDown() { scenario?.close() }

    private fun waitFor(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFocused(text: String) =
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText(text, substring = true)).assertIsFocused() }.isSuccess }

    /** Saving from a Popular card adds the Watch list row above it; the focused card must stay on screen. */
    @Test
    fun addingFromPopularKeepsTheFocusedCardOnScreen() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFocused("Lady and the Tramp")
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitFocused("Ronin")
        composeRule.onRoot().performKeyInput { pressKey(Key.Menu) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        waitFocused("Add to watch list")
        composeRule.onAllNodes(isRoot())[1].performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Watch list")
        composeRule.waitForIdle()
        composeRule.onNode(isFocused()).assertTextContains("Ronin", substring = true)
        // Wholly on screen, not just its outline peeking in at the bottom edge.
        val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        // positionInRoot and size aren't clipped to the visible area (boundsInRoot is).
        composeRule.waitUntil(5_000) {
            composeRule.onNode(isFocused()).fetchSemanticsNode().let { it.positionInRoot.y >= screen.top && it.positionInRoot.y + it.size.height <= screen.bottom }
        }
    }
}
