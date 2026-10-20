package com.keyspaceapps.goodtv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrubTest {
    private val twoHours = 2 * 3_600_000L

    @Test fun tapsMoveTenSecondsAndHoldingSpeedsUp() {
        assertEquals(10_000L, Scrub.step(0, twoHours))
        assertEquals(10_000L, Scrub.step(3, twoHours))
        assertEquals(30_000L, Scrub.step(4, twoHours))
        assertEquals(60_000L, Scrub.step(12, twoHours))
        assertEquals("1/50 of a long film", twoHours / 50, Scrub.step(24, twoHours))
        assertEquals("at least 2 min", 120_000L, Scrub.step(24, 20 * 60_000L))
    }

    @Test fun staysInsideTheVideo() {
        assertEquals(0L, Scrub.next(5_000, forward = false, repeatCount = 0, durationMs = twoHours))
        assertEquals(twoHours, Scrub.next(twoHours - 3_000, forward = true, repeatCount = 0, durationMs = twoHours))
        assertEquals(70_000L, Scrub.next(60_000, forward = true, repeatCount = 0, durationMs = twoHours))
        assertEquals("unknown duration: no upper bound", 40_000L, Scrub.next(10_000, forward = true, repeatCount = 5, durationMs = 0))
    }
}
