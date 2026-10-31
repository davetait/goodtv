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

class EspnCricketScorepanelTest {
    private val server = MockWebServer()
    private fun source() = EspnCricketScorepanel(OkHttpClient(), baseUrl = server.url("/apis/site/v2/sports/cricket/"))
    private val day = LocalDate.of(2026, 11, 1)

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    @Test fun onlyMensInternationalsFromTheScorepanel() = runTest {
        server.enqueue(MockResponse(code = 200, body = javaClass.getResource("/sports/cricket_scorepanel.json")!!.readText()))
        val games = source().games(League.INTERNATIONAL_CRICKET, day)
        val req = server.takeRequest()
        assertEquals("/apis/site/v2/sports/cricket/scorepanel", req.url.encodedPath)
        assertEquals("20261101", req.url.queryParameter("dates"))
        assertEquals("GoodTV/0.2", req.headers["User-Agent"])
        assertEquals(setOf("New Zealand v India", "Namibia v Ireland", "Afghanistan v Zimbabwe"), games.map { "${it.home.displayName} v ${it.away.displayName}" }.toSet())
        assertEquals(3, games.size)
        val nz = games.single { it.home.displayName == "New Zealand" }
        assertEquals("India", nz.away.displayName)
        assertEquals(League.INTERNATIONAL_CRICKET, nz.league)
        assertEquals("INTERNATIONAL_CRICKET:${nz.id}", nz.key)
    }

    @Test fun httpErrorIsASportsException() = runTest {
        server.enqueue(MockResponse(code = 502, body = "bad gateway"))
        try { source().games(League.INTERNATIONAL_CRICKET, day); fail() } catch (e: SportsException) { }
    }
}
