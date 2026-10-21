package com.keyspaceapps.goodtv.ui.series

import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgressRepository
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.DisplayTitle
import com.keyspaceapps.goodtv.ui.browse.Formats
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.player.UpNext
import kotlinx.coroutines.flow.first

/** The episode to offer when [PlayerKey] ends: what the prompt shows, and where Play now goes. */
data class NextEpisode(val prompt: UpNext, val key: PlayerKey)

object NextEpisodes {
    /**
     * For an episode, the next one in the series ([SeriesResumes.next]), starting where the user
     * left it if they had already begun it. Null for movies, live channels, the last episode, or
     * when the series can't be loaded.
     */
    suspend fun after(key: PlayerKey, library: LibraryRepository, progress: WatchProgressRepository): NextEpisode? {
        if (key.refKind != PlayerKey.KIND_EPISODE) return null
        val seriesId = key.seriesId ?: return null
        val detail = library.seriesDetail(key.sourceId, seriesId).getOrNull() ?: return null
        val next = SeriesResumes.next(detail.episodes, key.refId) ?: return null
        val saved = progress.observe(WatchRef(key.sourceId, WatchKind.EPISODE, next.id, seriesId)).first()?.takeIf { it.resumable }
        val series = DisplayTitle.parse(detail.series.name)
        val prompt = UpNext(
            heading = listOfNotNull("S${next.season} E${next.number}", DisplayTitle.episodeTitle(next.title, series.title)).joinToString(" · "),
            seriesTitle = series.labelWithoutYear,
            meta = Formats.duration(next.durationSecs),
            summary = next.plot?.takeIf { it.isNotBlank() },
            imageUrl = next.imageUrl ?: detail.backdropUrl ?: detail.series.coverUrl,
        )
        return NextEpisode(prompt, episodePlayerKey(detail, next, saved?.positionMs ?: 0))
    }
}
