package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.repo.LibraryStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** In-memory LibraryStore for JVM tests. Not thread-safe beyond what StateFlow gives. */
class FakeLibraryStore : LibraryStore {
    val sources = MutableStateFlow<List<SourceRecord>>(emptyList())
    val catalogs = MutableStateFlow<Map<Long, Catalog>>(emptyMap())
    private val movieDetails = mutableMapOf<Pair<Long, String>, MovieDetail>()
    private val seriesDetails = mutableMapOf<Pair<Long, String>, SeriesDetail>()
    private var nextId = 1L

    override fun observeSources(): Flow<List<SourceRecord>> = sources
    override suspend fun getSources(): List<SourceRecord> = sources.value
    override suspend fun getSource(id: Long): SourceRecord? = sources.value.firstOrNull { it.id == id }

    override suspend fun insertSource(record: SourceRecord): Long {
        val id = nextId++
        sources.value = sources.value + record.copy(id = id)
        return id
    }

    override suspend fun updateSource(record: SourceRecord) {
        sources.value = sources.value.map { if (it.id == record.id) record else it }
    }

    override suspend fun deleteSource(id: Long) {
        sources.value = sources.value.filterNot { it.id == id }
        catalogs.value = catalogs.value - id
    }

    override suspend fun replaceCatalog(sourceId: Long, catalog: Catalog) {
        catalogs.value = catalogs.value + (sourceId to catalog)
    }

    override suspend fun markSync(sourceId: Long, syncedAt: Long?, error: String?) {
        sources.value = sources.value.map {
            if (it.id == sourceId) it.copy(lastSyncAt = syncedAt ?: it.lastSyncAt, lastError = error) else it
        }
    }

    override fun observeSummaries(): Flow<List<SourceSummary>> = combine(sources, catalogs) { s, c ->
        s.map { r ->
            val cat = c[r.id]
            SourceSummary(r.id, r.nickname, r.type, cat?.live?.size ?: 0, cat?.movies?.size ?: 0, cat?.series?.size ?: 0, r.lastSyncAt, r.lastError)
        }
    }

    override fun observeCategories(kind: ContentKind): Flow<List<CategoryWithSource>> = combine(sources, catalogs) { s, c ->
        s.filter { it.enabled }.flatMap { r ->
            (c[r.id]?.categories ?: emptyList()).filter { it.kind == kind }.map { CategoryWithSource(it, r.nickname) }
        }
    }

    override fun observeLiveChannels(sourceId: Long, categoryId: String): Flow<List<LiveChannel>> =
        catalogs.map { it[sourceId]?.live?.filter { ch -> ch.categoryId == categoryId } ?: emptyList() }

    override fun observeMovies(sourceId: Long, categoryId: String, limit: Int): Flow<List<Movie>> =
        catalogs.map { it[sourceId]?.movies?.filter { m -> m.categoryId == categoryId }?.take(limit) ?: emptyList() }

    override fun observeSeries(sourceId: Long, categoryId: String, limit: Int): Flow<List<Series>> =
        catalogs.map { it[sourceId]?.series?.filter { m -> m.categoryId == categoryId }?.take(limit) ?: emptyList() }

    override suspend fun getMovie(sourceId: Long, id: String): Movie? = catalogs.value[sourceId]?.movies?.firstOrNull { it.id == id }
    override suspend fun getSeries(sourceId: Long, id: String): Series? = catalogs.value[sourceId]?.series?.firstOrNull { it.id == id }
    override suspend fun getMovieDetail(sourceId: Long, id: String): MovieDetail? = movieDetails[sourceId to id]
    override suspend fun saveMovieDetail(detail: MovieDetail) { movieDetails[detail.movie.sourceId to detail.movie.id] = detail }
    override suspend fun getSeriesDetail(sourceId: Long, id: String): SeriesDetail? = seriesDetails[sourceId to id]
    override suspend fun saveSeriesDetail(detail: SeriesDetail) { seriesDetails[detail.series.sourceId to detail.series.id] = detail }
}
