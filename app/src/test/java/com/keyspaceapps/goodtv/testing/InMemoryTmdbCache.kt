package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.tmdb.TitleMatch
import com.keyspaceapps.goodtv.data.tmdb.TmdbCache
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta

class InMemoryTmdbCache : TmdbCache {
    private val metas = mutableMapOf<Pair<TmdbKind, Long>, TmdbMeta>()
    private val matches = mutableMapOf<Pair<TmdbKind, String>, TitleMatch>()
    override suspend fun meta(kind: TmdbKind, tmdbId: Long) = metas[kind to tmdbId]
    override suspend fun putMeta(meta: TmdbMeta) { metas[meta.kind to meta.tmdbId] = meta }
    override suspend fun titleMatch(kind: TmdbKind, queryKey: String) = matches[kind to queryKey]
    override suspend fun putTitleMatch(kind: TmdbKind, queryKey: String, match: TitleMatch) { matches[kind to queryKey] = match }
    override suspend fun prune(before: Long) {
        metas.values.removeAll { it.fetchedAt < before }
        matches.values.removeAll { it.fetchedAt < before }
    }
}
