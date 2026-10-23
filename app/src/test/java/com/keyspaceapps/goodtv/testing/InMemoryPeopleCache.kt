package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.tmdb.PeopleCache
import com.keyspaceapps.goodtv.data.tmdb.PersonCredit
import com.keyspaceapps.goodtv.data.tmdb.PersonTitle
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbPerson
import com.keyspaceapps.goodtv.data.tmdb.inDateOrder
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory PeopleCache for JVM tests. [observeTitles] applies only the one-copy pick and the date order;
 * the hidden and disabled rules are proven on Room in PeopleDaoTest.
 */
class InMemoryPeopleCache(
    private val movies: List<Movie> = emptyList(),
    private val series: List<Series> = emptyList(),
) : PeopleCache {
    private val titlePeople = mutableMapOf<Pair<TmdbKind, Long>, Pair<List<TmdbPerson>, Long>>()
    private val credits = MutableStateFlow<Map<Long, Pair<List<PersonCredit>, Long>>>(emptyMap())
    private val names = mutableMapOf<Long, String>()

    override suspend fun titlePeople(kind: TmdbKind, tmdbId: Long) = titlePeople[kind to tmdbId]

    override suspend fun putTitlePeople(kind: TmdbKind, tmdbId: Long, people: List<TmdbPerson>, fetchedAt: Long) {
        titlePeople[kind to tmdbId] = people to fetchedAt
    }

    override suspend fun creditsFetchedAt(personId: Long): Long? = credits.value[personId]?.second

    override suspend fun putCredits(personId: Long, name: String, credits: List<PersonCredit>, fetchedAt: Long) {
        names[personId] = name
        this.credits.value = this.credits.value + (personId to (credits to fetchedAt))
    }

    override fun observeTitles(personId: Long): Flow<List<PersonTitle>> = credits.map { all ->
        all[personId]?.first.orEmpty().mapNotNull { credit ->
            when (credit.kind) {
                TmdbKind.MOVIE -> movies.filter { it.tmdbId == credit.tmdbId }
                    .minWithOrNull(compareBy<Movie>({ langRank(it.language) }, { it.quality != null }, { it.sourceId }, { it.id }))
                    ?.let { PersonTitle.OfMovie(it, credit.date) }
                TmdbKind.TV -> series.filter { it.tmdbId == credit.tmdbId }
                    .minWithOrNull(compareBy<Series>({ langRank(it.language) }, { it.quality != null }, { it.sourceId }, { it.id }))
                    ?.let { PersonTitle.OfSeries(it, credit.date) }
            }
        }.inDateOrder()
    }

    override suspend fun prune(before: Long) {
        titlePeople.values.removeAll { it.second < before }
        credits.value = credits.value.filterValues { it.second >= before }
    }

    private fun langRank(language: String?) = when (language) { "EN" -> 0; null -> 1; else -> 2 }
}
