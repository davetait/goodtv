package com.keyspaceapps.goodtv.ui.nav

import androidx.media3.exoplayer.ExoPlayer
import com.keyspaceapps.goodtv.data.guide.PlaybackState
import com.keyspaceapps.goodtv.ui.live.LivePreviews
import com.keyspaceapps.goodtv.ui.live.PreviewEngine
import com.keyspaceapps.goodtv.ui.live.PreviewHandle
import com.keyspaceapps.goodtv.ui.live.PreviewStatus
import com.keyspaceapps.goodtv.ui.live.PreviewTarget
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

/**
 * The player entry's claim on a parked preview: taken while composing, so a composition that never commits
 * (abandoned) must still give it back, or the handle plays on, owned by nobody.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandOffClaimTest {
    private class FakeHandle : PreviewHandle {
        var releases = 0
        override val state: StateFlow<PreviewStatus> = MutableStateFlow(PreviewStatus.PLAYING)
        override val player: ExoPlayer? = null
        override fun resume() = Unit
        override fun pause() = Unit
        override fun release() { releases++ }
    }

    private val opened = mutableListOf<FakeHandle>()
    private val playback = PlaybackState()
    private val fox = PreviewTarget("fox", 7, "fx", "Fox")

    /** A guide that previewed [fox], selected it and parked its player for the full-screen player (which takes it, then the guide goes). */
    private fun TestScope.parked(): LivePreviews {
        val engine = object : PreviewEngine {
            override fun open(url: String): PreviewHandle = FakeHandle().also { opened += it }
        }
        val previews = LivePreviews(backgroundScope, playback, resolveUrl = { "u" }, engine = engine).also { it.setEnabled(true) }
        previews.focus(fox)
        advanceTimeBy(1_000)
        runCurrent()
        previews.park(fox, previews.takeFor(fox)!!, null)
        return previews
    }

    @Test fun anAbandonedClaimGivesThePlayerBack() = runTest {
        val previews = parked()
        val claim = HandOffClaim.take(previews, fox.sourceId, fox.channelId).also { previews.leave() }
        assertSame(opened.single(), claim.handOff?.handle)
        claim.onAbandoned()
        assertFalse(previews.handingOff)
        // Back with the guide: the guide shows it on the row, the same player.
        previews.focus(fox)
        assertSame(opened.single(), previews.current.value?.second)
        assertEquals(0, opened.single().releases)
    }

    @Test fun anAbandonedClaimNobodyShowsIsReleased() = runTest {
        val previews = parked()
        HandOffClaim.take(previews, fox.sourceId, fox.channelId).also { previews.leave() }.onAbandoned()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, opened.single().releases)
        assertFalse(playback.playing.value)
    }

    @Test fun theClaimGivesBackOnce() = runTest {
        val previews = parked()
        val claim = HandOffClaim.take(previews, fox.sourceId, fox.channelId).also { previews.leave() }
        claim.onRemembered()
        claim.giveBack()
        previews.focus(fox)
        claim.giveBack()
        claim.onForgotten()
        assertSame(opened.single(), previews.current.value?.second)
        assertEquals(0, opened.single().releases)
        assertTrue(playback.playing.value)
    }

    @Test fun noHandOffNoClaim() = runTest {
        val previews = parked()
        val claim = HandOffClaim.take(previews, fox.sourceId, "another")
        assertNull(claim.handOff)
        claim.onAbandoned()
        assertEquals(1, opened.single().releases) // takeParked released the other channel's player
    }
}
