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

    @Query("SELECT * FROM live_channels WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY number, COALESCE(title, name), language")
    fun observeLive(sourceId: Long, categoryId: String): Flow<List<LiveChannelEntity>>

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY addedAt DESC, COALESCE(title, name), language LIMIT :limit")
    fun observeMovies(sourceId: Long, categoryId: String, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY COALESCE(title, name), language LIMIT :limit")
    fun observeSeries(sourceId: Long, categoryId: String, limit: Int): Flow<List<SeriesEntity>>

    @Query(
        """SELECT m.* FROM movies m JOIN sources s ON s.id = m.sourceId
           WHERE s.enabled = 1 AND m.categoryId IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = m.sourceId AND h.kind = 'MOVIE' AND h.categoryId = m.categoryId)
           ORDER BY m.sourceId, m.categoryId, m.addedAt DESC, COALESCE(m.title, m.name), m.language""",
    )
    fun observeEnabledMovies(): Flow<List<MovieEntity>>

    @Query(
        """SELECT x.* FROM series x JOIN sources s ON s.id = x.sourceId
           WHERE s.enabled = 1 AND x.categoryId IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = x.sourceId AND h.kind = 'SERIES' AND h.categoryId = x.categoryId)
           ORDER BY x.sourceId, x.categoryId, COALESCE(x.title, x.name), x.language""",
    )
    fun observeEnabledSeries(): Flow<List<SeriesEntity>>

    // Search: clean title contains [pattern] (already LIKE-escaped with backslash), titles starting with it first.
    // Enabled sources only; items in hidden categories are left out, uncategorised ones are kept.
    @Query(
        """SELECT c.* FROM live_channels c JOIN sources s ON s.id = c.sourceId
           WHERE s.enabled = 1 AND COALESCE(c.title, c.name) LIKE '%' || :pattern || '%' ESCAPE '\'
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = c.sourceId AND h.kind = 'LIVE' AND h.categoryId = c.categoryId)
           ORDER BY CASE WHEN COALESCE(c.title, c.name) LIKE :pattern || '%' ESCAPE '\' THEN 0 ELSE 1 END, COALESCE(c.title, c.name), c.language
           LIMIT :limit""",
    )
    fun searchLive(pattern: String, limit: Int): Flow<List<LiveChannelEntity>>

    @Query(
        """SELECT m.* FROM movies m JOIN sources s ON s.id = m.sourceId
           WHERE s.enabled = 1 AND COALESCE(m.title, m.name) LIKE '%' || :pattern || '%' ESCAPE '\'
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = m.sourceId AND h.kind = 'MOVIE' AND h.categoryId = m.categoryId)
           ORDER BY CASE WHEN COALESCE(m.title, m.name) LIKE :pattern || '%' ESCAPE '\' THEN 0 ELSE 1 END, COALESCE(m.title, m.name), m.language
           LIMIT :limit""",
    )
    fun searchMovies(pattern: String, limit: Int): Flow<List<MovieEntity>>

    @Query(
        """SELECT x.* FROM series x JOIN sources s ON s.id = x.sourceId
           WHERE s.enabled = 1 AND COALESCE(x.title, x.name) LIKE '%' || :pattern || '%' ESCAPE '\'
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = x.sourceId AND h.kind = 'SERIES' AND h.categoryId = x.categoryId)
           ORDER BY CASE WHEN COALESCE(x.title, x.name) LIKE :pattern || '%' ESCAPE '\' THEN 0 ELSE 1 END, COALESCE(x.title, x.name), x.language
           LIMIT :limit""",
    )
    fun searchSeries(pattern: String, limit: Int): Flow<List<SeriesEntity>>

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

    /** Hidden categories of one kind that still exist, across enabled sources (drives the "everything hidden" state). */
    @Query(
        """SELECT COUNT(*) FROM hidden_categories h
           JOIN sources s ON s.id = h.sourceId
           JOIN categories c ON c.sourceId = h.sourceId AND c.kind = h.kind AND c.id = h.categoryId
           WHERE s.enabled = 1 AND h.kind = :kind""",
    )
    fun observeHiddenCount(kind: String): Flow<Int>

    /** Drops hidden rows whose category no longer exists for this source and kind. */
    @Query(
        """DELETE FROM hidden_categories WHERE sourceId = :sourceId AND kind = :kind
           AND categoryId NOT IN (SELECT id FROM categories WHERE sourceId = :sourceId AND kind = :kind)""",
    )
    suspend fun pruneMissing(sourceId: Long, kind: String)
}

@Dao
interface TmdbDao {
    @Query("SELECT * FROM tmdb_meta WHERE kind = :kind AND tmdbId = :tmdbId")
    suspend fun meta(kind: String, tmdbId: Long): TmdbMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMeta(row: TmdbMetaEntity)

    @Query("SELECT * FROM tmdb_title_match WHERE kind = :kind AND queryKey = :queryKey")
    suspend fun titleMatch(kind: String, queryKey: String): TmdbTitleMatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTitleMatch(row: TmdbTitleMatchEntity)

    /** Drops cache rows fetched before [before], keeping the cache inside TMDB's 6-month limit. */
    @Query("DELETE FROM tmdb_meta WHERE fetchedAt < :before") suspend fun pruneMeta(before: Long)
    @Query("DELETE FROM tmdb_title_match WHERE fetchedAt < :before") suspend fun pruneTitleMatches(before: Long)
}

data class GenreCountRow(val genre: String, val count: Int)

@Dao
interface GenreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSeriesGenres(rows: List<SeriesGenreEntity>)
    @Query("DELETE FROM series_genres WHERE sourceId = :sourceId") suspend fun deleteSeriesGenres(sourceId: Long)

    // Genre browsing: enabled sources only, hidden categories left out, newest first.
    @Query(
        """SELECT m.* FROM movies m JOIN sources s ON s.id = m.sourceId
           WHERE s.enabled = 1 AND m.tmdbId IN (SELECT tmdbId FROM tmdb_genres WHERE kind = 'MOVIE' AND genre = :genre)
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = m.sourceId AND h.kind = 'MOVIE' AND h.categoryId = m.categoryId)
           ORDER BY m.addedAt DESC, COALESCE(m.title, m.name), m.language LIMIT :limit""",
    )
    fun observeMovies(genre: String, limit: Int): Flow<List<MovieEntity>>

    @Query(
        """SELECT x.* FROM series x JOIN sources s ON s.id = x.sourceId
           WHERE s.enabled = 1
             AND EXISTS (SELECT 1 FROM series_genres g WHERE g.sourceId = x.sourceId AND g.seriesId = x.id AND g.genre = :genre)
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = x.sourceId AND h.kind = 'SERIES' AND h.categoryId = x.categoryId)
           ORDER BY COALESCE(x.title, x.name), x.language LIMIT :limit""",
    )
    fun observeSeries(genre: String, limit: Int): Flow<List<SeriesEntity>>

    @Query(
        """SELECT g.genre AS genre, COUNT(*) AS count FROM movies m JOIN sources s ON s.id = m.sourceId
           JOIN tmdb_genres g ON g.kind = 'MOVIE' AND g.tmdbId = m.tmdbId
           WHERE s.enabled = 1
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = m.sourceId AND h.kind = 'MOVIE' AND h.categoryId = m.categoryId)
           GROUP BY g.genre""",
    )
    fun observeMovieCounts(): Flow<List<GenreCountRow>>

    @Query(
        """SELECT g.genre AS genre, COUNT(*) AS count FROM series x JOIN sources s ON s.id = x.sourceId
           JOIN series_genres g ON g.sourceId = x.sourceId AND g.seriesId = x.id
           WHERE s.enabled = 1
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = x.sourceId AND h.kind = 'SERIES' AND h.categoryId = x.categoryId)
           GROUP BY g.genre""",
    )
    fun observeSeriesCounts(): Flow<List<GenreCountRow>>

    /** Distinct TMDB ids of enabled movies whose genres were never fetched, or not since [before]. */
    @Query(
        """SELECT DISTINCT m.tmdbId FROM movies m JOIN sources s ON s.id = m.sourceId
           WHERE s.enabled = 1 AND m.tmdbId IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM tmdb_genre_status st WHERE st.kind = 'MOVIE' AND st.tmdbId = m.tmdbId AND st.fetchedAt >= :before)""",
    )
    suspend fun pendingMovieTmdbIds(before: Long): List<Long>

    @Query("SELECT COUNT(DISTINCT m.tmdbId) FROM movies m JOIN sources s ON s.id = m.sourceId WHERE s.enabled = 1 AND m.tmdbId IS NOT NULL")
    suspend fun movieTmdbIdCount(): Int

    @Query("DELETE FROM tmdb_genres WHERE kind = :kind AND tmdbId IN (:ids)") suspend fun deleteTmdbGenres(kind: String, ids: List<Long>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTmdbGenres(rows: List<TmdbGenreEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTmdbGenreStatus(rows: List<TmdbGenreStatusEntity>)
}
