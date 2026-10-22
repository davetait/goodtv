package com.keyspaceapps.goodtv.data.guide

import kotlinx.coroutines.flow.MutableStateFlow

/** Whether the player is on screen; big background downloads wait while it is. */
class PlaybackState {
    val playing = MutableStateFlow(false)
}
