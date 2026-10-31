package com.keyspaceapps.goodtv.data.sports

import com.keyspaceapps.goodtv.data.repo.LibraryStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class GameWithChannels(val game: SportsGame, val channels: List<ChannelCandidate>)

/** One league's row. [failed]: its schedule couldn't be read and there is no earlier copy to show. */
data class LeagueGames(val league: League, val games: List<GameWithChannels>, val failed: Boolean)

data class SportsBoard(val leagues: List<LeagueGames>)

/**
 * The Sports screen's data: for each switched-on league in alphabetical order of label, today's games that are live or still
 * to come (finished ones dropped; a game from yesterday that is still live counts), live first,
 * then by start, each with the channels [GameMatcher] finds for it.
 *
 * Schedules are read every [pollMs] while collected, each league and day at once. A read that fails
 * or takes over [LEAGUE_TIMEOUT_MS] keeps the last good games for up to [KEEP_LAST_GOOD_MS]; after
 * that, or with none, a league whose today can't be read is flagged [LeagueGames.failed]. Collecting also starts one
 * [LiveRefresher.refreshIfStale], concurrently: the first board comes from the channels the store
 * already has, and refreshed names arrive through [LibraryStore.observeSportsCandidates].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsRepository(
    private val sources: Map<League, ScheduleSource>,
    private val enabled: Flow<Set<League>>,
    private val store: LibraryStore,
    private val refresher: LiveRefresher,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val pollMs: Long = 60_000,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class Read(val games: List<SportsGame>, val at: Instant)
    private val lastGood = HashMap<Pair<League, LocalDate>, Read>()

    fun observe(): Flow<SportsBoard> = channelFlow {
        // Mapping ~57,000 channels: off the collector's (main) thread.
        launch(compute) { refresher.refreshIfStale() }
        // A change to the switched-on leagues restarts the loop, so a league switched on is read at once.
        val schedules = enabled.distinctUntilChanged().flatMapLatest { leagues ->
            flow {
                while (true) {
                    emit(readAll(leagues))
                    delay(pollMs)
                }
            }
        }
        combine(schedules, store.observeSportsCandidates().flowOn(compute)) { s, c -> s to c }.collect { (s, c) ->
            send(withContext(compute) { build(s, c) })
        }
    }

    /** Per league, yesterday's and today's games, or null when today's couldn't be read. */
    private suspend fun readAll(leagues: Set<League>): Map<League, List<SportsGame>?> = coroutineScope {
        val now = clock()
        val today = now.atZone(zone()).toLocalDate()
        League.entries.filter { it in leagues && it in sources }.map { league ->
            async {
                val (yesterday, current) = listOf(today.minusDays(1), today).map { async { readDay(league, it, now) } }.awaitAll()
                league to current?.let { (yesterday.orEmpty() + it).distinctBy { g -> g.key } }
            }
        }.awaitAll().toMap()
    }

    /** One day of one league; on failure or [LEAGUE_TIMEOUT_MS], the last good read if under [KEEP_LAST_GOOD_MS] old. */
    private suspend fun readDay(league: League, day: LocalDate, now: Instant): List<SportsGame>? = try {
        withTimeout(LEAGUE_TIMEOUT_MS) { sources.getValue(league).games(league, day) }
            .also { synchronized(lastGood) { lastGood[league to day] = Read(it, now) } }
    } catch (e: TimeoutCancellationException) {
        kept(league, day, now)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        kept(league, day, now)
    }

    private fun kept(league: League, day: LocalDate, now: Instant): List<SportsGame>? = synchronized(lastGood) {
        lastGood[league to day]?.takeIf { now.toEpochMilli() - it.at.toEpochMilli() <= KEEP_LAST_GOOD_MS }?.games
    }

    private fun build(schedules: Map<League, List<SportsGame>?>, channels: List<ChannelCandidate>): SportsBoard {
        val now = clock()
        val today = now.atZone(zone()).toLocalDate()
        val shown = schedules.mapValues { (_, games) ->
            games?.filter { it.state == GameState.LIVE || (it.state == GameState.SCHEDULED && it.startsAt.atZone(zone()).toLocalDate() == today) }
                ?.sortedWith(compareBy<SportsGame> { if (it.state == GameState.LIVE) 0 else 1 }.thenBy { it.startsAt })
        }
        // Finished games are matched too, so yesterday's channel for the same matchup goes to
        // yesterday's game instead of attaching to today's.
        val matched = GameMatcher.match(schedules.values.filterNotNull().flatten(), channels, now)
        return SportsBoard(
            schedules.keys.sortedBy { it.label.lowercase() }.mapNotNull { league ->
                val games = shown[league] ?: return@mapNotNull LeagueGames(league, emptyList(), failed = true)
                if (games.isEmpty()) null else LeagueGames(league, games.map { GameWithChannels(it, matched[it.key].orEmpty()) }, failed = false)
            },
        )
    }

    companion object {
        /** A league that hasn't answered in this long counts as failed for this read. */
        const val LEAGUE_TIMEOUT_MS = 10_000L
        /** A failed read shows the last good games for this long, then the league says it couldn't load. */
        const val KEEP_LAST_GOOD_MS = 10 * 60_000L
    }
}
