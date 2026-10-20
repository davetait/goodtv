package com.keyspaceapps.goodtv.ui.player

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKeysTest {
    private fun hidden(code: Int, live: Boolean = false) = PlayerKeys.actionFor(code, live, controlsVisible = false)
    private fun shown(code: Int, live: Boolean = false) = PlayerKeys.actionFor(code, live, controlsVisible = true)

    @Test
    fun withControlsHiddenSelectPlaysOrPausesAndLeftRightStartSeeking() {
        assertEquals(PlayerAction.TOGGLE_PLAY, hidden(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(PlayerAction.TOGGLE_PLAY, hidden(KeyEvent.KEYCODE_ENTER))
        assertEquals(PlayerAction.SCRUB_FORWARD, hidden(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlayerAction.SCRUB_BACK, hidden(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerAction.SHOW_CONTROLS, hidden(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PlayerAction.SHOW_CONTROLS, hidden(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun withControlsShownTheDpadMovesFocusInstead() {
        assertNull(shown(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(shown(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(shown(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun mediaKeysWorkEitherWay() {
        for (visible in listOf(true, false)) {
            assertEquals(PlayerAction.TOGGLE_PLAY, PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false, visible))
            assertEquals(PlayerAction.PLAY, PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY, false, visible))
            assertEquals(PlayerAction.PAUSE, PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PAUSE, false, visible))
            assertEquals(PlayerAction.SEEK_FORWARD, PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, false, visible))
            assertEquals(PlayerAction.SEEK_BACK, PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_REWIND, false, visible))
        }
    }

    @Test
    fun liveCanPauseButNotSeek() {
        assertEquals(PlayerAction.SHOW_CONTROLS, hidden(KeyEvent.KEYCODE_DPAD_CENTER, live = true))
        assertEquals(PlayerAction.SHOW_CONTROLS, hidden(KeyEvent.KEYCODE_DPAD_RIGHT, live = true))
        assertEquals(PlayerAction.TOGGLE_PLAY, hidden(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, live = true))
        assertNull(hidden(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, live = true))
        assertNull(hidden(KeyEvent.KEYCODE_MEDIA_REWIND, live = true))
    }

    @Test
    fun unknownKeyIsNull() = assertNull(hidden(KeyEvent.KEYCODE_A))

    /** Compose packs the Android code into Key.keyCode's upper 32 bits; .toInt() of it is 0 for every D-pad key. */
    @Test
    fun composeKeysMapThroughNativeKeyCode() {
        assertEquals(PlayerAction.SCRUB_FORWARD, hidden(Key.DirectionRight.nativeKeyCode))
        assertEquals(PlayerAction.SCRUB_BACK, hidden(Key.DirectionLeft.nativeKeyCode))
        assertEquals(PlayerAction.TOGGLE_PLAY, hidden(Key.DirectionCenter.nativeKeyCode))
        assertNull("the old bug", hidden(Key.DirectionRight.keyCode.toInt()))
    }
}
