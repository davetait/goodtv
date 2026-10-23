package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.settings.KeyStatus
import com.keyspaceapps.goodtv.data.settings.OpenSubtitlesAccess
import com.keyspaceapps.goodtv.ui.settings.IntegrationChecks
import com.keyspaceapps.goodtv.ui.settings.IntegrationsScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The Integrations screen: copy, focus, and saved keys only ever shown masked. Made-up keys only. */
@OptIn(ExperimentalTestApi::class)
class IntegrationsScreenTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()

    /** Accepts everything without a network call. */
    private val fakeChecks = object : IntegrationChecks {
        override suspend fun tmdb(key: String) = true
        override suspend fun openSubtitles(access: OpenSubtitlesAccess) = KeyStatus.CONNECTED
    }

    @Before
    fun clearTypedValues() = runBlocking {
        graph.integrationChecksOverride = fakeChecks
        graph.integrations.removeTmdb()
        graph.integrations.removeOpenSubtitles()
    }

    @After
    fun tearDown() = runBlocking {
        graph.integrations.removeTmdb()
        graph.integrations.removeOpenSubtitles()
        graph.integrationChecksOverride = null
    }

    private fun show() = composeRule.setContent { GoodTvTheme { IntegrationsScreen() } }

    private fun waitFor(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun showsDomainsHintAndExplanation() {
        show()
        waitFor("themoviedb.org")
        composeRule.onNodeWithText("themoviedb.org", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Tip: use the Google TV app on your phone to paste keys.").assertIsDisplayed()
        val why = "The API key lets GoodTV search for subtitles. Your OpenSubtitles username and password are needed to " +
            "download them: without signing in, downloads are limited to a small daily allowance."
        composeRule.onNodeWithText(why).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("opensubtitles.com", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tmdbFieldHasInitialFocusAndDownReachesSave() {
        show()
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithTag("tmdb-field", useUnmergedTree = true).assertIsFocused() }.isSuccess
        }
        composeRule.onNodeWithTag("tmdb-field", useUnmergedTree = true).assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tmdb-save").assertIsFocused()
    }

    @Test
    fun savedKeyIsMaskedNeverShownInFull() {
        runBlocking { graph.integrations.saveTmdb("abcd1234wxyz", KeyStatus.CONNECTED) }
        show()
        waitFor("••••wxyz")
        composeRule.onNodeWithText("Connected", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodes(hasText("abcd1234wxyz", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodes(hasText("abcd1234wxyz", substring = true)).fetchSemanticsNodes().size)
    }
}
