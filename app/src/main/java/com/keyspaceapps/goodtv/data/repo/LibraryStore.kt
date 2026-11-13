package com.keyspaceapps.goodtv.data.repo

import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.data.source.LiveCatalog
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.domain.SourceSummary
import kotlinx.coroutines.flow.Flow

data class CategoryKey(val sourceId: Long, val categoryId: String)

/**
 * One sync's writes; nothing is visible until [commit]. Calls arrive in any order, from any thread. A kind's
 * list may arrive in several pieces: they append, and the pieces of one kind arrive in list order, one call
 * at a time, so the result is as if the whole list had been written at once (later duplicates still win).
 */
interface CatalogWriter {
    suspend fun categories(kind: ContentKind, list: List<Category>)
    suspend fun live(list: List<LiveChannel>)
    suspend fun movies(list: List<Movie>)
    suspend fun series(list: List<Series>)
    /** Replaces the source's catalog with what was written, as replaceCatalog does, in one short transaction. */
    suspend fun commit()
    /** Drops what was written; the library is untouched. Safe to call more than once, and after commit. */
    suspend fun abandon()
}

/**
 * Holds a sync's lists in memory and hands them to [replace] as one [Catalog] on commit. Lists of the
 * same kind are appended. Categories are ordered by kind, so a kind written with an empty list adds
 * nothing and replaceCatalog prunes hidden choices only for kinds the panel actually listed.
 */
class BufferingCatalogWriter(private val replace: suspend (Catalog) -> Unit) : CatalogWriter {
    private val lock = Any()
    private val categories = java.util.EnumMap<ContentKind, MutableList<Category>>(ContentKind::class.java)
    private var live = ArrayList<LiveChannel>()
    private var movies = ArrayList<Movie>()
    private var series = ArrayList<Series>()
    private var closed = false

    private inline fun write(block: () -> Unit) = synchronized(lock) {
        check(!closed) { "Catalog writer already committed or abandoned" }
        block()
    }

    override suspend fun categories(kind: ContentKind, list: List<Category>) = write { categories.getOrPut(kind) { ArrayList() }.addAll(list) }
    override suspend fun live(list: List<LiveChannel>) = write { live.addAll(list) }
    override suspend fun movies(list: List<Movie>) = write { movies.addAll(list) }
    override suspend fun series(list: List<Series>) = write { series.addAll(list) }

    override suspend fun commit() {
        val catalog = synchronized(lock) {
            check(!closed) { "Catalog writer already committed or abandoned" }
            closed = true
            Catalog(ContentKind.entries.flatMap { categories[it].orEmpty() }, live, movies, series).also { clear() }
        }
        replace(catalog)
    }

    override suspend fun abandon() = synchronized(lock) {
        closed = true
        clear()
    }

    /** Lets go of the lists without emptying them: commit has handed them on. */
    private fun clear() {
        categories.clear()
        live = ArrayList()
        movies = ArrayList()
        series = ArrayList()
    }
}

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

    /** Starts one sync of [sourceId]: the caller writes the lists, then commits or abandons. */
    suspend fun beginCatalog(sourceId: Long): CatalogWriter

    /** [syncedAt] null keeps the existing timestamp (used when recording a failure). */
    /** Replaces only this source's live categories and channels; movies, series, hidden choices and the sync time stay. */
    suspend fun replaceLive(sourceId: Long, live: LiveCatalog)
    /** Every live channel of enabled sources in visible categories, with its category's name (for the Sports screen). */
    fun observeSportsCandidates(): Flow<List<ChannelCandidate>>

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
