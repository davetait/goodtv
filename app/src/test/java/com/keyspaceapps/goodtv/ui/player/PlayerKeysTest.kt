package com.keyspaceapps.goodtv.ui.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
