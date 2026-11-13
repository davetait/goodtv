package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.USER_AGENT
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

/**
 * Thin client for `player_api.php`. Every failure surfaces as [SourceException] with on-screen copy.
 * Responses are parsed straight from the socket: real providers answer list calls with tens of
 * megabytes of JSON, which does not fit in a TV app's heap as a String.
 */
@OptIn(ExperimentalSerializationApi::class)
class XtreamApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun auth(cfg: SourceConfig.Xtream): XtAuthResponse =
        get(XtreamUrls.api(cfg), XtAuthResponse.serializer())

    suspend fun liveCategories(cfg: SourceConfig.Xtream): List<XtCategory> =
        get(XtreamUrls.api(cfg, "get_live_categories"), ListSerializer(XtCategory.serializer()))

    suspend fun vodCategories(cfg: SourceConfig.Xtream): List<XtCategory> =
        get(XtreamUrls.api(cfg, "get_vod_categories"), ListSerializer(XtCategory.serializer()))

    suspend fun seriesCategories(cfg: SourceConfig.Xtream): List<XtCategory> =
        get(XtreamUrls.api(cfg, "get_series_categories"), ListSerializer(XtCategory.serializer()))

    suspend fun liveStreams(cfg: SourceConfig.Xtream): List<XtLiveStream> =
        get(XtreamUrls.api(cfg, "get_live_streams"), ListSerializer(XtLiveStream.serializer()))

    suspend fun vodStreams(cfg: SourceConfig.Xtream): List<XtVodStream> =
        get(XtreamUrls.api(cfg, "get_vod_streams"), ListSerializer(XtVodStream.serializer()))

    suspend fun series(cfg: SourceConfig.Xtream): List<XtSeries> =
        get(XtreamUrls.api(cfg, "get_series"), ListSerializer(XtSeries.serializer()))

    suspend fun vodInfo(cfg: SourceConfig.Xtream, vodId: String): XtVodInfoResponse =
        get(XtreamUrls.api(cfg, "get_vod_info", mapOf("vod_id" to vodId)), XtVodInfoResponse.serializer())

    suspend fun seriesInfo(cfg: SourceConfig.Xtream, seriesId: String): XtSeriesInfoResponse =
        get(XtreamUrls.api(cfg, "get_series_info", mapOf("series_id" to seriesId)), XtSeriesInfoResponse.serializer())

    /**
     * Decodes a list response element by element, handing on pieces of at most [chunk] items as they are read.
     * Pieces arrive in list order; an empty list is handed on as one empty piece. [onChunk] runs on the IO
     * dispatcher, and its own failures pass through unchanged. A body that turns malformed after earlier pieces
     * were handed on fails as [SourceException] like any bad response, so the caller must drop those pieces.
     */
    suspend fun <T> streamList(url: HttpUrl, element: KSerializer<T>, chunk: Int, onChunk: suspend (List<T>) -> Unit) {
        require(chunk > 0) { "chunk must be positive" }
        read(url) { stream ->
            var piece = ArrayList<T>(chunk)
            var handedOn = false
            for (item in json.decodeToSequence(stream, element, DecodeSequenceMode.ARRAY_WRAPPED)) {
                piece.add(item)
                if (piece.size == chunk) {
                    handOn(onChunk, piece)
                    handedOn = true
                    piece = ArrayList(chunk)
                }
            }
            if (piece.isNotEmpty() || !handedOn) handOn(onChunk, piece)
        }
    }

    /** Marks the caller's failure, so [read] does not take it for a bad response. */
    private class CallerFailure(val error: Throwable) : Exception(null, null, false, false)

    private suspend fun <T> handOn(onChunk: suspend (List<T>) -> Unit, piece: List<T>) {
        try {
            onChunk(piece)
        } catch (e: Throwable) {
            throw CallerFailure(e)
        }
    }

    /**
     * The provider's guide (`xmltv.php`), streamed: it can be hundreds of MB. The caller closes the
     * stream, which releases the connection.
     */
    suspend fun xmltv(cfg: SourceConfig.Xtream): InputStream = withContext(io) {
        val request = Request.Builder().url(XtreamUrls.xmltv(cfg)).header("User-Agent", USER_AGENT).build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw SourceException("Server returned HTTP ${response.code}")
            }
            response.body.byteStream()
        } catch (e: IOException) {
            throw SourceException("Could not reach server: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private suspend fun <T> get(url: HttpUrl, strategy: DeserializationStrategy<T>): T =
        read(url) { stream -> json.decodeFromStream(strategy, stream) }

    /**
     * Requests [url] and hands the response body to [parse] on the IO dispatcher. A blocking OkHttp read
     * ignores coroutine cancellation, so a watcher cancels the [okhttp3.Call] when this coroutine is
     * cancelled: the read then fails at once, and that failure surfaces as cancellation, never as a
     * [SourceException].
     */
    private suspend fun <T> read(url: HttpUrl, parse: suspend (InputStream) -> T): T = withContext(io) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val call = client.newCall(request)
        val scopeJob = coroutineContext.job
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // Cancelled with the call's coroutine, not merely released after a finished call.
                if (!scopeJob.isActive) call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw SourceException("Server returned HTTP ${response.code}")
                response.body.byteStream().use { stream -> parse(stream) }
            }
        } catch (e: CallerFailure) {
            throw e.error
        } catch (e: IOException) {
            ensureActive()
            throw SourceException("Could not reach server: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: SerializationException) {
            ensureActive()
            throw SourceException("Server returned an unexpected response", e)
        } catch (e: IllegalArgumentException) {
            ensureActive()
            throw SourceException("Server returned an unexpected response", e)
        } finally {
            watcher.cancel()
        }
    }
}
