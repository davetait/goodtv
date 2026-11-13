package com.keyspaceapps.goodtv.data.db

import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The staging writer must leave exactly the rows replaceCatalog leaves, all columns, and nothing of a
 * sync may show (or stay behind) until it commits. Categories keep replaceCatalog's row order; live
 * channels, movies, series and series genres are compared by key, because the swap rewrites only rows
 * that changed and so keeps the rowids of the rest.
 */
class CatalogStagingTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var store: RoomLibraryStore
    private lateinit var refDb: GoodTvDatabase
    private lateinit var ref: RoomLibraryStore

    private val realTables = listOf("categories", "live_channels", "movies", "series", "series_genres", "hidden_categories")
    private val stagingTables = listOf("staging_categories", "staging_live", "staging_movies", "staging_series", "staging_series_genres")

    @Before
    fun setUp() {
        val json = Json { ignoreUnknownKeys = true }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        store = RoomLibraryStore(db, json)
        refDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        ref = RoomLibraryStore(refDb, json)
    }

    @After
    fun tearDown() {
        db.close()
        refDb.close()
    }

    private fun record(nick: String) = SourceRecord(
        id = 0, type = SourceType.XTREAM, nickname = nick,
        config = SourceConfig.Xtream("http://s", "u", "p", listOf("ts")), enabled = true, lastSyncAt = null, lastError = null,
    )

    /** The key of each table the swap diffs; the other tables keep storage order. */
    private val keys = mapOf(
        "live_channels" to "sourceId, id", "movies" to "sourceId, id", "series" to "sourceId, id",
        "series_genres" to "sourceId, seriesId, genre",
    )

    /** Every row of [table], all columns with their SQLite types, by key for the diffed tables, else in storage order. */
    private fun dump(d: GoodTvDatabase, table: String, where: String = ""): List<List<Any?>> =
        d.openHelper.readableDatabase.query("SELECT * FROM $table $where ORDER BY ${keys[table] ?: "rowid"}").use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { i -> c.cell(i) })
            }
        }

    private fun Cursor.cell(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_STRING -> getString(i)
        else -> getBlob(i).toList()
    }

    /** Each row's rowid, by its key. */
    private fun rowids(d: GoodTvDatabase, table: String): Map<String, Long> =
        d.openHelper.readableDatabase.query("SELECT ${keys.getValue(table).replace(", ", " || '/' || ")}, rowid FROM $table").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }

    private fun count(d: GoodTvDatabase, table: String): Int =
        d.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getInt(0) }

    private fun library(d: GoodTvDatabase) = realTables.associateWith { dump(d, it) }

    private fun assertStagingEmpty(d: GoodTvDatabase) = stagingTables.forEach { assertEquals("$it is empty", 0, count(d, it)) }

    private fun oldCatalog(sid: Long, tag: String = "old") = Catalog(
        categories = listOf(
            Category(sid, ContentKind.LIVE, "c1", "Sports $tag"),
            Category(sid, ContentKind.LIVE, "gone", "Gone $tag"),
            Category(sid, ContentKind.MOVIE, "m1", "Action $tag"),
            Category(sid, ContentKind.SERIES, "s1", "Drama $tag"),
        ),
        live = (1..30).map { LiveChannel(sid, "L$it", "c1", "$tag channel $it", null, it) },
        movies = (1..40).map { Movie(sid, "M$it", "m1", "$tag movie $it", null, "mkv", 7.0, null, tmdbId = it.toLong()) },
        series = (1..20).map { Series(sid, "S$it", "s1", "$tag series $it", null, null, null, genres = setOf(Genre.DRAMA)) },
    )

    // Sizes cross the 5,000-row chunk and every per-statement row limit; duplicates check last-row-wins.
    private fun newLive(sid: Long) = (1..6_003).map {
        LiveChannel(
            sid, "L$it", if (it % 7 == 0) null else "c${it % 3}", "UK: Channel $it ᴴᴰ", if (it % 2 == 0) "http://logo/$it" else null,
            if (it % 5 == 0) null else it, title = "Channel $it", language = if (it % 4 == 0) "EN" else null,
            quality = if (it % 3 == 0) "HD" else null, epgChannelId = if (it % 6 == 0) "ch$it.uk" else null,
        )
    } + LiveChannel(sid, "L10", "c1", "Duplicate wins", null, 10)

    private fun newMovies(sid: Long) = (1..12_345).map {
        Movie(
            sid, "M$it", if (it % 11 == 0) null else "m${it % 4}", "EN - Film $it (19${it % 100})", if (it % 2 == 0) "http://p/$it" else null,
            if (it % 3 == 0) "mp4" else "mkv", if (it % 5 == 0) null else (it % 100) / 10.0, if (it % 9 == 0) null else 1_600_000_000L + it,
            tmdbId = if (it % 4 == 0) null else it * 3L, title = "Film $it (19${it % 100})", language = if (it % 2 == 0) "EN" else null,
            quality = if (it % 7 == 0) "4K" else null,
        )
    } + Movie(sid, "M7", "m1", "Duplicate movie", null, "avi", 1.5, null, tmdbId = 99L)

    private fun newSeries(sid: Long) = (1..5_432).map {
        Series(
            sid, "S$it", if (it % 13 == 0) null else "s${it % 2}", "Show $it", if (it % 2 == 0) "http://c/$it" else null,
            if (it % 3 == 0) "Plot $it" else null, if (it % 4 == 0) null else 6.5, tmdbId = if (it % 5 == 0) null else it.toLong(),
            genres = when (it % 4) {
                0 -> emptySet()
                1 -> setOf(Genre.DRAMA)
                2 -> linkedSetOf(Genre.CRIME, Genre.THRILLER, Genre.MYSTERY)
                else -> linkedSetOf(Genre.COMEDY, Genre.FAMILY)
            },
            title = "Show $it", language = if (it % 3 == 0) "FR" else null, quality = if (it % 2 == 0) "HD" else null,
        )
    } + Series(sid, "S3", "s1", "Duplicate show", null, null, null, genres = setOf(Genre.WAR))

    private fun liveCats(sid: Long) = listOf(Category(sid, ContentKind.LIVE, "c0", "News"), Category(sid, ContentKind.LIVE, "c1", "Sports"))
    private fun moreLiveCats(sid: Long) = listOf(Category(sid, ContentKind.LIVE, "c2", "Kids"), Category(sid, ContentKind.LIVE, "c1", "Sports again"))
    private fun movieCats(sid: Long) = (0..3).map { Category(sid, ContentKind.MOVIE, "m$it", "Movies $it") }
    private fun seriesCats(sid: Long) = (0..1).map { Category(sid, ContentKind.SERIES, "s$it", "Series $it") }

    private suspend fun seed(s: RoomLibraryStore, d: GoodTvDatabase): Long {
        val sid = s.insertSource(record("Home"))
        s.replaceCatalog(sid, oldCatalog(sid))
        d.hidden().insertAll(
            listOf(
                HiddenCategoryEntity(sid, "LIVE", "c1"), HiddenCategoryEntity(sid, "LIVE", "gone"),
                HiddenCategoryEntity(sid, "MOVIE", "m1"), HiddenCategoryEntity(sid, "SERIES", "gone-too"),
            ),
        )
        return sid
    }

    /** Writes [catalog] through a staging writer, each kind's categories then its list, and commits. */
    private suspend fun commit(s: RoomLibraryStore, sid: Long, catalog: Catalog) {
        val w = s.beginCatalog(sid)
        for (kind in ContentKind.entries) w.categories(kind, catalog.categories.filter { it.kind == kind })
        w.live(catalog.live)
        w.movies(catalog.movies)
        w.series(catalog.series)
        w.commit()
    }

    private fun bigCatalog(sid: Long) =
        Catalog(liveCats(sid) + moreLiveCats(sid) + movieCats(sid) + seriesCats(sid), newLive(sid), newMovies(sid), newSeries(sid))

    @Test
    fun commitGivesTheSameRowsAsReplaceCatalog() = runBlocking {
        val sid = seed(store, db)
        assertEquals(sid, seed(ref, refDb))
        val live = newLive(sid)
        val movies = newMovies(sid)
        val series = newSeries(sid)
        ref.replaceCatalog(sid, Catalog(liveCats(sid) + moreLiveCats(sid) + movieCats(sid) + seriesCats(sid), live, movies, series))

        val w = store.beginCatalog(sid)
        // Out of order, in pieces, from several coroutines at once, as the downloads hand them on.
        w.series(series.subList(0, 2_000))
        w.categories(ContentKind.SERIES, seriesCats(sid))
        listOf(
            async { w.categories(ContentKind.LIVE, liveCats(sid)) },
            async { w.categories(ContentKind.MOVIE, movieCats(sid)) },
        ).awaitAll()
        w.movies(movies.subList(0, 5_000))
        w.series(series.subList(2_000, series.size))
        w.movies(movies.subList(5_000, movies.size))
        w.categories(ContentKind.LIVE, moreLiveCats(sid))
        w.live(live)
        w.commit()
        w.abandon() // safe after commit

        for (t in realTables) assertEquals(t, dump(refDb, t), dump(db, t))
        assertEquals(refDb.lineup().generation(), db.lineup().generation())
        assertTrue(count(db, "movies") > 12_000)
        assertStagingEmpty(db)
    }

    @Test
    fun piecesGiveTheSameRowsAsOneList() = runBlocking {
        val sid = seed(store, db)
        assertEquals(sid, seed(ref, refDb))
        val catalog = bigCatalog(sid)
        commit(ref, sid, catalog)

        // Each kind in pieces of 5,000 (as the sync streams them) and of an odd size that splits rows
        // across staging chunks; kinds interleave, but each kind's pieces arrive in list order.
        val w = store.beginCatalog(sid)
        for (kind in ContentKind.entries) catalog.categories.filter { it.kind == kind }.chunked(3).forEach { w.categories(kind, it) }
        val live = catalog.live.chunked(5_000).iterator()
        val movies = catalog.movies.chunked(5_000).iterator()
        val series = catalog.series.chunked(1_234).iterator()
        while (live.hasNext() || movies.hasNext() || series.hasNext()) {
            if (movies.hasNext()) w.movies(movies.next())
            if (series.hasNext()) w.series(series.next())
            if (live.hasNext()) w.live(live.next())
        }
        w.commit()

        for (t in realTables) assertEquals(t, dump(refDb, t), dump(db, t))
        assertTrue(count(db, "series_genres") > 5_000)
        assertStagingEmpty(db)
    }

    @Test
    fun failureAfterStagingKeepsTheLibrary() = runBlocking {
        val sid = seed(store, db)
        val before = library(db)
        val generation = db.lineup().generation()

        val w = store.beginCatalog(sid)
        w.categories(ContentKind.MOVIE, movieCats(sid))
        w.movies(newMovies(sid))
        w.abandon()
        w.abandon()

        assertEquals(before, library(db))
        assertEquals(generation, db.lineup().generation())
        assertStagingEmpty(db)
    }

    @Test
    fun twoSourcesOnlyTheSyncedOneChanges() = runBlocking {
        val a = seed(store, db)
        val b = store.insertSource(record("Other"))
        store.replaceCatalog(b, oldCatalog(b, "other"))
        db.hidden().insertAll(listOf(HiddenCategoryEntity(b, "LIVE", "gone")))
        val bBefore = realTables.associateWith { dump(db, it, "WHERE sourceId = $b") }
        // The reference has the same two sources, so the staging path must take the per-source deletes.
        assertEquals(a, seed(ref, refDb))
        assertEquals(b, ref.insertSource(record("Other")))
        ref.replaceCatalog(b, oldCatalog(b, "other"))
        refDb.hidden().insertAll(listOf(HiddenCategoryEntity(b, "LIVE", "gone")))
        val live = newLive(a).take(100)
        val movies = newMovies(a).take(100)
        val series = newSeries(a).take(100)
        ref.replaceCatalog(a, Catalog(liveCats(a) + movieCats(a) + seriesCats(a), live, movies, series))

        val w = store.beginCatalog(a)
        w.categories(ContentKind.LIVE, liveCats(a))
        w.categories(ContentKind.MOVIE, movieCats(a))
        w.categories(ContentKind.SERIES, seriesCats(a))
        w.live(live)
        w.movies(movies)
        w.series(series)
        w.commit()

        assertEquals(bBefore, realTables.associateWith { dump(db, it, "WHERE sourceId = $b") })
        for (t in realTables) assertEquals(t, dump(refDb, t, "WHERE sourceId = $a"), dump(db, t, "WHERE sourceId = $a"))
        assertNotEquals(0, count(db, "movies"))
        assertStagingEmpty(db)
    }

    @Test
    fun commitWritesOnlyChangedRows() = runBlocking {
        val sid = seed(store, db)
        assertEquals(sid, seed(ref, refDb))
        val before = bigCatalog(sid)
        store.replaceCatalog(sid, before)
        ref.replaceCatalog(sid, before)
        val ids = keys.keys.associateWith { rowids(db, it) }

        val movies = before.movies.filter { it.id != "M20" && it.id != "M21" }.map {
            when (it.id) {
                "M30" -> it.copy(name = "Renamed") // text
                "M32" -> it.copy(language = null) // nullable text, "EN" to NULL
                "M33" -> it.copy(rating = 9.9) // number
                "M40" -> it.copy(tmdbId = 123L) // NULL to a value
                else -> it
            }
        } + listOf(Movie(sid, "M-new-1", "m1", "New one", null, "mkv", null, null), Movie(sid, "M-new-2", "m2", "New two", null, "mp4", 5.0, 1L))
        val series = before.series.map { if (it.id == "S2") it.copy(genres = setOf(Genre.CRIME, Genre.HISTORY)) else it }
        val live = before.live.map { if (it.id == "L4") it.copy(epgChannelId = "new.uk") else it }
        val after = before.copy(live = live, movies = movies, series = series)
        assertEquals("M32 really had a language", "EN", before.movies.first { it.id == "M32" }.language)
        assertEquals("M40 really had no TMDB id", null, before.movies.first { it.id == "M40" }.tmdbId)
        ref.replaceCatalog(sid, after)
        commit(store, sid, after)

        for (t in realTables) assertEquals(t, dump(refDb, t), dump(db, t))
        val changed = mapOf(
            "movies" to setOf("$sid/M30", "$sid/M32", "$sid/M33", "$sid/M40", "$sid/M-new-1", "$sid/M-new-2"),
            "live_channels" to setOf("$sid/L4"),
            "series" to emptySet(),
            "series_genres" to setOf("$sid/S2/HISTORY"), // S2 keeps CRIME, loses THRILLER and MYSTERY
        )
        for ((t, old) in ids) {
            val now = rowids(db, t)
            for ((key, rowid) in now) if (key !in changed.getValue(t)) assertEquals("$t $key keeps its rowid", old[key], rowid)
            // A written row gets a rowid above every old one (a rewrite of all rows would reuse the old rowids in order).
            for (key in changed.getValue(t)) assertTrue("$t $key is written", now.getValue(key) > old.values.max())
        }
        assertTrue("M20, M21 are gone", rowids(db, "movies").keys.none { it == "$sid/M20" || it == "$sid/M21" })
        assertTrue("S2's other genres are gone", rowids(db, "series_genres").keys.none { it.startsWith("$sid/S2/") && !it.endsWith("CRIME") && !it.endsWith("HISTORY") })
        val m = db.library().getMovie(sid, "M32")!!
        assertEquals(null, m.language)
        assertEquals("Renamed", db.library().getMovie(sid, "M30")!!.name)
        assertEquals(9.9, db.library().getMovie(sid, "M33")!!.rating!!, 0.0)
        assertEquals(123L, db.library().getMovie(sid, "M40")!!.tmdbId)
        assertStagingEmpty(db)
    }

    @Test
    fun aFirstSyncIntoAnEmptyLibraryMatchesReplaceCatalog() = runBlocking {
        // Another source has rows, so only this source starts empty.
        val other = store.insertSource(record("Other"))
        store.replaceCatalog(other, oldCatalog(other, "other"))
        assertEquals(other, ref.insertSource(record("Other")))
        ref.replaceCatalog(other, oldCatalog(other, "other"))
        val sid = store.insertSource(record("Home"))
        assertEquals(sid, ref.insertSource(record("Home")))
        ref.replaceCatalog(sid, bigCatalog(sid))

        commit(store, sid, bigCatalog(sid))

        for (t in realTables) assertEquals(t, dump(refDb, t), dump(db, t))
        assertTrue(count(db, "movies") > 12_000)
        assertStagingEmpty(db)
    }

    @Test
    fun aFailureDuringTheSwapKeepsTheLibrary() = runBlocking {
        val sid = seed(store, db)
        val before = library(db)
        val generation = db.lineup().generation()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER boom BEFORE INSERT ON movies WHEN NEW.id = 'boom' BEGIN SELECT RAISE(ABORT, 'boom'); END")

        val w = store.beginCatalog(sid)
        w.categories(ContentKind.LIVE, liveCats(sid))
        w.categories(ContentKind.MOVIE, movieCats(sid))
        w.live(newLive(sid))
        w.movies(newMovies(sid) + Movie(sid, "boom", "m1", "Boom", null, "mkv", null, null))
        w.series(newSeries(sid))
        val failure = runCatching { w.commit() }.exceptionOrNull()
        assertTrue("commit failed in the swap: $failure", failure?.message?.contains("boom") == true)

        assertEquals(before, library(db))
        assertEquals(generation, db.lineup().generation())
        w.abandon()
        assertStagingEmpty(db)
        val temps = sql.query("SELECT name FROM sqlite_temp_master WHERE type = 'table' AND name LIKE 'sync_%'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals("no temp table left behind", emptyList<String>(), temps)

        sql.execSQL("DROP TRIGGER boom")
        assertEquals(sid, seed(ref, refDb))
        ref.replaceCatalog(sid, bigCatalog(sid))
        commit(store, sid, bigCatalog(sid))
        for (t in realTables) assertEquals(t, dump(refDb, t), dump(db, t))
        assertStagingEmpty(db)
    }

    @Test
    fun aSecondIdenticalSyncChangesNothing() = runBlocking {
        val sid = seed(store, db)
        commit(store, sid, bigCatalog(sid))
        // Another source's rows above this one's, so a rewrite could not land on the same rowids.
        val other = store.insertSource(record("Other"))
        store.replaceCatalog(other, oldCatalog(other, "other"))
        val first = keys.keys.associateWith { rowids(db, it) }
        val rows = library(db)
        assertTrue(first.getValue("movies").size > 12_000)

        commit(store, sid, bigCatalog(sid))

        for ((t, ids) in first) assertEquals("$t rowids", ids, rowids(db, t))
        assertEquals(rows - "categories", library(db) - "categories") // categories are fully replaced (ruling R8)
        assertStagingEmpty(db)
    }

    @Test
    fun emptyKindReplacesRowsButKeepsHiddenChoices() = runBlocking {
        val sid = seed(store, db)

        val w = store.beginCatalog(sid)
        w.categories(ContentKind.LIVE, liveCats(sid))
        w.categories(ContentKind.MOVIE, emptyList())
        w.movies(emptyList())
        w.categories(ContentKind.SERIES, seriesCats(sid))
        w.series(newSeries(sid).take(10))
        w.commit()

        assertEquals(0, count(db, "movies"))
        val kinds = dump(db, "categories").map { it[1] }.toSet()
        assertEquals(setOf("LIVE", "SERIES"), kinds)
        val hidden = dump(db, "hidden_categories").map { it[1] to it[2] }.toSet()
        // Movies were listed empty: their hidden choice stays. Live and series prune what is gone.
        assertEquals(setOf("LIVE" to "c1", "MOVIE" to "m1"), hidden)
        assertStagingEmpty(db)
    }

    @Test
    fun nothingVisibleBeforeCommit() = runBlocking {
        val sid = seed(store, db)
        val before = library(db)

        val w = store.beginCatalog(sid)
        w.categories(ContentKind.LIVE, liveCats(sid))
        w.categories(ContentKind.MOVIE, movieCats(sid))
        w.live(newLive(sid))
        w.movies(newMovies(sid))
        w.series(newSeries(sid))

        assertEquals(before, library(db))
        assertTrue(count(db, "staging_movies") > 12_000)
        w.commit()
        assertNotEquals(before, library(db))
    }

    @Test
    fun aNewSyncStartsWithCleanStaging() = runBlocking {
        val sid = seed(store, db)
        val first = store.beginCatalog(sid)
        first.categories(ContentKind.MOVIE, listOf(Category(sid, ContentKind.MOVIE, "dead", "From the dead sync")))
        first.movies(listOf(Movie(sid, "ghost", "dead", "Ghost", null, "mkv", null, null)))
        first.series(listOf(Series(sid, "ghost", "dead", "Ghost show", null, null, null, genres = setOf(Genre.HORROR))))
        // The process dies here: neither commit nor abandon runs.

        val second = store.beginCatalog(sid)
        second.categories(ContentKind.MOVIE, movieCats(sid))
        second.movies(listOf(Movie(sid, "real", "m1", "Real", null, "mkv", null, null)))
        second.commit()

        assertEquals(listOf("real"), dump(db, "movies").map { it[1] })
        assertEquals(listOf("m0", "m1", "m2", "m3"), dump(db, "categories").map { it[2] })
        assertEquals(0, count(db, "series"))
        assertEquals(0, count(db, "series_genres"))
        assertStagingEmpty(db)
    }

    @Test
    fun stagedRowsBelongToTheWritersSource() = runBlocking {
        val sid = store.insertSource(record("Home"))
        val other = sid + 100
        val w = store.beginCatalog(sid)
        w.categories(ContentKind.LIVE, listOf(Category(other, ContentKind.LIVE, "c1", "Sports")))
        w.categories(ContentKind.MOVIE, listOf(Category(other, ContentKind.MOVIE, "m1", "Films")))
        w.live(listOf(LiveChannel(other, "L1", "c1", "Channel 1", null, 1)))
        w.movies(listOf(Movie(other, "M1", "m1", "Movie 1", null, "mkv", null, null)))
        w.series(listOf(Series(other, "S1", null, "Series 1", null, null, null, genres = setOf(Genre.DRAMA))))
        w.commit()

        assertEquals(listOf("c1", "m1"), dump(db, "categories", "WHERE sourceId = $sid").map { it[2] })
        assertEquals(listOf("L1"), dump(db, "live_channels", "WHERE sourceId = $sid").map { it[1] })
        assertEquals(listOf("M1"), dump(db, "movies", "WHERE sourceId = $sid").map { it[1] })
        assertEquals(listOf("S1"), dump(db, "series", "WHERE sourceId = $sid").map { it[1] })
        assertEquals(1, dump(db, "series_genres", "WHERE sourceId = $sid").size)
        assertStagingEmpty(db)
    }

    @Test
    fun deletingASourceClearsItsStaging() = runBlocking {
        val gone = seed(store, db)
        val kept = store.insertSource(record("Kept"))
        for (sid in listOf(gone, kept)) {
            val w = store.beginCatalog(sid)
            w.categories(ContentKind.LIVE, liveCats(sid))
            w.live(newLive(sid).take(10))
            w.movies(newMovies(sid).take(10))
            w.series(newSeries(sid).take(10))
        }

        store.deleteSource(gone)

        stagingTables.forEach { assertEquals("$it has none of the deleted source's rows", 0, dump(db, it, "WHERE sourceId = $gone").size) }
        stagingTables.forEach { assertTrue("$it keeps the other source's rows", dump(db, it, "WHERE sourceId = $kept").isNotEmpty()) }
    }
}
