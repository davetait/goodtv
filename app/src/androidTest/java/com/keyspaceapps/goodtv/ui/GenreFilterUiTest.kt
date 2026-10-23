package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.db.RoomTmdbGenreStore
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.movies.MoviesScreen
import com.keyspaceapps.goodtv.ui.series.SeriesScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The genre dropdown over the Movies and Series rows, driven with the D-pad. */
@OptIn(ExperimentalTestApi::class)
class GenreFilterUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun seed() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
        graph.clearDatabaseForTest()
        val sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null))
        graph.store.replaceCatalog(
            sid,
            Catalog(
                categories = listOf(Category(sid, ContentKind.MOVIE, "m", "Films"), Category(sid, ContentKind.SERIES, "s", "Shows")),
                live = emptyList(),
                movies = listOf(
                    Movie(sid, "1", "m", "Heat", null, "mkv", null, 1, tmdbId = 949),
                    Movie(sid, "2", "m", "Up", null, "mkv", null, 2, tmdbId = 14160),
                ),
                series = listOf(
                    Series(sid, "a", "s", "Dark", null, null, null, genres = setOf(Genre.MYSTERY)),
                    Series(sid, "b", "s", "Bluey", null, null, null, genres = setOf(Genre.KIDS)),
                ),
            ),
        )
        RoomTmdbGenreStore(graph.db).saveMovieGenres(mapOf(949L to setOf(Genre.CRIME), 14160L to setOf(Genre.ANIMATION)), fetchedAt = System.currentTimeMillis())
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** Opens the dropdown from the first poster (UP, Select) and moves [downs] rows down the list before Select. */
    private fun pickFromDropdown(downs: Int) {
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("All genres").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        composeRule.onAllNodes(isRoot())[1].performKeyInput { repeat(downs) { pressKey(Key.DirectionDown) }; pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun choosingAMovieGenreShowsOnlyItsTitlesAndAllGenresBringsTheRowsBack() {
        composeRule.setContent { GoodTvTheme { MoviesScreen(onMovie = {}, onSeeAll = {}) } }
        waitFor("Films")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Up").assertIsFocused() }.isSuccess }

        pickFromDropdown(downs = 2) // All genres, Animation · 1, Crime · 1 (alphabetical)
        waitFor("Crime · 1")
        composeRule.onNodeWithText("Heat").assertIsDisplayed()
        composeRule.onAllNodesWithText("Up").assertCountEquals(0)
        composeRule.onAllNodesWithText("Films").assertCountEquals(0)
        composeRule.onNodeWithText("Crime · 1").assertIsFocused() // focus returns to the dropdown

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        composeRule.onAllNodes(isRoot())[1].performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionUp); pressKey(Key.DirectionCenter) }
        waitFor("Films")
        composeRule.onNodeWithText("All genres").assertIsDisplayed()
    }

    @Test
    fun seriesGenresComeFromTheProvider() {
        composeRule.setContent { GoodTvTheme { SeriesScreen(onSeries = {}, onSeeAll = {}) } }
        waitFor("Shows")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Bluey").assertIsFocused() }.isSuccess }
        pickFromDropdown(downs = 1) // All genres, Kids · 1, Mystery · 1
        waitFor("Kids · 1")
        composeRule.onNodeWithText("Bluey").assertIsDisplayed()
        composeRule.onAllNodesWithText("Dark").assertCountEquals(0)
    }

    /** The search bar sits right of the dropdown; typing shows matches within the chosen genre. */
    @Test
    fun searchShowsMatchesWithinTheChosenGenre() {
        composeRule.setContent { GoodTvTheme { MoviesScreen(onMovie = {}, onSeeAll = {}) } }
        waitFor("Films")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText("Up").assertIsFocused() }.isSuccess }
        composeRule.onNodeWithText("Search movies").assertIsDisplayed()

        composeRule.onNode(hasSetTextAction()).performTextInput("u")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Films")).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithText("Up").assertIsDisplayed()
        composeRule.onAllNodesWithText("Heat").assertCountEquals(0)

        composeRule.onNodeWithText("All genres").requestFocus()
        pickFromDropdownFocused(downs = 2) // Crime: "Up" isn't crime
        waitFor("Nothing in this genre matches “u”")

        composeRule.onNode(hasSetTextAction()).performTextClearance()
        waitFor("Heat")
    }

    private fun pickFromDropdownFocused(downs: Int) {
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        composeRule.onAllNodes(isRoot())[1].performKeyInput { repeat(downs) { pressKey(Key.DirectionDown) }; pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
    }
}

