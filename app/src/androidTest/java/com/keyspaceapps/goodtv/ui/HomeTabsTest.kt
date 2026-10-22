package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.requestFocus
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** D-pad navigation across the top tabs on the real Home screen. */
@OptIn(ExperimentalTestApi::class)
class HomeTabsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() { scenario?.close() }

    /** Seed before launching so HomeViewModel resolves the initial tab against real data. */
    private fun seedAndLaunch() {
        seed()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun seed() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
        graph.db.clearAllTables()
        graph.settings.setLastTab("LIVE")
        val id = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
        graph.store.replaceCatalog(
            id,
            Catalog(
                categories = listOf(Category(id, ContentKind.LIVE, "c1", "Sports"), Category(id, ContentKind.MOVIE, "m1", "Action")),
                live = listOf(LiveChannel(id, "l1", "c1", "Channel One", null, 1)),
                movies = listOf(Movie(id, "mv1", "m1", "Movie One", null, "mp4", null, null)),
                series = emptyList(),
            ),
        )
    }

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun hasAnyChipLabel() = listOf("All", "Favourites", "Network", "News", "Sports", "Entertainment", "Comedy", "Movies", "Kids", "Documentary", "Events")
        .map { hasText(it) and !tab(it) }.reduce { a, b -> a or b }

    private fun waitFocused(matcher: SemanticsMatcher) {
        try {
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
        }
    }

    private fun waitFor(text: String) {
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
        }
    }

    /**
     * The Live tab is the guide: focus starts on the first row's cell ("No guide info" here, as wide as the
     * row). UP goes to the row above it (a genre chip or the search field to their right), UP again to the
     * tab whose horizontal span overlaps it ("Sources"), not the selected tab. LEFT then walks along the
     * tabs and Select switches.
     */
    @Test
    fun upFromTheGuideReachesTheTabsAndLeftWalksTabs() {
        seedAndLaunch()
        waitFor("Channel One")
        waitFocused(hasText("No guide info"))

        // A row-wide cell sits under both the chips and the search field to their right.
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        waitFocused(hasAnyChipLabel() or hasTestTag("guide_search"))
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        waitFocused(hasText("Sources"))
        composeRule.onNodeWithText("Live").assertIsSelected()

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithText("Series").assertIsFocused()

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("Series").assertIsSelected()
    }

    @Test
    fun rightFromLiveTabWalksPastSportsToMoviesAndSelectOpensIt() {
        seedAndLaunch()
        waitFor("Channel One")
        composeRule.onNodeWithText("Live").requestFocus()
        composeRule.onNodeWithText("Live").assertIsFocused()

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        // The guide's chips share names with tabs ("Sports", "Movies").
        composeRule.onNode(tab("Sports")).assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNode(tab("Movies")).assertIsFocused()

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Movie One")
        composeRule.onNodeWithText("Action").assertIsDisplayed()
    }
}
