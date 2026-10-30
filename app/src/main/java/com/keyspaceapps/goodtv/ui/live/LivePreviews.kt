package com.keyspaceapps.goodtv.ui.live

import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.keyspaceapps.goodtv.data.guide.PlaybackState
import com.keyspaceapps.goodtv.ui.player.PlayerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What a preview plays: one copy of one channel. */
data class PreviewTarget(val rowKey: String, val sourceId: Long, val channelId: String, val label: String)

/** The player behind a preview; ExoPlayer in the app, a fake in tests. */
interface PreviewEngine {
    fun open(url: String): PreviewHandle
}

interface PreviewHandle {
    val state: StateFlow<PreviewStatus>
    /** For the panel's surface and the hand-off to the full-screen player; null in fakes. */
    val player: ExoPlayer?
    /** Plays again if full screen left it paused: a preview always plays. */
    fun resume()
    /** Holds it, silent, while a hand-back waits for the guide to show it. */
    fun pause()
    fun release()
}

enum class PreviewStatus { LOADING, PLAYING, FAILED }

/**
 * The guide's live preview: starts the row focus rests on for [restMs], stops it the moment focus
 * moves to another row or leaves, and can hand its player to the full-screen player and take it back.
 * While the session holds a working player it holds [playback]'s gate, so guide downloads wait.
 *
 * Call from the main thread; [scope] must run on it too (ExoPlayer is main-thread only).
 */
class LivePreviews(
    private val scope: CoroutineScope,
    private val playback: PlaybackState,
    private val resolveUrl: suspend (PreviewTarget) -> String,
    private val engine: PreviewEngine,
    private val restMs: Long = 1_000,
    private val handBackGraceMs: Long = 3_000,
) {
    /** A preview handed to full screen; [from] is where the guide drew it, in window pixels (null: unknown). */
    class HandOff(val target: PreviewTarget, val handle: PreviewHandle, val from: Rect?)

    /** A handle the session answers for; [done] once released, failed or taken, after which the session never touches it again. */
    private class Held(val target: PreviewTarget, val handle: PreviewHandle, var gate: Boolean) {
        var done = false
        var watcher: Job? = null
    }

    private val shown = MutableStateFlow<Pair<PreviewTarget, PreviewHandle>?>(null)

    /** What the panel draws: the row's preview, loading, playing or failed. */
    val current: StateFlow<Pair<PreviewTarget, PreviewHandle>?> = shown.asStateFlow()

    private var enabled = false
    private var focused: PreviewTarget? = null
    private var starting: Job? = null
    private var held: Held? = null
    private var handBack: Held? = null
    private var handBackTimer: Job? = null
    private var parked: HandOff? = null
    private var covered = 0

    /**
     * From [park] until the hand-off comes back ([giveBack]) or is let go: a push or pop of the player now
     * is the picture growing out of the panel or shrinking back into it.
     */
    var handingOff: Boolean = false
        private set

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            focused?.let { rest(it) }
        } else {
            stop()
            dropHandBack()
            dropParked()
        }
    }

    /** null = focus left the grid, or the focused cell has no playable copy. */
    fun focus(target: PreviewTarget?) {
        val sameRow = target != null && target.rowKey == focused?.rowKey
        focused = target
        // Same row keeps its preview (or its failure) or its pending start; with neither (after a select), it rests again.
        if (sameRow && (held != null || starting?.isActive == true)) return
        stop()
        val back = handBack
        if (back != null && target != null && back.target.rowKey == target.rowKey) {
            claimHandBack(back)
            return
        }
        dropHandBack()
        if (target != null && enabled) rest(target)
    }

    /** The guide is gone (another tab, a pushed screen, Home, background). */
    fun leave() {
        focused = null
        stop()
        dropHandBack()
        dropParked()
    }

    /** Keeps what [takeFor] gave the guide, and where the panel drew it, for the full-screen player about to open. */
    fun park(target: PreviewTarget, handle: PreviewHandle, from: Rect?) {
        dropParked()
        starting?.cancel()
        parked = HandOff(target, handle, from)
        handingOff = true
    }

    /**
     * The full-screen player for [sourceId]/[channelId] takes the parked hand-off, once; it then owns the
     * handle until [giveBack]. A hand-off parked for another channel is released.
     */
    fun takeParked(sourceId: Long, channelId: String): HandOff? {
        val p = parked ?: return null
        parked = null
        if (p.target.sourceId != sourceId || p.target.channelId != channelId) {
            p.handle.release()
            handingOff = false
            return null
        }
        return p
    }

    /**
     * A full-screen player is up (calls nest): the session stops what it holds and starts nothing, whatever
     * the guide reports underneath it, since the player holds the one stream. [uncover] rests on the row
     * the guide last reported.
     */
    fun cover() {
        covered++
        stop()
        dropHandBack()
    }

    fun uncover() {
        if (covered == 0) return
        covered--
        if (covered == 0) focused?.let(::focus)
    }

    /**
     * Selecting [target]: the live handle if it's [target]'s copy (loading or playing), detached from
     * the session, which also gives up its gate (the full-screen player holds its own). Otherwise
     * null, and whatever the session holds stops, since the full-screen player is about to open its own stream.
     */
    fun takeFor(target: PreviewTarget): PreviewHandle? {
        val candidate = held?.takeIf { it.canHandOver(target) } ?: handBack?.takeIf { it.canHandOver(target) }
        if (candidate == null) {
            stop()
            dropHandBack()
            return null
        }
        if (candidate === held) {
            starting?.cancel()
            held = null
            shown.value = null
        } else {
            handBackTimer?.cancel()
            handBack = null
        }
        candidate.done = true
        candidate.watcher?.cancel()
        exitGate(candidate)
        return candidate.handle
    }

    /**
     * Full screen closed: keep [handle] for [target] if the guide already has that row focused, or
     * focuses it within the grace period; release it at once if the guide is on another row (which then
     * rests) or previews are off. The session owns it again either way. It plays only once the guide
     * shows it: waiting, it's paused, so no sound comes over the logo or from a guide that isn't there.
     */
    fun giveBack(target: PreviewTarget, handle: PreviewHandle) {
        handingOff = false
        stop()
        dropHandBack()
        val now = focused
        if (!enabled || (now != null && now.rowKey != target.rowKey)) {
            // Off, or the guide is already on another row (its window or chip changed): that row rests instead.
            handle.release()
            if (enabled && now != null) rest(now)
            return
        }
        playback.enter()
        val back = Held(target, handle, gate = true)
        if (now != null) {
            // Full screen may have left it paused; kept, it plays (a still frame would hold the gate for nothing).
            handle.resume()
            hold(back)
        } else {
            handle.pause()
            handBack = back
            handBackTimer = scope.launch {
                delay(handBackGraceMs)
                dropHandBack()
            }
        }
    }

    private fun Held.canHandOver(target: PreviewTarget): Boolean =
        !done && this.target.sourceId == target.sourceId && this.target.channelId == target.channelId &&
            handle.state.value != PreviewStatus.FAILED

    private fun rest(target: PreviewTarget) {
        if (covered > 0) return
        starting = scope.launch {
            delay(restMs)
            val url = try {
                resolveUrl(target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Nothing suspends from here on, so a stop on the main thread can't slip in between.
            coroutineContext.ensureActive()
            val handle = url?.let {
                try {
                    engine.open(it)
                } catch (_: Exception) {
                    null
                }
            }
            if (handle == null) {
                val unavailable = Unavailable()
                shown.value = target to unavailable
                held = Held(target, unavailable, gate = false).also { it.done = true }
                return@launch
            }
            playback.enter()
            hold(Held(target, handle, gate = true))
        }
    }

    private fun hold(h: Held) {
        held = h
        shown.value = h.target to h.handle
        h.watcher = scope.launch {
            h.handle.state.first { it == PreviewStatus.FAILED }
            // Keep showing the failure, but let go of the player and the gate.
            release(h)
        }
    }

    private fun claimHandBack(back: Held) {
        handBackTimer?.cancel()
        handBack = null
        back.handle.resume()
        hold(back)
    }

    /** Cancels a pending start and releases the session's preview, if any. */
    private fun stop() {
        starting?.cancel()
        starting = null
        held?.let { release(it) }
        held = null
        shown.value = null
    }

    private fun dropParked() {
        val p = parked ?: return
        parked = null
        p.handle.release()
        handingOff = false
    }

    private fun dropHandBack() {
        handBackTimer?.cancel()
        handBackTimer = null
        handBack?.let { release(it) }
        handBack = null
    }

    private fun release(h: Held) {
        if (h.done) return
        h.done = true
        h.watcher?.cancel()
        h.handle.release()
        exitGate(h)
    }

    private fun exitGate(h: Held) {
        if (!h.gate) return
        h.gate = false
        playback.exit()
    }

    /** A copy whose stream couldn't be found or opened: the panel shows "Preview unavailable". */
    private class Unavailable : PreviewHandle {
        override val state: StateFlow<PreviewStatus> = MutableStateFlow(PreviewStatus.FAILED)
        override val player: ExoPlayer? = null
        override fun resume() = Unit
        override fun pause() = Unit
        override fun release() = Unit
    }
}

/** The app's previews: an ExoPlayer per preview, playing at once, with sound. Main thread only. */
class ExoPreviewEngine(private val context: Context) : PreviewEngine {
    override fun open(url: String): PreviewHandle = ExoPreviewHandle(PlayerFactory.createHandable(context), url)
}

private class ExoPreviewHandle(override val player: ExoPlayer, url: String) : PreviewHandle, Player.Listener {
    private val mapper = PreviewStatusMapper()
    override val state: StateFlow<PreviewStatus> = mapper.status
    private var released = false

    init {
        player.addListener(this)
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = true
        player.prepare()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = mapper.onIsPlaying(isPlaying)
    override fun onPlaybackStateChanged(playbackState: Int) = mapper.onPlaybackState(playbackState)
    override fun onPlayerError(error: PlaybackException) = mapper.onError()

    override fun resume() {
        if (!released) player.playWhenReady = true
    }

    override fun pause() {
        if (!released) player.playWhenReady = false
    }

    override fun release() {
        if (released) return
        released = true
        player.removeListener(this)
        player.release()
    }
}

/** Turns a preview player's events into a [PreviewStatus]. */
internal class PreviewStatusMapper {
    private val current = MutableStateFlow(PreviewStatus.LOADING)
    val status: StateFlow<PreviewStatus> = current.asStateFlow()

    /** Playing from any state: full screen's retry can bring a failed player back. */
    fun onIsPlaying(isPlaying: Boolean) {
        if (isPlaying) current.value = PreviewStatus.PLAYING
    }

    fun onPlaybackState(playbackState: Int) {
        when (playbackState) {
            // A live stream that ends has nothing left to show.
            Player.STATE_ENDED -> current.value = PreviewStatus.FAILED
            // A retry after a failure is loading again; rebuffering while playing keeps the picture.
            Player.STATE_BUFFERING -> if (current.value == PreviewStatus.FAILED) current.value = PreviewStatus.LOADING
        }
    }

    fun onError() {
        current.value = PreviewStatus.FAILED
    }
}
