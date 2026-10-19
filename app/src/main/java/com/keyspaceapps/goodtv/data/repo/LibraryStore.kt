package com.keyspaceapps.goodtv.data.repo

import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.domain.SourceSummary
import kotlinx.coroutines.flow.Flow

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

    suspend fun getMovie(sourceId: Long, id: String): Movie?
    suspend fun getSeries(sourceId: Long, id: String): Series?
    suspend fun getMovieDetail(sourceId: Long, id: String): MovieDetail?
    suspend fun saveMovieDetail(detail: MovieDetail)
    suspend fun getSeriesDetail(sourceId: Long, id: String): SeriesDetail?
    suspend fun saveSeriesDetail(detail: SeriesDetail)
}
