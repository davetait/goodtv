package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Season
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceInfo

/** DTO → domain. Entries without an id are dropped rather than failing the whole catalog. */
object XtreamMappers {
    fun toSourceInfo(auth: XtAuthResponse): SourceInfo {
        val u = auth.user_info
        return SourceInfo(
            status = u?.status ?: "Unknown",
            expiresAt = u?.exp_date?.let { it * 1000 },
            maxConnections = u?.max_connections,
            allowedOutputFormats = u?.allowed_output_formats ?: emptyList(),
        )
    }

    fun categories(sourceId: Long, kind: ContentKind, list: List<XtCategory>): List<Category> =
        list.mapNotNull { c -> c.category_id?.let { Category(sourceId, kind, it, c.category_name ?: it) } }

    fun live(sourceId: Long, list: List<XtLiveStream>): List<LiveChannel> =
        list.mapNotNull { s ->
            s.stream_id?.let { LiveChannel(sourceId, it, s.category_id, s.name ?: it, s.stream_icon, s.num) }
        }

    fun movies(sourceId: Long, list: List<XtVodStream>): List<Movie> =
        list.mapNotNull { s ->
            s.stream_id?.let {
                Movie(sourceId, it, s.category_id, s.name ?: it, s.stream_icon, s.container_extension ?: "mp4", s.rating, s.added)
            }
        }

    fun series(sourceId: Long, list: List<XtSeries>): List<Series> =
        list.mapNotNull { s ->
            s.series_id?.let { Series(sourceId, it, s.category_id, s.name ?: it, s.cover, s.plot, s.rating) }
        }

    fun toCatalog(
        sourceId: Long,
        liveCats: List<XtCategory>, vodCats: List<XtCategory>, seriesCats: List<XtCategory>,
        liveStreams: List<XtLiveStream>, vodStreams: List<XtVodStream>, seriesList: List<XtSeries>,
    ): Catalog = Catalog(
        categories = categories(sourceId, ContentKind.LIVE, liveCats) +
            categories(sourceId, ContentKind.MOVIE, vodCats) +
            categories(sourceId, ContentKind.SERIES, seriesCats),
        live = live(sourceId, liveStreams),
        movies = movies(sourceId, vodStreams),
        series = series(sourceId, seriesList),
    )

    fun toMovieDetail(sourceId: Long, movieId: String, r: XtVodInfoResponse): MovieDetail {
        val d = r.movie_data
        val i = r.info
        val movie = Movie(
            sourceId = sourceId,
            id = d?.stream_id ?: movieId,
            categoryId = d?.category_id,
            name = d?.name ?: movieId,
            posterUrl = i?.movie_image,
            containerExtension = d?.container_extension ?: "mp4",
            rating = i?.rating,
            addedAt = null,
        )
        return MovieDetail(movie, i?.plot, i?.genre, i?.cast, i?.director, i?.releasedate, i?.duration_secs, i?.backdrop_path?.firstOrNull())
    }

    fun toSeriesDetail(sourceId: Long, seriesId: String, r: XtSeriesInfoResponse): SeriesDetail {
        val i = r.info
        val series = Series(sourceId, seriesId, i?.category_id, i?.name ?: seriesId, i?.cover, i?.plot, i?.rating)
        val seasons = r.seasons.mapNotNull { s -> s.season_number?.let { Season(it, s.name ?: "Season $it", s.cover) } }
        val episodes = r.episodes.mapNotNull { e ->
            e.id?.let {
                Episode(
                    sourceId = sourceId, seriesId = seriesId, id = it,
                    season = e.season ?: 1, number = e.episode_num ?: 0, title = e.title ?: "Episode ${e.episode_num ?: ""}".trim(),
                    containerExtension = e.container_extension ?: "mp4",
                    durationSecs = e.info?.duration_secs, plot = e.info?.plot, imageUrl = e.info?.movie_image,
                )
            }
        }
        return SeriesDetail(series, i?.plot, i?.genre, i?.cast, i?.releaseDate, i?.backdrop_path?.firstOrNull(), seasons, episodes)
    }
}
