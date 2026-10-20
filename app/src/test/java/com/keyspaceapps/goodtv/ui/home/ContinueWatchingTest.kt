package com.keyspaceapps.goodtv.ui.home

import com.keyspaceapps.goodtv.data.progress.ContinueItem
import com.keyspaceapps.goodtv.data.progress.PlaybackInfo
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {
    private val hour = 3_600_000L

    private fun item(ref: WatchRef, positionMs: Long, finished: Boolean = false, info: PlaybackInfo? = null) =
        ContinueItem(WatchProgress(ref, positionMs, 2 * hour, finished, 1, info), "Title", null, null, "Title")

    @Test
    fun movieGoesStraightBackIntoPlayback() {
        val key = item(WatchRef(3, WatchKind.MOVIE, "m1"), hour, info = PlaybackInfo("The Movie (EN)", "mkv")).navKey()
        assertEquals(PlayerKey(3, PlayerKey.KIND_MOVIE, "m1", "mkv", "The Movie (EN)", startPositionMs = hour), key)
    }

    @Test
    fun episodeResumesWithItsSeasonAndNumber() {
        val ref = WatchRef(3, WatchKind.EPISODE, "e7", "s1")
        val key = item(ref, hour, info = PlaybackInfo("Show S2 E4", "mp4", 2, 4)).navKey()
        assertEquals(PlayerKey(3, PlayerKey.KIND_EPISODE, "e7", "mp4", "Show S2 E4", hour, "s1", 2, 4), key)
    }

    @Test
    fun finishedEpisodeOpensTheSeriesForTheNextOne() {
        val ref = WatchRef(3, WatchKind.EPISODE, "e7", "s1")
        assertEquals(SeriesDetailKey(3, "s1"), item(ref, 2 * hour, finished = true, info = PlaybackInfo("Show", "mp4", 2, 4)).navKey())
    }

    @Test
    fun rowsSavedWithoutPlaybackDetailsOpenTheDetailPage() {
        assertEquals(MovieDetailKey(3, "m1"), item(WatchRef(3, WatchKind.MOVIE, "m1"), hour).navKey())
        assertEquals(SeriesDetailKey(3, "s1"), item(WatchRef(3, WatchKind.EPISODE, "e7", "s1"), hour).navKey())
    }
}
