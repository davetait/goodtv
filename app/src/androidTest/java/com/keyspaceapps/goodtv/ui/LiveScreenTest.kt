package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.ui.live.LiveScreen
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LiveScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var sourceA = 0L
    private var sourceB = 0L

    @Before
    fun seed() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
        graph.db.clearAllTables()
        fun record(nick: String) = SourceRecord(0, SourceType.XTREAM, nick, SourceConfig.Xtream("http://s", "u", "p"), true, 1L, null)
        sourceA = graph.store.insertSource(record("A"))
        sourceB = graph.store.insertSource(record("B"))
        for ((id, tag) in listOf(sourceA to "A", sourceB to "B")) {
            graph.store.replaceCatalog(
                id,
                Catalog(
                    categories = listOf(Category(id, ContentKind.LIVE, "sports", "Sports"), Category(id, ContentKind.LIVE, "weather", "Weather")),
                    live = listOf(
                        LiveChannel(id, "$tag-s1", "sports", "$tag Sports 1", null, 1),
                        LiveChannel(id, "$tag-s2", "sports", "$tag Sports 2", null, 2),
                        LiveChannel(id, "$tag-w1", "weather", "$tag Weather 1", null, 1),
                    ),
                    movies = emptyList(), series = emptyList(),
                ),
            )
        }
    }

    private fun setLive(onPlay: (PlayerKey) -> Unit = {}) {
        composeRule.setContent { GoodTvTheme { LiveScreen(onPlay = onPlay) } }
        try {
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("A Sports 1")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree")
            throw e
        }
    }

    @Test
    fun sidebarShowsCategoriesWithSourceLabel() {
        setLive()
        composeRule.onNodeWithText("Sports · A").assertIsDisplayed()
        composeRule.onNodeWithText("Weather · A").assertIsDisplayed()
        composeRule.onNodeWithText("Sports · B").assertIsDisplayed()
    }

    @Test
    fun firstChannelFocusedAndDpadDownMoves() {
        setLive()
        composeRule.onNodeWithText("A Sports 1").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("A Sports 2").assertIsFocused()
    }

    @Test
    fun selectingChannelEmitsPlayerKey() {
        var played: PlayerKey? = null
        setLive { played = it }
        composeRule.onNodeWithText("A Sports 1").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(PlayerKey(sourceA, PlayerKey.KIND_LIVE, "A-s1", null, "A Sports 1"), played)
    }

    @Test
    fun dpadLeftReachesSidebarAndChangesCategory() {
        setLive()
        composeRule.onNodeWithText("A Sports 1").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithText("Sports · A").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Weather · A").assertIsFocused()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("A Weather 1")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("A Weather 1").assertIsDisplayed()
    }
}
