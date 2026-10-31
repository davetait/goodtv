package com.keyspaceapps.goodtv.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeagueTest {
    @Test fun defaultsAreTheOriginalSix() {
        assertEquals(setOf("MLB", "NBA", "NFL", "NHL", "PWHL", "WNBA"), League.entries.filter { it.onByDefault }.map { it.name }.toSet())
    }

    @Test fun twentySevenLeagues() = assertEquals(27, League.entries.size)

    @Test fun soccerAndCricketPutHomeFirst() {
        assertTrue(League.PREMIER_LEAGUE.homeFirst)
        assertTrue(League.IPL.homeFirst)
        assertFalse(League.NHL.homeFirst)
    }

    @Test fun wordsAreNormalised() {
        for (league in League.entries) {
            assertTrue("${league.name} has no words", league.words.isNotEmpty())
            for (w in league.words) {
                assertTrue("${league.name}: empty word", w.isNotEmpty())
                assertEquals("${league.name}: '$w'", GameMatcher.normalize(w), w)
            }
        }
    }
}
