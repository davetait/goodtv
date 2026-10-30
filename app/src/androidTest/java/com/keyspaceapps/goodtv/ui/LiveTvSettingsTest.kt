package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The Live TV settings row and its switch. The emulator's settings hold real data, so the switch is put back off after. */
@OptIn(ExperimentalTestApi::class)
class LiveTvSettingsTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()

    @Before
    fun clearState() = runBlocking {
        graph.clearDatabaseForTest()
        graph.settings.setLivePreviews(false)
        graph.settings.setLastTab("SETTINGS")
    }

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking { graph.settings.setLivePreviews(false) }
    }

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun waitFor(matcher: SemanticsMatcher, unmerged: Boolean = false) {
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) { composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e }
    }

    private fun waitFocused(matcher: SemanticsMatcher) = waitFor(matcher and isFocused())

    private fun press(key: Key) = composeRule.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun liveTvRowShowsStatusAndOpensTheSwitch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasText("Live previews off"), unmerged = true)
        waitFocused(hasText("Sources"))
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        waitFocused(hasText("Live TV"))

        press(Key.DirectionCenter)
        waitFocused(hasText("Live previews"))
        press(Key.DirectionCenter)
        waitUntilOn()

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("Live previews on"), unmerged = true)
        waitFocused(hasText("Live TV"))
        composeRule.onNode(tab("Settings")).assertIsSelected()
    }

    private fun waitUntilOn() {
        composeRule.waitUntil(10_000) { runBlocking { graph.settings.livePreviews.first() } }
        assertTrue(runBlocking { graph.settings.livePreviews.first() })
    }
}
