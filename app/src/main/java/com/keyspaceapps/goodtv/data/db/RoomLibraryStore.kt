package com.keyspaceapps.goodtv.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.keyspaceapps.goodtv.data.repo.CatalogWriter
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.EnumSet
import kotlinx.serialization.json.Json

class RoomLibraryStore(
    private val db: GoodTvDatabase,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) : LibraryStore {
    private val sources get() = db.sources()
    private val library get() = db.library()
    private val hidden get() = db.hidden()
    private val genres get() = db.genres()
    private val lineup get() = db.lineup()

    override fun observeSources(): Flow<List<SourceRecord>> = sources.observeAll().map { list -> list.map { it.toRecord(json) } }
    override suspend fun getSources(): List<SourceRecord> = sources.getAll().map { it.toRecord(json) }
    override suspend fun getSource(id: Long): SourceRecord? = sources.getById(id)?.toRecord(json)

    // Each library write also bumps the lineup generation, in the same transaction, so a saved lineup is never trusted past one.
    override suspend fun insertSource(record: SourceRecord): Long = db.withTransaction {
        sources.insert(record.copy(id = 0).toEntity(json, clock())).also { lineup.bumpGeneration() }
    }

    override suspend fun updateSource(record: SourceRecord) = db.withTransaction {
        val existing = sources.getById(record.id) ?: return@withTransaction
        sources.update(record.toEntity(json, existing.createdAt))
        lineup.bumpGeneration()
    }

    override suspend fun deleteSource(id: Long) = db.withTransaction {
        sources.deleteById(id)
        lineup.bumpGeneration()
    }

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
        genres.deleteSeriesGenres(sourceId)
        genres.insertSeriesGenres(catalog.series.flatMap { s -> s.genres.map { SeriesGenreEntity(sourceId, s.id, it.name) } })
        // Prune hidden rows for categories that are gone, but only for kinds the panel actually
        // listed this time: an empty list on a bad day must not wipe the user's choices.
        ContentKind.entries
            .filter { kind -> catalog.categories.any { it.kind == kind } }
            .forEach { kind -> hidden.pruneMissing(sourceId, kind.name) }
        lineup.bumpGeneration()
    }

    override suspend fun beginCatalog(sourceId: Long): CatalogWriter = StagingCatalogWriter(db, sourceId).also { it.start() }

    override suspend fun replaceLive(sourceId: Long, live: LiveCatalog) = db.withTransaction {
        library.deleteCategoriesOfKind(sourceId, ContentKind.LIVE.name)
        library.deleteLive(sourceId)
        library.insertCategories(live.categories.map { it.toEntity() })
        library.insertLive(live.live.map { it.toEntity() })
        if (live.categories.isNotEmpty()) hidden.pruneMissing(sourceId, ContentKind.LIVE.name)
        lineup.bumpGeneration()
    }

    override fun observeSportsCandidates(): Flow<List<ChannelCandidate>> =
        library.observeSportsCandidates().map { rows -> rows.map { ChannelCandidate(it.live.toDomain(), it.categoryName) } }

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

    override fun observeMoviesByCategory(limitPerCategory: Int): Flow<Map<CategoryKey, List<Movie>>> =
        library.observeEnabledMovies()
            .map { rows -> rows.groupBy { CategoryKey(it.sourceId, it.categoryId!!) }.mapValues { (_, v) -> v.take(limitPerCategory).map { it.toDomain() } } }
            .distinctUntilChanged()

    override fun observeSeriesByCategory(limitPerCategory: Int): Flow<Map<CategoryKey, List<Series>>> =
        library.observeEnabledSeries()
            .map { rows -> rows.groupBy { CategoryKey(it.sourceId, it.categoryId!!) }.mapValues { (_, v) -> v.take(limitPerCategory).map { it.toDomain() } } }
            .distinctUntilChanged()

    override fun searchLive(query: String, limit: Int): Flow<List<LiveChannel>> =
        library.searchLive(escapeLike(query), limit).map { rows -> rows.map { it.toDomain() } }

    override fun searchMovies(query: String, limit: Int, genre: Genre?): Flow<List<Movie>> =
        (if (genre == null) library.searchMovies(escapeLike(query), limit) else genres.searchMovies(escapeLike(query), genre.name, limit))
            .map { rows -> rows.map { it.toDomain() } }

    override fun searchSeries(query: String, limit: Int, genre: Genre?): Flow<List<Series>> =
        (if (genre == null) library.searchSeries(escapeLike(query), limit) else genres.searchSeries(escapeLike(query), genre.name, limit))
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeMoviesByGenre(genre: Genre, limit: Int): Flow<List<Movie>> =
        genres.observeMovies(genre.name, limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeSeriesByGenre(genre: Genre, limit: Int): Flow<List<Series>> =
        genres.observeSeries(genre.name, limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeGenreCounts(kind: ContentKind): Flow<Map<Genre, Int>> =
        when (kind) {
            ContentKind.MOVIE -> genres.observeMovieCounts()
            ContentKind.SERIES -> genres.observeSeriesCounts()
            ContentKind.LIVE -> flowOf(emptyList())
        }.map { rows -> rows.mapNotNull { r -> Genre.entries.firstOrNull { it.name == r.genre }?.let { it to r.count } }.toMap() }

    override fun observeAllCategories(sourceId: Long, kind: ContentKind): Flow<List<Category>> =
        library.observeAllCategories(sourceId, kind.name).map { rows -> rows.map { it.toDomain() } }

    override fun observeHiddenCategories(sourceId: Long): Flow<Set<Pair<ContentKind, String>>> =
        hidden.observeForSource(sourceId).map { rows -> rows.map { ContentKind.valueOf(it.kind) to it.categoryId }.toSet() }

    override fun observeHiddenCount(kind: ContentKind): Flow<Int> = hidden.observeHiddenCount(kind.name)

    override suspend fun setCategoriesHidden(sourceId: Long, kind: ContentKind, ids: Collection<String>, hidden: Boolean) {
        if (ids.isEmpty()) return
        db.withTransaction {
            if (hidden) this.hidden.insertAll(ids.map { HiddenCategoryEntity(sourceId, kind.name, it) })
            else ids.chunked(500).forEach { this.hidden.delete(sourceId, kind.name, it) }
            lineup.bumpGeneration()
        }
    }

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

/** Escapes LIKE wildcards so a query such as "100%" matches literally (paired with `ESCAPE '\'`). */
internal fun escapeLike(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * Writes a sync's lists into the staging tables as they arrive, each chunk of at most [CHUNK] rows in
 * its own short transaction, so no write lock is held across a download. [commit] then swaps staging
 * into the real tables in one transaction and leaves the same rows as [RoomLibraryStore.replaceCatalog]
 * given the lists as one catalog. Categories keep its row order too; live channels, movies, series and
 * series genres are diffed, so only new or changed rows are written and the rest keep their rowids
 * (every list query on them orders explicitly). Until the commit the library is untouched.
 * One writer per source at a time (SyncManager runs one refresh per source).
 */
internal class StagingCatalogWriter(private val db: GoodTvDatabase, private val sourceId: Long) : CatalogWriter {
    private val mutex = Mutex()
    /** Kinds whose written category list was non-empty: only those prune hidden choices, as replaceCatalog does. */
    private val listed = EnumSet.noneOf(ContentKind::class.java)
    /** No more writes: committed or abandoned. */
    private var closed = false
    /** Staging holds nothing of this writer's: it committed, or abandon cleared it. A later abandon must not touch another sync's rows. */
    private var finished = false

    /** Clears what an earlier sync of this source left behind (the process died before it committed or abandoned). */
    suspend fun start() = db.withTransaction { STAGING_TABLES.forEach { removeSourceRows(it) } }

    override suspend fun categories(kind: ContentKind, list: List<Category>) = write {
        if (list.isNotEmpty()) listed += kind
        stage(CATEGORIES, list) { i, c -> val e = c.toEntity(); bindLong(i, e.sourceId); bindString(i + 1, e.kind); bindString(i + 2, e.id); bindString(i + 3, e.name) }
    }

    override suspend fun live(list: List<LiveChannel>) = write {
        stage(LIVE, list) { i, l ->
            val e = l.toEntity()
            bindLong(i, e.sourceId); bindString(i + 1, e.id); bindText(i + 2, e.categoryId); bindString(i + 3, e.name); bindText(i + 4, e.logoUrl)
            bindNumber(i + 5, e.number?.toLong()); bindText(i + 6, e.title); bindText(i + 7, e.language); bindText(i + 8, e.quality); bindText(i + 9, e.epgChannelId)
        }
    }

    override suspend fun movies(list: List<Movie>) = write {
        stage(MOVIES, list) { i, m ->
            val e = m.toEntity()
            bindLong(i, e.sourceId); bindString(i + 1, e.id); bindText(i + 2, e.categoryId); bindString(i + 3, e.name); bindText(i + 4, e.posterUrl)
            bindString(i + 5, e.containerExtension); bindReal(i + 6, e.rating); bindNumber(i + 7, e.addedAt); bindNumber(i + 8, e.tmdbId)
            bindText(i + 9, e.title); bindText(i + 10, e.language); bindText(i + 11, e.quality)
        }
    }

    /** Series genres are derived per chunk, in replaceCatalog's order, so no catalog-wide list is built. */
    override suspend fun series(list: List<Series>) = write {
        forEachChunk(list) { chunk ->
            db.withTransaction { insertRows(SERIES, chunk) { i, s -> bindSeries(i, s) } }
            val genres = chunk.flatMap { s -> s.genres.map { SeriesGenreEntity(sourceId, s.id, it.name) } }
            stage(SERIES_GENRES, genres) { i, g -> bindLong(i, g.sourceId); bindString(i + 1, g.seriesId); bindString(i + 2, g.genre) }
        }
    }

    override suspend fun commit() = mutex.withLock {
        check(!closed) { "Catalog writer already committed or abandoned" }
        closed = true
        db.withTransaction {
            removeSourceRows(CATEGORIES.real)
            val library = db.library()
            library.deleteMovieDetails(sourceId)
            library.deleteSeriesInfo(sourceId)
            library.deleteSeasons(sourceId)
            library.deleteEpisodes(sourceId)
            // Categories in replaceCatalog's order: by kind, then as written.
            val kindOrder = ContentKind.entries.joinToString(" ", "CASE kind ", " END") { "WHEN '${it.name}' THEN ${it.ordinal}" }
            copy(CATEGORIES, "$kindOrder, seq")
            DIFFED.forEach { swapChanged(it) }
            // Prune hidden rows for categories that are gone, but only for kinds the panel actually
            // listed this time: an empty list on a bad day must not wipe the user's choices.
            ContentKind.entries.filter { it in listed }.forEach { db.hidden().pruneMissing(sourceId, it.name) }
            db.lineup().bumpGeneration()
            STAGING_TABLES.forEach { removeSourceRows(it) }
        }
        finished = true
    }

    override suspend fun abandon() = mutex.withLock {
        closed = true
        if (!finished) {
            db.withTransaction { STAGING_TABLES.forEach { removeSourceRows(it) } }
            finished = true
        }
    }

    private suspend fun write(block: suspend () -> Unit) = mutex.withLock {
        check(!closed) { "Catalog writer already committed or abandoned" }
        block()
    }

    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    private suspend fun <T> forEachChunk(list: List<T>, block: suspend (List<T>) -> Unit) {
        var from = 0
        while (from < list.size) {
            val to = minOf(from + CHUNK, list.size)
            block(list.subList(from, to))
            from = to
        }
    }

    private suspend fun <T> stage(table: Table, list: List<T>, bind: SupportSQLiteStatement.(Int, T) -> Unit) =
        forEachChunk(list) { chunk -> db.withTransaction { insertRows(table, chunk, bind) } }

    /** Multi-row INSERTs, each within SQLite's 999 bound parameters. Call inside a transaction. */
    private fun <T> insertRows(table: Table, rows: List<T>, bind: SupportSQLiteStatement.(Int, T) -> Unit) {
        val perStatement = MAX_PARAMS / table.columns.size
        var full: SupportSQLiteStatement? = null
        try {
            var from = 0
            while (from < rows.size) {
                val n = minOf(perStatement, rows.size - from)
                val statement = if (n == perStatement) full ?: compileInsert(table, n).also { full = it } else compileInsert(table, n)
                try {
                    for (r in 0 until n) statement.bind(r * table.columns.size + 1, rows[from + r])
                    statement.executeInsert()
                } finally {
                    if (statement !== full) statement.close()
                }
                from += n
            }
        } finally {
            full?.close()
        }
    }

    private fun compileInsert(table: Table, rows: Int): SupportSQLiteStatement {
        val row = table.columns.joinToString(",", "(", ")") { "?" }
        return sql.compileStatement(
            "INSERT INTO ${table.staging} (${table.columnList}) VALUES " + List(rows) { row }.joinToString(","),
        )
    }

    /** Today's DAO inserts are REPLACE: a repeated id keeps the last row, so staging is copied in arrival order. */
    private fun copy(table: Table, orderBy: String = "seq") = sql.execSQL(
        "INSERT OR REPLACE INTO ${table.real} (${table.columnList}) SELECT ${table.columnList} FROM ${table.staging} WHERE sourceId = ? ORDER BY $orderBy",
        arrayOf<Any?>(sourceId),
    )

    /**
     * Makes this source's rows of [table] equal its staged rows while writing only what differs. A temp
     * table keyed like the real one takes the staged rows in arrival order with REPLACE, so it holds the
     * last row per key, as a full copy would leave. Then real rows whose key is gone are deleted, and
     * staged rows that are new or differ in any column (NULL-safe `IS`) are written. Call inside the
     * commit's transaction.
     */
    private fun swapChanged(table: Table) {
        val last = "temp.sync_${table.real}"
        val key = table.columns.filter { it in table.key }
        sql.execSQL("DROP TABLE IF EXISTS $last")
        // No declared types: values keep the storage classes staging gave them, so `IS` compares like for like.
        sql.execSQL("CREATE TABLE $last (${table.columnList}, PRIMARY KEY (${key.joinToString(",")})) WITHOUT ROWID")
        sql.execSQL(
            "INSERT OR REPLACE INTO $last SELECT ${table.columnList} FROM ${table.staging} WHERE sourceId = ? ORDER BY seq",
            arrayOf<Any?>(sourceId),
        )
        // The outer row is named by its table, not an alias, in the subquery.
        val keyGone = key.joinToString(" AND ") { "t.$it = ${table.real}.$it" }
        sql.execSQL(
            "DELETE FROM ${table.real} WHERE sourceId = ? AND NOT EXISTS (SELECT 1 FROM $last t WHERE $keyGone)",
            arrayOf<Any?>(sourceId),
        )
        val sameKey = key.joinToString(" AND ") { "t.$it = r.$it" }
        val sameRow = (listOf("r.sourceId = ?", sameKey) + table.columns.filter { it != "sourceId" && it !in key }.map { "r.$it IS t.$it" })
            .joinToString(" AND ")
        sql.execSQL(
            "INSERT OR REPLACE INTO ${table.real} (${table.columnList}) SELECT ${table.columnList} FROM $last t " +
                "WHERE NOT EXISTS (SELECT 1 FROM ${table.real} r WHERE $sameRow)",
            arrayOf<Any?>(sourceId),
        )
        sql.execSQL("DROP TABLE $last")
    }

    /**
     * When no other source has rows in [table], an unconditional DELETE skips the per-source lookup. On the
     * staging tables SQLite truncates at once; the real tables' foreign keys make it delete row by row.
     */
    private fun removeSourceRows(table: String) {
        val others = sql.query(
            "SELECT EXISTS(SELECT 1 FROM $table WHERE sourceId < ?) OR EXISTS(SELECT 1 FROM $table WHERE sourceId > ?)",
            arrayOf<Any?>(sourceId, sourceId),
        ).use { it.moveToFirst() && it.getInt(0) != 0 }
        if (others) sql.execSQL("DELETE FROM $table WHERE sourceId = ?", arrayOf<Any?>(sourceId)) else sql.execSQL("DELETE FROM $table")
    }

    private fun SupportSQLiteStatement.bindSeries(i: Int, s: Series) {
        val e = s.toEntity()
        bindLong(i, e.sourceId); bindString(i + 1, e.id); bindText(i + 2, e.categoryId); bindString(i + 3, e.name); bindText(i + 4, e.coverUrl)
        bindText(i + 5, e.plot); bindReal(i + 6, e.rating); bindNumber(i + 7, e.tmdbId); bindText(i + 8, e.title); bindText(i + 9, e.language); bindText(i + 10, e.quality)
    }

    private fun SupportSQLiteStatement.bindText(i: Int, v: String?) = if (v == null) bindNull(i) else bindString(i, v)
    private fun SupportSQLiteStatement.bindNumber(i: Int, v: Long?) = if (v == null) bindNull(i) else bindLong(i, v)
    private fun SupportSQLiteStatement.bindReal(i: Int, v: Double?) = if (v == null) bindNull(i) else bindDouble(i, v)

    /**
     * A real table, its staging twin and their shared columns, in the order the binders above fill them;
     * [key] is the real table's primary key within a source.
     */
    private class Table(val real: String, val staging: String, val columns: List<String>, val key: Set<String> = setOf("id")) {
        val columnList = columns.joinToString(",")
    }

    private companion object {
        const val CHUNK = 5_000
        const val MAX_PARAMS = 999
        val CATEGORIES = Table("categories", "staging_categories", listOf("sourceId", "kind", "id", "name"), setOf("kind", "id"))
        val LIVE = Table(
            "live_channels", "staging_live",
            listOf("sourceId", "id", "categoryId", "name", "logoUrl", "number", "title", "language", "quality", "epgChannelId"),
        )
        val MOVIES = Table(
            "movies", "staging_movies",
            listOf("sourceId", "id", "categoryId", "name", "posterUrl", "containerExtension", "rating", "addedAt", "tmdbId", "title", "language", "quality"),
        )
        val SERIES = Table(
            "series", "staging_series",
            listOf("sourceId", "id", "categoryId", "name", "coverUrl", "plot", "rating", "tmdbId", "title", "language", "quality"),
        )
        val SERIES_GENRES = Table("series_genres", "staging_series_genres", listOf("sourceId", "seriesId", "genre"), setOf("seriesId", "genre"))
        val ALL = listOf(CATEGORIES, LIVE, MOVIES, SERIES, SERIES_GENRES)
        /** Swapped by diff; categories keep the full replace, which also keeps the panel's order (ruling R8). */
        val DIFFED = listOf(LIVE, MOVIES, SERIES, SERIES_GENRES)
        val STAGING_TABLES = ALL.map { it.staging }
    }
}
