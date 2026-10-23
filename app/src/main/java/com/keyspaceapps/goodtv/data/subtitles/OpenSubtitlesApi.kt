package com.keyspaceapps.goodtv.data.subtitles

import com.keyspaceapps.goodtv.data.settings.KeyStatus
import com.keyspaceapps.goodtv.data.settings.OpenSubtitlesAccess
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/** What to look subtitles up by: a movie's TMDB id, or a series' TMDB id with season and episode. */
data class SubtitleQuery(val tmdbId: Long, val season: Int? = null, val episode: Int? = null) {
    val isEpisode: Boolean get() = season != null && episode != null
    /** Names the cached files: "m603", "e136315s1e2". */
    val key: String get() = if (isEpisode) "e${tmdbId}s${season}e$episode" else "m$tmdbId"
}

/** One subtitle file OpenSubtitles offers. */
data class OsSubtitle(
    val fileId: Long,
    val release: String,
    val fileName: String?,
    val language: String,
    val hearingImpaired: Boolean,
    val fromTrusted: Boolean,
    val aiTranslated: Boolean,
    val machineTranslated: Boolean,
    val downloadCount: Int,
)

/** An OpenSubtitles account; logging in raises the daily download allowance. Never printed. */
data class OpenSubtitlesLogin(val username: String, val password: String) {
    override fun toString() = "OpenSubtitlesLogin(***)"
}

/** A download hop that isn't https (the link itself, or a redirect it leads to). */
class InsecureLinkException(url: HttpUrl) : IOException("Refused a non-https download hop to ${url.host}")

/** Refuses any request that isn't https. As a network interceptor it sees every redirect hop. */
object HttpsOnly : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps) throw InsecureLinkException(url)
        return chain.proceed(chain.request())
    }
}

/** [resetUtc] is when the daily allowance renews, for [Reason.QUOTA]. */
class OpenSubtitlesException(message: String, val reason: Reason, val resetUtc: String? = null) : Exception(message) {
    enum class Reason { AUTH, QUOTA, RATE_LIMITED, NETWORK, SERVER, BAD_RESPONSE }
}

/**
 * Thin client for the OpenSubtitles REST API: search by TMDB id, then a download link (which costs
 * one of the day's downloads) and the file itself. Each call asks [access] for the key and optional
 * login in effect (null fails as [OpenSubtitlesException.Reason.AUTH] without a request). With a
 * login it logs in once per access and sends the token to the host the login names; without, the
 * key alone (a smaller allowance). [onResult] hears [KeyStatus.CONNECTED] when a call succeeds,
 * [KeyStatus.REJECTED_LOGIN] when the login is refused and [KeyStatus.REJECTED_KEY] when any other
 * call is. [configured] is a non-blocking hint for [isConfigured].
 * [loginScheme] is the scheme for that host (https; tests use http). Download links come from the
 * API's response, so only https links are followed (unless [allowInsecureLinks], for tests) and no
 * response is read past [MAX_BYTES].
 */
class OpenSubtitlesApi(
    private val client: OkHttpClient,
    private val access: suspend () -> OpenSubtitlesAccess?,
    private val configured: () -> Boolean,
    private val onResult: (OpenSubtitlesAccess, KeyStatus) -> Unit = { _, _ -> },
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val loginScheme: String = "https",
    private val allowInsecureLinks: Boolean = false,
) {
    /** A fixed key and optional login (tests). */
    constructor(
        client: OkHttpClient,
        apiKey: String,
        credentials: OpenSubtitlesLogin? = null,
        baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
        io: CoroutineDispatcher = Dispatchers.IO,
        loginScheme: String = "https",
        allowInsecureLinks: Boolean = false,
    ) : this(client, OpenSubtitlesAccess(apiKey, credentials).let { fixed -> { fixed } }, { true }, { _, _ -> }, baseUrl, io, loginScheme, allowInsecureLinks)

    private class Session(val access: OpenSubtitlesAccess, val token: String, val base: HttpUrl)

    private val loginLock = Mutex()
    // Downloads follow links from the API's response: every hop, redirects included, must be https.
    private val downloadClient = if (allowInsecureLinks) client else client.newBuilder().addNetworkInterceptor(HttpsOnly).build()
    @Volatile private var session: Session? = null

    /** True when a key is in effect; calls made while false fail as [OpenSubtitlesException.Reason.AUTH]. */
    val isConfigured: Boolean get() = configured()

    /**
     * Whether OpenSubtitles accepts [candidate]: its key (a `infos/formats` call), then its login if
     * it has one. Throws [OpenSubtitlesException] when it can't tell (network, server, rate limit).
     * Doesn't touch the live session or [onResult].
     */
    suspend fun check(candidate: OpenSubtitlesAccess): KeyStatus {
        try {
            call(Request.Builder().url(baseUrl.newBuilder().addPathSegments("infos/formats").build()).get(), candidate.apiKey, null, JsonObject.serializer())
        } catch (e: OpenSubtitlesException) {
            if (e.reason == OpenSubtitlesException.Reason.AUTH) return KeyStatus.REJECTED_KEY
            throw e
        }
        val login = candidate.login ?: return KeyStatus.CONNECTED
        try {
            login(candidate.apiKey, login)
        } catch (e: OpenSubtitlesException) {
            if (e.reason == OpenSubtitlesException.Reason.AUTH) return KeyStatus.REJECTED_LOGIN
            throw e
        }
        return KeyStatus.CONNECTED
    }

    suspend fun search(query: SubtitleQuery): List<OsSubtitle> = withSession { apiKey, token, base ->
        val url = base.newBuilder().addPathSegment("subtitles").apply {
            addQueryParameter("languages", "en")
            if (query.isEpisode) {
                addQueryParameter("parent_tmdb_id", query.tmdbId.toString())
                addQueryParameter("season_number", query.season.toString())
                addQueryParameter("episode_number", query.episode.toString())
            } else {
                addQueryParameter("tmdb_id", query.tmdbId.toString())
            }
        }.build()
        val page = call(Request.Builder().url(url).get(), apiKey, token, OsSearchPage.serializer())
        page.data.mapNotNull { item ->
            val a = item.attributes
            val file = a.files.firstOrNull() ?: return@mapNotNull null
            OsSubtitle(
                fileId = file.file_id, release = a.release.orEmpty(), fileName = file.file_name, language = a.language.orEmpty(),
                hearingImpaired = a.hearing_impaired, fromTrusted = a.from_trusted == true,
                aiTranslated = a.ai_translated, machineTranslated = a.machine_translated, downloadCount = a.download_count,
            )
        }
    }

    /** Spends one download: the temporary link to [fileId] as SRT. */
    suspend fun downloadLink(fileId: Long): String = withSession { apiKey, token, base ->
        val body = json.encodeToString(OsDownloadRequest.serializer(), OsDownloadRequest(fileId)).toRequestBody(JSON)
        call(Request.Builder().url(base.newBuilder().addPathSegment("download").build()).post(body), apiKey, token, OsDownload.serializer()).link
            ?: throw OpenSubtitlesException("OpenSubtitles returned no download link", OpenSubtitlesException.Reason.BAD_RESPONSE)
    }

    /** The subtitle file behind a download link. */
    suspend fun fetchText(link: String): String = withContext(io) {
        val url = link.toHttpUrlOrNull()?.takeIf { it.isHttps || allowInsecureLinks }
            ?: throw OpenSubtitlesException("OpenSubtitles returned an unusable download link", OpenSubtitlesException.Reason.BAD_RESPONSE)
        try {
            downloadClient.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) throw OpenSubtitlesException("Subtitle download failed (HTTP ${r.code})", reasonFor(r.code))
                readCapped(r.body)
            }
        } catch (e: InsecureLinkException) {
            throw OpenSubtitlesException("OpenSubtitles returned an unusable download link", OpenSubtitlesException.Reason.BAD_RESPONSE)
        } catch (e: IOException) {
            throw OpenSubtitlesException("Could not reach OpenSubtitles", OpenSubtitlesException.Reason.NETWORK)
        }
    }

    /**
     * Runs [block] with the session; a rejected token (logins expire) logs in again once and
     * retries, so a long-running TV doesn't quietly lose OpenSubtitles.
     */
    private suspend fun <T> withSession(block: suspend (apiKey: String, token: String?, base: HttpUrl) -> T): T {
        val access = access() ?: throw OpenSubtitlesException("No OpenSubtitles key is set", OpenSubtitlesException.Reason.AUTH)
        val (token, base) = session(access)
        val result = try {
            reportingKey(access, retryable = token != null) { block(access.apiKey, token, base) }
        } catch (e: OpenSubtitlesException) {
            if (e.reason != OpenSubtitlesException.Reason.AUTH || token == null) throw e
            loginLock.withLock { if (session?.token == token) session = null }
            val (fresh, freshBase) = session(access)
            reportingKey(access) { block(access.apiKey, fresh, freshBase) }
        }
        onResult(access, KeyStatus.CONNECTED)
        return result
    }

    /** Runs [block]; a refusal is [KeyStatus.REJECTED_KEY], unless [retryable] (a token that may have expired, retried first). */
    private inline fun <T> reportingKey(access: OpenSubtitlesAccess, retryable: Boolean = false, block: () -> T): T = try {
        block()
    } catch (e: OpenSubtitlesException) {
        if (e.reason == OpenSubtitlesException.Reason.AUTH && !retryable) onResult(access, KeyStatus.REJECTED_KEY)
        throw e
    }

    /** The token and base url for [access], logging in when it has a login and no session was made for it. */
    private suspend fun session(access: OpenSubtitlesAccess): Pair<String?, HttpUrl> {
        val login = access.login ?: return null to baseUrl
        session?.takeIf { it.access == access }?.let { return it.token to it.base }
        return loginLock.withLock {
            val current = session?.takeIf { it.access == access } ?: run {
                val (token, base) = try {
                    login(access.apiKey, login)
                } catch (e: OpenSubtitlesException) {
                    if (e.reason == OpenSubtitlesException.Reason.AUTH) onResult(access, KeyStatus.REJECTED_LOGIN)
                    throw e
                }
                Session(access, token, base).also { session = it }
            }
            current.token to current.base
        }
    }

    /** Logs in; a refused login is [OpenSubtitlesException.Reason.AUTH]. */
    private suspend fun login(apiKey: String, login: OpenSubtitlesLogin): Pair<String, HttpUrl> {
        val body = json.encodeToString(OsLoginRequest.serializer(), OsLoginRequest(login.username, login.password)).toRequestBody(JSON)
        val r = call(Request.Builder().url(baseUrl.newBuilder().addPathSegment("login").build()).post(body), apiKey, null, OsLogin.serializer())
        val token = r.token ?: throw OpenSubtitlesException("OpenSubtitles login returned no token", OpenSubtitlesException.Reason.BAD_RESPONSE)
        val base = r.base_url?.takeIf { it.isNotBlank() }?.let { "$loginScheme://$it/api/v1/".toHttpUrlOrNull() } ?: baseUrl
        return token to base
    }

    private suspend fun <T> call(builder: Request.Builder, apiKey: String, token: String?, strategy: DeserializationStrategy<T>): T = withContext(io) {
        val request = builder.header("Api-Key", apiKey).header("User-Agent", USER_AGENT).header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        try {
            client.newCall(request).execute().use { r ->
                val text = readCapped(r.body)
                if (!r.isSuccessful) {
                    val err = runCatching { json.decodeFromString(OsError.serializer(), text) }.getOrNull()
                    throw OpenSubtitlesException(err?.message ?: "OpenSubtitles returned HTTP ${r.code}", reasonFor(r.code), err?.reset_time_utc)
                }
                json.decodeFromString(strategy, text)
            }
        } catch (e: IOException) {
            throw OpenSubtitlesException("Could not reach OpenSubtitles", OpenSubtitlesException.Reason.NETWORK)
        } catch (e: SerializationException) {
            throw OpenSubtitlesException("OpenSubtitles returned an unexpected response", OpenSubtitlesException.Reason.BAD_RESPONSE)
        } catch (e: IllegalArgumentException) {
            throw OpenSubtitlesException("OpenSubtitles returned an unexpected response", OpenSubtitlesException.Reason.BAD_RESPONSE)
        }
    }

    /** The body as UTF-8, refusing anything over [MAX_BYTES] (a subtitle file is well under 1 MB). */
    private fun readCapped(body: ResponseBody): String {
        val tooBig = OpenSubtitlesException("OpenSubtitles sent more than expected", OpenSubtitlesException.Reason.BAD_RESPONSE)
        if (body.contentLength() > MAX_BYTES) throw tooBig
        val source = body.source()
        if (source.request(MAX_BYTES + 1L)) throw tooBig
        return source.buffer.readUtf8()
    }

    private fun reasonFor(code: Int) = when {
        code == 401 || code == 403 -> OpenSubtitlesException.Reason.AUTH
        code == 406 -> OpenSubtitlesException.Reason.QUOTA
        code == 429 -> OpenSubtitlesException.Reason.RATE_LIMITED
        code >= 500 -> OpenSubtitlesException.Reason.SERVER
        else -> OpenSubtitlesException.Reason.BAD_RESPONSE
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.opensubtitles.com/api/v1/"
        const val USER_AGENT = "GoodTV v0.1"
        const val MAX_BYTES = 2 * 1024 * 1024
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }
    }
}
