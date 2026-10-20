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

@Database(
    entities = [
        SourceEntity::class, CategoryEntity::class, LiveChannelEntity::class, MovieEntity::class, SeriesEntity::class,
        MovieDetailEntity::class, SeriesInfoEntity::class, SeasonEntity::class, EpisodeEntity::class,
        HiddenCategoryEntity::class,
    ],
    version = 2,
    exportSchema = true, // schemas/ holds the baseline every future migration is written against
)
abstract class GoodTvDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun library(): LibraryDao
    abstract fun hidden(): HiddenCategoryDao

    companion object {
        fun create(context: Context): GoodTvDatabase =
            Room.databaseBuilder(context, GoodTvDatabase::class.java, "goodtv.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true) // last resort for an unknown version only
                .build()
    }
}
