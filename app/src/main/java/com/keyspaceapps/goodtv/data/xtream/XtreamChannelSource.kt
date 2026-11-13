package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.data.repo.CatalogWriter
import com.keyspaceapps.goodtv.data.source.ChannelSource
import com.keyspaceapps.goodtv.data.source.LiveCatalog
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.PlayableRef
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceInfo
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl

class XtreamChannelSource(private val api: XtreamApi) : ChannelSource {
    private companion object {
        /** Items per piece of a streamed list: the same as one staging write. */
        const val PIECE = 5_000
    }

    override val type = SourceType.XTREAM

    private fun xtream(config: SourceConfig): SourceConfig.Xtream =
        config as? SourceConfig.Xtream ?: throw IllegalArgumentException("Not an Xtream config")

    override suspend fun validate(config: SourceConfig): Result<SourceInfo> = runCatchingSource {
        val auth = api.auth(xtream(config))
        if (auth.user_info?.auth != 1) throw SourceException("Invalid username or password")
        XtreamMappers.toSourceInfo(auth)
    }

    override suspend fun fetchCatalog(sourceId: Long, config: SourceConfig): Result<Catalog> = runCatchingSource {
        val cfg = xtream(config)
        coroutineScope {
            val liveCats = async { api.liveCategories(cfg) }
            val vodCats = async { api.vodCategories(cfg) }
            val seriesCats = async { api.seriesCategories(cfg) }
            val live = async { api.liveStreams(cfg) }
            val vod = async { api.vodStreams(cfg) }
            val series = async { api.series(cfg) }
            XtreamMappers.toCatalog(
                sourceId,
                liveCats.await(), vodCats.await(), seriesCats.await(),
                live.await(), vod.await(), series.await(),
            )
        }
    }

    /**
     * All six calls start at once. Categories are converted on Dispatchers.Default when they arrive and written,
     * even when empty. Each big list is a pipeline (see [pipe]), handed to [writer] in pieces as it downloads, so
     * no stage holds a whole list. The first failure cancels the other calls.
     */
    override suspend fun streamCatalog(sourceId: Long, config: SourceConfig, writer: CatalogWriter): Result<Unit> = runCatchingSource {
        val cfg = xtream(config)
        coroutineScope {
            launch { writer.categories(ContentKind.LIVE, mapped { XtreamMappers.categories(sourceId, ContentKind.LIVE, api.liveCategories(cfg)) }) }
            launch { writer.categories(ContentKind.MOVIE, mapped { XtreamMappers.categories(sourceId, ContentKind.MOVIE, api.vodCategories(cfg)) }) }
            launch { writer.categories(ContentKind.SERIES, mapped { XtreamMappers.categories(sourceId, ContentKind.SERIES, api.seriesCategories(cfg)) }) }
            pipe(XtreamUrls.api(cfg, "get_live_streams"), XtLiveStream.serializer(), { XtreamMappers.live(sourceId, it) }, writer::live)
            pipe(XtreamUrls.api(cfg, "get_vod_streams"), XtVodStream.serializer(), { XtreamMappers.movies(sourceId, it) }, writer::movies)
            pipe(XtreamUrls.api(cfg, "get_series"), XtSeries.serializer(), { XtreamMappers.series(sourceId, it) }, writer::series)
        }
    }

    /** Downloads and converts inside one block, so the provider's raw list is garbage once converted. */
    private suspend fun <T> mapped(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    /**
     * One list as a pipeline. The download decodes pieces of [PIECE] items into a channel of two; one consumer
     * converts each piece on Dispatchers.Default and hands it to [write], so pieces reach the writer in list
     * order. At most four raw pieces of the list (one decoding, two queued, one converting) and one converted
     * piece are alive at once.
     * A failure on either side fails the caller's scope, which cancels the other side.
     */
    private fun <D, R> CoroutineScope.pipe(
        url: HttpUrl,
        element: KSerializer<D>,
        convert: (List<D>) -> List<R>,
        write: suspend (List<R>) -> Unit,
    ) = launch {
        val pieces = Channel<List<D>>(capacity = 2)
        launch {
            try {
                api.streamList(url, element, PIECE) { pieces.send(it) }
            } finally {
                pieces.close()
            }
        }
        for (piece in pieces) write(withContext(Dispatchers.Default) { convert(piece) })
    }

    override suspend fun fetchLive(sourceId: Long, config: SourceConfig): Result<LiveCatalog> = runCatchingSource {
        val cfg = xtream(config)
        coroutineScope {
            val cats = async { api.liveCategories(cfg) }
            val live = async { api.liveStreams(cfg) }
            LiveCatalog(XtreamMappers.categories(sourceId, ContentKind.LIVE, cats.await()), XtreamMappers.live(sourceId, live.await()))
        }
    }

    override suspend fun openGuide(sourceId: Long, config: SourceConfig): Result<InputStream> =
        runCatchingSource { api.xmltv(xtream(config)) }

    override suspend fun fetchMovieDetail(sourceId: Long, config: SourceConfig, movieId: String): Result<MovieDetail> =
        runCatchingSource { XtreamMappers.toMovieDetail(sourceId, movieId, api.vodInfo(xtream(config), movieId)) }

    override suspend fun fetchSeriesDetail(sourceId: Long, config: SourceConfig, seriesId: String): Result<SeriesDetail> =
        runCatchingSource { XtreamMappers.toSeriesDetail(sourceId, seriesId, api.seriesInfo(xtream(config), seriesId)) }

    override fun streamUrl(config: SourceConfig, ref: PlayableRef): String = XtreamUrls.stream(xtream(config), ref)

    private inline fun <T> runCatchingSource(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: SourceException) {
        Result.failure(e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(SourceException(e.message ?: "Unexpected error", e))
    }
}
