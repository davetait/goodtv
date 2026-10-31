package com.keyspaceapps.goodtv.data.sports

import com.keyspaceapps.goodtv.domain.LiveChannel
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/** A live channel that might carry a game, with its category's name (league words often live there). */
data class ChannelCandidate(val channel: LiveChannel, val categoryName: String)

/**
 * Pairs today's games with the live channels whose names mention both teams.
 *
 * A team is recognised by its display name, location, nickname or abbreviation (2–4 letters, whole
 * word only), never by a league word. The two teams must match different words of the name. A
 * channel that is over ("ENDED", "End", "Final"), an empty slot ("No event streaming"), a replay
 * or another league's ("CFL", "MLS", "AHL", …) never matches. A date in the name must be within [WINDOW_HOURS] of the start. When a name fits
 * several games, a league word in the name or category decides; if it still fits several, it matches
 * none (better "On 2 channels" than starting the wrong game).
 */
object GameMatcher {
    const val WINDOW_HOURS = 30L

    private val LEAGUE_WORDS = League.entries.flatMap { it.words }.toSet()
    /** Name starts (normalised) of a slot that is over or empty; the guide hides these too. */
    internal val CLOSED = listOf("ended ", "end ", "final ", "no event streaming")
    /** Leagues outside the six whose teams share cities with them (Toronto, Montreal, …): a channel naming one is theirs. */
    private val OTHER_LEAGUES = setOf("cfl", "mls", "fc", "cf", "ahl", "echl", "ohl", "whl", "qmjhl", "chl", "ncaa", "ncaaf", "ncaab", "nwsl", "ufl", "xfl", "nll", "khl")

    private data class Form(val words: List<String>, val game: SportsGame, val home: Boolean)
    private data class Hit(val game: SportsGame, val home: Boolean, val from: Int, val to: Int)

    fun match(games: List<SportsGame>, channels: List<ChannelCandidate>, now: Instant): Map<String, List<ChannelCandidate>> {
        val index = HashMap<String, MutableList<Form>>()
        for (g in games) for ((team, home) in listOf(g.home to true, g.away to false)) {
            for (form in forms(team)) index.getOrPut(form.first()) { mutableListOf() } += Form(form, g, home)
        }
        val out = LinkedHashMap<String, MutableList<ChannelCandidate>>()
        games.forEach { out[it.key] = mutableListOf() }
        for (c in channels) {
            val game = gameFor(c, index, now) ?: continue
            out.getValue(game.key) += c
        }
        return out
    }

    private fun gameFor(c: ChannelCandidate, index: Map<String, List<Form>>, now: Instant): SportsGame? {
        val name = normalize(c.channel.name)
        if (CLOSED.any { name.startsWith(it) } || " replay " in " $name ") return null
        val words = name.split(' ').filter { it.isNotEmpty() }
        if ((words + normalize(c.categoryName).split(' ')).any { it in OTHER_LEAGUES }) return null
        val hits = mutableListOf<Hit>()
        for (i in words.indices) {
            for (f in index[words[i]].orEmpty()) {
                val end = i + f.words.size
                if (end <= words.size && words.subList(i, end) == f.words) hits += Hit(f.game, f.home, i, end)
            }
        }
        if (hits.isEmpty()) return null
        var candidates = hits.groupBy { it.game }.filter { (_, h) ->
            val home = h.filter { it.home }
            val away = h.filter { !it.home }
            home.any { a -> away.any { b -> a.to <= b.from || b.to <= a.from } }
        }.keys.toList()
        val time = ChannelTimes.parse(c.channel.name, now)
        if (time != null) candidates = candidates.filter { abs(it.startsAt.epochSecond - time.epochSecond) <= WINDOW_HOURS * 3600 }
        if (candidates.size > 1) {
            val context = " $name ${normalize(c.categoryName)} "
            val withLeague = candidates.filter { " ${it.league.label.lowercase()} " in context }
            if (withLeague.isNotEmpty()) candidates = withLeague
        }
        if (candidates.size > 1 && time != null) {
            // The same matchup on two days (a series): the nearer start is this channel's game.
            val best = candidates.minOf { abs(it.startsAt.epochSecond - time.epochSecond) }
            candidates = candidates.filter { abs(it.startsAt.epochSecond - time.epochSecond) == best }
        }
        return candidates.singleOrNull()
    }

    private fun forms(team: Team): List<List<String>> = buildList {
        listOfNotNull(team.displayName, team.location, team.nickname).forEach { add(normalize(it)) }
        team.abbreviation.takeIf { it.length in 2..4 }?.let { add(normalize(it)) }
    }.filter { it.isNotEmpty() && it !in LEAGUE_WORDS }.distinct().map { it.split(' ') }

    private val MARKS = Regex("""\p{M}+""")
    private val NOT_ALPHANUMERIC = Regex("""[^a-z0-9]+""")

    /** Lower case, no accents, letters and digits only, single spaces. Patterns compiled once: this runs for every channel, often. */
    internal fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
            .replace(NOT_ALPHANUMERIC, " ").trim()
}

/** Reads the date and time some channel names carry; without a zone (or with one it doesn't know), New York time: the lineup is US. */
object ChannelTimes {
    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*\(?([A-Za-z]{1,4})\b)?""")
    private val DMY = Regex("""\b(\d{1,2})-(\d{1,2})-(\d{4})\s*\|?\s*(\d{1,2}):(\d{2})\s*(?:\(([A-Za-z]{1,4})\))?""")
    private val DAY_MONTH = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+($MONTHS)[a-z]*\.?\s+(?:(\d{4})\s+)?(\d{1,2}):(\d{2})(?::\d{2})?\s*(am|pm)?\b\s*([a-z]{1,4})?\b""", RegexOption.IGNORE_CASE)
    private val MONTH_DAY = Regex("""\b($MONTHS)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(?:(\d{4})\s+)?(\d{1,2}):(\d{2})(?::\d{2})?\s*(am|pm)?\b\s*([a-z]{1,4})?\b""", RegexOption.IGNORE_CASE)

    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    private val ZONES: Map<String, ZoneId> = mapOf(
        "UTC" to ZoneOffset.UTC, "GMT" to ZoneOffset.UTC, "Z" to ZoneOffset.UTC,
        "EDT" to ZoneOffset.ofHours(-4), "EST" to ZoneOffset.ofHours(-5), "CDT" to ZoneOffset.ofHours(-5), "CST" to ZoneOffset.ofHours(-6),
        "MDT" to ZoneOffset.ofHours(-6), "MST" to ZoneOffset.ofHours(-7), "PDT" to ZoneOffset.ofHours(-7), "PST" to ZoneOffset.ofHours(-8),
        "ADT" to ZoneOffset.ofHours(-3), "AST" to ZoneOffset.ofHours(-4), "NDT" to ZoneOffset.ofHoursMinutes(-2, -30), "NST" to ZoneOffset.ofHoursMinutes(-3, -30),
        "ET" to ZoneId.of("America/New_York"), "CT" to ZoneId.of("America/Chicago"), "MT" to ZoneId.of("America/Denver"), "PT" to ZoneId.of("America/Los_Angeles"),
        "BST" to ZoneOffset.ofHours(1), "CET" to ZoneOffset.ofHours(1), "CEST" to ZoneOffset.ofHours(2), "EET" to ZoneOffset.ofHours(2), "EEST" to ZoneOffset.ofHours(3),
    )

    fun parse(name: String, now: Instant): Instant? {
        ISO.find(name)?.let { m ->
            val g = m.groupValues
            return at(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0, g[7])
        }
        DMY.find(name)?.let { m ->
            val g = m.groupValues
            return at(g[3].toInt(), g[2].toInt(), g[1].toInt(), g[4].toInt(), g[5].toInt(), 0, g[6])
        }
        DAY_MONTH.find(name)?.let { m ->
            val g = m.groupValues
            return dated(now, g[3].toIntOrNull(), month(g[2]), g[1].toInt(), hour(g[4].toInt(), g[6]), g[5].toInt(), g[7])
        }
        MONTH_DAY.find(name)?.let { m ->
            val g = m.groupValues
            return dated(now, g[3].toIntOrNull(), month(g[1]), g[2].toInt(), hour(g[4].toInt(), g[6]), g[5].toInt(), g[7])
        }
        return null
    }

    private fun month(s: String) = MONTHS.split('|').indexOf(s.take(3).lowercase()) + 1

    private fun hour(h: Int, amPm: String): Int = when {
        amPm.isEmpty() || h > 12 -> h
        amPm.equals("pm", ignoreCase = true) -> h % 12 + 12
        else -> h % 12
    }

    /** A date without a year takes the year (last, this or next) that puts it nearest [now]. */
    private fun dated(now: Instant, year: Int?, month: Int, day: Int, hour: Int, minute: Int, zone: String): Instant? {
        if (year != null) return at(year, month, day, hour, minute, 0, zone)
        val y = now.atZone(ZoneOffset.UTC).year
        return listOf(y - 1, y, y + 1).mapNotNull { at(it, month, day, hour, minute, 0, zone) }
            .minByOrNull { abs(it.epochSecond - now.epochSecond) }
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, zone: String?): Instant? = runCatching {
        val z = zone?.uppercase()?.let { ZONES[it] } ?: NEW_YORK
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(z).toInstant()
    }.getOrNull()
}
