package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GenreQueriesTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var store: RoomLibraryStore
    private lateinit var genres: RoomTmdbGenreStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        store = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
        genres = RoomTmdbGenreStore(db)
    }

    @After fun tearDown() = db.close()

    private fun record(nick: String, enabled: Boolean = true) =
        SourceRecord(0, SourceType.XTREAM, nick, SourceConfig.Xtream("http://s", "u", "p"), enabled, null, null)

    private fun catalog(sid: Long, movies: List<Movie>, series: List<Series> = emptyList()) = Catalog(
        categories = listOf(Category(sid, ContentKind.MOVIE, "m", "M"), Category(sid, ContentKind.MOVIE, "h", "Hidden"), Category(sid, ContentKind.SERIES, "s", "S")),
        live = emptyList(), movies = movies, series = series,
    )

    @Test
    fun movieGenresAreSharedByTmdbIdAndSurviveAResync() = runBlocking {
        val a = store.insertSource(record("A"))
        val movies = listOf(
            Movie(a, "1", "m", "EN - Heat", null, "mkv", null, 1, tmdbId = 949),
            Movie(a, "2", "m", "DE - Heat", null, "mkv", null, 2, tmdbId = 949),
            Movie(a, "3", "h", "Heat (hidden category)", null, "mkv", null, 3, tmdbId = 949),
            Movie(a, "4", "m", "Up", null, "mkv", null, 4, tmdbId = 14160),
            Movie(a, "5", "m", "No id", null, "mkv", null, 5),
        )
        store.replaceCatalog(a, catalog(a, movies))
        store.setCategoriesHidden(a, ContentKind.MOVIE, listOf("h"), hidden = true)
        assertEquals(setOf(949L, 14160L), genres.pendingMovieTmdbIds(before = 0).toSet())
        assertEquals(2, genres.movieTmdbIdCount())

        genres.saveMovieGenres(mapOf(949L to setOf(Genre.CRIME, Genre.THRILLER), 14160L to setOf(Genre.ANIMATION)), fetchedAt = 100)
        assertEquals(emptyList<Long>(), genres.pendingMovieTmdbIds(before = 50))
        assertEquals("stale after the cache limit", setOf(949L, 14160L), genres.pendingMovieTmdbIds(before = 101).toSet())

        assertEquals(listOf("DE - Heat", "EN - Heat"), store.observeMoviesByGenre(Genre.CRIME, 10).first().map { it.name })
        assertEquals(mapOf(Genre.CRIME to 2, Genre.THRILLER to 2, Genre.ANIMATION to 1), store.observeGenreCounts(ContentKind.MOVIE).first())

        // A re-sync replaces the movie rows; genres are keyed by TMDB id, so they stay.
        store.replaceCatalog(a, catalog(a, movies.take(1)))
        assertEquals(listOf("EN - Heat"), store.observeMoviesByGenre(Genre.CRIME, 10).first().map { it.name })

        // Re-fetching an id replaces its genres.
        genres.saveMovieGenres(mapOf(949L to setOf(Genre.DRAMA)), fetchedAt = 200)
        assertEquals(emptyList<Movie>(), store.observeMoviesByGenre(Genre.CRIME, 10).first())
    }

    @Test
    fun seriesGenresAreReplacedOnResyncAndDisabledSourcesAreLeftOut() = runBlocking {
        val a = store.insertSource(record("A"))
        val off = store.insertSource(record("Off", enabled = false))
        store.replaceCatalog(a, catalog(a, emptyList(), listOf(Series(a, "x", "s", "Dark", null, null, null, genres = setOf(Genre.DRAMA, Genre.MYSTERY)))))
        store.replaceCatalog(off, catalog(off, emptyList(), listOf(Series(off, "y", "s", "Off show", null, null, null, genres = setOf(Genre.DRAMA)))))
        assertEquals(listOf("Dark"), store.observeSeriesByGenre(Genre.DRAMA, 10).first().map { it.name })
        assertEquals(mapOf(Genre.DRAMA to 1, Genre.MYSTERY to 1), store.observeGenreCounts(ContentKind.SERIES).first())

        store.replaceCatalog(a, catalog(a, emptyList(), listOf(Series(a, "x", "s", "Dark", null, null, null, genres = setOf(Genre.SCIFI)))))
        assertEquals(emptyList<Series>(), store.observeSeriesByGenre(Genre.DRAMA, 10).first())
        assertEquals(mapOf(Genre.SCIFI to 1), store.observeGenreCounts(ContentKind.SERIES).first())
    }
}
