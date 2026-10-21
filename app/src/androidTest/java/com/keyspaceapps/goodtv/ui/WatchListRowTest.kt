package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.progress.PlaybackInfo
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.tmdb.PopularMoviesQuery
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.home.HomeTabScreen
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import com.keyspaceapps.goodtv.ui.watchlist.WatchListHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Home's Watch list row: placement, order, opening and removing. */
@OptIn(ExperimentalTestApi::class)
class WatchListRowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    private val graph: AppGraph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
    private var sid = 0L
    private val opened = mutableListOf<GoodTvKey>()
    private val heat get() = WatchListRef(sid, WatchListKind.MOVIE, "m1")
    private val severance get() = WatchListRef(sid, WatchListKind.SERIES, "s1")

    @Before
    fun seed() = runBlocking {
        graph.db.clearAllTables()
        sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null))
        graph.store.replaceCatalog(
            sid,
            Catalog(
                listOf(Category(sid, ContentKind.MOVIE, "act", "Action"), Category(sid, ContentKind.SERIES, "dra", "Drama")),
                emptyList(),
                listOf(
                    Movie(sid, "m1", "act", "Heat", null, "mkv", null, null),
                    Movie(sid, "m2", "act", "Ronin", null, "mkv", null, null, tmdbId = 8195),
                    Movie(sid, "m3", "act", "Thief", null, "mkv", null, null),
                ),
                listOf(Series(sid, "s1", "dra", "Severance", null, null, null)),
            ),
        )
    }

    private fun save(vararg refs: WatchListRef) = runBlocking { refs.forEach { graph.watchList.add(it); Thread.sleep(5) } }

    private fun show() = composeRule.setContent {
        GoodTvTheme { WatchListHost(graph.watchList) { HomeTabScreen(onNavigate = { opened += it }) } }
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFocused(text: String) =
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText(text, substring = true)).assertIsFocused() }.isSuccess }

    private fun top(text: String) = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
    private fun left(text: String) = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left

    private fun removeFocused() {
        composeRule.onRoot().performKeyInput { pressKey(Key.Menu) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        waitFocused("Remove from watch list")
        composeRule.onAllNodes(isRoot())[1].performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun rowShowsSavedNewestFirstAfterContinueWatching() {
        runBlocking { graph.watchProgress.record(WatchRef(sid, WatchKind.MOVIE, "m3"), 42 * 60_000L, 120 * 60_000L, PlaybackInfo("Thief", "mkv")) }
        save(heat, severance)
        show()
        waitFor("Watch list")
        waitFor("Continue Watching")
        assertTrue(top("Watch list") > top("Continue Watching"))
        val (sev, heatLeft) = left("Severance") to left("Heat")
        assertTrue("Severance at $sev, Heat at $heatLeft", sev < heatLeft)
    }

    @Test
    fun emptyListHidesTheRow() {
        runBlocking { graph.watchProgress.record(WatchRef(sid, WatchKind.MOVIE, "m3"), 42 * 60_000L, 120 * 60_000L, PlaybackInfo("Thief", "mkv")) }
        show()
        waitFor("Continue Watching")
        assertEquals(0, composeRule.onAllNodes(hasText("Watch list")).fetchSemanticsNodes().size)
    }

    @Test
    fun selectingACardOpensItsDetail() {
        save(heat)
        show()
        waitFocused("Heat")
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf<GoodTvKey>(MovieDetailKey(sid, "m1")), opened)
    }

    @Test
    fun removingACardFocusesItsNeighbour() {
        save(heat, severance)
        show()
        waitFocused("Severance")
        removeFocused()
        waitFocused("Heat")
        assertEquals(setOf(heat), runBlocking { graph.watchList.saved().first() })
    }

    @Test
    fun removingTheLastCardMovesFocusToTheNextRow() {
        runBlocking { graph.db.popular().replace(PopularMoviesQuery.listKey, listOf(8195), System.currentTimeMillis()) }
        save(heat)
        show()
        waitFocused("Heat")
        removeFocused()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Watch list")).fetchSemanticsNodes().isEmpty() }
        waitFocused("Ronin")
    }

    /** With Continue Watching above, emptying the Watch list hands focus up to Continue Watching's first card. */
    @Test
    fun removingTheLastCardWithContinueWatchingAboveFocusesIt() {
        runBlocking { graph.watchProgress.record(WatchRef(sid, WatchKind.MOVIE, "m3"), 42 * 60_000L, 120 * 60_000L, PlaybackInfo("Thief", "mkv")) }
        save(heat)
        show()
        waitFocused("Thief")
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitFocused("Heat")
        removeFocused()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Watch list")).fetchSemanticsNodes().isEmpty() }
        waitFocused("Thief")
    }
}
