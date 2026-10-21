package com.keyspaceapps.goodtv.data.sports

import java.time.Instant
import java.time.LocalDate

/** The leagues on the Sports screen, in display order. [espnPath] is null for leagues ESPN doesn't cover. */
enum class League(val label: String, val espnPath: String?) {
    NHL("NHL", "hockey/nhl"),
    NBA("NBA", "basketball/nba"),
    MLB("MLB", "baseball/mlb"),
    NFL("NFL", "football/nfl"),
    WNBA("WNBA", "basketball/wnba"),
    PWHL("PWHL", null),
}

/** A team as the schedule names it; every non-null form can identify it in a channel name. */
data class Team(val displayName: String, val nickname: String?, val location: String?, val abbreviation: String, val logoUrl: String?)

enum class GameState { SCHEDULED, LIVE, FINAL }

/** One game. [detail] is the clock or period while live ("13:16 - 2nd"); [broadcasters] as the schedule lists them. */
data class SportsGame(
    val league: League,
    val id: String,
    val startsAt: Instant,
    val state: GameState,
    val away: Team,
    val home: Team,
    val awayScore: String?,
    val homeScore: String?,
    val detail: String?,
    val broadcasters: List<String>,
) {
    val key: String get() = "${league.name}:$id"
}

class SportsException(message: String) : Exception(message)

/** Where a league's day of games comes from. Throws [SportsException] when it can't be read. */
interface ScheduleSource {
    suspend fun games(league: League, day: LocalDate): List<SportsGame>
}

internal const val SPORTS_USER_AGENT = "GoodTV/0.2"
