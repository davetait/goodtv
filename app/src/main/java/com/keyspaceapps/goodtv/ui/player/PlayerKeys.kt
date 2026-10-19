package com.keyspaceapps.goodtv.ui.player

import android.view.KeyEvent

enum class PlayerAction { TOGGLE_PLAY, SEEK_FORWARD, SEEK_BACK }

/** Maps remote keys to transport actions. Live streams have no transport, so every key maps to null. */
object PlayerKeys {
    fun actionFor(keyCode: Int, isLive: Boolean): PlayerAction? {
        if (isLive) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerAction.TOGGLE_PLAY
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerAction.SEEK_FORWARD
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerAction.SEEK_BACK
            else -> null
        }
    }
}
