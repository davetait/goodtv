package com.keyspaceapps.goodtv.data.sports

import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
 * ESPN's cricket scorepanel for one day (unofficial and undocumented): every cricket match of the
 * day, of which this keeps the men's internationals (Test, ODI and T20I). They sit in no single
 * ESPN league, so they get a league of their own, [League.INTERNATIONAL_CRICKET].
 */
class EspnCricketScorepanel(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ScheduleSource {
    override suspend fun games(league: League, day: LocalDate): List<SportsGame> = withContext(io) {
        require(league.source == LeagueSource.EspnInternationalCricket) { "The cricket scorepanel doesn't cover ${league.label}" }
        val url = baseUrl.newBuilder().addPathSegment("scorepanel")
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
            json.decodeFromString(Panel.serializer(), body).scores.flatMap { it.events }
                .filter { it.competitions.firstOrNull()?.matchClass?.internationalClassId in INTERNATIONAL }
                .mapNotNull { it.toGame(league) }
        } catch (e: SerializationException) {
            throw SportsException("${league.label} schedule: unexpected response")
        } catch (e: IllegalArgumentException) {
            throw SportsException("${league.label} schedule: unexpected response")
        }
    }

    @Serializable private data class Panel(val scores: List<Group> = emptyList())
    @Serializable private data class Group(val events: List<EspnEvent> = emptyList())

    private companion object {
        /** ESPN's class ids: 1 Test, 2 ODI, 3 T20I; 0 is domestic. */
        val INTERNATIONAL = setOf("1", "2", "3")
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    }
}
