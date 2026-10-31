package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.sports.League
import com.keyspaceapps.goodtv.data.sports.LeagueGroup
import com.keyspaceapps.goodtv.settingsDataStore
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The Settings Sports row and the league switches. The emulator's settings hold real data, so the stored
 * league set is cleared before launch (nothing stored means the defaults) and again after.
 */
@OptIn(ExperimentalTestApi::class)
class SportsSettingsTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<*>? = null

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val graph: AppGraph get() = context.appGraph()

    private fun clearStoredLeagues() = runBlocking {
        context.settingsDataStore.edit { it.remove(stringSetPreferencesKey("sports_leagues")) }
        Unit
    }

    @Before
    fun setUp() = runBlocking {
        graph.clearDatabaseForTest()
        clearStoredLeagues()
        graph.settings.setLastTab("SETTINGS")
    }

    @After
    fun tearDown() {
        scenario?.close()
        clearStoredLeagues()
    }

    private fun waitFor(matcher: SemanticsMatcher, unmerged: Boolean = false) {
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) { composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e }
    }

    private fun waitFocused(matcher: SemanticsMatcher) = waitFor(matcher and isFocused())

    private fun press(key: Key) = composeRule.onRoot().performKeyInput { pressKey(key) }

    private fun openSportsSettings() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFocused(hasText("Sources"))
        repeat(3) { press(Key.DirectionDown) }
        waitFocused(hasText("Sports"))
        press(Key.DirectionCenter)
        waitFocused(hasText("AHL"))
    }

    private fun leagueIs(label: String, on: Boolean) = hasText(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, if (on) ToggleableState.On else ToggleableState.Off)

    private fun y(label: String): Float = composeRule.onNode(hasText(label)).fetchSemanticsNode().positionInRoot.y

    @Test
    fun settingsRowCountsLeaguesOn() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(hasText("6 leagues on"), unmerged = true)
    }

    @Test
    fun headingsAndAlphabeticalLeagues() {
        openSportsSettings()
        waitFor(hasText("North America"))
        assertTrue(y("North America") < y("AHL"))
        assertTrue(y("AHL") < y("College Basketball (Men's)"))
        assertTrue(y("College Basketball (Men's)") < y("MLB"))
        // World soccer and Cricket lie further down the list; scroll to them with keys.
        repeat(League.entries.count { it.group == LeagueGroup.NORTH_AMERICA } - 1) { press(Key.DirectionDown) }
        waitFocused(hasText("WNBA"))
        press(Key.DirectionDown)
        waitFor(hasText("World soccer"))
        assertTrue(y("World soccer") < y("Bundesliga"))
        waitFocused(hasText("Bundesliga"))
    }

    @Test
    fun selectFlipsALeague() {
        openSportsSettings()
        waitFor(leagueIs("MLS", on = false))
        // AHL, College Basketball x2, College Football, Liga MX, MLB, MLS.
        repeat(6) { press(Key.DirectionDown) }
        waitFocused(hasText("MLS"))
        press(Key.DirectionCenter)
        waitFor(leagueIs("MLS", on = true))
        assertTrue(League.MLS in runBlocking { graph.settings.sportsLeagues.first() })
        press(Key.DirectionCenter)
        waitFor(leagueIs("MLS", on = false))
        assertEquals(false, League.MLS in runBlocking { graph.settings.sportsLeagues.first() })
    }
}
