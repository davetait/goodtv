package com.keyspaceapps.goodtv.data.sports

import com.keyspaceapps.goodtv.domain.LiveChannel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMatcherTest {
    private val now = Instant.parse("2026-10-20T20:00:00Z")

    private fun team(display: String, nick: String?, location: String?, abbr: String) = Team(display, nick, location, abbr, null)
    private fun game(league: League, id: String, away: Team, home: Team, at: String = "2026-10-20T23:00:00Z", state: GameState = GameState.SCHEDULED) =
        SportsGame(league, id, Instant.parse(at), state, away, home, null, null, null, emptyList())

    private val sharks = team("San Jose Sharks", "Sharks", "San Jose", "SJ")
    private val habs = team("Montreal Canadiens", "Canadiens", "Montreal", "MTL")
    private val knicks = team("New York Knicks", "Knicks", "New York", "NY")
    private val sixers = team("Philadelphia 76ers", "76ers", "Philadelphia", "PHI")
    private val devils = team("New Jersey Devils", "Devils", "New Jersey", "NJ")
    private val avs = team("Colorado Avalanche", "Avalanche", "Colorado", "COL")
    private val pistons = team("Detroit Pistons", "Pistons", "Detroit", "DET")
    private val celtics = team("Boston Celtics", "Celtics", "Boston", "BOS")
    private val winnipeg = team("Winnipeg Jets", "Jets", "Winnipeg", "WPG")
    private val blues = team("St. Louis Blues", "Blues", "St. Louis", "STL")
    private val nyJets = team("New York Jets", "Jets", "New York", "NYJ")
    private val pats = team("New England Patriots", "Patriots", "New England", "NE")
    private val nashville = team("Nashville Predators", "Predators", "Nashville", "NSH")

    private val sjMtl = game(League.NHL, "1", sharks, habs)
    private val nyPhi = game(League.NBA, "2", knicks, sixers)
    private val njCol = game(League.NHL, "3", devils, avs)
    private val detBos = game(League.NBA, "4", pistons, celtics, at = "2026-10-20T19:00:00Z", state = GameState.FINAL)
    private val wpgStl = game(League.NHL, "5", winnipeg, blues, at = "2026-10-21T01:00:00Z")
    private val nyjNe = game(League.NFL, "6", nyJets, pats, at = "2026-10-21T00:15:00Z")
    private val tonight = listOf(sjMtl, nyPhi, njCol, detBos, wpgStl, nyjNe)

    private var nextId = 0
    private fun ch(name: String, category: String = "US| SPORT PPV") =
        ChannelCandidate(LiveChannel(1, "c${nextId++}", "cat", name, null, null), category)

    private fun matchOf(name: String, games: List<SportsGame> = tonight, category: String = "US| SPORT PPV"): List<String> {
        val c = ch(name, category)
        return GameMatcher.match(games, listOf(c), now).filterValues { c in it }.keys.sorted()
    }

    @Test fun matchesTheProvidersNamingStyles() {
        assertEquals(listOf(sjMtl.key), matchOf("LIVE | NHL MONTREAL - SAN JOSE | Tue 20 Oct 19:05 EDT (US) | 8K EXCLUSIVE | US: VIAPLAY PPV 3"))
        assertEquals(listOf(sjMtl.key), matchOf("NHL: San Jose vs Montreal @ Oct 20 7:00 PM :Sportsnet+  15"))
        assertEquals(listOf(nyPhi.key), matchOf("US (Peacock 005) | 76ers vs. Knicks (2026-10-20 18:30:05)"))
        assertEquals(listOf(nyPhi.key), matchOf("LIVE | NYK - KNICKS VS PHI - 76ERS | Tue 20 Oct 18:00 EDT (US) | 8K EXCLUSIVE | US: NBA PASS PPV 2"))
        assertEquals(listOf(njCol.key), matchOf("LIVE | NHL: NEW JERSEY DEVILS - COLORADO AVALANCHE | Wed 21 Oct 00:55 EEST (FI)"))
        assertEquals("22 h off, inside the window", listOf(nyPhi.key), matchOf("Next | 76ers vs. Knicks | all | 20-10-2026 | 01:00 (GMT) | 8K EXCLUSIVE | CA: SOCCER PPV 30"))
        val all = GameMatcher.match(tonight, emptyList(), now)
        assertEquals("every game is present, empty when unmatched", tonight.map { it.key }.toSet(), all.keys)
        assertTrue(all.values.all { it.isEmpty() })
    }

    @Test fun endedAndEmptySlotsNeverMatch() {
        assertEquals(emptyList<String>(), matchOf("ENDED | DET - PISTONS VS BOS - CELTICS | Tue 20 Oct 14:00 EDT (US) | 8K EXCLUSIVE | US: NBA PASS PPV 1"))
        assertEquals(emptyList<String>(), matchOf("End | Celtics vs. Pistons | all | 20-10-2026 | 21:00 (GMT) | 8K EXCLUSIVE"))
        assertEquals(emptyList<String>(), matchOf("- NO EVENT STREAMING - | 8K EXCLUSIVE | US: MLS PPV 1"))
        assertEquals(emptyList<String>(), matchOf("NBA REPLAY: Celtics vs Pistons"))
    }

    @Test fun needsBothTeams() {
        assertEquals(emptyList<String>(), matchOf("US: NHL NETWORK"))
        assertEquals(emptyList<String>(), matchOf("MONTREAL CANADIENS PREGAME"))
    }

    @Test fun sharedNicknameNeedsALeagueWord() {
        assertEquals("Blues are NHL-only", listOf(wpgStl.key), matchOf("Next | Jets vs. Blues | all | 21-10-2026 | 01:00 (GMT)"))
        assertEquals(listOf(nyjNe.key), matchOf("Jets vs. Patriots"))
        val rangers = team("New York Rangers", "Rangers", "New York", "NYR")
        val giants = team("New York Giants", "Giants", "New York", "NYG")
        val nhl = game(League.NHL, "7", winnipeg, rangers)
        val nfl = game(League.NFL, "8", nyJets, giants)
        assertEquals("fits both, no league word: neither", emptyList<String>(), matchOf("Jets vs New York", listOf(nhl, nfl)))
        assertEquals(listOf(nfl.key), matchOf("NFL: Jets vs New York", listOf(nhl, nfl)))
    }

    @Test fun leagueWordInCategoryDecides() {
        val rangers = team("New York Rangers", "Rangers", "New York", "NYR")
        val bruins = team("Boston Bruins", "Bruins", "Boston", "BOS")
        val nhl = game(League.NHL, "9", rangers, bruins)
        val nba = game(League.NBA, "10", knicks, celtics)
        assertEquals(listOf(nba.key), matchOf("New York vs Boston @ Oct 20 7:00 PM", listOf(nhl, nba), category = "US| NBA PPV"))
        assertEquals(emptyList<String>(), matchOf("New York vs Boston @ Oct 20 7:00 PM", listOf(nhl, nba), category = "US| SPORT PPV"))
    }

    @Test fun staleDateOutsideWindowDoesNotMatch() {
        assertEquals(emptyList<String>(), matchOf("LIVE | NHL MONTREAL - SAN JOSE | Sun 18 Oct 19:05 EDT (US)"))
    }

    @Test fun yesterdaysSameMatchupGoesToYesterdaysGame() {
        // A series: the same teams yesterday (finished) and today. Yesterday's channel, 24 h before
        // today's start and inside the window, belongs to yesterday's game, not today's.
        val yesterday = game(League.NHL, "y", sharks, habs, at = "2026-10-19T23:05:00Z", state = GameState.FINAL)
        val today = game(League.NHL, "t", sharks, habs, at = "2026-10-20T23:05:00Z")
        val stale = ch("LIVE | NHL MONTREAL - SAN JOSE | Mon 19 Oct 19:05 EDT (US)")
        val fresh = ch("LIVE | NHL MONTREAL - SAN JOSE | Tue 20 Oct 19:05 EDT (US)")
        val m = GameMatcher.match(listOf(yesterday, today), listOf(stale, fresh), now)
        assertEquals(listOf(fresh), m.getValue(today.key))
        assertEquals(listOf(stale), m.getValue(yesterday.key))
    }

    @Test fun otherLeaguesSharingCitiesDoNotMatch() {
        val leafs = team("Toronto Maple Leafs", "Maple Leafs", "Toronto", "TOR")
        val g = game(League.NHL, "12", leafs, habs)
        assertEquals(emptyList<String>(), matchOf("CFL: Toronto Argonauts vs Montreal Alouettes | Tue 20 Oct 19:00 EDT", listOf(g)))
        assertEquals(emptyList<String>(), matchOf("Toronto FC vs CF Montréal", listOf(g)))
        assertEquals(emptyList<String>(), matchOf("Toronto vs Montreal", listOf(g), category = "CA| MLS PPV"))
        assertEquals("the NHL game itself still matches", listOf(g.key), matchOf("NHL: Toronto vs Montreal", listOf(g)))
    }

    @Test fun abbreviationsOnlyAsWholeWords() {
        assertEquals(emptyList<String>(), matchOf("SJÖBERG vs MONTREAL"))
        assertEquals(emptyList<String>(), matchOf("SJÄLLAND MTL"))
        assertEquals(listOf(sjMtl.key), matchOf("MTL - SJ"))
    }

    @Test fun leagueWordIsNeverATeamName() {
        val vegas = Team("PWHL Las Vegas", null, "Las Vegas", "VEG", null)
        val frost = Team("Minnesota Frost", "Frost", "Minnesota", "MIN", null)
        val hamilton = Team("PWHL Hamilton", null, "Hamilton", "HAM", null)
        val detroit = Team("PWHL Detroit", null, "Detroit", "DET", null)
        val games = listOf(game(League.PWHL, "p1", frost, vegas), game(League.PWHL, "p2", hamilton, detroit))
        assertEquals(emptyList<String>(), matchOf("PWHL PPV 1", games))
        assertEquals(emptyList<String>(), matchOf("PWHL: PWHL vs PWHL", games))
        assertEquals(listOf("PWHL:p1"), matchOf("PWHL: Minnesota Frost at Las Vegas", games))
    }

    @Test fun accentsAndCaseDoNotMatter() {
        val g = game(League.NHL, "11", habs, nashville)
        assertEquals(listOf(g.key), matchOf("(Apple) 003 |  Montréal vs. Nashville  (2026-10-20 23:00:00)", listOf(g)))
    }

    @Test fun channelTimesFormats() {
        fun t(s: String) = ChannelTimes.parse(s, now)
        assertEquals(Instant.parse("2026-10-20T23:05:00Z"), t("LIVE | X | Tue 20 Oct 19:05 EDT (US) |"))
        assertEquals("no zone → New York", Instant.parse("2026-10-20T23:00:00Z"), t("NHL: San Jose vs Montreal @ Oct 20 7:00 PM :Sportsnet+ 15"))
        assertEquals(Instant.parse("2026-10-20T22:30:05Z"), t("76ers vs. Knicks (2026-10-20 18:30:05)"))
        assertEquals("an ISO time reads a zone after it", Instant.parse("2026-10-20T18:30:00Z"), t("76ers vs. Knicks 2026-10-20 18:30 UTC"))
        assertEquals(Instant.parse("2026-10-21T01:30:00Z"), t("Lakers vs. Suns 2026-10-20 18:30 PT"))
        assertEquals(Instant.parse("2026-10-20T01:00:00Z"), t("Next | 76ers vs. Knicks | all | 20-10-2026 | 01:00 (GMT) | 8K"))
        assertEquals(Instant.parse("2026-10-19T02:08:00Z"), t("MLB 01 : Rays x Guardians start:2026-10-18 22:08:00 stop:2026-10-19 05:21:20"))
        assertEquals("ET in November is EST", Instant.parse("2026-11-08T20:00:00Z"), t("CFL 00: Montreal Alouettes vs Hamilton Tiger-Cats | Sat 8th Nov 3:00 PM ET"))
        assertEquals(Instant.parse("2026-10-20T21:55:00Z"), t("NEW JERSEY DEVILS - COLORADO AVALANCHE | Wed 21 Oct 00:55 EEST (FI)"))
        assertNull(t("US: NHL NETWORK"))
        assertEquals("a year-less date takes the nearest year", Instant.parse("2027-01-01T01:00:00Z"),
            ChannelTimes.parse("X @ Dec 31 8:00 PM", Instant.parse("2027-01-02T00:00:00Z")))
    }
}
