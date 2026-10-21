package com.keyspaceapps.goodtv.data.xtream

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class XtreamChannelSource(private val api: XtreamApi) : ChannelSource {
    override val type = SourceType.XTREAM

    /** At most two list downloads in flight: bounds memory on huge catalogs and is gentler on panels that throttle. */
    private val concurrentCalls = Semaphore(2)

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
            val liveCats = async { concurrentCalls.withPermit { api.liveCategories(cfg) } }
            val vodCats = async { concurrentCalls.withPermit { api.vodCategories(cfg) } }
            val seriesCats = async { concurrentCalls.withPermit { api.seriesCategories(cfg) } }
            val live = async { concurrentCalls.withPermit { api.liveStreams(cfg) } }
            val vod = async { concurrentCalls.withPermit { api.vodStreams(cfg) } }
            val series = async { concurrentCalls.withPermit { api.series(cfg) } }
            XtreamMappers.toCatalog(
                sourceId,
                liveCats.await(), vodCats.await(), seriesCats.await(),
                live.await(), vod.await(), series.await(),
            )
        }
    }

    override suspend fun fetchLive(sourceId: Long, config: SourceConfig): Result<LiveCatalog> = runCatchingSource {
        val cfg = xtream(config)
        coroutineScope {
            val cats = async { concurrentCalls.withPermit { api.liveCategories(cfg) } }
            val live = async { concurrentCalls.withPermit { api.liveStreams(cfg) } }
            LiveCatalog(XtreamMappers.categories(sourceId, ContentKind.LIVE, cats.await()), XtreamMappers.live(sourceId, live.await()))
        }
    }

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
