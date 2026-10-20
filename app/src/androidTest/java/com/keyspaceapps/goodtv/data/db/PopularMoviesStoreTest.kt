package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Popular Movies picks one playable copy per ranked TMDB id. */
class PopularMoviesStoreTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var library: RoomLibraryStore
    private lateinit var popular: RoomPopularMoviesStore
    private val key = "movie:test"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        library = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
        popular = RoomPopularMoviesStore(db.popular())
    }

    @After fun tearDown() = db.close()

    private fun source(name: String) = SourceRecord(0, SourceType.XTREAM, name, SourceConfig.Xtream("http://$name", "u", "p"), true, null, null)

    private fun movie(sid: Long, id: String, tmdbId: Long, language: String?, quality: String? = null, category: String = "c") =
        Movie(sid, id, category, "raw $id", null, "mkv", null, null, tmdbId, title = "Film $tmdbId", language = language, quality = quality)

    private suspend fun shown() = popular.observeMovies(key, 20).first().map { it.id }

    @Test
    fun picksEnglishThenUntaggedThenAnyInRankOrder() = runBlocking {
        val a = library.insertSource(source("a"))
        val cats = listOf(Category(a, ContentKind.MOVIE, "c", "Movies"))
        library.replaceCatalog(
            a,
            Catalog(
                cats, emptyList(),
                listOf(
                    movie(a, "1-de", 1, "DE"), movie(a, "1-en-4k", 1, "EN", "4K"), movie(a, "1-en", 1, "EN"), movie(a, "1-none", 1, null),
                    movie(a, "2-de", 2, "DE"), movie(a, "2-none", 2, null),
                    movie(a, "3-fr", 3, "FR"),
                ),
                emptyList(),
            ),
        )
        popular.replace(key, listOf(3, 99, 1, 2), fetchedAt = 5)
        assertEquals("ids missing from the library are skipped", listOf("3-fr", "1-en", "2-none"), shown())
        assertEquals(5L, popular.fetchedAt(key))
        assertEquals(listOf("3-fr", "1-en"), popular.observeMovies(key, 2).first().map { it.id })
    }

    @Test
    fun disabledSourcesAndHiddenCategoriesDontCount() = runBlocking {
        val a = library.insertSource(source("a"))
        val b = library.insertSource(source("b"))
        library.replaceCatalog(a, Catalog(listOf(Category(a, ContentKind.MOVIE, "c", "Movies")), emptyList(), listOf(movie(a, "a1-en", 1, "EN"), movie(a, "a2-en", 2, "EN")), emptyList()))
        library.replaceCatalog(
            b,
            Catalog(
                listOf(Category(b, ContentKind.MOVIE, "c", "Movies"), Category(b, ContentKind.MOVIE, "x", "Adult")), emptyList(),
                listOf(movie(b, "b1-de", 1, "DE"), movie(b, "b3-en", 3, "EN", category = "x")),
                emptyList(),
            ),
        )
        popular.replace(key, listOf(1, 2, 3), fetchedAt = 1)
        library.updateSource(library.getSource(a)!!.copy(enabled = false))
        library.setCategoriesHidden(b, ContentKind.MOVIE, listOf("x"), hidden = true)
        assertEquals("falls back to another source's copy; nothing visible for 2 or 3", listOf("b1-de"), shown())
    }

    @Test
    fun replacingDropsTheOldList() = runBlocking {
        popular.replace("old", listOf(1), fetchedAt = 1)
        popular.replace(key, listOf(2), fetchedAt = 2)
        assertNull(popular.fetchedAt("old"))
        assertEquals(2L, popular.fetchedAt(key))
    }
}
