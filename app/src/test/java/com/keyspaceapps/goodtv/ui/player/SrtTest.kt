package com.keyspaceapps.goodtv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtTest {
    @Test fun parsesCuesAndMultiLineText() {
        val cues = Srt.parse("1\n00:00:01,000 --> 00:00:02,500\nHello\nthere\n\n2\n00:01:00,250 --> 00:01:03,000\nAgain\n")
        assertEquals(listOf(TimedCue(1000, 2500, "Hello\nthere"), TimedCue(60_250, 63_000, "Again")), cues)
    }

    @Test fun parsesMessyRealWorldSrt() {
        val text = "﻿1\r\n00:00:01,000 --> 00:00:02,000\r\n<i>Hi</i>\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\n{\\an8}Top\r\n\r\n3\r\n00:00:05.000 --> 00:00:06.000\r\n<font color=\"#ffffff\">Dot</font>"
        assertEquals(listOf("Hi", "Top", "Dot"), Srt.parse(text).map { it.text })
        assertEquals(5_000L, Srt.parse(text).last().startMs)
    }

    @Test fun skipsBrokenBlocks() {
        val cues = Srt.parse("1\n00:00:01,000 --> nonsense\nBad\n\n2\n00:00:02,000 --> 00:00:03,000\nGood\n\n3\n00:00:04,000 --> 00:00:05,000\n\n")
        assertEquals(listOf("Good"), cues.map { it.text })
    }

    @Test fun cuesAtShiftsByOffsetAndClampsAtZero() {
        val cues = listOf(TimedCue(1000, 2000, "A"))
        assertEquals(listOf("A"), cues.at(1500, 0).map { it.text })
        assertEquals("later by 1 s", listOf("A"), cues.at(2500, 1000).map { it.text })
        assertTrue("not before it starts", cues.at(1500, 1000).isEmpty())
        assertEquals("earlier by 0.5 s", listOf("A"), cues.at(600, -500).map { it.text })
        assertTrue(cues.at(0, -5000).isEmpty())
        assertTrue("ended", cues.at(2000, 0).isEmpty())
    }
}
