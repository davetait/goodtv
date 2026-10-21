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
    val title: String? = null, val language: String? = null, val quality: String? = null,
)

@Entity(
    tableName = "movies",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId", "categoryId"), Index("tmdbId")],
)
data class MovieEntity(
    val sourceId: Long, val id: String, val categoryId: String?, val name: String, val posterUrl: String?,
    val containerExtension: String, val rating: Double?, val addedAt: Long?, val tmdbId: Long? = null,
    val title: String? = null, val language: String? = null, val quality: String? = null,
)

@Entity(
    tableName = "series",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId", "categoryId")],
)
data class SeriesEntity(
    val sourceId: Long, val id: String, val categoryId: String?, val name: String, val coverUrl: String?, val plot: String?, val rating: Double?,
    val tmdbId: Long? = null, val title: String? = null, val language: String? = null, val quality: String? = null,
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

/**
 * Cached TMDB details for one movie or TV series ([kind] is "MOVIE" or "TV"). Kept at most
 * [com.keyspaceapps.goodtv.data.tmdb.TmdbRepository.META_TTL_MS]; TMDB's terms cap caching at 6 months.
 */
@Entity(tableName = "tmdb_meta", primaryKeys = ["kind", "tmdbId"])
data class TmdbMetaEntity(
    val kind: String, val tmdbId: Long, val language: String, val title: String, val overview: String?,
    val rating: Double?, val voteCount: Int, val year: Int?, val genres: String, val runtimeMinutes: Int?,
    val posterUrl: String?, val backdropUrl: String?, val fetchedAt: Long,
)

/** Result of a title search for an item without a TMDB id. [tmdbId] null records "no confident match". */
@Entity(tableName = "tmdb_title_match", primaryKeys = ["kind", "queryKey"])
data class TmdbTitleMatchEntity(val kind: String, val queryKey: String, val tmdbId: Long?, val fetchedAt: Long)

/** A series' genres, mapped from the provider's genre text at sync ([com.keyspaceapps.goodtv.domain.Genre] names). */
@Entity(
    tableName = "series_genres",
    primaryKeys = ["sourceId", "seriesId", "genre"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("genre")],
)
data class SeriesGenreEntity(val sourceId: Long, val seriesId: String, val genre: String)

/**
 * TMDB genres per TMDB id ([kind] "MOVIE"). Keyed by TMDB id rather than catalog row, so they survive
 * re-syncs and one fetch covers every language copy of a film.
 */
@Entity(tableName = "tmdb_genres", primaryKeys = ["kind", "tmdbId", "genre"], indices = [Index("kind", "genre")])
data class TmdbGenreEntity(val kind: String, val tmdbId: Long, val genre: String)

/** When genres for a TMDB id were last fetched (also for ids TMDB had none for, so they aren't re-asked). */
@Entity(tableName = "tmdb_genre_status", primaryKeys = ["kind", "tmdbId"])
data class TmdbGenreStatusEntity(val kind: String, val tmdbId: Long, val fetchedAt: Long)

/** A ranked TMDB list (Home's Popular Movies) and when it was fetched; [listKey] names the query. */
@Entity(tableName = "tmdb_lists")
data class TmdbListEntity(@PrimaryKey val listKey: String, val fetchedAt: Long)

/** One entry of a [TmdbListEntity], [rank] 0 = most popular. */
@Entity(
    tableName = "tmdb_list_items",
    primaryKeys = ["listKey", "rank"],
    foreignKeys = [ForeignKey(entity = TmdbListEntity::class, parentColumns = ["listKey"], childColumns = ["listKey"], onDelete = ForeignKey.CASCADE)],
)
data class TmdbListItemEntity(val listKey: String, val rank: Int, val tmdbId: Long)

/** Where the user stopped in a movie or episode ([kind] "MOVIE"/"EPISODE"); kept across re-syncs. */
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["sourceId", "kind", "itemId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId", "seriesId"), Index("updatedAt")],
)
data class WatchProgressEntity(
    val sourceId: Long, val kind: String, val itemId: String, val seriesId: String?,
    val positionMs: Long, val durationMs: Long, val finished: Boolean, val updatedAt: Long,
    val title: String? = null, val ext: String? = null, val season: Int? = null, val episode: Int? = null,
)

/** A title saved to watch later ([kind] "MOVIE"/"SERIES"): the exact copy, kept across re-syncs, gone with its source. */
@Entity(
    tableName = "watch_list",
    primaryKeys = ["sourceId", "kind", "itemId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index("savedAt")],
)
data class WatchListEntity(val sourceId: Long, val kind: String, val itemId: String, val savedAt: Long)
