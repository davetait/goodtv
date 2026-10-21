package com.keyspaceapps.goodtv.data.repo

import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.domain.SourceSummary
import kotlinx.coroutines.flow.Flow

data class CategoryKey(val sourceId: Long, val categoryId: String)

/** The on-device library cache. The UI reads only from here; sync writes into it. */
interface LibraryStore {
    fun observeSources(): Flow<List<SourceRecord>>
    suspend fun getSources(): List<SourceRecord>
    suspend fun getSource(id: Long): SourceRecord?
    suspend fun insertSource(record: SourceRecord): Long
    suspend fun updateSource(record: SourceRecord)
    suspend fun deleteSource(id: Long)

    /** Atomically drops every cached row of [sourceId] and inserts [catalog]. */
    suspend fun replaceCatalog(sourceId: Long, catalog: Catalog)

    /** [syncedAt] null keeps the existing timestamp (used when recording a failure). */
    suspend fun markSync(sourceId: Long, syncedAt: Long?, error: String?)

    fun observeSummaries(): Flow<List<SourceSummary>>
    fun observeCategories(kind: ContentKind): Flow<List<CategoryWithSource>>
    fun observeLiveChannels(sourceId: Long, categoryId: String): Flow<List<LiveChannel>>
    fun observeMovies(sourceId: Long, categoryId: String, limit: Int): Flow<List<Movie>>
    fun observeSeries(sourceId: Long, categoryId: String, limit: Int): Flow<List<Series>>

    /**
     * Every enabled source's movies grouped by category, newest first, at most [limitPerCategory]
     * per category. One query for the whole Movies screen instead of one per category.
     */
    fun observeMoviesByCategory(limitPerCategory: Int): Flow<Map<CategoryKey, List<Movie>>>
    fun observeSeriesByCategory(limitPerCategory: Int): Flow<Map<CategoryKey, List<Series>>>

    /**
     * Items of enabled sources whose name contains [query] (case-insensitive for ASCII), names that
     * start with it first, then by name. Items in hidden categories are left out. At most [limit].
     */
    fun searchLive(query: String, limit: Int): Flow<List<LiveChannel>>
    /** With [genre], only titles in it (movies: TMDB genres; series: the provider's). */
    fun searchMovies(query: String, limit: Int, genre: Genre? = null): Flow<List<Movie>>
    fun searchSeries(query: String, limit: Int, genre: Genre? = null): Flow<List<Series>>

    /** Enabled, non-hidden movies TMDB lists under [genre], newest first, at most [limit]. */
    fun observeMoviesByGenre(genre: Genre, limit: Int): Flow<List<Movie>>
    /** Enabled, non-hidden series the provider files under [genre], by name, at most [limit]. */
    fun observeSeriesByGenre(genre: Genre, limit: Int): Flow<List<Series>>
    /** How many enabled, non-hidden titles of [kind] (MOVIE or SERIES) each genre has; absent = none. */
    fun observeGenreCounts(kind: ContentKind): Flow<Map<Genre, Int>>

    /** Every category of one source and kind, hidden or not, in name order (for the Categories screen). */
    fun observeAllCategories(sourceId: Long, kind: ContentKind): Flow<List<Category>>
    fun observeHiddenCategories(sourceId: Long): Flow<Set<Pair<ContentKind, String>>>
    /** Hidden categories of [kind] that still exist, across enabled sources. */
    fun observeHiddenCount(kind: ContentKind): Flow<Int>
    suspend fun setCategoriesHidden(sourceId: Long, kind: ContentKind, ids: Collection<String>, hidden: Boolean)

    suspend fun getMovie(sourceId: Long, id: String): Movie?
    suspend fun getSeries(sourceId: Long, id: String): Series?
    suspend fun getMovieDetail(sourceId: Long, id: String): MovieDetail?
    suspend fun saveMovieDetail(detail: MovieDetail)
    suspend fun getSeriesDetail(sourceId: Long, id: String): SeriesDetail?
    suspend fun saveSeriesDetail(detail: SeriesDetail)
}
