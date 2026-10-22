package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.db.RoomTmdbCache
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.movies.MovieDetailScreen
import com.keyspaceapps.goodtv.ui.movies.MoviesScreen
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.series.SeriesDetailScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Posters and detail pages against a mock TMDB server (the runner keeps the real API off). */
class TmdbUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var graph: AppGraph
    private var sid = 0L

    private fun movieJson(id: Long, title: String, votes: Int) =
        """{"id":$id,"title":"$title","overview":"TMDB summary of $title.","release_date":"1999-03-31","runtime":136,"genres":[{"id":28,"name":"Action"}],"vote_average":8.2,"vote_count":$votes,"poster_path":"/p.jpg"}"""

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/3/movie/603" -> MockResponse(code = 200, body = movieJson(603, "The Matrix", 26280))
                "/3/movie/604" -> MockResponse(code = 200, body = movieJson(604, "Few Votes", 3))
                "/3/tv/1399" -> MockResponse(code = 200, body = """{"id":1399,"name":"Game of Thrones","overview":"TMDB summary of Game of Thrones.","first_air_date":"2011-04-17","vote_average":8.5,"vote_count":25000}""")
                else -> MockResponse(code = 404, body = "{}")
            }
        }
        server.start()
        graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
        graph.db.clearAllTables()
        graph.tmdbOverride = TmdbRepository(
            TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("test"), baseUrl = server.url("/3/")),
            RoomTmdbCache(graph.db.tmdb()), language = { "en-US" },
        )
        sid = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null))
        val movies = listOf(
            Movie(sid, "m1", "act", "4K-DE - Matrix (1999)", null, "mkv", 7.0, null, tmdbId = 603),
            Movie(sid, "m2", "act", "Few Votes", null, "mkv", null, null, tmdbId = 604),
            Movie(sid, "m3", "act", "Unknown Film", null, "mkv", 6.0, null, tmdbId = 999),
        )
        graph.store.replaceCatalog(
            sid,
            Catalog(
                categories = listOf(Category(sid, ContentKind.MOVIE, "act", "Action")), live = emptyList(), movies = movies,
                series = listOf(Series(sid, "s1", null, "EN - Game of Thrones", null, null, null, tmdbId = 1399)),
            ),
        )
        graph.store.saveMovieDetail(MovieDetail(movies[0], "Provider plot in German.", "Action", null, null, "1999-03-31", 8160, null))
        graph.store.saveMovieDetail(MovieDetail(movies[2], "Provider plot only.", null, null, null, null, null, null))
        graph.store.saveSeriesDetail(SeriesDetail(Series(sid, "s1", null, "EN - Game of Thrones", null, "Provider series plot.", null), null, "Drama", null, "2011-04-17", null, emptyList(), emptyList()))
    }

    @After
    fun tearDown() {
        graph.tmdbOverride = null
        server.close()
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun postersShowTheRatingOnlyWhenEnoughPeopleVoted() {
        composeRule.setContent { GoodTvTheme { MoviesScreen(onMovie = {}, onSeeAll = {}) } }
        waitFor("★ 8.2")
        // The Matrix (26k votes) gets a badge; "Few Votes" (3 votes) and the unknown id don't.
        composeRule.onAllNodesWithText("★ 8.2").assertCountEquals(1)
        composeRule.onNodeWithText("Few Votes").assertIsDisplayed()
    }

    @Test
    fun movieDetailShowsTmdbRatingSummaryAndCredit() {
        composeRule.setContent { GoodTvTheme { MovieDetailScreen(key = MovieDetailKey(sid, "m1"), onPlay = {}) } }
        waitFor("TMDB summary of The Matrix.")
        composeRule.onNodeWithText("★ 8.2 TMDB", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Provider plot in German.").assertCountEquals(0)
        composeRule.onNodeWithText("Details from TMDB", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("TMDB").assertIsDisplayed()
    }

    @Test
    fun movieDetailFallsBackToTheProviderWhenTmdbHasNothing() {
        composeRule.setContent { GoodTvTheme { MovieDetailScreen(key = MovieDetailKey(sid, "m3"), onPlay = {}) } }
        waitFor("Provider plot only.")
        composeRule.onNodeWithText("★ 6.0", substring = true).assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("TMDB", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("TMDB").assertCountEquals(0)
    }

    @Test
    fun seriesDetailShowsTmdbRatingAndSummary() {
        composeRule.setContent { GoodTvTheme { SeriesDetailScreen(key = SeriesDetailKey(sid, "s1"), onPlay = {}) } }
        waitFor("TMDB summary of Game of Thrones.")
        composeRule.onNodeWithText("★ 8.5 TMDB", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Provider series plot.").assertCountEquals(0)
    }
}
