package com.keyspaceapps.goodtv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class, CategoryEntity::class, LiveChannelEntity::class, MovieEntity::class, SeriesEntity::class,
        MovieDetailEntity::class, SeriesInfoEntity::class, SeasonEntity::class, EpisodeEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class GoodTvDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): GoodTvDatabase =
            Room.databaseBuilder(context, GoodTvDatabase::class.java, "goodtv.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
