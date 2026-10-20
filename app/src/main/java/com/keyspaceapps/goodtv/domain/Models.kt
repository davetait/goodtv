package com.keyspaceapps.goodtv.domain

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType

enum class ContentKind { LIVE, MOVIE, SERIES }

data class Category(val sourceId: Long, val kind: ContentKind, val id: String, val name: String)

data class LiveChannel(
    val sourceId: Long,
    val id: String,
    val categoryId: String?,
    val name: String,
    val logoUrl: String?,
    val number: Int?,
)

data class Movie(
    val sourceId: Long,
    val id: String,
    val categoryId: String?,
    val name: String,
    val posterUrl: String?,
    val containerExtension: String,
    val rating: Double?,
    val addedAt: Long?,
    /** themoviedb.org movie id when the provider supplies one. */
    val tmdbId: Long? = null,
)

data class Series(
    val sourceId: Long,
    val id: String,
    val categoryId: String?,
    val name: String,
    val coverUrl: String?,
    val plot: String?,
    val rating: Double?,
    /** themoviedb.org TV series id when the provider supplies one. */
    val tmdbId: Long? = null,
)

data class Catalog(
    val categories: List<Category>,
    val live: List<LiveChannel>,
    val movies: List<Movie>,
    val series: List<Series>,
)

data class MovieDetail(
    val movie: Movie,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseDate: String?,
    val durationSecs: Int?,
    val backdropUrl: String?,
)

data class Season(val number: Int, val name: String, val coverUrl: String?)

data class Episode(
    val sourceId: Long,
    val seriesId: String,
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val containerExtension: String,
    val durationSecs: Int?,
    val plot: String?,
    val imageUrl: String?,
)

data class SeriesDetail(
    val series: Series,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val releaseDate: String?,
    val backdropUrl: String?,
    val seasons: List<Season>,
    val episodes: List<Episode>,
)

/** Identifies something playable within one source; the source turns it into a URL. */
sealed interface PlayableRef {
    data class Live(val id: String) : PlayableRef
    data class Movie(val id: String, val ext: String) : PlayableRef
    data class Episode(val id: String, val ext: String) : PlayableRef
}

data class PlayableItem(val title: String, val url: String, val isLive: Boolean)

data class SourceInfo(
    val status: String,
    val expiresAt: Long?,
    val maxConnections: Int?,
    val allowedOutputFormats: List<String>,
)

data class SourceRecord(
    val id: Long,
    val type: SourceType,
    val nickname: String,
    val config: SourceConfig,
    val enabled: Boolean,
    val lastSyncAt: Long?,
    val lastError: String?,
)

data class SourceSummary(
    val id: Long,
    val nickname: String,
    val type: SourceType,
    val liveCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
    val lastSyncAt: Long?,
    val lastError: String?,
    val hiddenCount: Int = 0,
)

data class CategoryWithSource(val category: Category, val sourceNickname: String)
