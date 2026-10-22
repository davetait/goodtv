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

/** v5 → v6: watch progress for resume. DDL matches schemas/6.json. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `watch_progress` (`sourceId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
                "`seriesId` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `finished` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `kind`, `itemId`), " +
                "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_progress_sourceId_seriesId` ON `watch_progress` (`sourceId`, `seriesId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_progress_updatedAt` ON `watch_progress` (`updatedAt`)")
    }
}

/** v6 → v7: playback details on watch progress, so Continue Watching can resume without the detail page. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `title` TEXT")
        db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `ext` TEXT")
        db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `season` INTEGER")
        db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `episode` INTEGER")
    }
}

/** v7 → v8: ranked TMDB lists for Home's Popular Movies. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `tmdb_lists` (`listKey` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`listKey`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmdb_list_items` (`listKey` TEXT NOT NULL, `rank` INTEGER NOT NULL, `tmdbId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`listKey`, `rank`), FOREIGN KEY(`listKey`) REFERENCES `tmdb_lists`(`listKey`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

/** v8 → v9: the watch list. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `watch_list` (`sourceId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `itemId` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sourceId`, `kind`, `itemId`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_list_sourceId` ON `watch_list` (`sourceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_list_savedAt` ON `watch_list` (`savedAt`)")
    }
}

/** v9 → v10: the Live TV guide, channel links, favourite channels and the provider's guide id. DDL matches schemas/10.json. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `live_channels` ADD COLUMN `epgChannelId` TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS `guide_channels` (`guideId` TEXT NOT NULL, `origin` TEXT NOT NULL, `logoUrl` TEXT, PRIMARY KEY(`guideId`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `guide_programmes` (`guideId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                "`description` TEXT, `genres` TEXT NOT NULL, `imageUrl` TEXT, PRIMARY KEY(`guideId`, `startMs`), " +
                "FOREIGN KEY(`guideId`) REFERENCES `guide_channels`(`guideId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_guide_programmes_endMs` ON `guide_programmes` (`endMs`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `channel_guide` (`sourceId` INTEGER NOT NULL, `channelId` TEXT NOT NULL, `guideId` TEXT NOT NULL, " +
                "PRIMARY KEY(`sourceId`, `channelId`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `favourite_channels` (`rowKey` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`rowKey`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `guide_meta` (`origin` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`origin`))")
    }
}

@Database(
    entities = [
        SourceEntity::class, CategoryEntity::class, LiveChannelEntity::class, MovieEntity::class, SeriesEntity::class,
        MovieDetailEntity::class, SeriesInfoEntity::class, SeasonEntity::class, EpisodeEntity::class,
        HiddenCategoryEntity::class, TmdbMetaEntity::class, TmdbTitleMatchEntity::class,
        SeriesGenreEntity::class, TmdbGenreEntity::class, TmdbGenreStatusEntity::class, WatchProgressEntity::class,
        TmdbListEntity::class, TmdbListItemEntity::class, WatchListEntity::class,
        GuideChannelEntity::class, GuideProgrammeEntity::class, ChannelGuideEntity::class, FavouriteChannelEntity::class, GuideMetaEntity::class,
    ],
    version = 10,
    exportSchema = true, // schemas/ holds the baseline every future migration is written against
)
abstract class GoodTvDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun library(): LibraryDao
    abstract fun hidden(): HiddenCategoryDao
    abstract fun tmdb(): TmdbDao
    abstract fun genres(): GenreDao
    abstract fun watchProgress(): WatchProgressDao
    abstract fun popular(): PopularDao
    abstract fun watchList(): WatchListDao
    abstract fun guide(): GuideDao

    companion object {
        fun create(context: Context): GoodTvDatabase =
            Room.databaseBuilder(context, GoodTvDatabase::class.java, "goodtv.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration(dropAllTables = true) // last resort for an unknown version only
                .build()
    }
}
