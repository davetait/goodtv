package com.keyspaceapps.goodtv.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Every schema upgrade must keep the user's sources and library. */
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GoodTvDatabase::class.java)

    @Test
    fun migrate1To2KeepsSourcesAndAddsHiddenCategories() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, NULL, NULL, 0)")
            execSQL("INSERT INTO categories (sourceId, kind, id, name) VALUES (1, 'LIVE', 'c1', 'Sports')")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT nickname FROM sources").use { c -> c.moveToFirst(); assertEquals("Home", c.getString(0)) }
        db.execSQL("INSERT INTO hidden_categories (sourceId, kind, categoryId) VALUES (1, 'LIVE', 'c1')")
        db.query("SELECT COUNT(*) FROM hidden_categories").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.close()
    }

    /** A v2 library keeps its movies and series; they gain an empty tmdbId and the TMDB cache tables exist. */
    @Test
    fun migrate2To3AddsTmdbIdsAndCacheTables() {
        helper.createDatabase(dbName, 2).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1700000000000, NULL, 0)")
            execSQL("INSERT INTO movies (sourceId, id, categoryId, name, posterUrl, containerExtension, rating, addedAt) VALUES (1, 'm1', 'c', 'The Matrix', NULL, 'mkv', 8.0, NULL)")
            execSQL("INSERT INTO series (sourceId, id, categoryId, name, coverUrl, plot, rating) VALUES (1, 's1', 'c', 'Dark', NULL, NULL, NULL)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3)

        db.query("SELECT name, tmdbId FROM movies").use { c -> c.moveToFirst(); assertEquals("The Matrix", c.getString(0)); assertTrue(c.isNull(1)) }
        db.query("SELECT name, tmdbId FROM series").use { c -> c.moveToFirst(); assertEquals("Dark", c.getString(0)); assertTrue(c.isNull(1)) }
        db.query("SELECT lastSyncAt FROM sources").use { c -> c.moveToFirst(); assertTrue("re-sync to fetch ids", c.isNull(0)) }
        db.execSQL("INSERT INTO tmdb_title_match (kind, queryKey, tmdbId, fetchedAt) VALUES ('MOVIE', 'heat|1995', NULL, 1)")
        db.query("SELECT COUNT(*) FROM tmdb_meta").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    /** v3 → v4 adds the genre tables and marks sources stale so the next sync fills series genres. */
    @Test
    fun migrate3To4AddsGenreTables() {
        helper.createDatabase(dbName, 3).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1700000000000, NULL, 0)")
            execSQL("INSERT INTO movies (sourceId, id, categoryId, name, posterUrl, containerExtension, rating, addedAt, tmdbId) VALUES (1, 'm1', 'c', 'Heat', NULL, 'mkv', NULL, NULL, 949)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        db.query("SELECT tmdbId FROM movies").use { c -> c.moveToFirst(); assertEquals(949L, c.getLong(0)) }
        db.query("SELECT lastSyncAt FROM sources").use { c -> c.moveToFirst(); assertTrue(c.isNull(0)) }
        db.execSQL("INSERT INTO series_genres (sourceId, seriesId, genre) VALUES (1, 's1', 'DRAMA')")
        db.execSQL("INSERT INTO tmdb_genres (kind, tmdbId, genre) VALUES ('MOVIE', 949, 'CRIME')")
        db.execSQL("INSERT INTO tmdb_genre_status (kind, tmdbId, fetchedAt) VALUES ('MOVIE', 949, 1)")
        db.close()
    }

    /** v4 → v5 adds clean-title columns; existing rows keep working on their raw name until the next sync. */
    @Test
    fun migrate4To5AddsTitleColumns() {
        helper.createDatabase(dbName, 4).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1700000000000, NULL, 0)")
            execSQL("INSERT INTO live_channels (sourceId, id, categoryId, name, logoUrl, number) VALUES (1, 'l1', 'c', 'UK: BBC ONE', NULL, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5)
        db.query("SELECT name, title, language FROM live_channels").use { c ->
            c.moveToFirst(); assertEquals("UK: BBC ONE", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
        }
        db.query("SELECT lastSyncAt FROM sources").use { c -> c.moveToFirst(); assertTrue(c.isNull(0)) }
        db.close()
    }
}
