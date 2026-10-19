package com.keyspaceapps.goodtv.data.source

import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.PlayableRef
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceInfo

/** A provider of channels/movies/series. One implementation per [SourceType]. */
interface ChannelSource {
    val type: SourceType
    suspend fun validate(config: SourceConfig): Result<SourceInfo>
    suspend fun fetchCatalog(sourceId: Long, config: SourceConfig): Result<Catalog>
    suspend fun fetchMovieDetail(sourceId: Long, config: SourceConfig, movieId: String): Result<MovieDetail>
    suspend fun fetchSeriesDetail(sourceId: Long, config: SourceConfig, seriesId: String): Result<SeriesDetail>
    fun streamUrl(config: SourceConfig, ref: PlayableRef): String
}

/** Thrown by sources with a message fit to show on screen. */
class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
