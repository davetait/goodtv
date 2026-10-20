package com.keyspaceapps.goodtv.data.repo

import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.domain.PlayableRef
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.domain.SourceInfo
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.domain.SourceSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The single entry point the UI uses. Reads come from the cache; writes go through sync. */
class LibraryRepository(
    private val store: LibraryStore,
    private val registry: SourceRegistry,
    private val sync: SyncManager,
    private val settings: AppSettings,
) {
    val sources: Flow<List<SourceSummary>> = store.observeSummaries()
    val syncState: StateFlow<SyncState> = sync.state

    fun categories(kind: ContentKind): Flow<List<CategoryWithSource>> = store.observeCategories(kind)
    fun liveChannels(sourceId: Long, categoryId: String): Flow<List<LiveChannel>> = store.observeLiveChannels(sourceId, categoryId)
    fun movies(sourceId: Long, categoryId: String, limit: Int): Flow<List<Movie>> = store.observeMovies(sourceId, categoryId, limit)
    fun series(sourceId: Long, categoryId: String, limit: Int): Flow<List<Series>> = store.observeSeries(sourceId, categoryId, limit)
    fun moviesByCategory(limitPerCategory: Int): Flow<Map<CategoryKey, List<Movie>>> = store.observeMoviesByCategory(limitPerCategory)
    fun seriesByCategory(limitPerCategory: Int): Flow<Map<CategoryKey, List<Series>>> = store.observeSeriesByCategory(limitPerCategory)
    fun searchLive(query: String, limit: Int): Flow<List<LiveChannel>> = store.searchLive(query, limit)
    fun searchMovies(query: String, limit: Int): Flow<List<Movie>> = store.searchMovies(query, limit)
    fun searchSeries(query: String, limit: Int): Flow<List<Series>> = store.searchSeries(query, limit)
    fun allCategories(sourceId: Long, kind: ContentKind): Flow<List<Category>> = store.observeAllCategories(sourceId, kind)
    fun hiddenCategories(sourceId: Long): Flow<Set<Pair<ContentKind, String>>> = store.observeHiddenCategories(sourceId)
    fun hiddenCount(kind: ContentKind): Flow<Int> = store.observeHiddenCount(kind)
    suspend fun setCategoriesHidden(sourceId: Long, kind: ContentKind, ids: Collection<String>, hidden: Boolean) =
        store.setCategoriesHidden(sourceId, kind, ids, hidden)
    fun enabledSourceCount(): Flow<Int> = store.observeSources().map { list -> list.count { it.enabled } }

    suspend fun source(id: Long): SourceRecord? = store.getSource(id)
    fun refresh(sourceId: Long) = sync.refresh(sourceId)
    fun refreshStale() = sync.refreshStale()

    suspend fun validate(config: SourceConfig): Result<SourceInfo> = registry.forType(config.type).validate(config)

    /** Inserts and starts the first sync in the background; the Sources screen shows its progress. */
    suspend fun addSource(nickname: String, config: SourceConfig): Long {
        val id = store.insertSource(SourceRecord(0, config.type, nickname, config, enabled = true, lastSyncAt = null, lastError = null))
        sync.refresh(id)
        return id
    }

    suspend fun updateSource(id: Long, nickname: String, config: SourceConfig) {
        val existing = store.getSource(id) ?: return
        sync.cancel(id) // a refresh of the old config must not report against the new one
        store.updateSource(existing.copy(nickname = nickname, config = config, type = config.type))
        sync.refresh(id)
    }

    suspend fun removeSource(id: Long) {
        sync.cancel(id) // otherwise the in-flight refresh would insert rows for a deleted source
        store.deleteSource(id)
    }

    /** The synced catalog row (carries the provider's TMDB id, which the detail call does not). */
    suspend fun catalogMovie(sourceId: Long, movieId: String): Movie? = store.getMovie(sourceId, movieId)
    suspend fun catalogSeries(sourceId: Long, seriesId: String): Series? = store.getSeries(sourceId, seriesId)

    suspend fun movieDetail(sourceId: Long, movieId: String): Result<MovieDetail> {
        store.getMovieDetail(sourceId, movieId)?.let { return Result.success(it) }
        val record = store.getSource(sourceId) ?: return Result.failure(SourceException("Source not found"))
        return registry.forType(record.type).fetchMovieDetail(sourceId, record.config, movieId)
            .onSuccess { store.saveMovieDetail(it) }
    }

    suspend fun seriesDetail(sourceId: Long, seriesId: String): Result<SeriesDetail> {
        store.getSeriesDetail(sourceId, seriesId)?.let { return Result.success(it) }
        val record = store.getSource(sourceId) ?: return Result.failure(SourceException("Source not found"))
        return registry.forType(record.type).fetchSeriesDetail(sourceId, record.config, seriesId)
            .onSuccess { store.saveSeriesDetail(it) }
    }

    suspend fun playable(sourceId: Long, ref: PlayableRef, title: String): PlayableItem {
        val record = store.getSource(sourceId) ?: throw SourceException("Source not found")
        return PlayableItem(title, registry.forType(record.type).streamUrl(record.config, ref), isLive = ref is PlayableRef.Live)
    }

    /** Inserts the developer source once, and only into an empty library. */
    suspend fun ensureDebugSource(record: SourceRecord?) {
        if (record == null) return
        if (settings.debugSourceInserted.first()) return
        settings.setDebugSourceInserted(true)
        if (store.getSources().isNotEmpty()) return
        val id = store.insertSource(record)
        sync.refresh(id)
    }
}
