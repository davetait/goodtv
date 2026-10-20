package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.tmdb.TitleMatch
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RoomTmdbCacheTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var cache: RoomTmdbCache

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        cache = RoomTmdbCache(db.tmdb())
    }

    @After fun tearDown() = db.close()

    private fun meta(id: Long, fetchedAt: Long, genres: List<String> = listOf("Action", "Sci-Fi & Fantasy")) = TmdbMeta(
        TmdbKind.MOVIE, id, "The Matrix", "Neo…", 8.2, 26280, 1999, genres, 136, "https://p", null, "en-US", fetchedAt,
    )

    @Test
    fun metaRoundTripsIncludingGenresAndNulls() = runBlocking {
        cache.putMeta(meta(603, 10))
        assertEquals(meta(603, 10), cache.meta(TmdbKind.MOVIE, 603))
        cache.putMeta(meta(604, 10, genres = emptyList()))
        assertEquals(emptyList<String>(), cache.meta(TmdbKind.MOVIE, 604)!!.genres)
        assertNull(cache.meta(TmdbKind.TV, 603))
    }

    @Test
    fun titleMatchStoresNoMatchAndPruneDropsOldRows() = runBlocking {
        cache.putTitleMatch(TmdbKind.MOVIE, "obscure|", TitleMatch(null, 5))
        cache.putTitleMatch(TmdbKind.MOVIE, "heat|1995", TitleMatch(949, 50))
        cache.putMeta(meta(603, 5))
        assertEquals(TitleMatch(null, 5), cache.titleMatch(TmdbKind.MOVIE, "obscure|"))

        cache.prune(before = 10)
        assertNull(cache.titleMatch(TmdbKind.MOVIE, "obscure|"))
        assertNull(cache.meta(TmdbKind.MOVIE, 603))
        assertEquals(TitleMatch(949, 50), cache.titleMatch(TmdbKind.MOVIE, "heat|1995"))
    }
}
