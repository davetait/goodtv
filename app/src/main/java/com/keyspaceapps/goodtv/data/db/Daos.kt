package com.keyspaceapps.goodtv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY id") fun observeAll(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM sources ORDER BY id") suspend fun getAll(): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun getById(id: Long): SourceEntity?
    @Insert suspend fun insert(entity: SourceEntity): Long
    @Update suspend fun update(entity: SourceEntity)
    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteById(id: Long)

    @Query("UPDATE sources SET lastSyncAt = COALESCE(:syncedAt, lastSyncAt), lastError = :error WHERE id = :id")
    suspend fun markSync(id: Long, syncedAt: Long?, error: String?)

    @Query(
        """SELECT s.id, s.nickname, s.type, s.lastSyncAt, s.lastError,
           (SELECT COUNT(*) FROM live_channels l WHERE l.sourceId = s.id) AS liveCount,
           (SELECT COUNT(*) FROM movies m WHERE m.sourceId = s.id) AS movieCount,
           (SELECT COUNT(*) FROM series x WHERE x.sourceId = s.id) AS seriesCount,
           (SELECT COUNT(*) FROM hidden_categories h WHERE h.sourceId = s.id) AS hiddenCount
           FROM sources s ORDER BY s.id""",
    )
    fun observeSummaries(): Flow<List<SummaryRow>>
}

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCategories(rows: List<CategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLive(rows: List<LiveChannelEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMovies(rows: List<MovieEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSeries(rows: List<SeriesEntity>)

    @Query("DELETE FROM categories WHERE sourceId = :sourceId") suspend fun deleteCategories(sourceId: Long)
    @Query("DELETE FROM live_channels WHERE sourceId = :sourceId") suspend fun deleteLive(sourceId: Long)
    @Query("DELETE FROM movies WHERE sourceId = :sourceId") suspend fun deleteMovies(sourceId: Long)
    @Query("DELETE FROM series WHERE sourceId = :sourceId") suspend fun deleteSeries(sourceId: Long)
    @Query("DELETE FROM movie_details WHERE sourceId = :sourceId") suspend fun deleteMovieDetails(sourceId: Long)
    @Query("DELETE FROM series_info WHERE sourceId = :sourceId") suspend fun deleteSeriesInfo(sourceId: Long)
    @Query("DELETE FROM seasons WHERE sourceId = :sourceId") suspend fun deleteSeasons(sourceId: Long)
    @Query("DELETE FROM episodes WHERE sourceId = :sourceId") suspend fun deleteEpisodes(sourceId: Long)

    @Query(
        """SELECT c.sourceId, c.kind, c.id, c.name, s.nickname AS sourceNickname
           FROM categories c JOIN sources s ON s.id = c.sourceId
           WHERE c.kind = :kind AND s.enabled = 1
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = c.sourceId AND h.kind = c.kind AND h.categoryId = c.id)
           ORDER BY c.sourceId, c.name""",
    )
    fun observeCategories(kind: String): Flow<List<CategoryRow>>

    @Query("SELECT * FROM categories WHERE sourceId = :sourceId AND kind = :kind ORDER BY name")
    fun observeAllCategories(sourceId: Long, kind: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM live_channels WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY number, name")
    fun observeLive(sourceId: Long, categoryId: String): Flow<List<LiveChannelEntity>>

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY addedAt DESC, name LIMIT :limit")
    fun observeMovies(sourceId: Long, categoryId: String, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY name LIMIT :limit")
    fun observeSeries(sourceId: Long, categoryId: String, limit: Int): Flow<List<SeriesEntity>>

    @Query(
        """SELECT m.* FROM movies m JOIN sources s ON s.id = m.sourceId
           WHERE s.enabled = 1 AND m.categoryId IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = m.sourceId AND h.kind = 'MOVIE' AND h.categoryId = m.categoryId)
           ORDER BY m.sourceId, m.categoryId, m.addedAt DESC, m.name""",
    )
    fun observeEnabledMovies(): Flow<List<MovieEntity>>

    @Query(
        """SELECT x.* FROM series x JOIN sources s ON s.id = x.sourceId
           WHERE s.enabled = 1 AND x.categoryId IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = x.sourceId AND h.kind = 'SERIES' AND h.categoryId = x.categoryId)
           ORDER BY x.sourceId, x.categoryId, x.name""",
    )
    fun observeEnabledSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND id = :id") suspend fun getMovie(sourceId: Long, id: String): MovieEntity?
    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND id = :id") suspend fun getSeries(sourceId: Long, id: String): SeriesEntity?

    @Query("SELECT * FROM movie_details WHERE sourceId = :sourceId AND movieId = :id") suspend fun getMovieDetail(sourceId: Long, id: String): MovieDetailEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMovieDetail(row: MovieDetailEntity)

    @Query("SELECT * FROM series_info WHERE sourceId = :sourceId AND seriesId = :id") suspend fun getSeriesInfo(sourceId: Long, id: String): SeriesInfoEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSeriesInfo(row: SeriesInfoEntity)
    @Query("SELECT * FROM seasons WHERE sourceId = :sourceId AND seriesId = :seriesId ORDER BY number") suspend fun getSeasons(sourceId: Long, seriesId: String): List<SeasonEntity>
    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId ORDER BY season, number") suspend fun getEpisodes(sourceId: Long, seriesId: String): List<EpisodeEntity>
    @Query("DELETE FROM seasons WHERE sourceId = :sourceId AND seriesId = :seriesId") suspend fun deleteSeasonsOf(sourceId: Long, seriesId: String)
    @Query("DELETE FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId") suspend fun deleteEpisodesOf(sourceId: Long, seriesId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSeasons(rows: List<SeasonEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEpisodes(rows: List<EpisodeEntity>)
}

@Dao
interface HiddenCategoryDao {
    @Query("SELECT * FROM hidden_categories WHERE sourceId = :sourceId")
    fun observeForSource(sourceId: Long): Flow<List<HiddenCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(rows: List<HiddenCategoryEntity>)

    @Query("DELETE FROM hidden_categories WHERE sourceId = :sourceId AND kind = :kind AND categoryId IN (:ids)")
    suspend fun delete(sourceId: Long, kind: String, ids: List<String>)

    @Query("DELETE FROM hidden_categories WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun deleteKind(sourceId: Long, kind: String)

    /** Drops hidden rows whose category no longer exists for this source and kind. */
    @Query(
        """DELETE FROM hidden_categories WHERE sourceId = :sourceId AND kind = :kind
           AND categoryId NOT IN (SELECT id FROM categories WHERE sourceId = :sourceId AND kind = :kind)""",
    )
    suspend fun pruneMissing(sourceId: Long, kind: String)
}
