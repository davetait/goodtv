package com.keyspaceapps.goodtv.ui.player

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLifecycleTest {
    @Test
    fun stopPausesAndStartResumes() {
        assertEquals(PlayerLifecycleAction.PAUSE, PlayerLifecycle.actionFor(Lifecycle.Event.ON_STOP))
        assertEquals(PlayerLifecycleAction.RESUME, PlayerLifecycle.actionFor(Lifecycle.Event.ON_START))
    }

    @Test
    fun otherEventsDoNothing() {
        assertNull(PlayerLifecycle.actionFor(Lifecycle.Event.ON_PAUSE))
        assertNull(PlayerLifecycle.actionFor(Lifecycle.Event.ON_RESUME))
        assertNull(PlayerLifecycle.actionFor(Lifecycle.Event.ON_DESTROY))
    }
}
