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
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.keyspaceapps.goodtv.data.source.LiveCatalog
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
    /** Leagues whose source never answers. */
    private val stalled = mutableSetOf<League>()
    private val fake = object : ScheduleSource {
        override suspend fun games(league: League, day: LocalDate): List<SportsGame> {
            reads += league
            if (league in stalled) awaitCancellation()
            return schedule.getValue(league)(day)
        }
    }

    private val defaults = League.entries.filter { it.onByDefault }.toSet()

    private fun TestScope.repo(enabled: Flow<Set<League>> = MutableStateFlow(defaults)) = SportsRepository(
        sources = League.entries.associateWith { fake },
        enabled = enabled,
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
        assertEquals(listOf(League.NBA, League.NHL), board.leagues.map { it.league })
        assertEquals(listOf("h2", "h3"), board.leagues[1].games.map { it.game.id })
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
        val repo = SportsRepository(League.entries.associateWith { fake }, MutableStateFlow(defaults), store, LiveRefresher(store, SourceRegistry(listOf(FakeChannelSource())), { false }),
            clock = { now }, zone = { la }, compute = StandardTestDispatcher(testScheduler))
        val board = collect(repo).last()
        assertEquals(listOf("late"), board.leagues.single().games.map { it.game.id })
    }

    @Test fun oneFailingSourceIsFlaggedOthersStillShow() = runTest {
        init()
        schedule[League.NHL] = { throw SportsException("down") }
        schedule[League.NBA] = { listOf(game(League.NBA, "b1", "2026-10-21T00:30:00Z", GameState.SCHEDULED)) }
        val board = collect(repo()).last()
        assertEquals(League.NBA, board.leagues[0].league)
        assertEquals(LeagueGames(League.NHL, emptyList(), failed = true), board.leagues[1])
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

    @Test fun yesterdaysChannelForTheSameMatchupStaysOffTodaysGame() = runTest {
        init()
        val sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, now.toEpochMilli(), null))
        store.replaceCatalog(sid, Catalog(listOf(Category(sid, ContentKind.LIVE, "n", "US| NBA PASS PPV")), listOf(
            LiveChannel(sid, "old", "n", "LIVE | NYK - KNICKS VS PHI - 76ERS | Mon 19 Oct 19:00 EDT (US)", null, 1),
        ), emptyList(), emptyList()))
        val knicks = Team("New York Knicks", "Knicks", "New York", "NY", null)
        val sixers = Team("Philadelphia 76ers", "76ers", "Philadelphia", "PHI", null)
        schedule[League.NBA] = { d ->
            if (d == LocalDate.of(2026, 10, 19)) listOf(game(League.NBA, "y", "2026-10-19T23:00:00Z", GameState.FINAL, knicks, sixers))
            else listOf(game(League.NBA, "t", "2026-10-20T23:00:00Z", GameState.LIVE, knicks, sixers))
        }
        val g = collect(repo()).last().leagues.single().games.single()
        assertEquals("t", g.game.id)
        assertEquals(emptyList<String>(), g.channels.map { it.channel.id })
    }

    @Test fun aStalledLeagueTimesOutAndTheOthersShow() = runTest {
        init()
        stalled += League.NHL
        schedule[League.NBA] = { listOf(game(League.NBA, "b1", "2026-10-21T00:30:00Z", GameState.SCHEDULED)) }
        val boards = collect(repo())
        advanceTimeBy(SportsRepository.LEAGUE_TIMEOUT_MS + 1); runCurrent()
        val board = boards.first()
        assertEquals(League.NBA, board.leagues[0].league)
        assertEquals(LeagueGames(League.NHL, emptyList(), failed = true), board.leagues[1])
    }

    @Test fun aLongOutageIsFlaggedNotHidden() = runTest {
        init()
        var up = true
        schedule[League.NHL] = { if (up) listOf(game(League.NHL, "h1", "2026-10-21T00:00:00Z", GameState.SCHEDULED)) else throw SportsException("down") }
        val boards = collect(repo())
        up = false
        now = now.plusMillis(SportsRepository.KEEP_LAST_GOOD_MS + 60_000)
        advanceTimeBy(60_001); runCurrent()
        assertEquals(LeagueGames(League.NHL, emptyList(), failed = true), boards.last().leagues.single())
    }

    @Test fun todayStillShowsWhenOnlyYesterdayFails() = runTest {
        init()
        schedule[League.NHL] = { d -> if (d == LocalDate.of(2026, 10, 19)) throw SportsException("down") else listOf(game(League.NHL, "h1", "2026-10-21T00:00:00Z", GameState.SCHEDULED)) }
        val row = collect(repo()).last().leagues.single()
        assertEquals(false, row.failed)
        assertEquals(listOf("h1"), row.games.map { it.game.id })
    }

    private fun today(vararg leagues: League) {
        val today = LocalDate.of(2026, 10, 20)
        leagues.forEach { l ->
            schedule[l] = { d -> if (d == today) listOf(game(l, "${l.name}1", "2026-10-21T00:30:00Z", GameState.SCHEDULED)) else emptyList() }
        }
    }

    @Test fun onlyEnabledLeaguesAreRead() = runTest {
        init()
        today(League.NHL, League.MLS)
        collect(repo(MutableStateFlow(setOf(League.NHL, League.MLS))))
        assertEquals(setOf(League.NHL, League.MLS), reads.toSet())
    }

    @Test fun rowsAreAlphabetical() = runTest {
        init()
        val on = setOf(League.NHL, League.BUNDESLIGA, League.MLS, League.AHL)
        today(*on.toTypedArray())
        val board = collect(repo(MutableStateFlow(on))).last()
        assertEquals(listOf(League.AHL, League.BUNDESLIGA, League.MLS, League.NHL), board.leagues.map { it.league })
    }

    @Test fun switchingALeagueOnFetchesItAtOnce() = runTest {
        init()
        today(League.NHL, League.MLS)
        val enabled = MutableStateFlow(setOf(League.NHL))
        val boards = collect(repo(enabled))
        assertTrue(League.MLS !in reads)
        enabled.value = setOf(League.NHL, League.MLS)
        runCurrent()
        assertTrue(League.MLS in reads)
        assertEquals(listOf(League.MLS, League.NHL), boards.last().leagues.map { it.league })
    }

    @Test fun switchingALeagueOffDropsItsRow() = runTest {
        init()
        today(League.NHL, League.MLS)
        val enabled = MutableStateFlow(setOf(League.NHL, League.MLS))
        val boards = collect(repo(enabled))
        assertEquals(listOf(League.MLS, League.NHL), boards.last().leagues.map { it.league })
        enabled.value = setOf(League.MLS)
        runCurrent()
        assertEquals(listOf(League.MLS), boards.last().leagues.map { it.league })
    }

    /** The live-list refresh maps ~57,000 channels; it must not run on the collector's (main) thread. */
    @Test fun refreshRunsOnTheComputeDispatcher() = runBlocking {
        init()
        val compute = Executors.newSingleThreadExecutor { Thread(it, "sports-compute") }.asCoroutineDispatcher()
        val channels = FakeChannelSource()
        val sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, 0L, null))
        var thread: String? = null
        channels.liveResults[sid] = { thread = Thread.currentThread().name.substringBefore(" @"); Result.success(LiveCatalog(emptyList(), emptyList())) }
        val repo = SportsRepository(League.entries.associateWith { fake }, MutableStateFlow(defaults), store, LiveRefresher(store, SourceRegistry(listOf(channels)), { false }),
            clock = { now }, zone = { zone }, compute = compute)
        val job = launch { repo.observe().collect { } }
        withTimeout(5_000) { while (thread == null) delay(10) }
        job.cancel(); compute.close()
        assertEquals("sports-compute", thread)
    }
}
