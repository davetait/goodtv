package com.keyspaceapps.goodtv.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val nickname: String,
    val configJson: String,
    val enabled: Boolean,
    val lastSyncAt: Long?,
    val lastError: String?,
    val createdAt: Long,
)

private const val SOURCES = "sources"

@Entity(
    tableName = "categories",
    primaryKeys = ["sourceId", "kind", "id"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId")],
)
data class CategoryEntity(val sourceId: Long, val kind: String, val id: String, val name: String)

@Entity(
    tableName = "live_channels",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId", "categoryId")],
)
data class LiveChannelEntity(
    val sourceId: Long, val id: String, val categoryId: String?, val name: String, val logoUrl: String?, val number: Int?,
)

@Entity(
    tableName = "movies",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId", "categoryId")],
)
data class MovieEntity(
    val sourceId: Long, val id: String, val categoryId: String?, val name: String, val posterUrl: String?,
    val containerExtension: String, val rating: Double?, val addedAt: Long?,
)

@Entity(
    tableName = "series",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId", "categoryId")],
)
data class SeriesEntity(
    val sourceId: Long, val id: String, val categoryId: String?, val name: String, val coverUrl: String?, val plot: String?, val rating: Double?,
)

/** Full movie detail snapshot (includes the movie fields so the detail survives a catalog refresh). */
@Entity(
    tableName = "movie_details",
    primaryKeys = ["sourceId", "movieId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId")],
)
data class MovieDetailEntity(
    val sourceId: Long, val movieId: String, val categoryId: String?, val name: String, val posterUrl: String?,
    val containerExtension: String, val rating: Double?, val addedAt: Long?,
    val plot: String?, val genre: String?, val cast: String?, val director: String?, val releaseDate: String?,
    val durationSecs: Int?, val backdropUrl: String?,
)

@Entity(
    tableName = "series_info",
    primaryKeys = ["sourceId", "seriesId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId")],
)
data class SeriesInfoEntity(
    val sourceId: Long, val seriesId: String, val categoryId: String?, val name: String, val coverUrl: String?,
    val seriesPlot: String?, val rating: Double?,
    val plot: String?, val genre: String?, val cast: String?, val releaseDate: String?, val backdropUrl: String?,
)

@Entity(
    tableName = "seasons",
    primaryKeys = ["sourceId", "seriesId", "number"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId", "seriesId")],
)
data class SeasonEntity(val sourceId: Long, val seriesId: String, val number: Int, val name: String, val coverUrl: String?)

@Entity(
    tableName = "episodes",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId", "seriesId")],
)
data class EpisodeEntity(
    val sourceId: Long, val id: String, val seriesId: String, val season: Int, val number: Int, val title: String,
    val containerExtension: String, val durationSecs: Int?, val plot: String?, val imageUrl: String?,
)

/** Only switched-off categories are stored; "everything shown" is the empty table. FK to sources only. */
@Entity(
    tableName = "hidden_categories",
    primaryKeys = ["sourceId", "kind", "categoryId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId")],
)
data class HiddenCategoryEntity(val sourceId: Long, val kind: String, val categoryId: String)

/** Query projections. */
data class SummaryRow(
    val id: Long, val nickname: String, val type: String, val lastSyncAt: Long?, val lastError: String?,
    val liveCount: Int, val movieCount: Int, val seriesCount: Int, val hiddenCount: Int,
)

data class CategoryRow(val sourceId: Long, val kind: String, val id: String, val name: String, val sourceNickname: String)
