package com.keyspaceapps.goodtv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2: hidden_categories (per-source switched-off categories). DDL matches schemas/2.json. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `hidden_categories` (`sourceId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                "`categoryId` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `kind`, `categoryId`), " +
                "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_hidden_categories_sourceId` ON `hidden_categories` (`sourceId`)")
    }
}

/** v2 → v3: TMDB ids on movies and series, plus the TMDB metadata cache. DDL matches schemas/3.json. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `movies` ADD COLUMN `tmdbId` INTEGER")
        db.execSQL("ALTER TABLE `series` ADD COLUMN `tmdbId` INTEGER")
        // Existing rows have no ids until the next sync; mark sources stale so the next launch fetches them.
        db.execSQL("UPDATE `sources` SET `lastSyncAt` = NULL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmdb_meta` (`kind` TEXT NOT NULL, `tmdbId` INTEGER NOT NULL, `language` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `overview` TEXT, `rating` REAL, `voteCount` INTEGER NOT NULL, `year` INTEGER, " +
                "`genres` TEXT NOT NULL, `runtimeMinutes` INTEGER, `posterUrl` TEXT, `backdropUrl` TEXT, " +
                "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`kind`, `tmdbId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmdb_title_match` (`kind` TEXT NOT NULL, `queryKey` TEXT NOT NULL, " +
                "`tmdbId` INTEGER, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`kind`, `queryKey`))",
        )
    }
}

/** v3 → v4: genre tables (series from the panel, movies from TMDB). DDL matches schemas/4.json. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_tmdbId` ON `movies` (`tmdbId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `series_genres` (`sourceId` INTEGER NOT NULL, `seriesId` TEXT NOT NULL, `genre` TEXT NOT NULL, " +
                "PRIMARY KEY(`sourceId`, `seriesId`, `genre`), " +
                "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_genres_genre` ON `series_genres` (`genre`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmdb_genres` (`kind` TEXT NOT NULL, `tmdbId` INTEGER NOT NULL, `genre` TEXT NOT NULL, " +
                "PRIMARY KEY(`kind`, `tmdbId`, `genre`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tmdb_genres_kind_genre` ON `tmdb_genres` (`kind`, `genre`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmdb_genre_status` (`kind` TEXT NOT NULL, `tmdbId` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`kind`, `tmdbId`))",
        )
        // Series genres are filled at sync; mark sources stale so the next launch fills them for existing libraries.
        db.execSQL("UPDATE `sources` SET `lastSyncAt` = NULL")
    }
}

/** v4 → v5: clean display titles (title, language, quality) on channels, movies and series. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("live_channels", "movies", "series")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `title` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `language` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `quality` TEXT")
        }
        // Titles are computed at sync; until the next one, lists fall back to the raw name.
        db.execSQL("UPDATE `sources` SET `lastSyncAt` = NULL")
    }
}

@Database(
    entities = [
        SourceEntity::class, CategoryEntity::class, LiveChannelEntity::class, MovieEntity::class, SeriesEntity::class,
        MovieDetailEntity::class, SeriesInfoEntity::class, SeasonEntity::class, EpisodeEntity::class,
        HiddenCategoryEntity::class, TmdbMetaEntity::class, TmdbTitleMatchEntity::class,
        SeriesGenreEntity::class, TmdbGenreEntity::class, TmdbGenreStatusEntity::class,
    ],
    version = 5,
    exportSchema = true, // schemas/ holds the baseline every future migration is written against
)
abstract class GoodTvDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun library(): LibraryDao
    abstract fun hidden(): HiddenCategoryDao
    abstract fun tmdb(): TmdbDao
    abstract fun genres(): GenreDao

    companion object {
        fun create(context: Context): GoodTvDatabase =
            Room.databaseBuilder(context, GoodTvDatabase::class.java, "goodtv.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration(dropAllTables = true) // last resort for an unknown version only
                .build()
    }
}
