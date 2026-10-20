package com.keyspaceapps.goodtv.ui.series

import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.domain.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesResumeTest {
    private fun ep(id: String, season: Int, number: Int) = Episode(1, "s", id, season, number, "E$number", "mp4", null, null, null)
    private val episodes = listOf(ep("e3", 1, 3), ep("e1", 1, 1), ep("e2", 1, 2), ep("f1", 2, 1))
    private fun p(id: String, pos: Long, finished: Boolean, at: Long) = WatchProgress(WatchRef(1, WatchKind.EPISODE, id, "s"), pos, 2_700_000, finished, at)

    @Test fun nothingWatchedOffersNothing() = assertNull(SeriesResumes.of(episodes, emptyList()))

    @Test fun resumesThePartWatchedEpisode() {
        val r = SeriesResumes.of(episodes, listOf(p("e2", 760_000, false, 5)))!!
        assertEquals("e2", r.episode.id); assertEquals(760_000L, r.startMs); assertEquals(true, r.resume)
    }

    @Test fun afterAFinishedEpisodeOffersTheNextOneAcrossSeasons() {
        val r = SeriesResumes.of(episodes, listOf(p("e3", 2_650_000, true, 9), p("e1", 760_000, false, 1)))!!
        assertEquals("f1", r.episode.id); assertEquals(0L, r.startMs); assertEquals(false, r.resume)
    }

    @Test fun theNextEpisodeResumesIfItWasAlreadyStarted() {
        val r = SeriesResumes.of(episodes, listOf(p("e1", 2_650_000, true, 9), p("e2", 300_000, false, 2)))!!
        assertEquals("e2", r.episode.id); assertEquals(300_000L, r.startMs); assertEquals(true, r.resume)
    }

    @Test fun finishingTheLastEpisodeOffersNothing() = assertNull(SeriesResumes.of(episodes, listOf(p("f1", 2_650_000, true, 9))))
}
