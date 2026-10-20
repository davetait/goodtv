package com.keyspaceapps.goodtv.data.db

import androidx.room.withTransaction
import com.keyspaceapps.goodtv.data.tmdb.TmdbGenreStore
import com.keyspaceapps.goodtv.domain.Genre

class RoomTmdbGenreStore(private val db: GoodTvDatabase) : TmdbGenreStore {
    private val dao get() = db.genres()

    override suspend fun pendingMovieTmdbIds(before: Long): List<Long> = dao.pendingMovieTmdbIds(before)

    override suspend fun movieTmdbIdCount(): Int = dao.movieTmdbIdCount()

    override suspend fun saveMovieGenres(genres: Map<Long, Set<Genre>>, fetchedAt: Long) = db.withTransaction {
        genres.keys.chunked(500).forEach { dao.deleteTmdbGenres(KIND, it) }
        dao.insertTmdbGenres(genres.flatMap { (id, set) -> set.map { TmdbGenreEntity(KIND, id, it.name) } })
        dao.upsertTmdbGenreStatus(genres.keys.map { TmdbGenreStatusEntity(KIND, it, fetchedAt) })
    }

    private companion object { const val KIND = "MOVIE" }
}
