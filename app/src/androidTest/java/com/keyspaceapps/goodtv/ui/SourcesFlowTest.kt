package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end through the real Activity against a MockWebServer that speaks Xtream.
 * The runner disables the debug prefilled source, and the database is cleared before each test.
 */
class SourcesFlowTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val server = MockWebServer()

    /** Clear state first, then launch, so HomeViewModel resolves "no sources" against real data. */
    @Before
    fun setUp() {
        runBlocking {
            val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.db.clearAllTables()
            graph.settings.setLastTab("LIVE")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.queryParameter("action")) {
                null -> json("""{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000","max_connections":"2","allowed_output_formats":["m3u8"]},"server_info":{}}""")
                "get_live_categories" -> json("""[{"category_id":"1","category_name":"Sports"},{"category_id":"2","category_name":"News"}]""")
                "get_vod_categories" -> json("""[{"category_id":"10","category_name":"Action"}]""")
                "get_series_categories" -> json("""[{"category_id":"20","category_name":"Drama"}]""")
                "get_live_streams" -> json("""[{"stream_id":1,"name":"Sports One","category_id":"1"},{"stream_id":"2","name":"News One","category_id":2}]""")
                "get_vod_streams" -> json("""[{"stream_id":100,"name":"Movie A","category_id":"10","container_extension":"mkv"}]""")
                "get_series" -> json("""[{"series_id":200,"name":"Series A","category_id":"20"}]""")
                else -> json("[]", 404)
            }
        }
        server.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    private fun json(body: String, code: Int = 200) = MockResponse(code = code, body = body)

    /** tv-material surfaces respond to D-pad keys, not touch; trigger the click action directly. */
    private fun SemanticsNodeInteraction.tvClick() = performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun freshInstallLandsOnSourcesWithAddFocused() {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Add source")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Add source").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun addSourceShowsCountsAndLiveCategories() {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Add source")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Add source").tvClick()
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Test & save")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree")
            throw e
        }
        composeRule.onNodeWithText("Nickname").performTextInput("Living Room")
        composeRule.onNodeWithText("Server URL").performTextInput(server.url("/").toString().trimEnd('/'))
        composeRule.onNodeWithText("Username").performTextInput("u")
        composeRule.onNodeWithText("Password").performTextInput("p")
        composeRule.onNodeWithText("Test & save").tvClick()
        // Sign-in result stays on screen until the user presses Done; the sync runs in the background.
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Done")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Active", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsFocused().tvClick()

        try {
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodes(hasText("2 live", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            android.util.Log.i("GoodTvTree", "server requests received: ${server.requestCount}")
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree")
            throw e
        }
        composeRule.onNodeWithText("Living Room").assertIsDisplayed()

        composeRule.onNodeWithText("Live").tvClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Sports")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Sports").assertIsDisplayed()
    }
}
