package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.db.RoomPeopleCache
import com.keyspaceapps.goodtv.data.db.RoomTmdbCache
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.tmdb.PeopleRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Clicking a person opens their titles; typed names find people in Home search. Mock TMDB server (the runner keeps the real API off). */
@OptIn(ExperimentalTestApi::class)
class PersonSearchTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val server = MockWebServer()
    private lateinit var graph: AppGraph
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun credit(id: Long, date: String) = """{"id":$id,"media_type":"movie","character":"Lead","release_date":"$date","genre_ids":[28]}"""

    /** Tom Cruise (500) counts for Top Gun: Maverick (2022), Rogue Nation (2015) and Tom Cruise: Live (2008). */
    private fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/3/movie/361743" -> MockResponse(code = 200, body = """{"id":361743,"title":"Top Gun: Maverick","overview":"TMDB summary.","release_date":"2022-05-24","vote_average":8.2,"vote_count":9000}""")
                "/3/movie/361743/credits" -> MockResponse(
                    code = 200,
                    body = """{"cast":[{"id":500,"name":"Tom Cruise","character":"Maverick","order":0},{"id":501,"name":"Miles Teller","character":"Rooster","order":1}],"crew":[]}""",
                )
                "/3/person/500/combined_credits" -> MockResponse(
                    code = 200,
                    body = """{"id":500,"cast":[${credit(361743, "2022-05-24")},${credit(177677, "2015-07-31")},${credit(2009, "2008-08-08")}],"crew":[]}""",
                )
                "/3/search/person" -> if (request.url.queryParameter("query") == "Tom Cruise") {
                    MockResponse(code = 200, body = """{"page":1,"results":[{"id":500,"name":"Tom Cruise","known_for_department":"Acting","popularity":45.2}]}""")
                } else {
                    MockResponse(code = 200, body = """{"page":1,"results":[]}""")
                }
                else -> MockResponse(code = 404, body = "{}")
            }
        }
        server.start()
    }

    private fun seedAndLaunch(lastTab: String, withLive: Boolean = false) {
        startServer()
        runBlocking {
            graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.db.clearAllTables()
            graph.settings.setLastTab(lastTab)
            val api = TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("test"), baseUrl = server.url("/3/"))
            graph.tmdbOverride = TmdbRepository(api, RoomTmdbCache(graph.db.tmdb()), language = { "en-US" })
            graph.peopleOverride = PeopleRepository(api, RoomPeopleCache(graph.db.people()), language = { "en-US" })
            val sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
            val movies = listOfNotNull(
                Movie(sid, "m1", "c1", "Top Gun: Maverick", null, "mkv", null, 300, tmdbId = 361743),
                Movie(sid, "m2", "c1", "Rogue Nation", null, "mkv", null, 200, tmdbId = 177677),
                if (withLive) Movie(sid, "m3", "c1", "Tom Cruise: Live", null, "mkv", null, 100, tmdbId = 2009) else null,
            )
            graph.store.replaceCatalog(sid, Catalog(listOf(Category(sid, ContentKind.MOVIE, "c1", "Action")), emptyList(), movies, emptyList()))
            for (m in movies) graph.store.saveMovieDetail(MovieDetail(m, "Provider plot.", null, null, null, null, null, null))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        graph.tmdbOverride = null
        graph.peopleOverride = null
        server.close()
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
        }
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilFocused(check: () -> Unit) = composeRule.waitUntil(5_000) { runCatching(check).isSuccess }

    private fun press(key: Key) {
        composeRule.onRoot().performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
    }

    /** Whether the [index]th "Tom Cruise: Live" poster from the top (0: his section, 1: the title results) has focus. Node order isn't screen order. */
    private fun liveCopyFocused(index: Int): Boolean =
        composeRule.onAllNodes(hasText("Tom Cruise: Live")).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }
            .getOrNull(index)?.config?.getOrElseNullable(SemanticsProperties.Focused) { null } == true

    private fun pressBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** From the Movies tab, open Top Gun: Maverick and click Tom Cruise in its People row. */
    private fun openTomCruiseFromTopGun() {
        waitFor("Top Gun: Maverick")
        waitUntilFocused { composeRule.onNodeWithText("Top Gun: Maverick").assertIsFocused() }
        press(Key.DirectionCenter)
        waitForTag("person-500")
        waitUntilFocused { composeRule.onNodeWithText("Play").assertIsFocused() }
        press(Key.DirectionDown)
        composeRule.onNodeWithTag("person-500", useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionCenter)
    }

    @Test
    fun clickingAPersonOpensTheirTitlesAndBackReturns() {
        seedAndLaunch("MOVIES")
        openTomCruiseFromTopGun()

        waitFor("With Tom Cruise · 2 titles")
        composeRule.onNodeWithText("With Tom Cruise · 2 titles").assertIsDisplayed()
        composeRule.onNodeWithText("Top Gun: Maverick").assertIsDisplayed()
        composeRule.onNodeWithText("Rogue Nation").assertIsDisplayed()
        // The search box holds his name; focus is on his newest title.
        composeRule.onNode(hasSetTextAction() and hasText("Tom Cruise")).assertExists()
        waitUntilFocused { composeRule.onNodeWithText("Top Gun: Maverick").assertIsFocused() }

        pressBack()
        waitForTag("person-500")
        waitUntilFocused { composeRule.onNodeWithTag("person-500", useUnmergedTree = true).assertIsFocused() }
        composeRule.onAllNodesWithText("With Tom Cruise", substring = true).assertCountEquals(0)
    }

    @Test
    fun personAndTitleSectionsMayShareACopy() {
        seedAndLaunch("MOVIES", withLive = true)
        openTomCruiseFromTopGun()

        waitFor("With Tom Cruise · 3 titles")
        // "Tom Cruise" also matches the title "Tom Cruise: Live", so the same copy is in both sections.
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Tom Cruise: Live").fetchSemanticsNodes().size == 2 }
        waitUntilFocused { composeRule.onNodeWithText("Top Gun: Maverick").assertIsFocused() }

        // RIGHT twice to the person section's copy, DOWN to the ordinary result's copy below.
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertTrue(liveCopyFocused(0))
        press(Key.DirectionDown)
        assertTrue(liveCopyFocused(1))

        press(Key.DirectionCenter)
        waitFor("Play")
        pressBack()

        // Bringing the lower copy into view may scroll the header away, so wait for both copies instead.
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Tom Cruise: Live").fetchSemanticsNodes().size == 2 }
        composeRule.onNode(hasSetTextAction() and hasText("Tom Cruise")).assertExists()
        composeRule.waitUntil(5_000) { liveCopyFocused(1) }
        assertFalse(liveCopyFocused(0))
    }

    @Test
    fun homeSearchFindsAPersonByTypedName() {
        seedAndLaunch("HOME")
        waitFor("Search movies, shows and channels")
        composeRule.onNode(hasSetTextAction()).performTextInput("Tom Cruise")
        waitFor("With Tom Cruise · 2 titles")
        composeRule.onNodeWithText("Top Gun: Maverick").assertIsDisplayed()
        composeRule.onNodeWithText("Rogue Nation").assertIsDisplayed()
        composeRule.onAllNodesWithText("Nothing matches", substring = true).assertCountEquals(0)
    }
}
