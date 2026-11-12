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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class XtreamChannelSource(private val api: XtreamApi) : ChannelSource {
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
     * All six calls start at once. Each list is converted on Dispatchers.Default as soon as it arrives and handed
     * to [writer], so it can be dropped before the slower lists finish. Each kind's categories are
     * written even when empty. The first failure cancels the other calls.
     */
    override suspend fun streamCatalog(sourceId: Long, config: SourceConfig, writer: CatalogWriter): Result<Unit> = runCatchingSource {
        val cfg = xtream(config)
        coroutineScope {
            launch { writer.categories(ContentKind.LIVE, mapped { XtreamMappers.categories(sourceId, ContentKind.LIVE, api.liveCategories(cfg)) }) }
            launch { writer.categories(ContentKind.MOVIE, mapped { XtreamMappers.categories(sourceId, ContentKind.MOVIE, api.vodCategories(cfg)) }) }
            launch { writer.categories(ContentKind.SERIES, mapped { XtreamMappers.categories(sourceId, ContentKind.SERIES, api.seriesCategories(cfg)) }) }
            launch { writer.live(mapped { XtreamMappers.live(sourceId, api.liveStreams(cfg)) }) }
            launch { writer.movies(mapped { XtreamMappers.movies(sourceId, api.vodStreams(cfg)) }) }
            launch { writer.series(mapped { XtreamMappers.series(sourceId, api.series(cfg)) }) }
        }
    }

    /** Downloads and converts inside one block, so the provider's raw list is garbage once converted. */
    private suspend fun <T> mapped(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

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
