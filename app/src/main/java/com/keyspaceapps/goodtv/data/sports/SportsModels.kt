package com.keyspaceapps.goodtv.data.sports

import java.time.Instant
import java.time.LocalDate

enum class Sport { BASEBALL, BASKETBALL, CRICKET, FOOTBALL, HOCKEY, LACROSSE, SOCCER }

/** The headings leagues sit under in Settings. */
enum class LeagueGroup { NORTH_AMERICA, WORLD_SOCCER, CRICKET }

/** Where a league's games are read from. */
sealed interface LeagueSource {
    /** ESPN's scoreboard at `sports/<path>/scoreboard`. */
    data class Espn(val path: String) : LeagueSource
    /** A HockeyTech scorebar, with the public key the league's site embeds. */
    data class HockeyTech(val clientCode: String, val key: String) : LeagueSource
    /** Men's Test, ODI and T20I matches from ESPN's cricket scorepanel. */
    data object EspnInternationalCricket : LeagueSource
}

/**
 * The leagues the Sports screen can show. Constant names are stored in settings and in game keys:
 * never rename them. The constant order doesn't decide display order. [words] are normalised
 * phrases that name the league in a channel's name or category.
 */
enum class League(
    val label: String,
    val sport: Sport,
    val group: LeagueGroup,
    val source: LeagueSource,
    val onByDefault: Boolean,
    val words: List<String>,
) {
    AHL("AHL", Sport.HOCKEY, LeagueGroup.NORTH_AMERICA, LeagueSource.HockeyTech("ahl", "50c2cd9b5e18e390"), false, listOf("ahl")),
    BIG_BASH("Big Bash", Sport.CRICKET, LeagueGroup.CRICKET, LeagueSource.Espn("cricket/8044"), false, listOf("big bash", "bbl")),
    BUNDESLIGA("Bundesliga", Sport.SOCCER, LeagueGroup.WORLD_SOCCER, LeagueSource.Espn("soccer/ger.1"), false, listOf("bundesliga")),
    CHAMPIONS_LEAGUE("Champions League", Sport.SOCCER, LeagueGroup.WORLD_SOCCER, LeagueSource.Espn("soccer/uefa.champions"), false, listOf("champions league", "ucl")),
    COLLEGE_BASKETBALL_MEN("College Basketball (Men's)", Sport.BASKETBALL, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("basketball/mens-college-basketball"), false, listOf("ncaa", "ncaab", "ncaam", "college basketball")),
    COLLEGE_BASKETBALL_WOMEN("College Basketball (Women's)", Sport.BASKETBALL, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("basketball/womens-college-basketball"), false, listOf("ncaa", "ncaab", "ncaaw", "college basketball")),
    COLLEGE_FOOTBALL("College Football", Sport.FOOTBALL, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("football/college-football"), false, listOf("ncaa", "ncaaf", "cfb", "college football")),
    EUROPA_LEAGUE("Europa League", Sport.SOCCER, LeagueGroup.WORLD_SOCCER, LeagueSource.Espn("soccer/uefa.europa"), false, listOf("europa league", "uel")),
    INTERNATIONAL_CRICKET("International Cricket", Sport.CRICKET, LeagueGroup.CRICKET, LeagueSource.EspnInternationalCricket, false, listOf("odi", "t20i", "icc")),
    IPL("IPL", Sport.CRICKET, LeagueGroup.CRICKET, LeagueSource.Espn("cricket/8048"), false, listOf("ipl", "indian premier league")),
    LA_LIGA("La Liga", Sport.SOCCER, LeagueGroup.WORLD_SOCCER, LeagueSource.Espn("soccer/esp.1"), false, listOf("la liga", "laliga")),
    LIGA_MX("Liga MX", Sport.SOCCER, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("soccer/mex.1"), false, listOf("liga mx", "ligamx")),
    LIGUE_1("Ligue 1", Sport.SOCCER, LeagueGroup.WORLD_SOCCER, LeagueSource.Espn("soccer/fra.1"), false, listOf("ligue 1")),
    MLB("MLB", Sport.BASEBALL, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("baseball/mlb"), true, listOf("mlb")),
    MLS("MLS", Sport.SOCCER, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("soccer/usa.1"), false, listOf("mls")),
    NBA("NBA", Sport.BASKETBALL, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("basketball/nba"), true, listOf("nba")),
    NFL("NFL", Sport.FOOTBALL, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("football/nfl"), true, listOf("nfl")),
    NHL("NHL", Sport.HOCKEY, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("hockey/nhl"), true, listOf("nhl")),
    NLL("NLL", Sport.LACROSSE, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("lacrosse/nll"), false, listOf("nll")),
    NWSL("NWSL", Sport.SOCCER, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("soccer/usa.nwsl"), false, listOf("nwsl")),
    PLL("PLL", Sport.LACROSSE, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("lacrosse/pll"), false, listOf("pll")),
    PREMIER_LEAGUE("Premier League", Sport.SOCCER, LeagueGroup.WORLD_SOCCER, LeagueSource.Espn("soccer/eng.1"), false, listOf("premier league", "epl")),
    PWHL("PWHL", Sport.HOCKEY, LeagueGroup.NORTH_AMERICA, LeagueSource.HockeyTech("pwhl", "446521baf8c38984"), true, listOf("pwhl")),
    SERIE_A("Serie A", Sport.SOCCER, LeagueGroup.WORLD_SOCCER, LeagueSource.Espn("soccer/ita.1"), false, listOf("serie a")),
    UFL("UFL", Sport.FOOTBALL, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("football/ufl"), false, listOf("ufl")),
    WNBA("WNBA", Sport.BASKETBALL, LeagueGroup.NORTH_AMERICA, LeagueSource.Espn("basketball/wnba"), true, listOf("wnba")),
    WORLD_CUP("World Cup", Sport.SOCCER, LeagueGroup.WORLD_SOCCER, LeagueSource.Espn("soccer/fifa.world"), false, listOf("world cup")),
}

/** Leagues whose games are women's; a channel naming women ("Women's Cricket", "Arsenal Women") only matches these. */
val League.womens: Boolean get() = this == League.WNBA || this == League.NWSL || this == League.PWHL || this == League.COLLEGE_BASKETBALL_WOMEN

/** Soccer and cricket cards read "Home v Away"; every other sport keeps "Away @ Home". */
val League.homeFirst: Boolean get() = sport == Sport.SOCCER || sport == Sport.CRICKET

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
