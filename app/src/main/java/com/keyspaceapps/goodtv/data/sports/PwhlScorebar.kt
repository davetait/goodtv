@file:Suppress("PropertyName")

package com.keyspaceapps.goodtv.data.sports

import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The PWHL's schedule from HockeyTech, the feed behind the league's site (unofficial; the key is
 * the public one the site embeds). Returns only games on [games]' day in the device's zone.
 */
class PwhlScorebar(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://lscluster.hockeytech.com/feed/".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ScheduleSource {
    override suspend fun games(league: League, day: LocalDate): List<SportsGame> = withContext(io) {
        require(league == League.PWHL) { "HockeyTech is only used for the PWHL" }
        val url = baseUrl.newBuilder()
            .addQueryParameter("feed", "modulekit").addQueryParameter("view", "scorebar")
            .addQueryParameter("client_code", "pwhl").addQueryParameter("key", KEY)
            .addQueryParameter("fmt", "json").addQueryParameter("lang", "en")
            .addQueryParameter("numberofdaysahead", "1").addQueryParameter("numberofdaysback", "1")
            .build()
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", SPORTS_USER_AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) throw SportsException("PWHL schedule: HTTP ${r.code}")
                r.body.string()
            }
        } catch (e: IOException) {
            throw SportsException("PWHL schedule: ${e.message ?: "network error"}")
        }
        val text = body.trim().let { if (it.startsWith("(") && it.endsWith(")")) it.substring(1, it.length - 1) else it }
        val games = try {
            json.decodeFromString(Feed.serializer(), text).SiteKit.Scorebar
        } catch (e: SerializationException) {
            throw SportsException("PWHL schedule: unexpected response")
        } catch (e: IllegalArgumentException) {
            throw SportsException("PWHL schedule: unexpected response")
        }
        games.mapNotNull { it.toGame() }.filter { it.startsAt.atZone(zone()).toLocalDate() == day }
    }

    private fun Game.toGame(): SportsGame? {
        val start = try { OffsetDateTime.parse(GameDateISO8601).toInstant() } catch (e: DateTimeParseException) { return null }
        val state = when (GameStatus) {
            "2", "3" -> GameState.LIVE
            "4" -> GameState.FINAL
            else -> GameState.SCHEDULED
        }
        val live = state != GameState.SCHEDULED
        return SportsGame(
            league = League.PWHL,
            id = ID,
            startsAt = start,
            state = state,
            away = Team(VisitorLongName, VisitorNickname.realNickname(), VisitorCity, VisitorCode, VisitorLogo),
            home = Team(HomeLongName, HomeNickname.realNickname(), HomeCity, HomeCode, HomeLogo),
            awayScore = VisitorGoals.takeIf { live },
            homeScore = HomeGoals.takeIf { live },
            detail = GameStatusString.takeIf { state == GameState.LIVE },
            broadcasters = emptyList(),
        )
    }

    /** Expansion teams carry "PWHL" as their nickname ("PWHL Las Vegas"); that's the league, not a name. */
    private fun String?.realNickname() = this?.takeUnless { it.isBlank() || it.equals("PWHL", ignoreCase = true) }

    @Serializable private data class Feed(val SiteKit: SiteKitBody = SiteKitBody())
    @Serializable private data class SiteKitBody(val Scorebar: List<Game> = emptyList())
    @Serializable private data class Game(
        val ID: String,
        val GameDateISO8601: String,
        val GameStatus: String = "1",
        val GameStatusString: String? = null,
        val HomeLongName: String,
        val HomeNickname: String? = null,
        val HomeCity: String? = null,
        val HomeCode: String = "",
        val HomeLogo: String? = null,
        val HomeGoals: String? = null,
        val VisitorLongName: String,
        val VisitorNickname: String? = null,
        val VisitorCity: String? = null,
        val VisitorCode: String = "",
        val VisitorLogo: String? = null,
        val VisitorGoals: String? = null,
    )

    private companion object {
        const val KEY = "446521baf8c38984"
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    }
}
