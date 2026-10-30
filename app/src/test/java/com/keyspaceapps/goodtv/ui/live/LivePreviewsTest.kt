package com.keyspaceapps.goodtv.ui.live

import androidx.compose.ui.geometry.Rect
import androidx.media3.exoplayer.ExoPlayer
import com.keyspaceapps.goodtv.data.guide.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's preview session: when the resting row's preview starts and stops, and handing its player over. */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePreviewsTest {
    private class FakeHandle(val url: String) : PreviewHandle {
        val status = MutableStateFlow(PreviewStatus.LOADING)
        var releases = 0
        override val state: StateFlow<PreviewStatus> = status
        override val player: ExoPlayer? = null
        override fun release() { releases++ }
    }

    private class FakeEngine : PreviewEngine {
        val opened = mutableListOf<FakeHandle>()
        override fun open(url: String): PreviewHandle = FakeHandle(url).also { opened += it }
    }

    private val engine = FakeEngine()
    private val playback = PlaybackState()

    private fun row(n: Int, sourceId: Long = 1, channel: String = "ch$n") = PreviewTarget("row$n", sourceId, channel, "Channel $n")

    private fun TestScope.session(scope: CoroutineScope = backgroundScope) = LivePreviews(
        scope, playback, resolveUrl = { "http://test/${it.sourceId}/${it.channelId}" }, engine = engine,
    ).also { it.setEnabled(true) }

    /** Focuses [target] and lets it rest for a full second. */
    private fun TestScope.rest(previews: LivePreviews, target: PreviewTarget) {
        previews.focus(target)
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test fun restingOneSecondStartsAPreview() = runTest {
        val previews = session()
        previews.focus(row(1))
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, engine.opened.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.opened.size)
        assertEquals("http://test/1/ch1", engine.opened[0].url)
        assertEquals(row(1), previews.current.value?.first)
        assertSame(engine.opened[0], previews.current.value?.second)
    }

    @Test fun heldArrowsStartNothingUntilRest() = runTest {
        val previews = session()
        for (n in 0 until 20) {
            previews.focus(row(n))
            advanceTimeBy(150)
            runCurrent()
        }
        assertEquals(0, engine.opened.size)
        advanceTimeBy(1_000 - 150 - 1)
        runCurrent()
        assertEquals(0, engine.opened.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.opened.size)
        assertEquals("http://test/1/ch19", engine.opened[0].url)
        assertEquals(row(19), previews.current.value?.first)
    }

    @Test fun movingAlongTheRowKeepsThePreview() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = engine.opened.single()
        // Another cell of the same row: an event slot target with the same row key.
        previews.focus(row(1).copy(label = "Later programme"))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
        assertEquals(0, handle.releases)
        assertSame(handle, previews.current.value?.second)
    }

    @Test fun movingToAnotherRowStopsAtOnce() = runTest {
        val previews = session()
        rest(previews, row(1))
        val first = engine.opened.single()
        previews.focus(row(2))
        assertEquals(1, first.releases)
        assertNull(previews.current.value)
        assertFalse(playback.playing.value)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, engine.opened.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, engine.opened.size)
        assertEquals("http://test/1/ch2", engine.opened[1].url)
    }

    @Test fun focusLeavingTheGridStopsAtOnce() = runTest {
        val previews = session()
        rest(previews, row(1))
        previews.focus(null)
        assertEquals(1, engine.opened.single().releases)
        assertNull(previews.current.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
    }

    @Test fun turningOffStopsAndReleases() = runTest {
        val previews = session()
        rest(previews, row(1))
        previews.setEnabled(false)
        assertEquals(1, engine.opened.single().releases)
        assertNull(previews.current.value)
        assertFalse(playback.playing.value)
        // Off: resting starts nothing.
        rest(previews, row(2))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
    }

    @Test fun turningOnStartsTheFocusedRowAfterRest() = runTest {
        val previews = session()
        previews.setEnabled(false)
        previews.focus(row(3))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, engine.opened.size)
        previews.setEnabled(true)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("http://test/1/ch3", engine.opened.single().url)
    }

    @Test fun turningOffCancelsAPendingStart() = runTest {
        val previews = session()
        previews.focus(row(1))
        advanceTimeBy(500)
        previews.setEnabled(false)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, engine.opened.size)
    }

    @Test fun leavingStopsAndReleases() = runTest {
        val previews = session()
        rest(previews, row(1))
        previews.leave()
        assertEquals(1, engine.opened.single().releases)
        assertNull(previews.current.value)
        assertFalse(playback.playing.value)
        // Pending starts are cancelled too.
        previews.focus(row(2))
        advanceTimeBy(500)
        previews.leave()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
    }

    @Test fun gateIsHeldOnlyWhilePlaying() = runTest {
        val previews = session()
        previews.focus(row(1))
        advanceTimeBy(500)
        assertFalse(playback.playing.value)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(playback.playing.value)
        engine.opened[0].status.value = PreviewStatus.PLAYING
        runCurrent()
        assertTrue(playback.playing.value)
        previews.focus(row(2))
        assertFalse(playback.playing.value)
        // The gate is counted: a preview's exit never takes away another player's enter.
        playback.enter()
        advanceTimeBy(1_000)
        runCurrent()
        previews.leave()
        assertTrue(playback.playing.value)
        playback.exit()
        assertFalse(playback.playing.value)
    }

    @Test fun failedPreviewReleasesTheGate() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = engine.opened.single()
        handle.status.value = PreviewStatus.FAILED
        runCurrent()
        assertEquals(1, handle.releases)
        assertFalse(playback.playing.value)
        // The panel still shows the failure.
        assertSame(handle, previews.current.value?.second)
        // And it's never released again.
        previews.focus(row(2))
        previews.leave()
        assertEquals(1, handle.releases)
        playback.enter()
        playback.exit()
        assertFalse(playback.playing.value)
    }

    @Test fun unresolvableCopyShowsAFailureWithoutAPlayer() = runTest {
        val previews = LivePreviews(backgroundScope, playback, resolveUrl = { error("Source not found") }, engine = engine)
        previews.setEnabled(true)
        rest(previews, row(1))
        assertEquals(0, engine.opened.size)
        assertEquals(PreviewStatus.FAILED, previews.current.value?.second?.state?.value)
        assertFalse(playback.playing.value)
        previews.leave()
        assertNull(previews.current.value)
    }

    @Test fun selectWhileLoadingHandsOverTheSamePlayer() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = engine.opened.single()
        assertEquals(PreviewStatus.LOADING, handle.state.value)
        val taken = previews.takeFor(row(1))
        assertSame(handle, taken)
        assertNull(previews.current.value)
        assertFalse(playback.playing.value)
        // The session no longer owns it: nothing it does releases it.
        handle.status.value = PreviewStatus.FAILED
        runCurrent()
        previews.focus(row(2))
        previews.setEnabled(false)
        previews.leave()
        assertEquals(0, handle.releases)
        assertFalse(playback.playing.value)
    }

    @Test fun selectWhilePlayingHandsOverTheSamePlayer() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = engine.opened.single()
        handle.status.value = PreviewStatus.PLAYING
        runCurrent()
        assertSame(handle, previews.takeFor(row(1).copy(label = "Other label")))
    }

    @Test fun takeForAnotherCopyReturnsNull() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = engine.opened.single()
        assertNull(previews.takeFor(row(1, sourceId = 2)))
        assertNull(previews.takeFor(row(1, channel = "other")))
        // The full-screen player opens its own stream, so the preview stops: one stream at a time.
        assertEquals(1, handle.releases)
        assertNull(previews.current.value)
        assertFalse(playback.playing.value)
    }

    @Test fun takeForAFailedPreviewReturnsNull() = runTest {
        val previews = session()
        rest(previews, row(1))
        engine.opened.single().status.value = PreviewStatus.FAILED
        runCurrent()
        assertNull(previews.takeFor(row(1)))
        assertEquals(1, engine.opened.single().releases)
    }

    @Test fun handBackIsKeptWhenTheGuideReturnsToTheRow() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = engine.opened.single()
        val taken = previews.takeFor(row(1))!!
        previews.leave() // the full-screen player covers the guide
        previews.giveBack(row(1), taken)
        assertTrue(playback.playing.value)
        advanceTimeBy(2_000)
        runCurrent()
        previews.focus(row(1))
        assertSame(handle, previews.current.value?.second)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
        assertEquals(0, handle.releases)
        assertTrue(playback.playing.value)
        // Kept like any preview: moving away releases it once, with the gate.
        previews.focus(row(2))
        assertEquals(1, handle.releases)
        assertFalse(playback.playing.value)
    }

    @Test fun handBackToTheStillFocusedRowIsKeptAtOnce() = runTest {
        val previews = session()
        rest(previews, row(1))
        val taken = previews.takeFor(row(1))!!
        previews.giveBack(row(1), taken)
        assertSame(taken, previews.current.value?.second)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, (taken as FakeHandle).releases)
        assertEquals(1, engine.opened.size)
        assertTrue(playback.playing.value)
    }

    @Test fun unclaimedHandBackIsReleased() = runTest {
        val previews = session()
        rest(previews, row(1))
        val taken = previews.takeFor(row(1))!! as FakeHandle
        previews.leave()
        previews.giveBack(row(1), taken)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(0, taken.releases)
        assertTrue(playback.playing.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, taken.releases)
        assertFalse(playback.playing.value)
        assertNull(previews.current.value)
        // Too late now: the row starts a fresh preview.
        rest(previews, row(1))
        assertEquals(2, engine.opened.size)
        assertEquals(1, taken.releases)
    }

    @Test fun handBackIsReleasedAtOnceWhenTheGuideFocusesAnotherRow() = runTest {
        val previews = session()
        rest(previews, row(1))
        val taken = previews.takeFor(row(1))!! as FakeHandle
        previews.leave()
        previews.giveBack(row(1), taken)
        previews.focus(row(2))
        assertEquals(1, taken.releases)
        assertFalse(playback.playing.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, taken.releases)
    }

    @Test fun handBackWhileOffIsReleased() = runTest {
        val previews = session()
        rest(previews, row(1))
        val taken = previews.takeFor(row(1))!! as FakeHandle
        previews.setEnabled(false)
        previews.giveBack(row(1), taken)
        assertEquals(1, taken.releases)
        assertFalse(playback.playing.value)
    }

    @Test fun focusingTheSameRowAfterASelectWithoutAPreviewRestartsIt() = runTest {
        val previews = session()
        rest(previews, row(1))
        assertNull(previews.takeFor(row(1, channel = "other copy")))
        // The full-screen player closes; the guide reports the same row again.
        previews.focus(row(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, engine.opened.size)
        assertSame(engine.opened[1], previews.current.value?.second)
    }

    @Test fun focusingTheSameRowAfterATakeThatNeverCameBackRestartsIt() = runTest {
        val previews = session()
        rest(previews, row(1))
        assertSame(engine.opened[0], previews.takeFor(row(1)))
        // The full-screen player failed and closed without giving the player back.
        previews.focus(row(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, engine.opened.size)
        assertTrue(playback.playing.value)
    }

    @Test fun handBackThatRecoveredInFullScreenIsKept() = runTest {
        val previews = session()
        rest(previews, row(1))
        val taken = previews.takeFor(row(1))!! as FakeHandle
        // Full screen: an error, then a successful retry on the same player.
        taken.status.value = PreviewStatus.FAILED
        taken.status.value = PreviewStatus.PLAYING
        previews.giveBack(row(1), taken)
        runCurrent()
        assertEquals(0, taken.releases)
        assertSame(taken, previews.current.value?.second)
        assertTrue(playback.playing.value)
    }

    // Going full screen: the guide parks the taken handle (with the panel's bounds) for the player screen.

    @Test fun parkedHandOffIsTakenOnceByItsChannel() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = previews.takeFor(row(1))!!
        val bounds = Rect(10f, 20f, 370f, 222.5f)
        assertFalse(previews.handingOff)
        previews.park(row(1), handle, bounds)
        assertTrue(previews.handingOff)
        val handOff = previews.takeParked(1, "ch1")!!
        assertSame(handle, handOff.handle)
        assertEquals(row(1), handOff.target)
        assertEquals(bounds, handOff.from)
        assertNull(previews.takeParked(1, "ch1"))
        // Still with full screen until it comes back.
        assertTrue(previews.handingOff)
        previews.giveBack(row(1), handle)
        assertFalse(previews.handingOff)
    }

    @Test fun parkedHandOffForAnotherChannelIsReleased() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = previews.takeFor(row(1))!! as FakeHandle
        previews.park(row(1), handle, null)
        assertNull(previews.takeParked(1, "other"))
        assertEquals(1, handle.releases)
        assertFalse(previews.handingOff)
        assertFalse(playback.playing.value)
    }

    @Test fun parkedHandOffNobodyTookIsReleasedWhenTheGuideGoes() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = previews.takeFor(row(1))!! as FakeHandle
        previews.park(row(1), handle, null)
        previews.leave()
        assertEquals(1, handle.releases)
        assertFalse(previews.handingOff)
        assertNull(previews.takeParked(1, "ch1"))
    }

    @Test fun aTakenHandOffIsNotReleasedWhenTheGuideGoes() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = previews.takeFor(row(1))!! as FakeHandle
        previews.park(row(1), handle, null)
        previews.takeParked(1, "ch1")
        previews.leave()
        previews.setEnabled(false)
        assertEquals(0, handle.releases)
        assertTrue(previews.handingOff)
    }

    // While a full-screen player is up, nothing starts: one stream at a time, whatever the guide reports.

    @Test fun nothingStartsWhileCovered() = runTest {
        val previews = session()
        previews.cover()
        previews.focus(row(1))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, engine.opened.size)
        previews.setEnabled(false)
        previews.setEnabled(true)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, engine.opened.size)
        // Full screen closed: the focused row rests as usual.
        previews.uncover()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, engine.opened.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.opened.size)
    }

    @Test fun coveringStopsAPendingStartAndAPlayingPreview() = runTest {
        val previews = session()
        rest(previews, row(1))
        val first = engine.opened.single()
        previews.focus(row(2))
        advanceTimeBy(500)
        previews.cover()
        assertEquals(1, first.releases)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
        assertNull(previews.current.value)
        assertFalse(playback.playing.value)
    }

    /** Rest, select, full screen, Back: the guide reports its row while the player still covers it. One open throughout. */
    @Test fun handOffAndBackOpensOneStream() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = previews.takeFor(row(1))!! as FakeHandle
        previews.park(row(1), handle, null)
        // A focus report in the frame of the push, before the player screen covers the guide.
        previews.focus(row(1))
        val handOff = previews.takeParked(1, "ch1")!!
        previews.cover()
        previews.leave() // the guide goes once the grow ends
        advanceTimeBy(5_000)
        runCurrent()
        // Back: the guide is drawn again and reports its row while the player shrinks over it.
        previews.focus(row(1))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
        previews.giveBack(handOff.target, handOff.handle)
        previews.uncover()
        assertSame(handle, previews.current.value?.second)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
        assertEquals(0, handle.releases)
        assertTrue(playback.playing.value)
    }

    @Test fun handBackWithFocusOnAnotherRowIsReleasedAndThatRowRests() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = previews.takeFor(row(1))!! as FakeHandle
        previews.park(row(1), handle, null)
        previews.takeParked(1, "ch1")
        previews.cover()
        previews.leave()
        // Back to a guide whose window or chip changed: another row has focus.
        previews.focus(row(2))
        previews.giveBack(row(1), handle)
        assertEquals(1, handle.releases)
        assertFalse(playback.playing.value)
        assertFalse(previews.handingOff)
        // Only once the full-screen player has gone does the focused row rest.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
        previews.uncover()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, engine.opened.size)
        assertEquals("row2", previews.current.value?.first?.rowKey)
    }

    @Test fun uncoveringBeforeTheHandBackStillKeepsIt() = runTest {
        val previews = session()
        rest(previews, row(1))
        val handle = previews.takeFor(row(1))!! as FakeHandle
        previews.park(row(1), handle, null)
        previews.takeParked(1, "ch1")
        previews.cover()
        previews.leave()
        previews.focus(row(1))
        previews.uncover()
        previews.giveBack(row(1), handle)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, engine.opened.size)
        assertSame(handle, previews.current.value?.second)
        assertEquals(0, handle.releases)
    }
}
