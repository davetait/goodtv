package com.keyspaceapps.goodtv.ui.nav

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.subtitles.SubtitleQuery

/** What to ask OpenSubtitles for when [PlayerKey] plays. */
object SubtitleQueries {
    /**
     * A movie by its TMDB id; an episode by its series' TMDB id with season and number. Null for live
     * channels and for titles the catalog has no TMDB id (or no season/number) for.
     */
    suspend fun forKey(key: PlayerKey, library: LibraryRepository): SubtitleQuery? = when (key.refKind) {
        PlayerKey.KIND_MOVIE -> library.catalogMovie(key.sourceId, key.refId)?.tmdbId?.takeIf { it > 0 }?.let { SubtitleQuery(it) }
        PlayerKey.KIND_EPISODE -> {
            val season = key.season
            val episode = key.episode
            val seriesId = key.seriesId
            if (season == null || episode == null || seriesId == null) null
            else library.catalogSeries(key.sourceId, seriesId)?.tmdbId?.takeIf { it > 0 }?.let { SubtitleQuery(it, season, episode) }
        }
        else -> null
    }
}
