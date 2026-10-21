package com.keyspaceapps.goodtv.data.sports

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class EspnScoreboardTest {
    private val server = MockWebServer()
    private fun fixture() = javaClass.getResource("/sports/espn_nhl.json")!!.readText()
    private fun source() = EspnScoreboard(OkHttpClient(), baseUrl = server.url("/apis/site/v2/sports/"))
    private val day = LocalDate.of(2026, 10, 20)

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    @Test fun requestsTheLeaguePathAndDate() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"events":[]}"""))
        source().games(League.NHL, day)
        val req = server.takeRequest()
        assertEquals("/apis/site/v2/sports/hockey/nhl/scoreboard", req.url.encodedPath)
        assertEquals("20261020", req.url.queryParameter("dates"))
        assertEquals("GoodTV/0.2", req.headers["User-Agent"])
    }

    @Test fun mapsTeamsScoresStateAndBroadcasters() = runTest {
        server.enqueue(MockResponse(code = 200, body = fixture()))
        val games = source().games(League.NHL, day)
        assertEquals(11, games.size)
        val g = games.first()
        assertEquals(League.NHL, g.league)
        assertEquals(Team("Montreal Canadiens", "Canadiens", "Montreal", "MTL", "https://a.espncdn.com/i/teamlogos/nhl/500/scoreboard/mtl.png"), g.home)
        assertEquals("San Jose Sharks", g.away.displayName)
        assertEquals(listOf("ESPN+", "NBC Sports BA"), g.broadcasters)
        assertEquals("NHL:${g.id}", g.key)
        assertEquals(java.time.Instant.parse("2026-10-20T23:00:00Z"), g.startsAt)
    }

    @Test fun statesMapFromEspn() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"events":[
          ${event("1", "pre", "7:00 PM")},${event("2", "in", "13:16 - 2nd")},${event("3", "post", "Final")}]}"""))
        val games = source().games(League.NBA, day)
        assertEquals(listOf(GameState.SCHEDULED, GameState.LIVE, GameState.FINAL), games.map { it.state })
        assertEquals("13:16 - 2nd", games[1].detail)
        assertEquals("2", games[1].homeScore)
    }

    @Test fun failuresAreSportsExceptions() = runTest {
        server.enqueue(MockResponse(code = 500, body = "oops"))
        try { source().games(League.NHL, day); fail() } catch (e: SportsException) { }
        server.enqueue(MockResponse(code = 200, body = "not json"))
        try { source().games(League.NHL, day); fail() } catch (e: SportsException) { }
    }

    private fun event(id: String, state: String, detail: String) = """{"id":"$id","date":"2026-10-20T23:00Z",
      "status":{"type":{"state":"$state","shortDetail":"$detail"}},
      "competitions":[{"competitors":[
        {"homeAway":"home","score":"2","team":{"displayName":"Boston Celtics","name":"Celtics","location":"Boston","abbreviation":"BOS","logo":"https://x/bos.png"}},
        {"homeAway":"away","score":"1","team":{"displayName":"Detroit Pistons","name":"Pistons","location":"Detroit","abbreviation":"DET","logo":"https://x/det.png"}}],
        "broadcasts":[]}]}"""
}
