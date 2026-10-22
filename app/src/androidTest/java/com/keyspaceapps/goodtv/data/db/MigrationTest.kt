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

    /** v5 → v6 adds watch progress; deleting a source removes its progress. */
    @Test
    fun migrate5To6AddsWatchProgress() {
        helper.createDatabase(dbName, 5).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1, NULL, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6)
        db.execSQL("INSERT INTO watch_progress (sourceId, kind, itemId, seriesId, positionMs, durationMs, finished, updatedAt) VALUES (1, 'MOVIE', 'm1', NULL, 60000, 0, 0, 1)")
        db.query("SELECT positionMs FROM watch_progress").use { c -> c.moveToFirst(); assertEquals(60000L, c.getLong(0)) }
        db.close()
    }

    /** v6 → v7 adds playback details to saved progress; existing rows keep their position. */
    @Test
    fun migrate6To7AddsPlaybackDetails() {
        helper.createDatabase(dbName, 6).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1, NULL, 0)")
            execSQL("INSERT INTO watch_progress (sourceId, kind, itemId, seriesId, positionMs, durationMs, finished, updatedAt) VALUES (1, 'MOVIE', 'm1', NULL, 60000, 0, 0, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7)
        db.query("SELECT positionMs, title, season FROM watch_progress").use { c ->
            c.moveToFirst()
            assertEquals(60000L, c.getLong(0))
            assertTrue(c.isNull(1) && c.isNull(2))
        }
        db.close()
    }

    /** v7 → v8 adds ranked TMDB lists; existing data is untouched. */
    @Test
    fun migrate7To8AddsTmdbLists() {
        helper.createDatabase(dbName, 7).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1, NULL, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8)
        db.execSQL("INSERT INTO tmdb_lists (listKey, fetchedAt) VALUES ('k', 1)")
        db.execSQL("INSERT INTO tmdb_list_items (listKey, rank, tmdbId) VALUES ('k', 0, 603)")
        db.query("SELECT tmdbId FROM tmdb_list_items").use { c -> c.moveToFirst(); assertEquals(603L, c.getLong(0)) }
        db.query("SELECT COUNT(*) FROM sources").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.close()
    }

    /** v8 → v9 adds the watch list; sources and watch progress survive, and the list goes with its source. */
    @Test
    fun migrate8To9AddsWatchList() {
        helper.createDatabase(dbName, 8).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1, NULL, 0)")
            execSQL("INSERT INTO watch_progress (sourceId, kind, itemId, seriesId, positionMs, durationMs, finished, updatedAt) VALUES (1, 'MOVIE', 'm1', NULL, 60000, 0, 0, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)
        db.query("SELECT positionMs FROM watch_progress").use { c -> c.moveToFirst(); assertEquals(60000L, c.getLong(0)) }
        db.query("SELECT COUNT(*) FROM watch_list").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.execSQL("INSERT INTO watch_list (sourceId, kind, itemId, savedAt) VALUES (1, 'MOVIE', 'm1', 5)")
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM sources WHERE id = 1")
        db.query("SELECT COUNT(*) FROM watch_list").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    /** v9 → v10 adds the guide tables and epgChannelId; sources, channels and the watch list survive. */
    @Test
    fun migrate9To10AddsGuide() {
        helper.createDatabase(dbName, 9).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1, NULL, 0)")
            execSQL("INSERT INTO live_channels (sourceId, id, categoryId, name, logoUrl, number) VALUES (1, 'l1', 'c', 'Fox News', NULL, 1)")
            execSQL("INSERT INTO watch_list (sourceId, kind, itemId, savedAt) VALUES (1, 'MOVIE', 'm1', 5)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10)
        db.query("SELECT name, epgChannelId FROM live_channels").use { c ->
            c.moveToFirst()
            assertEquals("Fox News", c.getString(0))
            assertTrue(c.isNull(1))
        }
        db.query("SELECT COUNT(*) FROM watch_list").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        for (t in listOf("guide_channels", "guide_programmes", "channel_guide", "favourite_channels", "guide_meta")) {
            db.query("SELECT COUNT(*) FROM $t").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
        db.execSQL("INSERT INTO channel_guide (sourceId, channelId, guideId) VALUES (1, 'l1', 'fox.us')")
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM sources WHERE id = 1")
        db.query("SELECT COUNT(*) FROM channel_guide").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    /** v10 → v11 adds the saved lineup tables; v10 data survives, the new tables start empty and the generation reads 0. */
    @Test
    fun migrate10To11AddsSavedLineup() {
        helper.createDatabase(dbName, 10).apply {
            execSQL("INSERT INTO sources (id, type, nickname, configJson, enabled, lastSyncAt, lastError, createdAt) VALUES (1, 'XTREAM', 'Home', '{\"type\":\"xtream\",\"server\":\"http://s\",\"username\":\"u\",\"password\":\"p\"}', 1, 1, NULL, 0)")
            execSQL("INSERT INTO live_channels (sourceId, id, categoryId, name, logoUrl, number, epgChannelId) VALUES (1, 'l1', 'c', 'Fox News', NULL, 1, 'fox.us')")
            execSQL("INSERT INTO favourite_channels (rowKey, addedAt) VALUES ('fox', 5)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11)
        db.query("SELECT name, epgChannelId FROM live_channels").use { c ->
            c.moveToFirst()
            assertEquals("Fox News", c.getString(0))
            assertEquals("fox.us", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM favourite_channels").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        for (t in listOf("lineup_rows", "lineup_feeds", "lineup_meta", "lineup_state")) {
            db.query("SELECT COUNT(*) FROM $t").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
        db.query("SELECT COALESCE(MAX(generation), 0) FROM lineup_state").use { c -> c.moveToFirst(); assertEquals(0L, c.getLong(0)) }
        // Feeds go with their row.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO lineup_rows (key, position, name, genre, sortKey, appCopy) VALUES ('r', 0, 'R', 'NEWS', 's', 0)")
        db.execSQL("INSERT INTO lineup_feeds (rowKey, position, sourceId, channelId, name, title, categoryName) VALUES ('r', 0, 1, 'l1', 'Fox News', 'Fox News', 'News')")
        db.execSQL("DELETE FROM lineup_rows")
        db.query("SELECT COUNT(*) FROM lineup_feeds").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    /** v11 → v12 records the app install that built the saved lineup; the lineup survives and reads 0, so it rebuilds once. */
    @Test
    fun migrate11To12AddsTheAppVersionStamp() {
        helper.createDatabase(dbName, 11).apply {
            execSQL("INSERT INTO lineup_rows (key, position, name, genre, sortKey, appCopy) VALUES ('r', 0, 'R', 'NEWS', 's', 0)")
            execSQL("INSERT INTO lineup_feeds (rowKey, position, sourceId, channelId, name, title, categoryName) VALUES ('r', 0, 1, 'l1', 'Fox News', 'Fox News', 'News')")
            execSQL("INSERT INTO lineup_meta (id, savedGeneration, rulesVersion) VALUES (0, 4, 1)")
            execSQL("INSERT INTO lineup_state (id, generation) VALUES (0, 4)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)
        db.query("SELECT savedGeneration, rulesVersion, appVersion FROM lineup_meta WHERE id = 0").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4L, c.getLong(0))
            assertEquals(1, c.getInt(1))
            assertEquals(0L, c.getLong(2))
        }
        for (t in listOf("lineup_rows", "lineup_feeds", "lineup_state")) {
            db.query("SELECT COUNT(*) FROM $t").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
        db.close()
    }
}
