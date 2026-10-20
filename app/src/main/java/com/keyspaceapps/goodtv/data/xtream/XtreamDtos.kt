@file:Suppress("PropertyName")

package com.keyspaceapps.goodtv.data.xtream

import kotlinx.serialization.Serializable

// Field names mirror the Xtream Codes player_api.php JSON. All flexible-typed fields default to
// null so absent keys parse (Json has explicitNulls = false).

@Serializable
data class XtAuthResponse(val user_info: XtUserInfo? = null, val server_info: XtServerInfo? = null)

@Serializable
data class XtUserInfo(
    @Serializable(FlexIntSerializer::class) val auth: Int? = null,
    @Serializable(FlexStringSerializer::class) val status: String? = null,
    @Serializable(FlexLongSerializer::class) val exp_date: Long? = null,
    @Serializable(FlexIntSerializer::class) val max_connections: Int? = null,
    @Serializable(FlexStringListSerializer::class) val allowed_output_formats: List<String> = emptyList(),
    @Serializable(FlexStringSerializer::class) val message: String? = null,
)

@Serializable
data class XtServerInfo(
    @Serializable(FlexStringSerializer::class) val url: String? = null,
    @Serializable(FlexStringSerializer::class) val port: String? = null,
    @Serializable(FlexStringSerializer::class) val https_port: String? = null,
    @Serializable(FlexStringSerializer::class) val server_protocol: String? = null,
)

@Serializable
data class XtCategory(
    @Serializable(FlexStringSerializer::class) val category_id: String? = null,
    @Serializable(FlexStringSerializer::class) val category_name: String? = null,
    @Serializable(FlexIntSerializer::class) val parent_id: Int? = null,
)

@Serializable
data class XtLiveStream(
    @Serializable(FlexStringSerializer::class) val stream_id: String? = null,
    @Serializable(FlexStringSerializer::class) val name: String? = null,
    @Serializable(FlexStringSerializer::class) val stream_icon: String? = null,
    @Serializable(FlexStringSerializer::class) val category_id: String? = null,
    @Serializable(FlexStringSerializer::class) val epg_channel_id: String? = null,
    @Serializable(FlexIntSerializer::class) val num: Int? = null,
)

@Serializable
data class XtVodStream(
    @Serializable(FlexStringSerializer::class) val stream_id: String? = null,
    @Serializable(FlexStringSerializer::class) val name: String? = null,
    @Serializable(FlexStringSerializer::class) val stream_icon: String? = null,
    @Serializable(FlexStringSerializer::class) val category_id: String? = null,
    @Serializable(FlexStringSerializer::class) val container_extension: String? = null,
    @Serializable(FlexDoubleSerializer::class) val rating: Double? = null,
    @Serializable(FlexLongSerializer::class) val added: Long? = null,
    /** TMDB movie id as sent: number, string, "", 0, "43332-" or even an IMDb "tt…" id. See [tmdbIdOf]. */
    @Serializable(FlexStringSerializer::class) val tmdb: String? = null,
)

@Serializable
data class XtVodInfoResponse(val info: XtVodInfo? = null, val movie_data: XtVodMovieData? = null)

@Serializable
data class XtVodInfo(
    @Serializable(FlexStringSerializer::class) val movie_image: String? = null,
    @Serializable(FlexStringSerializer::class) val plot: String? = null,
    @Serializable(FlexStringSerializer::class) val genre: String? = null,
    @Serializable(FlexStringSerializer::class) val cast: String? = null,
    @Serializable(FlexStringSerializer::class) val director: String? = null,
    @Serializable(FlexStringSerializer::class) val releasedate: String? = null,
    @Serializable(FlexIntSerializer::class) val duration_secs: Int? = null,
    @Serializable(FlexDoubleSerializer::class) val rating: Double? = null,
    @Serializable(FlexStringListSerializer::class) val backdrop_path: List<String> = emptyList(),
)

@Serializable
data class XtVodMovieData(
    @Serializable(FlexStringSerializer::class) val stream_id: String? = null,
    @Serializable(FlexStringSerializer::class) val name: String? = null,
    @Serializable(FlexStringSerializer::class) val container_extension: String? = null,
    @Serializable(FlexStringSerializer::class) val category_id: String? = null,
)

@Serializable
data class XtSeries(
    @Serializable(FlexStringSerializer::class) val series_id: String? = null,
    @Serializable(FlexStringSerializer::class) val name: String? = null,
    @Serializable(FlexStringSerializer::class) val cover: String? = null,
    @Serializable(FlexStringSerializer::class) val plot: String? = null,
    @Serializable(FlexStringSerializer::class) val cast: String? = null,
    @Serializable(FlexStringSerializer::class) val genre: String? = null,
    @Serializable(FlexStringSerializer::class) val releaseDate: String? = null,
    @Serializable(FlexDoubleSerializer::class) val rating: Double? = null,
    @Serializable(FlexStringSerializer::class) val category_id: String? = null,
    @Serializable(FlexStringListSerializer::class) val backdrop_path: List<String> = emptyList(),
    /** TMDB TV series id as sent; see [tmdbIdOf]. */
    @Serializable(FlexStringSerializer::class) val tmdb: String? = null,
)

@Serializable
data class XtSeriesInfoResponse(
    val info: XtSeriesInfo? = null,
    val seasons: List<XtSeason> = emptyList(),
    @Serializable(EpisodesSerializer::class) val episodes: List<XtEpisode> = emptyList(),
)

@Serializable
data class XtSeriesInfo(
    @Serializable(FlexStringSerializer::class) val name: String? = null,
    @Serializable(FlexStringSerializer::class) val cover: String? = null,
    @Serializable(FlexStringSerializer::class) val plot: String? = null,
    @Serializable(FlexStringSerializer::class) val cast: String? = null,
    @Serializable(FlexStringSerializer::class) val genre: String? = null,
    @Serializable(FlexStringSerializer::class) val releaseDate: String? = null,
    @Serializable(FlexDoubleSerializer::class) val rating: Double? = null,
    @Serializable(FlexStringSerializer::class) val category_id: String? = null,
    @Serializable(FlexStringListSerializer::class) val backdrop_path: List<String> = emptyList(),
)

@Serializable
data class XtSeason(
    @Serializable(FlexIntSerializer::class) val season_number: Int? = null,
    @Serializable(FlexStringSerializer::class) val name: String? = null,
    @Serializable(FlexStringSerializer::class) val cover: String? = null,
)

@Serializable
data class XtEpisode(
    @Serializable(FlexStringSerializer::class) val id: String? = null,
    @Serializable(FlexIntSerializer::class) val episode_num: Int? = null,
    @Serializable(FlexStringSerializer::class) val title: String? = null,
    @Serializable(FlexStringSerializer::class) val container_extension: String? = null,
    @Serializable(FlexIntSerializer::class) val season: Int? = null,
    val info: XtEpisodeInfo? = null,
)

@Serializable
data class XtEpisodeInfo(
    @Serializable(FlexStringSerializer::class) val movie_image: String? = null,
    @Serializable(FlexStringSerializer::class) val plot: String? = null,
    @Serializable(FlexIntSerializer::class) val duration_secs: Int? = null,
)
