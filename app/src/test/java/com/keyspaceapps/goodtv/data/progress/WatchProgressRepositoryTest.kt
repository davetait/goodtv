package com.keyspaceapps.goodtv.data.progress

import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.data.watchlist.WatchListRepository
import com.keyspaceapps.goodtv.testing.InMemoryWatchListStore
import com.keyspaceapps.goodtv.testing.InMemoryWatchProgressStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressRepositoryTest {
    private val store = InMemoryWatchProgressStore()
    private var now = 1_000L
    private val repo = WatchProgressRepository(store) { now }
    private val movie = WatchRef(1, WatchKind.MOVIE, "m1")
    private val twoHours = 2 * 3_600_000L

    @Test
    fun savesWhereTheUserStopped() = runTest {
        repo.record(movie, 42 * 60_000L, twoHours)
        val p = repo.observe(movie).first()!!
        assertEquals(42 * 60_000L, p.positionMs)
        assertTrue(p.resumable)
        assertFalse(p.finished)
        assertEquals(1_000L, p.updatedAt)
    }

    @Test
    fun underThirtySecondsClearsTheSavedSpot() = runTest {
        repo.record(movie, 42 * 60_000L, twoHours)
        repo.record(movie, 10_000L, twoHours) // "Start over", then left straight away
        assertNull(repo.observe(movie).first())
    }

    @Test
    fun theCreditsCountAsWatched() = runTest {
        // A 2-hour film: 5% (6 min) beats the 2-minute minimum.
        assertTrue(WatchRules.isFinished(twoHours - 5 * 60_000L, twoHours))
        assertFalse(WatchRules.isFinished(twoHours - 7 * 60_000L, twoHours))
        // A 22-minute episode: the 2-minute minimum applies.
        val ep = 22 * 60_000L
        assertTrue(WatchRules.isFinished(ep - 90_000L, ep))
        assertFalse(WatchRules.isFinished(ep - 150_000L, ep))
        // Unknown duration never finishes.
        assertFalse(WatchRules.isFinished(10 * 3_600_000L, 0))

        repo.record(movie, twoHours - 60_000L, twoHours)
        val p = repo.observe(movie).first()!!
        assertTrue(p.finished)
        assertFalse("finished titles aren't offered for resume", p.resumable)
    }

    @Test
    fun unknownDurationStillResumes() = runTest {
        repo.record(movie, 5 * 60_000L, 0)
        assertTrue(repo.observe(movie).first()!!.resumable)
    }

    @Test
    fun seriesProgressIsNewestFirstAndOnlyThatSeries() = runTest {
        repo.record(WatchRef(1, WatchKind.EPISODE, "e1", "s"), 60_000L, 0); now = 2_000
        repo.record(WatchRef(1, WatchKind.EPISODE, "e2", "s"), 60_000L, 0); now = 3_000
        repo.record(WatchRef(1, WatchKind.EPISODE, "x1", "other"), 60_000L, 0)
        assertEquals(listOf("e2", "e1"), repo.observeSeries(1, "s").first().map { it.ref.itemId })
    }

    private fun ep(id: String, series: String = "s") = WatchRef(1, WatchKind.EPISODE, id, series)

    @Test
    fun continueWatchingMergesMoviesAndSeriesNewestFirst() = runTest {
        store.catalog = mapOf((1L to "m1") to "Movie", (1L to "s") to "Show")
        repo.record(movie, 10 * 60_000L, twoHours); now = 2_000
        repo.record(ep("e1"), 5 * 60_000L, 0); now = 3_000
        repo.record(ep("e2"), 5 * 60_000L, 0)
        val items = repo.observeContinueWatching().first()
        assertEquals(listOf("Show", "Movie"), items.map { it.title })
        assertEquals("one card per series, on its latest episode", "e2", items[0].progress.ref.itemId)
        assertEquals("EPISODE:1:s", items[0].key)
    }

    @Test
    fun continueWatchingShowsAtMostThirty() = runTest {
        store.catalog = (1..40).associate { (1L to "m$it") to "Movie $it" }
        for (i in 1..40) { now = i.toLong(); repo.record(WatchRef(1, WatchKind.MOVIE, "m$i"), 60_000L, twoHours) }
        val items = repo.observeContinueWatching().first()
        assertEquals(WatchProgressRepository.CONTINUE_LIMIT, items.size)
        assertEquals("Movie 40", items.first().title)
        assertEquals("Movie 11", items.last().title)
    }

    @Test
    fun finishedMoviesLeaveButFinishedEpisodesKeepTheirSeries() = runTest {
        store.catalog = mapOf((1L to "m1") to "Movie", (1L to "s") to "Show")
        repo.record(movie, twoHours - 60_000L, twoHours)
        repo.record(ep("e1"), twoHours - 60_000L, twoHours)
        val items = repo.observeContinueWatching().first()
        assertEquals("the series stays for its next episode", listOf("Show"), items.map { it.title })
        assertTrue(items[0].progress.finished)
    }

    @Test
    fun titlesMissingFromTheCatalogAreLeftOut() = runTest {
        repo.record(movie, 10 * 60_000L, twoHours)
        assertTrue(repo.observeContinueWatching().first().isEmpty())
    }

    @Test
    fun removingAMovieResetsItsProgress() = runTest {
        store.catalog = mapOf((1L to "m1") to "Movie")
        repo.record(movie, 10 * 60_000L, twoHours)
        repo.remove(repo.observeContinueWatching().first().single())
        assertNull(repo.observe(movie).first())
        assertTrue(repo.observeContinueWatching().first().isEmpty())
    }

    @Test
    fun removingASeriesResetsEveryEpisodeOfItOnly() = runTest {
        store.catalog = mapOf((1L to "s") to "Show", (1L to "other") to "Other")
        repo.record(ep("e1"), twoHours - 60_000L, twoHours); now = 2_000
        repo.record(ep("e2"), 5 * 60_000L, twoHours); now = 3_000
        repo.record(ep("x1", "other"), 5 * 60_000L, twoHours)
        repo.remove(repo.observeContinueWatching().first().single { it.title == "Show" })
        assertTrue(repo.observeSeries(1, "s").first().isEmpty())
        assertEquals(listOf("x1"), repo.observeSeries(1, "other").first().map { it.ref.itemId })
    }

    private val finishedMovies = mutableListOf<WatchRef>()
    private val notifying = WatchProgressRepository(store, onMovieFinished = { finishedMovies += it }) { now }

    @Test
    fun finishedMovieCallsBack() = runTest {
        notifying.record(movie, 118 * 60_000L, twoHours)
        assertEquals(listOf(movie), finishedMovies)
    }

    @Test
    fun partwayKeepsIt() = runTest {
        notifying.record(movie, 10_000L, twoHours)
        notifying.record(movie, 60 * 60_000L, twoHours)
        assertEquals(emptyList<WatchRef>(), finishedMovies)
    }

    @Test
    fun finishedEpisodeDoesNotCallBack() = runTest {
        notifying.record(WatchRef(1, WatchKind.EPISODE, "e1", "s1"), 44 * 60_000L, 45 * 60_000L)
        assertEquals(emptyList<WatchRef>(), finishedMovies)
    }

    /** Only the exact copy that was saved leaves the watch list. */
    @Test
    fun finishingAnotherCopyKeepsTheSavedOne() = runTest {
        val watchList = WatchListRepository(InMemoryWatchListStore()) { now }
        val progress = WatchProgressRepository(store, onMovieFinished = { watchList.remove(WatchListRef(it.sourceId, WatchListKind.MOVIE, it.itemId)) }) { now }
        val saved = WatchListRef(1, WatchListKind.MOVIE, "m1")
        watchList.add(saved)
        progress.record(WatchRef(1, WatchKind.MOVIE, "m2"), 118 * 60_000L, twoHours)
        assertEquals(setOf(saved), watchList.saved().first())
        progress.record(movie, 118 * 60_000L, twoHours)
        assertEquals(emptySet<WatchListRef>(), watchList.saved().first())
    }

    /** A provider's 30-second "stream unavailable" clip played to its end isn't the film being watched. */
    @Test
    fun shortClipToTheEndDoesNotCallBack() = runTest {
        notifying.record(movie, 30_000L, 30_000L)
        notifying.record(movie, 5 * 60_000L, 5 * 60_000L)
        assertEquals(emptyList<WatchRef>(), finishedMovies)
    }
}
