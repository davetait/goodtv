package com.keyspaceapps.goodtv.ui

import android.text.format.DateUtils
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
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The Settings tab: where it sits, its two rows' status lines, and focus into and back from them. */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()

    @Before
    fun clearState() = runBlocking {
        graph.db.clearAllTables()
        graph.integrations.removeTmdb()
        graph.integrations.removeOpenSubtitles()
        graph.settings.setLastTab("SETTINGS")
    }

    @After
    fun tearDown() { scenario?.close() }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }

    private fun addSource(nickname: String, lastSyncAt: Long?) = runBlocking {
        graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, nickname, SourceConfig.Xtream("http://s", "u", "p"), true, lastSyncAt, null))
    }

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun dumpOnFailure(block: () -> Unit) {
        try { block() } catch (e: Throwable) { composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e }
    }

    private fun waitFor(matcher: SemanticsMatcher, unmerged: Boolean = false) = dumpOnFailure {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFocused(matcher: SemanticsMatcher) = waitFor(matcher and isFocused())

    private fun press(key: Key) = composeRule.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun settingsTabReplacesSourcesTab() {
        addSource("A", System.currentTimeMillis())
        runBlocking { graph.settings.setLastTab("HOME") }
        launch()
        waitFor(tab("Settings"))
        assertEquals(0, composeRule.onAllNodes(tab("Sources")).fetchSemanticsNodes().size)
        val tabs = composeRule.onAllNodes(isTab).fetchSemanticsNodes()
        val last = tabs.maxBy { it.boundsInRoot.left }
        assertEquals(listOf("Settings"), last.config[SemanticsProperties.Text].map { it.text })
    }

    @Test
    fun firstLaunchOpensSettingsWithSourcesFocused() {
        runBlocking { graph.settings.setLastTab("LIVE") }
        launch()
        waitFocused(hasText("Sources"))
        composeRule.onNode(tab("Settings")).assertIsSelected()
        waitFor(hasText("No sources yet"))

        press(Key.DirectionCenter)
        waitFocused(hasText("Add source"))
    }

    @Test
    fun sourcesRowShowsCountAndLastSync() {
        val now = System.currentTimeMillis()
        val latest = now - 2 * DateUtils.HOUR_IN_MILLIS
        addSource("A", now - 5 * DateUtils.HOUR_IN_MILLIS)
        addSource("B", latest)
        launch()
        val ago = DateUtils.getRelativeTimeSpanString(latest, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        waitFor(hasText("2 sources · last synced $ago"), unmerged = true)
    }

    @Test
    fun integrationsRowShowsBothStatuses() {
        addSource("A", System.currentTimeMillis())
        launch()
        waitFor(hasText("TMDB not set up · OpenSubtitles not set up"), unmerged = true)
    }

    @Test
    fun backFromIntegrationsRefocusesItsRow() {
        addSource("A", System.currentTimeMillis())
        launch()
        waitFocused(hasText("Sources"))
        press(Key.DirectionDown)
        waitFocused(hasText("Integrations"))

        press(Key.DirectionCenter)
        waitFor(hasText("Tip: use the Google TV app on your phone to paste keys."))
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        waitFocused(hasText("Integrations"))
        composeRule.onNode(tab("Settings")).assertIsSelected()
    }

    @Test
    fun savedSourcesTabMapsToSettings() {
        runBlocking { graph.settings.setLastTab("SOURCES") }
        addSource("A", System.currentTimeMillis())
        launch()
        waitFor(tab("Settings"))
        composeRule.onNode(tab("Settings")).assertIsSelected()
        waitFocused(hasText("Sources"))
    }
}
