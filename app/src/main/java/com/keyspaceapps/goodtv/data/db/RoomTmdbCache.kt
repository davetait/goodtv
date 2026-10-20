package com.keyspaceapps.goodtv.data.db

import com.keyspaceapps.goodtv.data.tmdb.TitleMatch
import com.keyspaceapps.goodtv.data.tmdb.TmdbCache
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta

class RoomTmdbCache(private val dao: TmdbDao) : TmdbCache {
    override suspend fun meta(kind: TmdbKind, tmdbId: Long): TmdbMeta? = dao.meta(kind.name, tmdbId)?.toDomain()

    override suspend fun putMeta(meta: TmdbMeta) = dao.upsertMeta(meta.toEntity())

    override suspend fun titleMatch(kind: TmdbKind, queryKey: String): TitleMatch? =
        dao.titleMatch(kind.name, queryKey)?.let { TitleMatch(it.tmdbId, it.fetchedAt) }

    override suspend fun putTitleMatch(kind: TmdbKind, queryKey: String, match: TitleMatch) =
        dao.upsertTitleMatch(TmdbTitleMatchEntity(kind.name, queryKey, match.tmdbId, match.fetchedAt))

    override suspend fun prune(before: Long) {
        dao.pruneMeta(before)
        dao.pruneTitleMatches(before)
    }
}

// Genres are stored newline-separated; TMDB genre names never contain one.
private fun TmdbMetaEntity.toDomain() = TmdbMeta(
    TmdbKind.valueOf(kind), tmdbId, title, overview, rating, voteCount, year,
    genres.split('\n').filter { it.isNotEmpty() }, runtimeMinutes, posterUrl, backdropUrl, language, fetchedAt,
)

private fun TmdbMeta.toEntity() = TmdbMetaEntity(
    kind.name, tmdbId, language, title, overview, rating, voteCount, year,
    genres.joinToString("\n"), runtimeMinutes, posterUrl, backdropUrl, fetchedAt,
)
