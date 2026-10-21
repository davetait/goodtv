package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
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
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.movies.MoviesScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import com.keyspaceapps.goodtv.ui.watchlist.WatchListHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Saving titles to the watch list: poster menus and the bookmark corner. */
@OptIn(ExperimentalTestApi::class)
class WatchListUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val graph: AppGraph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
    private var sid = 0L
    private val opened = mutableListOf<Any>()
    private val heat get() = WatchListRef(sid, WatchListKind.MOVIE, "m1")

    @Before
    fun seed() = runBlocking {
        graph.db.clearAllTables()
        sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null))
        graph.store.replaceCatalog(
            sid,
            Catalog(
                listOf(Category(sid, ContentKind.MOVIE, "act", "Action"), Category(sid, ContentKind.SERIES, "dra", "Drama"), Category(sid, ContentKind.LIVE, "news", "News")),
                listOf(LiveChannel(sid, "c1", "news", "Heat Channel", null, 1)),
                listOf(Movie(sid, "m1", "act", "Heat", null, "mkv", null, null), Movie(sid, "m2", "act", "Ronin", null, "mkv", null, null)),
                listOf(Series(sid, "s1", "dra", "Severance", null, null, null)),
            ),
        )
    }

    private fun showMovies() = composeRule.setContent {
        GoodTvTheme { WatchListHost(graph.watchList) { MoviesScreen(onMovie = { opened += it }, onSeeAll = { opened += it }) } }
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFocused(text: String) =
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText(text, substring = true)).assertIsFocused() }.isSuccess }

    /** Menu (what holding Select does) on the focused poster; returns the dialog once its button has focus. */
    private fun openMenu(button: String): SemanticsNodeInteraction {
        composeRule.onRoot().performKeyInput { pressKey(Key.Menu) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        waitFocused(button)
        return composeRule.onAllNodes(isRoot())[1]
    }

    private fun saved() = runBlocking { graph.watchList.saved().first() }

    @Test
    fun menuOnPosterOpensTheDialogNotTheDetail() {
        showMovies()
        waitFocused("Heat")
        openMenu("Add to watch list")
        composeRule.onNodeWithText("Add to watch list").assertIsDisplayed()
        assertEquals(emptyList<Any>(), opened)
    }

    @Test
    fun addingShowsTheBookmarkAndSaves() {
        showMovies()
        waitFocused("Heat")
        openMenu("Add to watch list").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
        composeRule.waitUntil(5_000) { heat in saved() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("watchlist-saved"), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        assertEquals(emptyList<Any>(), opened)
    }

    @Test
    fun savedPosterOffersRemove() {
        runBlocking { graph.watchList.add(heat) }
        showMovies()
        waitFocused("Heat")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("watchlist-saved"), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        openMenu("Remove from watch list").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { heat !in saved() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("watchlist-saved"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}
