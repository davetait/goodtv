package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import kotlinx.coroutines.flow.Flow

/** A library title in a person's credits; [date] is the credit's release or first-air date. */
sealed interface PersonTitle {
    val date: String?
    val title: String

    data class OfMovie(val movie: Movie, override val date: String?) : PersonTitle {
        override val title: String get() = movie.title
    }

    data class OfSeries(val series: Series, override val date: String?) : PersonTitle {
        override val title: String get() = series.title
    }
}

/** Newest date first, titles without a date last, then by title. */
fun List<PersonTitle>.inDateOrder(): List<PersonTitle> =
    sortedWith(compareBy<PersonTitle> { it.date == null }.thenByDescending { it.date }.thenBy { it.title })

/** Cached TMDB people: who is on a title, and which titles a person counts for. */
interface PeopleCache {
    suspend fun titlePeople(kind: TmdbKind, tmdbId: Long): Pair<List<TmdbPerson>, Long>? // people, fetchedAt
    suspend fun putTitlePeople(kind: TmdbKind, tmdbId: Long, people: List<TmdbPerson>, fetchedAt: Long)
    suspend fun creditsFetchedAt(personId: Long): Long?

    /** Replaces the person's credits in one transaction. */
    suspend fun putCredits(personId: Long, name: String, credits: List<PersonCredit>, fetchedAt: Long)

    /** Library titles in the person's credits: one copy each, enabled and visible only, newest date first (null dates last), then title. */
    fun observeTitles(personId: Long): Flow<List<PersonTitle>>
    suspend fun prune(before: Long)
}
