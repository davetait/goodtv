package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.db.RoomPeopleCache
import com.keyspaceapps.goodtv.data.db.RoomTmdbCache
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.tmdb.PeopleRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Season
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.movies.MovieDetailScreen
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.PersonSearchKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.series.SeriesDetailScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The People row on detail pages, against a mock TMDB server (the runner keeps the real API off). */
@OptIn(ExperimentalTestApi::class)
class PeopleRowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var graph: AppGraph
    private var sid = 0L

    private fun tvJson(id: Long, name: String) =
        """{"id":$id,"name":"$name","overview":"TMDB summary of $name.","first_air_date":"2011-04-17","vote_average":8.5,"vote_count":25000,""" +
            """"created_by":[{"id":9813,"name":"David Benioff"}],""" +
            """"aggregate_credits":{"cast":[{"id":1223786,"name":"Emilia Clarke","order":0,"roles":[{"character":"Daenerys Targaryen","episode_count":62}]}]}}"""

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/3/movie/603" -> MockResponse(code = 200, body = """{"id":603,"title":"The Matrix","overview":"TMDB summary of The Matrix.","release_date":"1999-03-31","vote_average":8.2,"vote_count":26280}""")
                "/3/movie/603/credits" -> MockResponse(
                    code = 200,
                    body = """{"cast":[{"id":6384,"name":"Keanu Reeves","character":"Neo","order":0},{"id":530,"name":"Carrie-Anne Moss","character":"Trinity","order":1}],""" +
                        """"crew":[{"id":9340,"name":"Lana Wachowski","job":"Director","department":"Directing"}]}""",
                )
                "/3/tv/1399" -> MockResponse(code = 200, body = tvJson(1399, "Game of Thrones"))
                "/3/tv/1400" -> MockResponse(code = 200, body = tvJson(1400, "Other Show"))
                else -> MockResponse(code = 404, body = "{}")
            }
        }
        server.start()
        graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
        graph.clearDatabaseForTest()
        val api = TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("test"), baseUrl = server.url("/3/"))
        graph.tmdbOverride = TmdbRepository(api, RoomTmdbCache(graph.db.tmdb()), language = { "en-US" })
        graph.peopleOverride = PeopleRepository(api, RoomPeopleCache(graph.db.people()), language = { "en-US" })
        sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null))
        val movies = listOf(
            Movie(sid, "m1", null, "4K-DE - Matrix (1999)", null, "mkv", 7.0, null, tmdbId = 603),
            Movie(sid, "m2", null, "Nobody Listed", null, "mkv", 6.0, null, tmdbId = 604),
        )
        val shows = listOf(
            Series(sid, "s1", null, "EN - Game of Thrones", null, null, null, tmdbId = 1399),
            Series(sid, "s2", null, "Other Show", null, null, null, tmdbId = 1400),
        )
        graph.store.replaceCatalog(sid, Catalog(emptyList(), emptyList(), movies, shows))
        graph.store.saveMovieDetail(MovieDetail(movies[0], "Provider plot.", null, "Keanu Reeves, Carrie-Anne Moss", "The Wachowskis", null, null, null))
        graph.store.saveMovieDetail(MovieDetail(movies[1], "Provider plot.", null, "A, B", null, null, null, null))
        for (s in shows) {
            val eps = listOf(
                Episode(sid, s.id, "${s.id}e1", 1, 1, "Winter", "mp4", 3000, null, null),
                Episode(sid, s.id, "${s.id}e2", 1, 2, "Kingsroad", "mp4", 3000, null, null),
            )
            graph.store.saveSeriesDetail(SeriesDetail(s, null, null, null, null, null, listOf(Season(1, "Season 1", null)), eps))
        }
        graph.watchProgress.record(WatchRef(sid, WatchKind.EPISODE, "s1e1", "s1"), 600_000, 3_000_000)
    }

    @After
    fun tearDown() {
        graph.tmdbOverride = null
        graph.peopleOverride = null
        server.close()
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun pressKey(key: Key) {
        composeRule.onRoot().performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
    }

    private val anyPersonTag = SemanticsMatcher("has a person- test tag") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("person-") == true
    }

    @Test
    fun moviePeopleRowIsBelowPlayAndOpensPersonSearch() {
        var opened: PersonSearchKey? = null
        composeRule.setContent { GoodTvTheme { MovieDetailScreen(MovieDetailKey(sid, "m1"), onPlay = {}, onPerson = { opened = it }) } }
        waitForTag("person-9340")
        composeRule.onNodeWithText("Play").assertIsFocused()
        composeRule.onNodeWithText("People").assertIsDisplayed()
        composeRule.onNodeWithText("Director", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Neo", useUnmergedTree = true).assertIsDisplayed()
        // TMDB's people replace the provider's text lines.
        composeRule.onAllNodesWithText("Cast:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Director:", substring = true).assertCountEquals(0)

        pressKey(Key.DirectionDown)
        composeRule.onNodeWithTag("person-9340", useUnmergedTree = true).assertIsFocused()
        pressKey(Key.DirectionRight)
        composeRule.onNodeWithTag("person-6384", useUnmergedTree = true).assertIsFocused()
        pressKey(Key.DirectionCenter)
        assertEquals(PersonSearchKey(6384, "Keanu Reeves"), opened)
    }

    @Test
    fun seriesPeopleRowKeepsStartingFocus() {
        var showing by mutableStateOf(SeriesDetailKey(sid, "s1"))
        // key() gives the second page fresh state, as navigating to it would.
        composeRule.setContent { GoodTvTheme { key(showing) { SeriesDetailScreen(showing, onPlay = {}, onPerson = {}) } } }
        waitForTag("person-9813")
        waitForText("Resume S1 E1")
        composeRule.onNodeWithText("Resume S1 E1", substring = true).assertIsFocused()
        composeRule.onNodeWithText("Creator", useUnmergedTree = true).assertIsDisplayed()
        pressKey(Key.DirectionUp)
        composeRule.onNodeWithTag("person-9813", useUnmergedTree = true).assertIsFocused()

        // Without progress the first episode takes focus, people row or not.
        composeRule.runOnIdle { showing = SeriesDetailKey(sid, "s2") }
        waitForText("Other Show")
        waitForTag("person-1223786")
        composeRule.onNodeWithText("E1 · Winter", substring = true).assertIsFocused()
    }

    @Test
    fun noPeopleFallsBackToProviderCast() {
        composeRule.setContent { GoodTvTheme { MovieDetailScreen(MovieDetailKey(sid, "m2"), onPlay = {}, onPerson = {}) } }
        waitForText("Cast: A, B")
        // TMDB answered (404, nobody listed): the cache holds the empty answer before we check.
        composeRule.waitUntil(10_000) { runBlocking { RoomPeopleCache(graph.db.people()).titlePeople(TmdbKind.MOVIE, 604) } != null }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cast: A, B").assertIsDisplayed()
        composeRule.onAllNodes(anyPersonTag, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("People").assertCountEquals(0)
    }
}
