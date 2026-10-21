package com.keyspaceapps.goodtv.data.sports

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsRepositoryTest {
    private val store = FakeLibraryStore()
    private var now = Instant.parse("2026-10-20T23:30:00Z")
    private val zone = ZoneId.of("America/Toronto")

    private fun team(n: String, a: String) = Team(n, n.substringAfterLast(' '), n.substringBeforeLast(' '), a, null)
    private fun game(league: League, id: String, at: String, state: GameState, away: Team = team("Away $id", "A$id"), home: Team = team("Home $id", "H$id"), homeScore: String? = null) =
        SportsGame(league, id, Instant.parse(at), state, away, home, null, homeScore, null, emptyList())

    /** Per league: what each day returns, or throws. */
    private val schedule = mutableMapOf<League, (LocalDate) -> List<SportsGame>>()
    private val reads = mutableListOf<League>()
    private val fake = object : ScheduleSource {
        override suspend fun games(league: League, day: LocalDate): List<SportsGame> { reads += league; return schedule.getValue(league)(day) }
    }

    private fun TestScope.repo() = SportsRepository(
        sources = League.entries.associateWith { fake },
        store = store,
        refresher = LiveRefresher(store, SourceRegistry(listOf(FakeChannelSource())), { false }),
        clock = { now }, zone = { zone }, compute = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.collect(repo: SportsRepository): MutableList<SportsBoard> {
        val boards = mutableListOf<SportsBoard>()
        backgroundScope.launch { repo.observe().collect { boards += it } }
        runCurrent()
        return boards
    }

    private fun none(): (LocalDate) -> List<SportsGame> = { emptyList() }
    private fun init() = League.entries.forEach { schedule[it] = none() }

    @Test fun leaguesInOrderLiveFirstNoFinals() = runTest {
        init()
        val today = LocalDate.of(2026, 10, 20)
        schedule[League.NBA] = { d -> if (d == today) listOf(game(League.NBA, "b1", "2026-10-21T00:30:00Z", GameState.SCHEDULED)) else emptyList() }
        schedule[League.NHL] = { d ->
            if (d == today) listOf(
                game(League.NHL, "h3", "2026-10-21T01:00:00Z", GameState.SCHEDULED),
                game(League.NHL, "h1", "2026-10-20T19:00:00Z", GameState.FINAL),
                game(League.NHL, "h2", "2026-10-20T23:00:00Z", GameState.LIVE),
            ) else emptyList()
        }
        val board = collect(repo()).last()
        assertEquals(listOf(League.NHL, League.NBA), board.leagues.map { it.league })
        assertEquals(listOf("h2", "h3"), board.leagues[0].games.map { it.game.id })
    }

    @Test fun liveGameFromYesterdayStillShows() = runTest {
        init()
        now = Instant.parse("2026-10-21T07:30:00Z") // 00:30 in Los Angeles
        val la = ZoneId.of("America/Los_Angeles")
        schedule[League.NHL] = { d ->
            if (d == LocalDate.of(2026, 10, 20)) listOf(
                game(League.NHL, "late", "2026-10-21T05:00:00Z", GameState.LIVE),
                game(League.NHL, "early", "2026-10-20T20:00:00Z", GameState.SCHEDULED),
            ) else emptyList()
        }
        val repo = SportsRepository(League.entries.associateWith { fake }, store, LiveRefresher(store, SourceRegistry(listOf(FakeChannelSource())), { false }),
            clock = { now }, zone = { la }, compute = StandardTestDispatcher(testScheduler))
        val board = collect(repo).last()
        assertEquals(listOf("late"), board.leagues.single().games.map { it.game.id })
    }

    @Test fun oneFailingSourceIsFlaggedOthersStillShow() = runTest {
        init()
        schedule[League.NHL] = { throw SportsException("down") }
        schedule[League.NBA] = { listOf(game(League.NBA, "b1", "2026-10-21T00:30:00Z", GameState.SCHEDULED)) }
        val board = collect(repo()).last()
        assertEquals(LeagueGames(League.NHL, emptyList(), failed = true), board.leagues[0])
        assertEquals(League.NBA, board.leagues[1].league)
    }

    @Test fun lastGoodScheduleSurvivesAnOutage() = runTest {
        init()
        var up = true
        schedule[League.NHL] = { if (up) listOf(game(League.NHL, "h1", "2026-10-21T00:00:00Z", GameState.SCHEDULED)) else throw SportsException("down") }
        val boards = collect(repo())
        up = false
        advanceTimeBy(60_001); runCurrent()
        val last = boards.last().leagues.single()
        assertEquals(listOf("h1"), last.games.map { it.game.id })
        assertEquals(false, last.failed)
    }

    @Test fun pollsEveryMinuteWhileCollected() = runTest {
        init()
        var score = "1"
        schedule[League.NHL] = { listOf(game(League.NHL, "h1", "2026-10-20T23:00:00Z", GameState.LIVE, homeScore = score)) }
        val boards = collect(repo())
        assertEquals("1", boards.last().leagues.single().games.single().game.homeScore)
        score = "2"
        advanceTimeBy(60_001); runCurrent()
        assertEquals("2", boards.last().leagues.single().games.single().game.homeScore)
    }

    @Test fun channelsAttach() = runTest {
        init()
        val sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, now.toEpochMilli(), null))
        store.replaceCatalog(sid, Catalog(listOf(Category(sid, ContentKind.LIVE, "p", "US| PEACOCK PPV")), listOf(
            LiveChannel(sid, "5", "p", "US (Peacock 005) | 76ers vs. Knicks (2026-10-20 18:30:05)", null, 5),
        ), emptyList(), emptyList()))
        schedule[League.NBA] = { listOf(game(League.NBA, "b1", "2026-10-20T23:00:00Z", GameState.LIVE,
            away = Team("New York Knicks", "Knicks", "New York", "NY", null), home = Team("Philadelphia 76ers", "76ers", "Philadelphia", "PHI", null))) }
        val g = collect(repo()).last().leagues.single().games.single()
        assertEquals(listOf("5"), g.channels.map { it.channel.id })
        assertEquals("US| PEACOCK PPV", g.channels.single().categoryName)
        assertTrue(reads.isNotEmpty())
    }
}
