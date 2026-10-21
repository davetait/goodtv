package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.keyspaceapps.goodtv.ui.player.PlayerControls
import com.keyspaceapps.goodtv.ui.player.PlayerPanel
import com.keyspaceapps.goodtv.ui.player.PlayerUi
import com.keyspaceapps.goodtv.ui.player.TrackChoice
import com.keyspaceapps.goodtv.ui.player.TrackRef
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Subtitles panel's OpenSubtitles row, Timing row and notices, without a player. */
@OptIn(ExperimentalTestApi::class)
class PlayerControlsTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val timing = mutableListOf<Long>()

    private fun show(ui: PlayerUi) = composeRule.setContent {
        GoodTvTheme {
            PlayerControls(
                ui = ui, scrubMs = null, panel = PlayerPanel.SUBTITLES,
                onTogglePlay = {}, onScrub = { _, _ -> }, onSeekBarSelect = {}, onOpenPanel = {}, onSelectTrack = { _, _ -> },
                onSubtitleTiming = { timing += it },
            )
        }
    }

    private fun waitFocused(text: String) =
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithText(text, substring = true).assertIsFocused() }.isSuccess }

    private val base = PlayerUi(title = "T", isLive = false, isPlaying = false, durationMs = 60_000)

    @Test fun onlineChoiceListedAfterTheFilesTracks() {
        show(base.copy(subtitles = listOf(
            TrackChoice("Off", true, null), TrackChoice("French", false, TrackRef(0, 0)), TrackChoice("English · OpenSubtitles", false, null, online = true),
        )))
        waitFocused("Off")
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("French").assertIsFocused()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("English · OpenSubtitles").assertIsFocused()
    }

    @Test fun timingRowShowsOffsetAndLeftRightAdjust() {
        show(base.copy(
            subtitles = listOf(TrackChoice("Off", false, null), TrackChoice("English · OpenSubtitles", true, null, online = true)),
            onlineSubtitleOffsetMs = 500,
        ))
        waitFocused("English · OpenSubtitles")
        composeRule.onNodeWithText("English · OpenSubtitles").assertIsSelected()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitFocused("Timing")
        composeRule.onNodeWithText("+0.5 s").assertExists()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        assertEquals(listOf(-500L, 500L, 500L), timing)
    }

    @Test fun noticeIsShownUnderTheTitle() {
        show(base.copy(
            subtitles = listOf(TrackChoice("Off", true, null)),
            subtitleNotice = "Daily OpenSubtitles limit reached. Try again after 00:00 UTC",
        ))
        composeRule.onNodeWithText("Daily OpenSubtitles limit reached. Try again after 00:00 UTC").assertExists()
    }
}
