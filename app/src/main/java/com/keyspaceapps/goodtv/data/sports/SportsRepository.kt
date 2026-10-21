package com.keyspaceapps.goodtv.data.sports

import com.keyspaceapps.goodtv.data.repo.LibraryStore
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameWithChannels(val game: SportsGame, val channels: List<ChannelCandidate>)

/** One league's row. [failed]: its schedule couldn't be read and there is no earlier copy to show. */
data class LeagueGames(val league: League, val games: List<GameWithChannels>, val failed: Boolean)

data class SportsBoard(val leagues: List<LeagueGames>)

/**
 * The Sports screen's data: for each league in [League] order, today's games that are live or still
 * to come (finished ones dropped; a game from yesterday that is still live counts), live first,
 * then by start, each with the channels [GameMatcher] finds for it.
 *
 * Schedules are read every [pollMs] while collected; a league whose read fails keeps its last good
 * games, or is flagged [LeagueGames.failed] when it has none. Collecting also starts one
 * [LiveRefresher.refreshIfStale], concurrently: the first board comes from the channels the store
 * already has, and refreshed names arrive through [LibraryStore.observeSportsCandidates].
 */
class SportsRepository(
    private val sources: Map<League, ScheduleSource>,
    private val store: LibraryStore,
    private val refresher: LiveRefresher,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val pollMs: Long = 60_000,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lastGood = HashMap<League, List<SportsGame>>()

    fun observe(): Flow<SportsBoard> = channelFlow {
        launch { refresher.refreshIfStale() }
        val schedules = flow {
            while (true) {
                emit(readAll())
                delay(pollMs)
            }
        }
        combine(schedules, store.observeSportsCandidates()) { s, c -> s to c }.collect { (s, c) ->
            send(withContext(compute) { build(s, c) })
        }
    }

    /** Per league, today's and yesterday's games, or null when they couldn't be read. */
    private suspend fun readAll(): Map<League, List<SportsGame>?> = coroutineScope {
        val today = clock().atZone(zone()).toLocalDate()
        League.entries.filter { it in sources }.map { league ->
            async {
                val source = sources.getValue(league)
                val games = try {
                    (source.games(league, today.minusDays(1)) + source.games(league, today)).distinctBy { it.key }
                        .also { synchronized(lastGood) { lastGood[league] = it } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    synchronized(lastGood) { lastGood[league] }
                }
                league to games
            }
        }.awaitAll().toMap()
    }

    private fun build(schedules: Map<League, List<SportsGame>?>, channels: List<ChannelCandidate>): SportsBoard {
        val now = clock()
        val today = now.atZone(zone()).toLocalDate()
        val shown = schedules.mapValues { (_, games) ->
            games?.filter { it.state == GameState.LIVE || (it.state == GameState.SCHEDULED && it.startsAt.atZone(zone()).toLocalDate() == today) }
                ?.sortedWith(compareBy<SportsGame> { if (it.state == GameState.LIVE) 0 else 1 }.thenBy { it.startsAt })
        }
        val matched = GameMatcher.match(shown.values.filterNotNull().flatten(), channels, now)
        return SportsBoard(
            League.entries.mapNotNull { league ->
                if (league !in schedules) return@mapNotNull null
                val games = shown[league] ?: return@mapNotNull LeagueGames(league, emptyList(), failed = true)
                if (games.isEmpty()) null else LeagueGames(league, games.map { GameWithChannels(it, matched[it.key].orEmpty()) }, failed = false)
            },
        )
    }
}
