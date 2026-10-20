package com.keyspaceapps.goodtv.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A v1 database with a source must survive the upgrade to v2 (hidden categories). */
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
}
