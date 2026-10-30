package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.state.ToggleableState
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
import com.keyspaceapps.goodtv.ui.settings.LiveTvSettingsScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
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
    private var scenario: ActivityScenario<*>? = null

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
        val main = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        waitFor(hasText("Live previews off"), unmerged = true)
        waitFocused(hasText("Sources"))
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        waitFocused(hasText("Live TV"))

        press(Key.DirectionCenter)
        waitFocused(hasText("Live previews"))
        press(Key.DirectionCenter)
        waitUntilOn()

        main.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("Live previews on"), unmerged = true)
        waitFocused(hasText("Live TV"))
        composeRule.onNode(tab("Settings")).assertIsSelected()
    }

    private var shown by mutableStateOf(true)
    private fun switchIs(on: Boolean) = hasText("Live previews") and
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, if (on) ToggleableState.On else ToggleableState.Off)

    /**
     * An "on" switch is on from its first frame, so it never flickers off and a quick Select turns it off, not on again.
     * (On the emulator the settings store answers within the test's first frame, so this guards the behaviour rather
     * than catching the old one-frame lag.)
     */
    @Test
    fun theSwitchIsRightOnItsFirstFrame() {
        runBlocking { graph.settings.setLivePreviews(true) }
        composeRule.waitUntil(5_000) { graph.livePreviewsSetting.value == true }
        val launched = ActivityScenario.launch(ComponentActivity::class.java).also { scenario = it }
        launched.onActivity { it.setContent { GoodTvTheme { if (shown) LiveTvSettingsScreen() } } }
        // Settled, it shows on (the row exposes the switch's state).
        waitFor(switchIs(on = true))
        shown = false
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        try {
            shown = true
            composeRule.mainClock.advanceTimeByFrame()
            assertTrue(composeRule.onAllNodes(switchIs(on = true)).fetchSemanticsNodes().isNotEmpty())
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        // Select flips what's shown: off.
        waitFocused(hasText("Live previews"))
        press(Key.DirectionCenter)
        composeRule.waitUntil(10_000) { !runBlocking { graph.settings.livePreviews.first() } }
        waitFor(switchIs(on = false))
        assertFalse(runBlocking { graph.settings.livePreviews.first() })
    }

    private fun waitUntilOn() {
        composeRule.waitUntil(10_000) { runBlocking { graph.settings.livePreviews.first() } }
        assertTrue(runBlocking { graph.settings.livePreviews.first() })
    }
}
