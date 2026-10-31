@file:Suppress("PropertyName")

package com.keyspaceapps.goodtv.data.sports

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ESPN's public scoreboard for one league and day (unofficial and undocumented, so it may change
 * without notice). Covers every [League] whose source is [LeagueSource.Espn].
 */
class EspnScoreboard(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://site.api.espn.com/apis/site/v2/sports/".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ScheduleSource {
    override suspend fun games(league: League, day: LocalDate): List<SportsGame> = withContext(io) {
        val path = (league.source as? LeagueSource.Espn)?.path ?: throw IllegalArgumentException("ESPN doesn't cover ${league.label}")
        val url = baseUrl.newBuilder().addPathSegments(path).addPathSegment("scoreboard")
            .addQueryParameter("dates", day.format(DateTimeFormatter.BASIC_ISO_DATE)).build()
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", SPORTS_USER_AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) throw SportsException("${league.label} schedule: HTTP ${r.code}")
                r.body.string()
            }
        } catch (e: IOException) {
            throw SportsException("${league.label} schedule: ${e.message ?: "network error"}")
        }
        try {
            json.decodeFromString(Scoreboard.serializer(), body).events.mapNotNull { it.toGame(league) }
        } catch (e: SerializationException) {
            throw SportsException("${league.label} schedule: unexpected response")
        } catch (e: IllegalArgumentException) {
            throw SportsException("${league.label} schedule: unexpected response")
        }
    }

    @Serializable private data class Scoreboard(val events: List<EspnEvent> = emptyList())

    private companion object {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    }
}

/** Maps one ESPN event (a scoreboard's, or a cricket scorepanel's) to a game; null when it lacks a side or a start. */
internal fun EspnEvent.toGame(league: League): SportsGame? {
    val c = competitions.firstOrNull() ?: return null
    val home = c.competitors.firstOrNull { it.homeAway == "home" } ?: return null
    val away = c.competitors.firstOrNull { it.homeAway == "away" } ?: return null
    val state = when (status.type.state) {
        "in" -> GameState.LIVE
        "post" -> GameState.FINAL
        else -> GameState.SCHEDULED
    }
    // Cricket's short detail is only "Live"; its summary says where the match stands ("NSW trail by 253 runs").
    val detail = if (league.sport == Sport.CRICKET) status.summary?.takeIf { it.isNotBlank() } ?: status.type.shortDetail else status.type.shortDetail
    return SportsGame(
        league = league,
        id = id,
        startsAt = parseEspnInstant(date) ?: return null,
        state = state,
        away = away.team.toTeam(),
        home = home.team.toTeam(),
        awayScore = away.score.takeIf { state != GameState.SCHEDULED },
        homeScore = home.score.takeIf { state != GameState.SCHEDULED },
        detail = detail.takeIf { state == GameState.LIVE },
        broadcasters = c.broadcasts.flatMap { it.names }.distinct(),
    )
}

private fun EspnTeam.toTeam() = Team(displayName, name, location, abbreviation, logo)

/** ESPN writes "2026-10-20T23:00Z" (no seconds). */
private fun parseEspnInstant(s: String): Instant? = try {
    OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]XXX")).toInstant()
} catch (e: DateTimeParseException) {
    runCatching { Instant.parse(s) }.getOrNull()
}

@Serializable internal data class EspnEvent(val id: String, val date: String, val status: EspnStatus = EspnStatus(), val competitions: List<EspnCompetition> = emptyList())
@Serializable internal data class EspnStatus(val summary: String? = null, val type: EspnStatusType = EspnStatusType())
@Serializable internal data class EspnStatusType(val state: String = "pre", val shortDetail: String? = null)
@Serializable internal data class EspnCompetition(
    val competitors: List<EspnCompetitor> = emptyList(),
    val broadcasts: List<EspnBroadcast> = emptyList(),
    @SerialName("class") val matchClass: EspnMatchClass? = null,
)
@Serializable internal data class EspnMatchClass(val internationalClassId: String? = null)
@Serializable internal data class EspnCompetitor(val homeAway: String = "", val score: String? = null, val team: EspnTeam)
@Serializable internal data class EspnTeam(
    val displayName: String,
    val name: String? = null,
    val location: String? = null,
    val abbreviation: String = "",
    val logo: String? = null,
)
@Serializable internal data class EspnBroadcast(val names: List<String> = emptyList())
