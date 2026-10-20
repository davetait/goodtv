package com.keyspaceapps.goodtv.ui.player

import android.view.KeyEvent

enum class PlayerAction { SHOW_CONTROLS, TOGGLE_PLAY, PLAY, PAUSE, SEEK_FORWARD, SEEK_BACK, SCRUB_FORWARD, SCRUB_BACK }

/**
 * Maps remote keys to player actions. Media keys (play/pause, fast-forward, rewind) work at any
 * time. The D-pad only acts here while the controls are hidden: Select plays or pauses, left/right
 * start seeking on the seek bar, up/down just show the controls. While they are visible the D-pad
 * moves focus between them instead (null). Live streams can pause but not seek.
 */
object PlayerKeys {
    fun actionFor(keyCode: Int, isLive: Boolean, controlsVisible: Boolean): PlayerAction? {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return PlayerAction.TOGGLE_PLAY
            KeyEvent.KEYCODE_MEDIA_PLAY -> return PlayerAction.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return PlayerAction.PAUSE
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return if (isLive) null else PlayerAction.SEEK_FORWARD
            KeyEvent.KEYCODE_MEDIA_REWIND -> return if (isLive) null else PlayerAction.SEEK_BACK
        }
        if (controlsVisible) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (isLive) PlayerAction.SHOW_CONTROLS else PlayerAction.TOGGLE_PLAY
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isLive) PlayerAction.SHOW_CONTROLS else PlayerAction.SCRUB_FORWARD
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isLive) PlayerAction.SHOW_CONTROLS else PlayerAction.SCRUB_BACK
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> PlayerAction.SHOW_CONTROLS
            else -> null
        }
    }
}
