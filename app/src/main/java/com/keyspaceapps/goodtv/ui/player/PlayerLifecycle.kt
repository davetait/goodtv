package com.keyspaceapps.goodtv.ui.player

import androidx.lifecycle.Lifecycle

enum class PlayerLifecycleAction { PAUSE, RESUME }

/** Leaving the app (Home button) pauses playback; coming back resumes it if it was playing. */
object PlayerLifecycle {
    fun actionFor(event: Lifecycle.Event): PlayerLifecycleAction? = when (event) {
        Lifecycle.Event.ON_STOP -> PlayerLifecycleAction.PAUSE
        Lifecycle.Event.ON_START -> PlayerLifecycleAction.RESUME
        else -> null
    }
}
