package com.keyspaceapps.goodtv.data.db

import com.keyspaceapps.goodtv.data.tmdb.PopularStore
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPopularStore(private val dao: PopularDao) : PopularStore {
    override suspend fun fetchedAt(listKey: String): Long? = dao.fetchedAt(listKey)
    override suspend fun replace(listKey: String, tmdbIds: List<Long>, fetchedAt: Long) = dao.replace(listKey, tmdbIds, fetchedAt)
    override fun observeMovies(listKey: String, limit: Int): Flow<List<Movie>> = dao.observeMovies(listKey, limit).map { rows -> rows.map { it.toDomain() } }
    override fun observeSeries(listKey: String, limit: Int): Flow<List<Series>> = dao.observeSeries(listKey, limit).map { rows -> rows.map { it.toDomain() } }
}
