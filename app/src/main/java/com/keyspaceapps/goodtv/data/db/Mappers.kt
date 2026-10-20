package com.keyspaceapps.goodtv.data.db

import com.keyspaceapps.goodtv.data.source.SourceConfigJson
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Season
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.domain.SourceSummary
import kotlinx.serialization.json.Json

internal fun SourceEntity.toRecord(json: Json) = SourceRecord(
    id = id, type = SourceType.valueOf(type), nickname = nickname,
    config = SourceConfigJson.decode(json, configJson), enabled = enabled, lastSyncAt = lastSyncAt, lastError = lastError,
)

internal fun SourceRecord.toEntity(json: Json, createdAt: Long) = SourceEntity(
    id = id, type = type.name, nickname = nickname, configJson = SourceConfigJson.encode(json, config),
    enabled = enabled, lastSyncAt = lastSyncAt, lastError = lastError, createdAt = createdAt,
)

internal fun SummaryRow.toSummary() = SourceSummary(id, nickname, SourceType.valueOf(type), liveCount, movieCount, seriesCount, lastSyncAt, lastError, hiddenCount)

internal fun CategoryRow.toDomain() = CategoryWithSource(Category(sourceId, ContentKind.valueOf(kind), id, name), sourceNickname)
internal fun CategoryEntity.toDomain() = Category(sourceId, ContentKind.valueOf(kind), id, name)

internal fun Category.toEntity() = CategoryEntity(sourceId, kind.name, id, name)
internal fun LiveChannel.toEntity() = LiveChannelEntity(sourceId, id, categoryId, name, logoUrl, number)
internal fun LiveChannelEntity.toDomain() = LiveChannel(sourceId, id, categoryId, name, logoUrl, number)
internal fun Movie.toEntity() = MovieEntity(sourceId, id, categoryId, name, posterUrl, containerExtension, rating, addedAt)
internal fun MovieEntity.toDomain() = Movie(sourceId, id, categoryId, name, posterUrl, containerExtension, rating, addedAt)
internal fun Series.toEntity() = SeriesEntity(sourceId, id, categoryId, name, coverUrl, plot, rating)
internal fun SeriesEntity.toDomain() = Series(sourceId, id, categoryId, name, coverUrl, plot, rating)

internal fun MovieDetail.toEntity() = MovieDetailEntity(
    movie.sourceId, movie.id, movie.categoryId, movie.name, movie.posterUrl, movie.containerExtension, movie.rating, movie.addedAt,
    plot, genre, cast, director, releaseDate, durationSecs, backdropUrl,
)

internal fun MovieDetailEntity.toDomain() = MovieDetail(
    Movie(sourceId, movieId, categoryId, name, posterUrl, containerExtension, rating, addedAt),
    plot, genre, cast, director, releaseDate, durationSecs, backdropUrl,
)

internal fun SeriesDetail.toInfoEntity() = SeriesInfoEntity(
    series.sourceId, series.id, series.categoryId, series.name, series.coverUrl, series.plot, series.rating,
    plot, genre, cast, releaseDate, backdropUrl,
)

internal fun Season.toEntity(sourceId: Long, seriesId: String) = SeasonEntity(sourceId, seriesId, number, name, coverUrl)
internal fun Episode.toEntity() = EpisodeEntity(sourceId, id, seriesId, season, number, title, containerExtension, durationSecs, plot, imageUrl)
internal fun EpisodeEntity.toDomain() = Episode(sourceId, seriesId, id, season, number, title, containerExtension, durationSecs, plot, imageUrl)

internal fun SeriesInfoEntity.toDomain(seasons: List<SeasonEntity>, episodes: List<EpisodeEntity>) = SeriesDetail(
    Series(sourceId, seriesId, categoryId, name, coverUrl, seriesPlot, rating),
    plot, genre, cast, releaseDate, backdropUrl,
    seasons.map { Season(it.number, it.name, it.coverUrl) },
    episodes.map { it.toDomain() },
)
