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
data class TmdbSearchPage<T>(val page: Int = 1, val results: List<T> = emptyList(), val total_results: Int = 0, val total_pages: Int = 1)

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

// People: credits for a title, a show's creators and aggregate cast, and a person's filmography.

@Serializable
data class TmdbCredits(val cast: List<TmdbCastCredit> = emptyList(), val crew: List<TmdbCrewCredit> = emptyList())

@Serializable
data class TmdbCastCredit(val id: Long, val name: String = "", val character: String? = null, val order: Int = Int.MAX_VALUE)

@Serializable
data class TmdbCrewCredit(val id: Long, val name: String = "", val job: String? = null, val department: String? = null)

@Serializable
data class TmdbNamed(val id: Long, val name: String = "")

/** `tv/{id}` with `append_to_response=aggregate_credits`: only the creators and the cast are read. */
@Serializable
data class TmdbTvPeople(
    val id: Long,
    val created_by: List<TmdbNamed> = emptyList(),
    val aggregate_credits: TmdbAggregateCredits? = null,
)

@Serializable
data class TmdbAggregateCredits(val cast: List<TmdbAggregateCast> = emptyList())

@Serializable
data class TmdbAggregateCast(val id: Long, val name: String = "", val order: Int = Int.MAX_VALUE, val roles: List<TmdbRole> = emptyList())

@Serializable
data class TmdbRole(val character: String? = null, val episode_count: Int = 0)

/** `person/{id}/combined_credits`. [TmdbPersonCast.media_type] is "movie" or "tv" (TMDB can also send others). */
@Serializable
data class TmdbPersonCredits(val cast: List<TmdbPersonCast> = emptyList(), val crew: List<TmdbPersonCrew> = emptyList())

@Serializable
data class TmdbPersonCast(
    val id: Long,
    val media_type: String = "",
    val character: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val episode_count: Int? = null,
    val genre_ids: List<Int> = emptyList(),
)

@Serializable
data class TmdbPersonCrew(
    val id: Long,
    val media_type: String = "",
    val job: String? = null,
    val department: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val episode_count: Int? = null,
)

@Serializable
data class TmdbPersonResult(
    val id: Long,
    val name: String = "",
    val known_for_department: String? = null,
    val popularity: Double? = null,
)

/** "2013-05-01" → 2013; "" or null → null. */
internal fun String?.tmdbYear(): Int? = this?.take(4)?.toIntOrNull()
