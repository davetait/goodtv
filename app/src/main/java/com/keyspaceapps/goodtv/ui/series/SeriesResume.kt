package com.keyspaceapps.goodtv.ui.series

import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.domain.Episode

/** What a series page offers at the top: carry on in [episode] from [startMs] ([resume]), or start the next one. */
data class SeriesResume(val episode: Episode, val startMs: Long, val resume: Boolean)

object SeriesResumes {
    /**
     * From the most recently watched episode: resume it if it's part-watched; if it's finished, the
     * next episode in season/number order (resuming that one if it was already started). Nothing
     * when no episode has progress or the last finished one was the final episode.
     */
    fun of(episodes: List<Episode>, progress: List<WatchProgress>): SeriesResume? {
        val ordered = episodes.sortedWith(compareBy({ it.season }, { it.number }))
        val byId = progress.associateBy { it.ref.itemId }
        val latest = progress.filter { p -> ordered.any { it.id == p.ref.itemId } }.maxByOrNull { it.updatedAt } ?: return null
        val index = ordered.indexOfFirst { it.id == latest.ref.itemId }
        if (latest.resumable) return SeriesResume(ordered[index], latest.positionMs, resume = true)
        if (!latest.finished) return null
        val next = ordered.getOrNull(index + 1) ?: return null
        val started = byId[next.id]?.takeIf { it.resumable }
        return SeriesResume(next, started?.positionMs ?: 0, resume = started != null)
    }

    /** The episode after [currentId] in season/number order (a finale leads to the next season's first), or null. */
    fun next(episodes: List<Episode>, currentId: String): Episode? {
        val ordered = episodes.sortedWith(compareBy({ it.season }, { it.number }))
        val index = ordered.indexOfFirst { it.id == currentId }
        return if (index < 0) null else ordered.getOrNull(index + 1)
    }
}

