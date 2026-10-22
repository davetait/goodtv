package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.USER_AGENT
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
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

    private suspend fun <T> get(url: HttpUrl, strategy: DeserializationStrategy<T>): T = withContext(io) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SourceException("Server returned HTTP ${response.code}")
                response.body.byteStream().use { stream -> json.decodeFromStream(strategy, stream) }
            }
        } catch (e: IOException) {
            throw SourceException("Could not reach server: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: SerializationException) {
            throw SourceException("Server returned an unexpected response", e)
        } catch (e: IllegalArgumentException) {
            throw SourceException("Server returned an unexpected response", e)
        }
    }
}
