package com.keyspaceapps.goodtv.ui.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.compose.ui.input.key.nativeKeyCode
import org.junit.Test

class PlayerKeysTest {
    @Test
    fun centreTogglesPlayForVod() {
        assertEquals(PlayerAction.TOGGLE_PLAY, PlayerKeys.actionFor(KeyEvent.KEYCODE_DPAD_CENTER, isLive = false))
        assertEquals(PlayerAction.TOGGLE_PLAY, PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, isLive = false))
    }

    @Test
    fun liveIgnoresAllTransportKeys() {
        assertNull(PlayerKeys.actionFor(KeyEvent.KEYCODE_DPAD_CENTER, isLive = true))
        assertNull(PlayerKeys.actionFor(KeyEvent.KEYCODE_DPAD_RIGHT, isLive = true))
        assertNull(PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_REWIND, isLive = true))
    }

    @Test
    fun seekKeys() {
        assertEquals(PlayerAction.SEEK_FORWARD, PlayerKeys.actionFor(KeyEvent.KEYCODE_DPAD_RIGHT, isLive = false))
        assertEquals(PlayerAction.SEEK_FORWARD, PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, isLive = false))
        assertEquals(PlayerAction.SEEK_BACK, PlayerKeys.actionFor(KeyEvent.KEYCODE_DPAD_LEFT, isLive = false))
        assertEquals(PlayerAction.SEEK_BACK, PlayerKeys.actionFor(KeyEvent.KEYCODE_MEDIA_REWIND, isLive = false))
    }

    @Test
    fun unknownKeyIsNull() = assertNull(PlayerKeys.actionFor(KeyEvent.KEYCODE_A, isLive = false))

    /** Compose packs the Android code into Key.keyCode's upper 32 bits; .toInt() of it is 0 for every D-pad key. */
    @Test
    fun composeKeysMapThroughNativeKeyCode() {
        assertEquals(PlayerAction.SEEK_FORWARD, PlayerKeys.actionFor(androidx.compose.ui.input.key.Key.DirectionRight.nativeKeyCode, isLive = false))
        assertEquals(PlayerAction.SEEK_BACK, PlayerKeys.actionFor(androidx.compose.ui.input.key.Key.DirectionLeft.nativeKeyCode, isLive = false))
        assertEquals(PlayerAction.TOGGLE_PLAY, PlayerKeys.actionFor(androidx.compose.ui.input.key.Key.DirectionCenter.nativeKeyCode, isLive = false))
        org.junit.Assert.assertNull("the old bug", PlayerKeys.actionFor(androidx.compose.ui.input.key.Key.DirectionRight.keyCode.toInt(), isLive = false))
    }
}
