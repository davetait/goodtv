package com.keyspaceapps.goodtv.data.tmdb

import kotlinx.serialization.Serializable

// Wire shapes for the TMDB v3 endpoints we call. Only the fields we use; everything is optional
// because TMDB returns null paths and "" dates for sparse entries.

@Serializable
data class TmdbGenre(val id: Int = 0, val name: String = "")

@Serializable
data class TmdbMovieDetails(
    val id: Long,
    val title: String = "",
    val original_title: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val release_date: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val vote_average: Double? = null,
    val vote_count: Int = 0,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val imdb_id: String? = null,
)

@Serializable
data class TmdbTvDetails(
    val id: Long,
    val name: String = "",
    val original_name: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val first_air_date: String? = null,
    val episode_run_time: List<Int> = emptyList(),
    val number_of_seasons: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val vote_average: Double? = null,
    val vote_count: Int = 0,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
)

@Serializable
data class TmdbSearchPage<T>(val page: Int = 1, val results: List<T> = emptyList(), val total_results: Int = 0)

@Serializable
data class TmdbMovieResult(
    val id: Long,
    val title: String = "",
    val original_title: String? = null,
    val release_date: String? = null,
    val popularity: Double? = null,
)

@Serializable
data class TmdbTvResult(
    val id: Long,
    val name: String = "",
    val original_name: String? = null,
    val first_air_date: String? = null,
    val popularity: Double? = null,
)

/** "2013-05-01" → 2013; "" or null → null. */
internal fun String?.tmdbYear(): Int? = this?.take(4)?.toIntOrNull()
