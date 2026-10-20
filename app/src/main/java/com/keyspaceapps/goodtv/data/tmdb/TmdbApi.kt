package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.USER_AGENT
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * TMDB accepts either credential from the account's API settings page: the v3 "API Key" (sent as
 * `api_key=`) or the "API Read Access Token" (a JWT, sent as `Authorization: Bearer`). Both give the
 * same v3 access, so we take whichever is configured.
 */
sealed interface TmdbCredential {
    data class ApiKey(val key: String) : TmdbCredential { override fun toString() = "ApiKey(***)" }
    data class Bearer(val token: String) : TmdbCredential { override fun toString() = "Bearer(***)" }

    companion object {
        /** Blank → null (TMDB off). A three-part token starting "eyJ" is a read access token. */
        fun from(raw: String): TmdbCredential? {
            val v = raw.trim()
            return when {
                v.isEmpty() -> null
                v.startsWith("eyJ") && v.count { it == '.' } == 2 -> Bearer(v)
                else -> ApiKey(v)
            }
        }
    }
}

class TmdbException(message: String, val reason: Reason, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { AUTH, RATE_LIMITED, NETWORK, SERVER, BAD_RESPONSE }
}

/**
 * Thin client for the TMDB v3 API. Details calls return null for an unknown id (HTTP 404); every
 * other failure is a [TmdbException]. At most [maxConcurrent] requests are in flight; HTTP 429 is
 * retried after `Retry-After` (or a short backoff) up to [maxAttempts] times.
 */
class TmdbApi(
    private val client: OkHttpClient,
    private val credential: TmdbCredential,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    maxConcurrent: Int = 8,
    private val maxAttempts: Int = 3,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val permits = Semaphore(maxConcurrent)

    suspend fun movie(id: Long, language: String): TmdbMovieDetails? =
        get(url("movie/$id", language), TmdbMovieDetails.serializer(), nullOn404 = true)

    suspend fun tv(id: Long, language: String): TmdbTvDetails? =
        get(url("tv/$id", language), TmdbTvDetails.serializer(), nullOn404 = true)

    suspend fun searchMovie(query: String, year: Int?, language: String): List<TmdbMovieResult> =
        get(
            url("search/movie", language) { addQueryParameter("query", query); year?.let { addQueryParameter("year", it.toString()) } },
            TmdbSearchPage.serializer(TmdbMovieResult.serializer()),
        )?.results.orEmpty()

    suspend fun searchTv(query: String, year: Int?, language: String): List<TmdbTvResult> =
        get(
            url("search/tv", language) { addQueryParameter("query", query); year?.let { addQueryParameter("first_air_date_year", it.toString()) } },
            TmdbSearchPage.serializer(TmdbTvResult.serializer()),
        )?.results.orEmpty()

    /**
     * Discover movies on subscription ([flatrate]) streaming in [region] on any of [providers]
     * (TMDB watch-provider ids, which differ by region), released between [releasedFrom] and
     * [releasedTo] (ISO dates), with at least [minVotes] votes, most popular first. One page is 20.
     */
    suspend fun discoverStreamingMovies(
        region: String,
        providers: List<Int>,
        releasedFrom: String,
        releasedTo: String,
        minVotes: Int,
        page: Int,
        language: String,
    ): TmdbSearchPage<TmdbMovieResult> =
        get(
            url("discover/movie", language) {
                addQueryParameter("watch_region", region)
                addQueryParameter("with_watch_providers", providers.joinToString("|"))
                addQueryParameter("with_watch_monetization_types", "flatrate")
                addQueryParameter("primary_release_date.gte", releasedFrom)
                addQueryParameter("primary_release_date.lte", releasedTo)
                addQueryParameter("vote_count.gte", minVotes.toString())
                addQueryParameter("sort_by", "popularity.desc")
                addQueryParameter("page", page.toString())
            },
            TmdbSearchPage.serializer(TmdbMovieResult.serializer()),
        ) ?: TmdbSearchPage()

    /**
     * Discover TV shows on subscription streaming in [region] on any of [providers] that aired an
     * episode between [airedFrom] and [airedTo] and premiered on or after [premieredFrom] (ISO
     * dates), with at least [minVotes] votes and none of [withoutGenres] (TMDB TV genre ids), most
     * popular first. One page is 20.
     */
    suspend fun discoverStreamingTv(
        region: String,
        providers: List<Int>,
        airedFrom: String,
        airedTo: String,
        premieredFrom: String,
        minVotes: Int,
        withoutGenres: List<Int>,
        page: Int,
        language: String,
    ): TmdbSearchPage<TmdbTvResult> =
        get(
            url("discover/tv", language) {
                addQueryParameter("watch_region", region)
                addQueryParameter("with_watch_providers", providers.joinToString("|"))
                addQueryParameter("with_watch_monetization_types", "flatrate")
                addQueryParameter("air_date.gte", airedFrom)
                addQueryParameter("air_date.lte", airedTo)
                addQueryParameter("first_air_date.gte", premieredFrom)
                addQueryParameter("vote_count.gte", minVotes.toString())
                if (withoutGenres.isNotEmpty()) addQueryParameter("without_genres", withoutGenres.joinToString(","))
                addQueryParameter("sort_by", "popularity.desc")
                addQueryParameter("page", page.toString())
            },
            TmdbSearchPage.serializer(TmdbTvResult.serializer()),
        ) ?: TmdbSearchPage()

    private fun url(path: String, language: String, extra: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        baseUrl.newBuilder().addPathSegments(path).apply {
            addQueryParameter("language", language)
            addQueryParameter("include_adult", "false")
            (credential as? TmdbCredential.ApiKey)?.let { addQueryParameter("api_key", it.key) }
            extra()
        }.build()

    // Holds its permit while backing off, so a 429 slows every caller down, not just this one.
    private suspend fun <T> get(url: HttpUrl, strategy: DeserializationStrategy<T>, nullOn404: Boolean = false): T? =
        permits.withPermit {
            repeat(maxAttempts) { attempt ->
                when (val outcome = call(url, strategy, nullOn404)) {
                    is Outcome.Done -> return outcome.value
                    is Outcome.RetryAfter -> if (attempt < maxAttempts - 1) sleep(outcome.millis ?: (1_000L shl attempt))
                }
            }
            throw TmdbException("TMDB is rate limiting requests", TmdbException.Reason.RATE_LIMITED)
        }

    private sealed interface Outcome<out T> {
        data class Done<T>(val value: T?) : Outcome<T>
        data class RetryAfter(val millis: Long?) : Outcome<Nothing>
    }

    private suspend fun <T> call(url: HttpUrl, strategy: DeserializationStrategy<T>, nullOn404: Boolean): Outcome<T> = withContext(io) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").apply {
            (credential as? TmdbCredential.Bearer)?.let { header("Authorization", "Bearer ${it.token}") }
        }.build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 && nullOn404 -> Outcome.Done(null)
                    response.code == 401 -> throw TmdbException("TMDB rejected the API key", TmdbException.Reason.AUTH)
                    response.code == 429 -> Outcome.RetryAfter(response.header("Retry-After")?.toLongOrNull()?.times(1_000))
                    response.code >= 500 -> throw TmdbException("TMDB returned HTTP ${response.code}", TmdbException.Reason.SERVER)
                    !response.isSuccessful -> throw TmdbException("TMDB returned HTTP ${response.code}", TmdbException.Reason.BAD_RESPONSE)
                    else -> Outcome.Done(json.decodeFromString(strategy, response.body.string()))
                }
            }
        } catch (e: IOException) {
            throw TmdbException("Could not reach TMDB: ${e.message ?: e.javaClass.simpleName}", TmdbException.Reason.NETWORK, e)
        } catch (e: SerializationException) {
            throw TmdbException("TMDB returned an unexpected response", TmdbException.Reason.BAD_RESPONSE, e)
        } catch (e: IllegalArgumentException) {
            throw TmdbException("TMDB returned an unexpected response", TmdbException.Reason.BAD_RESPONSE, e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.themoviedb.org/3/"

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
