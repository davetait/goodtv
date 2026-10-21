package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.repo.CategoryKey
import com.keyspaceapps.goodtv.data.repo.LibraryStore
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
import com.keyspaceapps.goodtv.data.source.LiveCatalog
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** In-memory LibraryStore for JVM tests. Not thread-safe beyond what StateFlow gives. */
class FakeLibraryStore : LibraryStore {
    val sources = MutableStateFlow<List<SourceRecord>>(emptyList())
    val catalogs = MutableStateFlow<Map<Long, Catalog>>(emptyMap())
    private val movieDetails = mutableMapOf<Pair<Long, String>, MovieDetail>()
    private val seriesDetails = mutableMapOf<Pair<Long, String>, SeriesDetail>()
    private var nextId = 1L
    val hidden = MutableStateFlow<Set<Triple<Long, ContentKind, String>>>(emptySet())
    /** TMDB genres by TMDB movie id, as the background fetcher would have stored them. */
    val movieGenres = MutableStateFlow<Map<Long, Set<Genre>>>(emptyMap())
    /** When set, replaceCatalog throws it (simulates a DB failure or a cascade-deleted source). */
    var replaceCatalogError: Throwable? = null

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
        replaceCatalogError?.let { throw it }
        catalogs.value = catalogs.value + (sourceId to catalog)
    }

    override suspend fun replaceLive(sourceId: Long, live: LiveCatalog) {
        val old = catalogs.value[sourceId] ?: Catalog(emptyList(), emptyList(), emptyList(), emptyList())
        catalogs.value = catalogs.value + (sourceId to old.copy(categories = old.categories.filter { it.kind != ContentKind.LIVE } + live.categories, live = live.live))
    }

    override fun observeSportsCandidates(): Flow<List<ChannelCandidate>> = combine(sources, catalogs, hidden) { s, c, h ->
        s.filter { it.enabled }.flatMap { r ->
            val cat = c[r.id] ?: return@flatMap emptyList()
            val names = cat.categories.filter { it.kind == ContentKind.LIVE }.associate { it.id to it.name }
            cat.live.mapNotNull { ch ->
                val id = ch.categoryId ?: return@mapNotNull null
                val name = names[id] ?: return@mapNotNull null
                if (Triple(r.id, ContentKind.LIVE, id) in h) null else ChannelCandidate(ch, name)
            }
        }
    }

    override suspend fun markSync(sourceId: Long, syncedAt: Long?, error: String?) {
        sources.value = sources.value.map {
            if (it.id == sourceId) it.copy(lastSyncAt = syncedAt ?: it.lastSyncAt, lastError = error) else it
        }
    }

    override fun observeSummaries(): Flow<List<SourceSummary>> = combine(sources, catalogs) { s, c ->
        s.map { r ->
            val cat = c[r.id]
            SourceSummary(r.id, r.nickname, r.type, cat?.live?.size ?: 0, cat?.movies?.size ?: 0, cat?.series?.size ?: 0, r.lastSyncAt, r.lastError, hidden.value.count { it.first == r.id })
        }
    }

    private fun isHidden(c: Category) = hidden.value.contains(Triple(c.sourceId, c.kind, c.id))

    override fun observeCategories(kind: ContentKind): Flow<List<CategoryWithSource>> = combine(sources, catalogs, hidden) { s, c, _ ->
        s.filter { it.enabled }.flatMap { r ->
            (c[r.id]?.categories ?: emptyList()).filter { it.kind == kind && !isHidden(it) }.map { CategoryWithSource(it, r.nickname) }
        }
    }

    override fun observeAllCategories(sourceId: Long, kind: ContentKind): Flow<List<Category>> =
        catalogs.map { c -> (c[sourceId]?.categories ?: emptyList()).filter { it.kind == kind }.sortedBy { it.name } }

    override fun observeHiddenCategories(sourceId: Long): Flow<Set<Pair<ContentKind, String>>> =
        hidden.map { set -> set.filter { it.first == sourceId }.map { it.second to it.third }.toSet() }

    override fun observeHiddenCount(kind: ContentKind): Flow<Int> = combine(sources, catalogs, hidden) { s, c, h ->
        s.filter { it.enabled }.sumOf { r ->
            (c[r.id]?.categories ?: emptyList()).count { it.kind == kind && h.contains(Triple(r.id, kind, it.id)) }
        }
    }

    override suspend fun setCategoriesHidden(sourceId: Long, kind: ContentKind, ids: Collection<String>, hidden: Boolean) {
        val rows = ids.map { Triple(sourceId, kind, it) }.toSet()
        this.hidden.value = if (hidden) this.hidden.value + rows else this.hidden.value - rows
    }

    private fun <T> search(query: String, limit: Int, kind: ContentKind, pick: (Catalog) -> List<T>, name: (T) -> String, sid: (T) -> Long, cat: (T) -> String?): Flow<List<T>> =
        combine(sources, catalogs, hidden) { s, c, h ->
            val enabled = s.filter { it.enabled }.map { it.id }.toSet()
            enabled.flatMap { id -> c[id]?.let(pick).orEmpty() }
                .filter { name(it).contains(query, ignoreCase = true) }
                .filterNot { item -> cat(item)?.let { Triple(sid(item), kind, it) in h } ?: false }
                .sortedWith(compareBy<T>({ if (name(it).startsWith(query, ignoreCase = true)) 0 else 1 }, { name(it) }))
                .take(limit)
        }

    override fun searchLive(query: String, limit: Int): Flow<List<LiveChannel>> =
        search(query, limit, ContentKind.LIVE, { it.live }, { it.name }, { it.sourceId }, { it.categoryId })
    override fun searchMovies(query: String, limit: Int, genre: Genre?): Flow<List<Movie>> =
        combine(search(query, Int.MAX_VALUE, ContentKind.MOVIE, { it.movies }, { it.name }, { it.sourceId }, { it.categoryId }), movieGenres) { list, g ->
            list.filter { m -> genre == null || (m.tmdbId?.let { genre in g[it].orEmpty() } ?: false) }.take(limit)
        }
    override fun searchSeries(query: String, limit: Int, genre: Genre?): Flow<List<Series>> =
        search(query, Int.MAX_VALUE, ContentKind.SERIES, { it.series }, { it.name }, { it.sourceId }, { it.categoryId })
            .map { list -> list.filter { genre == null || genre in it.genres }.take(limit) }

    private fun <T> visible(kind: ContentKind, pick: (Catalog) -> List<T>, sid: (T) -> Long, cat: (T) -> String?): Flow<List<T>> =
        combine(sources, catalogs, hidden) { s, c, h ->
            s.filter { it.enabled }.flatMap { r -> c[r.id]?.let(pick).orEmpty() }
                .filterNot { item -> cat(item)?.let { Triple(sid(item), kind, it) in h } ?: false }
        }

    override fun observeMoviesByGenre(genre: Genre, limit: Int): Flow<List<Movie>> =
        combine(visible(ContentKind.MOVIE, { it.movies }, { it.sourceId }, { it.categoryId }), movieGenres) { list, g ->
            list.filter { m -> m.tmdbId?.let { genre in g[it].orEmpty() } ?: false }
                .sortedWith(compareByDescending<Movie> { it.addedAt ?: 0 }.thenBy { it.name }).take(limit)
        }

    override fun observeSeriesByGenre(genre: Genre, limit: Int): Flow<List<Series>> =
        visible(ContentKind.SERIES, { it.series }, { it.sourceId }, { it.categoryId }).map { list ->
            list.filter { genre in it.genres }.sortedBy { it.name }.take(limit)
        }

    override fun observeGenreCounts(kind: ContentKind): Flow<Map<Genre, Int>> = when (kind) {
        ContentKind.MOVIE -> combine(visible(ContentKind.MOVIE, { it.movies }, { it.sourceId }, { it.categoryId }), movieGenres) { list, g ->
            list.flatMap { m -> m.tmdbId?.let { g[it] }.orEmpty() }.groupingBy { it }.eachCount()
        }
        ContentKind.SERIES -> visible(ContentKind.SERIES, { it.series }, { it.sourceId }, { it.categoryId }).map { list ->
            list.flatMap { it.genres }.groupingBy { it }.eachCount()
        }
        ContentKind.LIVE -> flowOf(emptyMap())
    }

    override fun observeLiveChannels(sourceId: Long, categoryId: String): Flow<List<LiveChannel>> =
        catalogs.map { it[sourceId]?.live?.filter { ch -> ch.categoryId == categoryId } ?: emptyList() }

    override fun observeMovies(sourceId: Long, categoryId: String, limit: Int): Flow<List<Movie>> =
        catalogs.map { it[sourceId]?.movies?.filter { m -> m.categoryId == categoryId }?.take(limit) ?: emptyList() }

    override fun observeSeries(sourceId: Long, categoryId: String, limit: Int): Flow<List<Series>> =
        catalogs.map { it[sourceId]?.series?.filter { m -> m.categoryId == categoryId }?.take(limit) ?: emptyList() }

    override fun observeMoviesByCategory(limitPerCategory: Int): Flow<Map<CategoryKey, List<Movie>>> = combine(sources, catalogs, hidden) { s, c, h ->
        s.filter { it.enabled }.flatMap { r -> c[r.id]?.movies ?: emptyList() }
            .filter { it.categoryId != null && !h.contains(Triple(it.sourceId, ContentKind.MOVIE, it.categoryId!!)) }
            .sortedWith(compareByDescending<Movie> { it.addedAt ?: Long.MIN_VALUE }.thenBy { it.name })
            .groupBy { CategoryKey(it.sourceId, it.categoryId!!) }
            .mapValues { (_, v) -> v.take(limitPerCategory) }
    }

    override fun observeSeriesByCategory(limitPerCategory: Int): Flow<Map<CategoryKey, List<Series>>> = combine(sources, catalogs, hidden) { s, c, h ->
        s.filter { it.enabled }.flatMap { r -> c[r.id]?.series ?: emptyList() }
            .filter { it.categoryId != null && !h.contains(Triple(it.sourceId, ContentKind.SERIES, it.categoryId!!)) }
            .sortedBy { it.name }
            .groupBy { CategoryKey(it.sourceId, it.categoryId!!) }
            .mapValues { (_, v) -> v.take(limitPerCategory) }
    }

    override suspend fun getMovie(sourceId: Long, id: String): Movie? = catalogs.value[sourceId]?.movies?.firstOrNull { it.id == id }
    override suspend fun getSeries(sourceId: Long, id: String): Series? = catalogs.value[sourceId]?.series?.firstOrNull { it.id == id }
    override suspend fun getMovieDetail(sourceId: Long, id: String): MovieDetail? = movieDetails[sourceId to id]
    override suspend fun saveMovieDetail(detail: MovieDetail) { movieDetails[detail.movie.sourceId to detail.movie.id] = detail }
    override suspend fun getSeriesDetail(sourceId: Long, id: String): SeriesDetail? = seriesDetails[sourceId to id]
    override suspend fun saveSeriesDetail(detail: SeriesDetail) { seriesDetails[detail.series.sourceId to detail.series.id] = detail }
}
