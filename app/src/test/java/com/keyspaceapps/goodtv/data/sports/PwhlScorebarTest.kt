package com.keyspaceapps.goodtv.data.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PwhlScorebarTest {
    private val server = MockWebServer()
    private fun fixture() = javaClass.getResource("/sports/pwhl.json")!!.readText()
    private fun source() = PwhlScorebar(OkHttpClient(), baseUrl = server.url("/feed/"), zone = { ZoneId.of("America/Toronto") })
    private val nov22 = LocalDate.of(2026, 11, 22)

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    @Test fun sendsTheLeagueKeyAndFormat() = runTest {
        server.enqueue(MockResponse(code = 200, body = fixture()))
        source().games(League.PWHL, nov22)
        val url = server.takeRequest().url
        assertEquals("modulekit", url.queryParameter("feed"))
        assertEquals("scorebar", url.queryParameter("view"))
        assertEquals("pwhl", url.queryParameter("client_code"))
        assertEquals("446521baf8c38984", url.queryParameter("key"))
        assertEquals("json", url.queryParameter("fmt"))
    }

    @Test fun stripsJsonpParentheses() = runTest {
        server.enqueue(MockResponse(code = 200, body = "(" + fixture() + ")"))
        assertEquals(1, source().games(League.PWHL, nov22).size)
    }

    @Test fun mapsTeams() = runTest {
        server.enqueue(MockResponse(code = 200, body = fixture()))
        val g = source().games(League.PWHL, nov22).single()
        assertEquals(Team("PWHL Las Vegas", null, "Las Vegas", "VEG", "https://assets.leaguestat.com/pwhl/logos/50x50/12.png"), g.home)
        assertEquals("Minnesota Frost", g.away.displayName)
        assertEquals("Frost", g.away.nickname)
        assertEquals(Instant.parse("2026-11-23T02:50:00Z"), g.startsAt)
        assertEquals(emptyList<String>(), g.broadcasters)
    }

    @Test fun statusMapping() = runTest {
        val body = fixture()
            .replaceFirst("\"GameStatus\": \"1\"", "\"GameStatus\": \"2\"").replaceFirst("\"GameStatus\":\"1\"", "\"GameStatus\":\"2\"")
        server.enqueue(MockResponse(code = 200, body = body))
        val live = source().games(League.PWHL, nov22).single()
        assertEquals(GameState.LIVE, live.state)
        assertEquals("0", live.homeScore)
        server.enqueue(MockResponse(code = 200, body = fixture()))
        val pre = source().games(League.PWHL, nov22).single()
        assertEquals(GameState.SCHEDULED, pre.state)
        assertNull(pre.homeScore)
    }

    @Test fun onlyTheRequestedDay() = runTest {
        server.enqueue(MockResponse(code = 200, body = fixture()))
        assertEquals(listOf("PWHL Las Vegas"), source().games(League.PWHL, nov22).map { it.home.displayName })
        server.enqueue(MockResponse(code = 200, body = fixture()))
        assertEquals(4, source().games(League.PWHL, nov22.plusDays(1)).size)
    }
}
