package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.source.ChannelSource
import com.keyspaceapps.goodtv.data.source.LiveCatalog
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.PlayableRef
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceInfo

/** Scripted ChannelSource for JVM tests. Results are looked up per sourceId; unknown ids fail. */
class FakeChannelSource : ChannelSource {
    override val type = SourceType.XTREAM

    var validateResult: Result<SourceInfo> = Result.success(SourceInfo("Active", null, 1, listOf("ts")))
    var validateCalls = 0
    val catalogResults = mutableMapOf<Long, suspend () -> Result<Catalog>>()
    val fetchCatalogCalls = mutableListOf<Long>()
    val movieDetailCalls = mutableListOf<String>()
    var movieDetail: MovieDetail? = null
    var seriesDetail: SeriesDetail? = null

    override suspend fun validate(config: SourceConfig): Result<SourceInfo> { validateCalls++; return validateResult }

    override suspend fun fetchCatalog(sourceId: Long, config: SourceConfig): Result<Catalog> {
        fetchCatalogCalls += sourceId
        return catalogResults[sourceId]?.invoke() ?: Result.failure(SourceException("No script for $sourceId"))
    }

    val liveResults = mutableMapOf<Long, suspend () -> Result<LiveCatalog>>()
    val fetchLiveCalls = mutableListOf<Long>()

    override suspend fun fetchLive(sourceId: Long, config: SourceConfig): Result<LiveCatalog> {
        fetchLiveCalls += sourceId
        return liveResults[sourceId]?.invoke() ?: Result.failure(SourceException("No live script for $sourceId"))
    }

    override suspend fun fetchMovieDetail(sourceId: Long, config: SourceConfig, movieId: String): Result<MovieDetail> {
        movieDetailCalls += movieId
        return movieDetail?.let { Result.success(it) } ?: Result.failure(SourceException("No detail"))
    }

    override suspend fun fetchSeriesDetail(sourceId: Long, config: SourceConfig, seriesId: String): Result<SeriesDetail> =
        seriesDetail?.let { Result.success(it) } ?: Result.failure(SourceException("No detail"))

    override fun streamUrl(config: SourceConfig, ref: PlayableRef): String = when (ref) {
        is PlayableRef.Live -> "fake://live/${ref.id}"
        is PlayableRef.Movie -> "fake://movie/${ref.id}.${ref.ext}"
        is PlayableRef.Episode -> "fake://episode/${ref.id}.${ref.ext}"
    }

    companion object {
        fun catalog(sourceId: Long, tag: String, live: Int = 2) = Catalog(
            categories = listOf(Category(sourceId, ContentKind.LIVE, "c1", "Sports $tag")),
            live = (1..live).map { LiveChannel(sourceId, "$tag$it", "c1", "Channel $tag $it", null, it) },
            movies = listOf(Movie(sourceId, "m$tag", "mc", "Movie $tag", null, "mkv", null, null)),
            series = emptyList(),
        )
    }
}
