package com.keyspaceapps.goodtv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerFormatsTest {
    @Test
    fun minutesAndSeconds() = assertEquals("01:05 / 45:00", PlayerFormats.position(65_000, 2_700_000))

    @Test
    fun hoursWhenLong() = assertEquals("1:00:00 / 2:05:09", PlayerFormats.position(3_600_000, 7_509_000))

    @Test
    fun unknownDurationShowsPositionOnly() {
        assertEquals("00:07", PlayerFormats.position(7_000, 0))
        assertEquals("00:07", PlayerFormats.position(7_000, -1))
    }

    @Test
    fun negativePositionClampsToZero() = assertEquals("00:00 / 00:10", PlayerFormats.position(-500, 10_000))
}
