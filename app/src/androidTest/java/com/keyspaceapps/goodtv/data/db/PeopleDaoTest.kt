package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.tmdb.PersonCredit
import com.keyspaceapps.goodtv.data.tmdb.PersonJob
import com.keyspaceapps.goodtv.data.tmdb.PersonTitle
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbPerson
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PeopleDaoTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var library: RoomLibraryStore
    private lateinit var cache: RoomPeopleCache

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        library = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
        cache = RoomPeopleCache(db.people())
    }

    @After fun tearDown() = db.close()

    private fun source(name: String) = SourceRecord(0, SourceType.XTREAM, name, SourceConfig.Xtream("http://$name", "u", "p"), true, null, null)

    private fun movie(sid: Long, id: String, tmdbId: Long, language: String?, quality: String? = null, category: String = "c") =
        Movie(sid, id, category, "raw $id", null, "mkv", null, null, tmdbId, title = "Film $tmdbId", language = language, quality = quality)

    private fun label(t: PersonTitle) = when (t) {
        is PersonTitle.OfMovie -> t.movie.id
        is PersonTitle.OfSeries -> t.series.id
    }

    @Test
    fun personTitlesSkipHiddenAndDisabledAndPickOneCopy() = runBlocking {
        val a = library.insertSource(source("a"))
        val b = library.insertSource(source("b"))
        library.replaceCatalog(
            a,
            Catalog(
                listOf(Category(a, ContentKind.MOVIE, "c", "Movies"), Category(a, ContentKind.MOVIE, "x", "Adult"), Category(a, ContentKind.SERIES, "c", "Shows")),
                emptyList(),
                listOf(
                    movie(a, "1-4k", 1, "EN", "4K"), movie(a, "1-hd", 1, "EN", "HD"), movie(a, "1-en", 1, "EN"), movie(a, "1-de", 1, "DE"),
                    movie(a, "2-hidden", 2, "EN", category = "x"),
                    movie(a, "4", 4, null),
                    movie(a, "5", 5, null),
                ),
                listOf(Series(a, "s10", "c", "raw s10", null, null, null, 10, title = "Show 10")),
            ),
        )
        library.replaceCatalog(b, Catalog(listOf(Category(b, ContentKind.MOVIE, "c", "Movies")), emptyList(), listOf(movie(b, "3-disabled", 3, "EN")), emptyList()))
        library.updateSource(library.getSource(b)!!.copy(enabled = false))
        library.setCategoriesHidden(a, ContentKind.MOVIE, listOf("x"), hidden = true)

        cache.putCredits(
            7, "Someone",
            listOf(
                PersonCredit(TmdbKind.MOVIE, 1, "2022-05-01"), PersonCredit(TmdbKind.MOVIE, 2, "2020-01-01"), PersonCredit(TmdbKind.MOVIE, 3, "2021-01-01"),
                PersonCredit(TmdbKind.MOVIE, 4, "2010-01-01"), PersonCredit(TmdbKind.TV, 10, "1986-09-01"), PersonCredit(TmdbKind.MOVIE, 5, null),
                PersonCredit(TmdbKind.MOVIE, 99, "2000-01-01"),
            ),
            fetchedAt = 1,
        )

        val titles = cache.observeTitles(7).first()
        assertEquals(listOf("1-en", "4", "s10", "5"), titles.map(::label))
        assertEquals(listOf<String?>("2022-05-01", "2010-01-01", "1986-09-01", null), titles.map { it.date })
        assertEquals(emptyList<PersonTitle>(), cache.observeTitles(8).first())
    }

    @Test
    fun putCreditsReplacesThePersonsCredits() = runBlocking {
        assertNull(cache.creditsFetchedAt(7))
        cache.putCredits(7, "Someone", listOf(PersonCredit(TmdbKind.MOVIE, 1, null), PersonCredit(TmdbKind.TV, 2, "2001-01-01")), fetchedAt = 5)
        cache.putCredits(7, "Someone", listOf(PersonCredit(TmdbKind.MOVIE, 3, "2002-01-01")), fetchedAt = 9)
        assertEquals(9L, cache.creditsFetchedAt(7))
        assertEquals(listOf(3L), db.openHelper.readableDatabase.query("SELECT tmdbId FROM tmdb_person_credits WHERE personId = 7").use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList()
        })
    }

    @Test
    fun titlePeopleRoundTripIncludingEmpty() = runBlocking {
        val people = listOf(TmdbPerson(1, "Keanu Reeves", "Neo", PersonJob.CAST), TmdbPerson(2, "Lana Wachowski", null, PersonJob.DIRECTOR))
        cache.putTitlePeople(TmdbKind.MOVIE, 603, people, 10)
        cache.putTitlePeople(TmdbKind.TV, 604, emptyList(), 11)
        assertEquals(people to 10L, cache.titlePeople(TmdbKind.MOVIE, 603))
        assertEquals(emptyList<TmdbPerson>() to 11L, cache.titlePeople(TmdbKind.TV, 604))
        assertNull(cache.titlePeople(TmdbKind.TV, 603))
        cache.prune(before = 11)
        assertNull(cache.titlePeople(TmdbKind.MOVIE, 603))
        assertEquals(emptyList<TmdbPerson>() to 11L, cache.titlePeople(TmdbKind.TV, 604))
    }
}
