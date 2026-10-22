package com.keyspaceapps.goodtv.data.db

import com.keyspaceapps.goodtv.data.tmdb.PeopleCache
import com.keyspaceapps.goodtv.data.tmdb.PersonCredit
import com.keyspaceapps.goodtv.data.tmdb.PersonTitle
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbPerson
import com.keyspaceapps.goodtv.data.tmdb.inDateOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class RoomPeopleCache(private val dao: PeopleDao) : PeopleCache {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TmdbPerson.serializer())

    override suspend fun titlePeople(kind: TmdbKind, tmdbId: Long): Pair<List<TmdbPerson>, Long>? =
        dao.titlePeople(kind.name, tmdbId)?.let { json.decodeFromString(serializer, it.people) to it.fetchedAt }

    override suspend fun putTitlePeople(kind: TmdbKind, tmdbId: Long, people: List<TmdbPerson>, fetchedAt: Long) =
        dao.upsertTitlePeople(TmdbTitlePeopleEntity(kind.name, tmdbId, json.encodeToString(serializer, people), fetchedAt))

    override suspend fun creditsFetchedAt(personId: Long): Long? = dao.creditsFetchedAt(personId)

    override suspend fun putCredits(personId: Long, name: String, credits: List<PersonCredit>, fetchedAt: Long) =
        dao.replaceCredits(
            TmdbPersonEntity(personId, name, fetchedAt),
            credits.map { TmdbPersonCreditEntity(personId, it.kind.name, it.tmdbId, it.date) },
        )

    override fun observeTitles(personId: Long): Flow<List<PersonTitle>> =
        combine(dao.observeMovies(personId), dao.observeSeries(personId)) { movies, series ->
            (movies.map { PersonTitle.OfMovie(it.movie.toDomain(), it.date) } +
                series.map { PersonTitle.OfSeries(it.series.toDomain(), it.date) }).inDateOrder()
        }

    override suspend fun prune(before: Long) {
        dao.pruneTitlePeople(before)
        dao.prunePeople(before)
    }
}
