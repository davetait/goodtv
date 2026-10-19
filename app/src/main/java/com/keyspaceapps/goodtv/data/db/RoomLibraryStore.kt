package com.keyspaceapps.goodtv.data.db

import androidx.room.withTransaction
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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class RoomLibraryStore(
    private val db: GoodTvDatabase,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) : LibraryStore {
    private val sources get() = db.sources()
    private val library get() = db.library()

    override fun observeSources(): Flow<List<SourceRecord>> = sources.observeAll().map { list -> list.map { it.toRecord(json) } }
    override suspend fun getSources(): List<SourceRecord> = sources.getAll().map { it.toRecord(json) }
    override suspend fun getSource(id: Long): SourceRecord? = sources.getById(id)?.toRecord(json)

    override suspend fun insertSource(record: SourceRecord): Long =
        sources.insert(record.copy(id = 0).toEntity(json, clock()))

    override suspend fun updateSource(record: SourceRecord) {
        val existing = sources.getById(record.id) ?: return
        sources.update(record.toEntity(json, existing.createdAt))
    }

    override suspend fun deleteSource(id: Long) = sources.deleteById(id)

    override suspend fun replaceCatalog(sourceId: Long, catalog: Catalog) = db.withTransaction {
        library.deleteCategories(sourceId)
        library.deleteLive(sourceId)
        library.deleteMovies(sourceId)
        library.deleteSeries(sourceId)
        library.deleteMovieDetails(sourceId)
        library.deleteSeriesInfo(sourceId)
        library.deleteSeasons(sourceId)
        library.deleteEpisodes(sourceId)
        library.insertCategories(catalog.categories.map { it.toEntity() })
        library.insertLive(catalog.live.map { it.toEntity() })
        library.insertMovies(catalog.movies.map { it.toEntity() })
        library.insertSeries(catalog.series.map { it.toEntity() })
    }

    override suspend fun markSync(sourceId: Long, syncedAt: Long?, error: String?) = sources.markSync(sourceId, syncedAt, error)

    override fun observeSummaries(): Flow<List<SourceSummary>> = sources.observeSummaries().map { rows -> rows.map { it.toSummary() } }

    override fun observeCategories(kind: ContentKind): Flow<List<CategoryWithSource>> =
        library.observeCategories(kind.name).map { rows -> rows.map { it.toDomain() } }

    override fun observeLiveChannels(sourceId: Long, categoryId: String): Flow<List<LiveChannel>> =
        library.observeLive(sourceId, categoryId).map { rows -> rows.map { it.toDomain() } }

    override fun observeMovies(sourceId: Long, categoryId: String, limit: Int): Flow<List<Movie>> =
        library.observeMovies(sourceId, categoryId, limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeSeries(sourceId: Long, categoryId: String, limit: Int): Flow<List<Series>> =
        library.observeSeries(sourceId, categoryId, limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getMovie(sourceId: Long, id: String): Movie? = library.getMovie(sourceId, id)?.toDomain()
    override suspend fun getSeries(sourceId: Long, id: String): Series? = library.getSeries(sourceId, id)?.toDomain()

    override suspend fun getMovieDetail(sourceId: Long, id: String): MovieDetail? = library.getMovieDetail(sourceId, id)?.toDomain()
    override suspend fun saveMovieDetail(detail: MovieDetail) = library.upsertMovieDetail(detail.toEntity())

    override suspend fun getSeriesDetail(sourceId: Long, id: String): SeriesDetail? {
        val info = library.getSeriesInfo(sourceId, id) ?: return null
        return info.toDomain(library.getSeasons(sourceId, id), library.getEpisodes(sourceId, id))
    }

    override suspend fun saveSeriesDetail(detail: SeriesDetail) = db.withTransaction {
        val sid = detail.series.sourceId
        val seriesId = detail.series.id
        library.upsertSeriesInfo(detail.toInfoEntity())
        library.deleteSeasonsOf(sid, seriesId)
        library.deleteEpisodesOf(sid, seriesId)
        library.insertSeasons(detail.seasons.map { it.toEntity(sid, seriesId) })
        library.insertEpisodes(detail.episodes.map { it.toEntity() })
    }
}
