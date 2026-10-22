package com.keyspaceapps.goodtv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND kind = :kind") suspend fun deleteCategoriesOfKind(sourceId: Long, kind: String)

    @Query(
        """SELECT l.*, c.name AS categoryName FROM live_channels l
           JOIN sources s ON s.id = l.sourceId AND s.enabled = 1
           JOIN categories c ON c.sourceId = l.sourceId AND c.kind = 'LIVE' AND c.id = l.categoryId
           WHERE NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = l.sourceId AND h.kind = 'LIVE' AND h.categoryId = l.categoryId)""",
    )
    fun observeSportsCandidates(): Flow<List<SportsCandidateRow>>
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

    // Search within a genre: the same matching and order as LibraryDao.searchMovies/searchSeries.
    @Query(
        """SELECT m.* FROM movies m JOIN sources s ON s.id = m.sourceId
           WHERE s.enabled = 1 AND COALESCE(m.title, m.name) LIKE '%' || :pattern || '%' ESCAPE '\'
             AND m.tmdbId IN (SELECT tmdbId FROM tmdb_genres WHERE kind = 'MOVIE' AND genre = :genre)
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = m.sourceId AND h.kind = 'MOVIE' AND h.categoryId = m.categoryId)
           ORDER BY CASE WHEN COALESCE(m.title, m.name) LIKE :pattern || '%' ESCAPE '\' THEN 0 ELSE 1 END, COALESCE(m.title, m.name), m.language
           LIMIT :limit""",
    )
    fun searchMovies(pattern: String, genre: String, limit: Int): Flow<List<MovieEntity>>

    @Query(
        """SELECT x.* FROM series x JOIN sources s ON s.id = x.sourceId
           WHERE s.enabled = 1 AND COALESCE(x.title, x.name) LIKE '%' || :pattern || '%' ESCAPE '\'
             AND EXISTS (SELECT 1 FROM series_genres g WHERE g.sourceId = x.sourceId AND g.seriesId = x.id AND g.genre = :genre)
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = x.sourceId AND h.kind = 'SERIES' AND h.categoryId = x.categoryId)
           ORDER BY CASE WHEN COALESCE(x.title, x.name) LIKE :pattern || '%' ESCAPE '\' THEN 0 ELSE 1 END, COALESCE(x.title, x.name), x.language
           LIMIT :limit""",
    )
    fun searchSeries(pattern: String, genre: String, limit: Int): Flow<List<SeriesEntity>>

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

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE sourceId = :sourceId AND kind = :kind AND itemId = :itemId")
    fun observe(sourceId: Long, kind: String, itemId: String): Flow<WatchProgressEntity?>

    @Query("SELECT * FROM watch_progress WHERE sourceId = :sourceId AND kind = 'EPISODE' AND seriesId = :seriesId ORDER BY updatedAt DESC")
    fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE sourceId = :sourceId AND kind = :kind AND itemId = :itemId")
    suspend fun delete(sourceId: Long, kind: String, itemId: String)

    @Query("DELETE FROM watch_progress WHERE sourceId = :sourceId AND kind = 'EPISODE' AND seriesId = :seriesId")
    suspend fun deleteSeries(sourceId: Long, seriesId: String)

    @Query(
        """SELECT p.*, COALESCE(m.title, m.name) AS itemTitle, m.language AS itemLanguage, m.quality AS itemQuality,
                  m.posterUrl AS imageUrl, m.tmdbId AS itemTmdbId, m.name AS rawName
           FROM watch_progress p
           JOIN movies m ON m.sourceId = p.sourceId AND m.id = p.itemId
           JOIN sources s ON s.id = p.sourceId
           WHERE p.kind = 'MOVIE' AND p.finished = 0 AND s.enabled = 1
           ORDER BY p.updatedAt DESC LIMIT :limit""",
    )
    fun observeContinueMovies(limit: Int): Flow<List<ContinueRow>>

    @Query(
        """SELECT p.*, COALESCE(x.title, x.name) AS itemTitle, x.language AS itemLanguage, x.quality AS itemQuality,
                  x.coverUrl AS imageUrl, x.tmdbId AS itemTmdbId, x.name AS rawName
           FROM watch_progress p
           JOIN series x ON x.sourceId = p.sourceId AND x.id = p.seriesId
           JOIN sources s ON s.id = p.sourceId
           WHERE p.kind = 'EPISODE' AND s.enabled = 1
             AND p.updatedAt = (SELECT MAX(q.updatedAt) FROM watch_progress q
                                WHERE q.kind = 'EPISODE' AND q.sourceId = p.sourceId AND q.seriesId = p.seriesId)
           ORDER BY p.updatedAt DESC LIMIT :limit""",
    )
    fun observeContinueSeries(limit: Int): Flow<List<ContinueRow>>
}

/** A progress row joined with its catalog title, for Continue Watching. */
data class ContinueRow(
    @androidx.room.Embedded val progress: WatchProgressEntity,
    val itemTitle: String,
    val itemLanguage: String?,
    val itemQuality: String?,
    val imageUrl: String?,
    val itemTmdbId: Long?,
    val rawName: String,
)

@Dao
interface PopularDao {
    @Query("SELECT fetchedAt FROM tmdb_lists WHERE listKey = :listKey") suspend fun fetchedAt(listKey: String): Long?
    @Query("DELETE FROM tmdb_lists WHERE listKey LIKE :kindPrefix || '%'") suspend fun deleteKind(kindPrefix: String)
    @Insert suspend fun insertList(row: TmdbListEntity)
    @Insert suspend fun insertItems(rows: List<TmdbListItemEntity>)

    @Transaction
    suspend fun replace(listKey: String, tmdbIds: List<Long>, fetchedAt: Long) {
        deleteKind(listKey.substringBefore(':') + ':') // items go with their list (cascade)
        insertList(TmdbListEntity(listKey, fetchedAt))
        insertItems(tmdbIds.mapIndexed { rank, id -> TmdbListItemEntity(listKey, rank, id) })
    }

    // Per ranked id, the copy to show: English, then untagged, then any language; standard quality
    // before 4K; enabled sources and visible categories only. (A correlated subquery: API 28's
    // SQLite has no window functions.)
    @Query(
        """SELECT m.* FROM tmdb_list_items i
           JOIN movies m ON m.rowid = (
               SELECT c.rowid FROM movies c JOIN sources s ON s.id = c.sourceId
               WHERE c.tmdbId = i.tmdbId AND s.enabled = 1
                 AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = c.sourceId AND h.kind = 'MOVIE' AND h.categoryId = c.categoryId)
               ORDER BY CASE WHEN c.language = 'EN' THEN 0 WHEN c.language IS NULL THEN 1 ELSE 2 END, c.quality IS NOT NULL, c.sourceId, c.id
               LIMIT 1)
           WHERE i.listKey = :listKey ORDER BY i.rank LIMIT :limit""",
    )
    fun observeMovies(listKey: String, limit: Int): Flow<List<MovieEntity>>

    // The same pick for series.
    @Query(
        """SELECT x.* FROM tmdb_list_items i
           JOIN series x ON x.rowid = (
               SELECT c.rowid FROM series c JOIN sources s ON s.id = c.sourceId
               WHERE c.tmdbId = i.tmdbId AND s.enabled = 1
                 AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = c.sourceId AND h.kind = 'SERIES' AND h.categoryId = c.categoryId)
               ORDER BY CASE WHEN c.language = 'EN' THEN 0 WHEN c.language IS NULL THEN 1 ELSE 2 END, c.quality IS NOT NULL, c.sourceId, c.id
               LIMIT 1)
           WHERE i.listKey = :listKey ORDER BY i.rank LIMIT :limit""",
    )
    fun observeSeries(listKey: String, limit: Int): Flow<List<SeriesEntity>>
}

/** A live channel with its category's name, for the Sports screen's matching. */
data class SportsCandidateRow(@androidx.room.Embedded val live: LiveChannelEntity, val categoryName: String)

@Dao
interface WatchListDao {
    @Query("SELECT * FROM watch_list") fun observeAll(): Flow<List<WatchListEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: WatchListEntity)

    @Query("DELETE FROM watch_list WHERE sourceId = :sourceId AND kind = :kind AND itemId = :itemId")
    suspend fun delete(sourceId: Long, kind: String, itemId: String)

    /** Saved titles still in the catalog, of enabled sources and visible categories, newest first. */
    @Query(
        """SELECT w.*, COALESCE(m.title, m.name) AS itemTitle, m.language AS itemLanguage, m.quality AS itemQuality,
                  m.posterUrl AS imageUrl, m.tmdbId AS itemTmdbId, m.name AS rawName
           FROM watch_list w
           JOIN movies m ON m.sourceId = w.sourceId AND m.id = w.itemId
           JOIN sources s ON s.id = w.sourceId
           WHERE w.kind = 'MOVIE' AND s.enabled = 1
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = m.sourceId AND h.kind = 'MOVIE' AND h.categoryId = m.categoryId)
           UNION ALL
           SELECT w.*, COALESCE(x.title, x.name) AS itemTitle, x.language AS itemLanguage, x.quality AS itemQuality,
                  x.coverUrl AS imageUrl, x.tmdbId AS itemTmdbId, x.name AS rawName
           FROM watch_list w
           JOIN series x ON x.sourceId = w.sourceId AND x.id = w.itemId
           JOIN sources s ON s.id = w.sourceId
           WHERE w.kind = 'SERIES' AND s.enabled = 1
             AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = x.sourceId AND h.kind = 'SERIES' AND h.categoryId = x.categoryId)
           ORDER BY savedAt DESC""",
    )
    fun observeRow(): Flow<List<WatchListRow>>
}

/** A watch list row joined with its catalog title, for Home's Watch list row. */
data class WatchListRow(
    @androidx.room.Embedded val saved: WatchListEntity,
    val itemTitle: String,
    val itemLanguage: String?,
    val itemQuality: String?,
    val imageUrl: String?,
    val itemTmdbId: Long?,
    val rawName: String,
)

@Dao
interface GuideDao {
    @Query("DELETE FROM guide_channels WHERE origin = :origin") suspend fun deleteChannels(origin: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertChannels(rows: List<GuideChannelEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProgrammes(rows: List<GuideProgrammeEntity>)

    @Query("SELECT * FROM guide_channels") fun observeChannels(): Flow<List<GuideChannelEntity>>

    @Query("SELECT * FROM guide_programmes WHERE guideId IN (:guideIds) AND startMs < :untilMs AND endMs > :fromMs ORDER BY guideId, startMs")
    fun observeProgrammes(guideIds: Set<String>, fromMs: Long, untilMs: Long): Flow<List<GuideProgrammeEntity>>

    @Query("SELECT * FROM guide_programmes WHERE title LIKE '%' || :pattern || '%' ESCAPE '\\' AND endMs > :fromMs ORDER BY startMs LIMIT :limit")
    suspend fun search(pattern: String, fromMs: Long, limit: Int): List<GuideProgrammeEntity>

    @Query("DELETE FROM channel_guide") suspend fun deleteLinks()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLinks(rows: List<ChannelGuideEntity>)
    @Query("SELECT * FROM channel_guide") fun observeLinks(): Flow<List<ChannelGuideEntity>>
    @Query("SELECT DISTINCT sourceId FROM channel_guide") suspend fun linkedSourceIds(): List<Long>

    @Query("SELECT fetchedAt FROM guide_meta WHERE origin = :origin") suspend fun fetchedAt(origin: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMeta(row: GuideMetaEntity)

    @Query("SELECT rowKey FROM favourite_channels") fun observeFavourites(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavourite(row: FavouriteChannelEntity)
    @Query("DELETE FROM favourite_channels WHERE rowKey = :rowKey") suspend fun removeFavourite(rowKey: String)
}

data class HiddenLiveRow(val sourceId: Long, val categoryId: String)

@Dao
interface LineupDao {
    @Query("DELETE FROM lineup_feeds") suspend fun deleteFeeds()
    @Query("DELETE FROM lineup_rows") suspend fun deleteRows()
    @Query("DELETE FROM lineup_meta") suspend fun deleteMeta()
    @Insert suspend fun insertRows(rows: List<LineupRowEntity>)
    @Insert suspend fun insertFeeds(rows: List<LineupFeedEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMeta(row: LineupMetaEntity)

    @Query("SELECT * FROM lineup_meta WHERE id = 0") suspend fun meta(): LineupMetaEntity?
    @Query("SELECT * FROM lineup_meta WHERE id = 0") fun observeMeta(): Flow<LineupMetaEntity?>
    @Query("SELECT * FROM lineup_rows ORDER BY position") suspend fun rows(): List<LineupRowEntity>
    @Query("SELECT * FROM lineup_feeds ORDER BY rowKey, position") suspend fun feeds(): List<LineupFeedEntity>

    @Query("SELECT COALESCE(MAX(generation), 0) FROM lineup_state") suspend fun generation(): Long
    @Query("SELECT COALESCE(MAX(generation), 0) FROM lineup_state") fun observeGeneration(): Flow<Long>
    @Query("INSERT OR IGNORE INTO lineup_state (id, generation) VALUES (0, 0)") suspend fun seedState()
    @Query("UPDATE lineup_state SET generation = generation + 1 WHERE id = 0") suspend fun increment()

    @Query("SELECT id FROM sources WHERE enabled = 1") fun observeEnabledSources(): Flow<List<Long>>
    @Query("SELECT sourceId, categoryId FROM hidden_categories WHERE kind = 'LIVE'") fun observeHiddenLive(): Flow<List<HiddenLiveRow>>
}
