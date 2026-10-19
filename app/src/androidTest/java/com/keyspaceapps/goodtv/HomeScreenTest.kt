package com.keyspaceapps.goodtv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.ui.HomeScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val channels = listOf(
        PlayableItem("Channel A", "https://example.invalid/a.m3u8", isLive = true),
        PlayableItem("Channel B", "https://example.invalid/b.m3u8", isLive = true),
    )

    @Test
    fun showsTitleAndFocusesFirstChannel() {
        composeRule.setContent {
            GoodTvTheme { HomeScreen(channels = channels, onChannelSelected = {}) }
        }

        composeRule.onNodeWithText("GoodTV").assertIsDisplayed()
        composeRule.onNodeWithText("Channel A").assertIsDisplayed()
        composeRule.onNodeWithText("Channel A").assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dpadDownMovesFocusAndCenterSelects() {
        var selected: PlayableItem? = null
        composeRule.setContent {
            GoodTvTheme { HomeScreen(channels = channels, onChannelSelected = { selected = it }) }
        }

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Channel B").assertIsFocused()

        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals("https://example.invalid/b.m3u8", selected?.url)
    }
}
