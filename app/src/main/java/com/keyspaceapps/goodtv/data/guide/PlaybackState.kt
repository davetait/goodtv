package com.keyspaceapps.goodtv.data.guide

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a player is on screen; big background downloads wait while one is. Counted, because a
 * play-next transition composes the next player before the old one leaves: the flag must not blink
 * false in between.
 */
class PlaybackState {
    private val active = MutableStateFlow(false)
    private var count = 0

    val playing: StateFlow<Boolean> = active

    @Synchronized fun enter() {
        count++
        active.value = true
    }

    @Synchronized fun exit() {
        if (count > 0) count--
        active.value = count > 0
    }
}
