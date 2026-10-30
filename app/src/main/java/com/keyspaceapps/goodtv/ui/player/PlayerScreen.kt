package com.keyspaceapps.goodtv.ui.player

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.progress.WatchRules
import com.keyspaceapps.goodtv.data.subtitles.OnlineSubtitleSource
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesException
import com.keyspaceapps.goodtv.data.subtitles.OsSubtitle
import com.keyspaceapps.goodtv.domain.PlayableItem
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Controls fade out after this long without a key press, while playing. */
const val CONTROLS_HIDE_MS = 4_000L
/** How often on-demand playback reports its position while playing. */
const val PROGRESS_REPORT_MS = 10_000L

/**
 * Full-screen player with minimal TV controls ([PlayerControls]): they show on any key and fade
 * after [CONTROLS_HIDE_MS] while playing, and stay up while paused, seeking or choosing a track.
 * With the controls hidden, Select plays/pauses and left/right start seeking ([PlayerKeys]); Back
 * closes a panel, then hides controls the user has been using, then leaves (controls that only
 * appeared on their own, as a stream starts, don't cost an extra Back). Subtitles are drawn by [SubtitleLayer].
 *
 * Episodes with a next one ([upNext]): at the credits (the point an episode counts as watched,
 * [WatchRules.isFinished]) the video shrinks top-left and [UpNextScreen] offers it; "Play now" or a
 * [UP_NEXT_COUNTDOWN_MS] countdown (which only runs while playing) calls [onPlayNext]. Back or "Back
 * to credits" dismisses it for good; if the episode then ends, the player leaves ([onBack]).
 *
 * [onlineSubtitles] (OpenSubtitles) is searched only once the file's tracks are known and it has no
 * English subtitles of its own (the file's own always win); then "English · OpenSubtitles" is
 * offered, downloaded only when chosen, drawn here with its Timing offset ([SubtitleTiming]).
 *
 * On demand, playback starts at [startPositionMs] and [onProgress] (position, duration) is called
 * every [PROGRESS_REPORT_MS] while playing, on pause, when the app goes to the background and when
 * the screen closes; never before playback has actually started, so a resume can't be wiped by
 * the 0 the player reports before its first seek.
 *
 * [adopted] is a player already playing [item] (the guide's live preview): the screen uses it as it is, without
 * preparing it again, listens and meters it from now on, and on closing passes it to [onAdoptedClosed] instead of
 * releasing it. With [growFrom] (its picture's bounds in the window), the picture sits there at [growth] 0, with the
 * screen's black clear so what's beneath shows, and fills the screen at 1; the controls wait for 1.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    item: PlayableItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startPositionMs: Long = 0,
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    upNext: UpNext? = null,
    onPlayNext: () -> Unit = {},
    onlineSubtitles: OnlineSubtitleSource? = null,
    adopted: ExoPlayer? = null,
    onAdoptedClosed: ((ExoPlayer) -> Unit)? = null,
    growFrom: Rect? = null,
    growth: () -> Float = { 1f },
) {
    val context = LocalContext.current
    var isBuffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    // Not "playWhenReady": inside the player's apply {} below, a local of that name would shadow the player's own.
    var wantsToPlay by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<PlaybackException?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Focus reaches the controls a frame after they appear; until then the screen keeps the D-pad,
    // so a quick run of presses (seeking from hidden controls) isn't lost in between.
    var controlsFocused by remember { mutableStateOf(false) }
    // Whether the user has pressed anything since the controls appeared. Back hides controls the
    // user is using; when they only showed by themselves (a stream starting) Back leaves at once.
    var controlsInUse by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val rootFocus = remember { FocusRequester() }
    var focusRequested by remember { mutableStateOf(false) }
    var upNextDismissed by remember { mutableStateOf(false) }
    var upNextLeftMs by remember { mutableLongStateOf(UP_NEXT_COUNTDOWN_MS) }
    val showUpNext = !item.isLive && upNext != null && !upNextDismissed && error == null &&
        durationMs > 0 && WatchRules.isFinished(positionMs, durationMs)
    val latestUpNext by rememberUpdatedState(upNext)
    val latestOnPlayNext by rememberUpdatedState(onPlayNext)
    val latestOnBack by rememberUpdatedState(onBack)
    // The countdown, Play now and the episode ending can coincide; hand over to the next one once.
    val playNextOnce = remember {
        var done = false
        { if (!done) { done = true; latestOnPlayNext() } }
    }

    // Counts downloads for the info box; a few additions per read, so it stays on.
    val traffic = remember(item.url) { TrafficMeter() }
    // An adopted player has already buffered some way: the bitrate counts from what arrives from now on.
    val bitrate = remember(item.url) { BitrateMeter().apply { reset(bytes = 0, fromMs = adopted?.bufferedPosition ?: if (item.isLive) 0 else startPositionMs) } }
    var showInfo by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(InfoUi()) }

    val player = remember(item.url, adopted) {
        adopted ?: PlayerFactory.create(context, traffic).apply {
            setMediaItem(MediaItem.fromUri(item.url), if (item.isLive) 0L else startPositionMs)
            playWhenReady = true
            prepare()
        }
    }
    val latestOnAdoptedClosed by rememberUpdatedState(onAdoptedClosed)
    // The video's size and shape as the player reports them, for fitting the picture to the screen.
    val presentation = rememberPresentationState(player)

    val report = remember(player) {
        var started = false
        object {
            fun markStarted() { started = true }
            fun now() {
                if (item.isLive || !started || onProgress == null) return
                onProgress(player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L)
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE && error == null
                if (playbackState == Player.STATE_ENDED) {
                    report.now()
                    // The credits ran out: on to the next episode, or out if Up next was dismissed.
                    if (latestUpNext != null) { if (upNextDismissed) latestOnBack() else playNextOnce() }
                }
            }
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { wantsToPlay = ready }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) bitrate.reset(traffic.bytes, new.positionMs)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) report.markStarted() else report.now()
            }
            override fun onPlayerError(e: PlaybackException) { error = e; isBuffering = false }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onCues(cueGroup: CueGroup) { cues = cueGroup.cues }
        }
        val switch = if (player === adopted) PlayerFactory.trafficOf(player) else null
        if (player === adopted) {
            // Already under way: start from where it is.
            error = player.playerError
            isPlaying = player.isPlaying
            wantsToPlay = player.playWhenReady
            isBuffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE && error == null
            tracks = player.currentTracks
            switch?.target = traffic
        }
        player.addListener(listener)
        onDispose {
            report.now() // leaving the player: save where we got to before releasing it
            player.removeListener(listener)
            if (switch?.target === traffic) switch.target = null
            val giveBack = latestOnAdoptedClosed
            if (player === adopted && giveBack != null) giveBack(player) else player.release()
        }
    }

    // Position, buffer and clock: often while the controls show, rarely otherwise (only "Ends at" needs it).
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            bufferedMs = player.bufferedPosition
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            now = System.currentTimeMillis()
            delay(if (controlsVisible) 250 else 1_000)
        }
    }
    if (!item.isLive) {
        LaunchedEffect(player) {
            while (true) {
                delay(PROGRESS_REPORT_MS)
                if (player.isPlaying) report.now()
            }
        }
    }

    // A pending seek lands a moment after the last press.
    LaunchedEffect(scrubMs) {
        val target = scrubMs ?: return@LaunchedEffect
        delay(Scrub.COMMIT_DELAY_MS)
        player.seekTo(target)
        positionMs = target
        scrubMs = null
    }

    LaunchedEffect(controlsVisible, lastInteraction, isPlaying, panel, scrubMs, error) {
        if (controlsVisible && isPlaying && panel == null && scrubMs == null && error == null) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    // With the controls (and Up next) gone, the screen itself takes the remote's keys again.
    LaunchedEffect(controlsVisible, showUpNext) {
        if (!controlsVisible) controlsInUse = false
        if (!controlsVisible && !showUpNext && focusRequested) rootFocus.requestFocus()
    }

    // The info box reads what the player already keeps (its buffer, decoder counters, the bytes counted
    // above) once a second, and only while it shows; it never asks the network or the player for more.
    LaunchedEffect(showInfo, player) {
        if (!showInfo) return@LaunchedEffect
        while (true) {
            val format = player.videoFormat
            val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
            val dropped = counters?.droppedBufferCount?.toLong() ?: 0L
            info = InfoUi(
                video = format?.let { InfoText.video(it.width, it.height, it.frameRate, it.sampleMimeType) },
                bitrateBps = bitrate.bitrateBps(traffic.bytes, player.bufferedPosition),
                declaredBps = format?.bitrate?.takeIf { it > 0 }?.toLong(),
                networkBps = traffic.networkBps(),
                bufferAheadMs = player.totalBufferedDuration,
                droppedFrames = dropped,
                totalFrames = dropped + (counters?.renderedOutputBufferCount?.toLong() ?: 0L),
            )
            delay(1_000)
        }
    }

    // Up next replaces the controls and counts down while the credits play.
    LaunchedEffect(showUpNext) {
        upNextLeftMs = UP_NEXT_COUNTDOWN_MS
        if (!showUpNext) return@LaunchedEffect
        controlsVisible = false
        panel = null
        scrubMs = null
        while (upNextLeftMs > 0) {
            delay(100)
            if (player.isPlaying) upNextLeftMs -= 100
        }
        playNextOnce()
    }

    // Pause when the user leaves the app (Home), resume on return if it was playing.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        var resumeOnStart = false
        val observer = LifecycleEventObserver { _, event ->
            when (PlayerLifecycle.actionFor(event)) {
                PlayerLifecycleAction.PAUSE -> { resumeOnStart = player.playWhenReady; player.pause(); report.now() }
                PlayerLifecycleAction.RESUME -> if (resumeOnStart) player.play()
                null -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun back() {
        when {
            error != null -> onBack()
            panel != null -> panel = null
            showUpNext -> upNextDismissed = true
            controlsVisible && controlsInUse -> controlsVisible = false
            else -> onBack()
        }
    }
    BackHandler(onBack = ::back)

    fun togglePlay() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    fun scrub(forward: Boolean, repeatCount: Int) {
        if (item.isLive) return
        scrubMs = Scrub.next(scrubMs ?: player.currentPosition, forward, repeatCount, durationMs)
    }

    // OpenSubtitles: searched once the file's tracks are known and only if it has no English of its own.
    val scope = rememberCoroutineScope()
    var onlineFound by remember { mutableStateOf<OsSubtitle?>(null) }
    var onlineAll by remember { mutableStateOf<List<TimedCue>?>(null) } // non-null while those subtitles are on
    var onlineOffsetMs by remember { mutableLongStateOf(0L) }
    var onlineCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var subtitleBusy by remember { mutableStateOf(false) }
    var subtitleNotice by remember { mutableStateOf<String?>(null) }
    // The download in progress: one at a time, and dropped if the user picks something else first.
    var onlineJob by remember { mutableStateOf<Job?>(null) }
    val needsOnline = onlineSubtitles != null && tracks.groups.isNotEmpty() && !remember(tracks) { hasEnglishTrack(tracks) }
    LaunchedEffect(onlineSubtitles, needsOnline) {
        if (needsOnline && onlineFound == null) onlineFound = onlineSubtitles?.find()
    }
    LaunchedEffect(onlineAll) {
        val all = onlineAll ?: run { onlineCues = emptyList(); return@LaunchedEffect }
        while (true) {
            onlineCues = all.at(player.currentPosition, onlineOffsetMs).map { Cue.Builder().setText(it.text).build() }
            delay(100)
        }
    }
    val quotaNotice = stringResource(R.string.player_subtitles_quota, "%s")
    val failedNotice = stringResource(R.string.player_subtitles_online_failed)
    fun chooseOnline(choice: OsSubtitle) {
        val source = onlineSubtitles ?: return
        if (onlineJob?.isActive == true) return // already downloading: a second press mustn't spend another
        subtitleBusy = true
        subtitleNotice = null
        onlineJob = scope.launch {
            val result = source.fetch(choice)
            val cues = result.getOrNull()?.let { withContext(Dispatchers.Default) { Srt.parse(it) } }
            val offset = if (cues != null) source.offsetMs() else 0L
            withContext(Dispatchers.Main.immediate) {
                subtitleBusy = false
                if (cues != null) {
                    onlineAll = cues
                    onlineOffsetMs = offset
                    player.selectTrack(C.TRACK_TYPE_TEXT, null) // the file has no English; its other subtitles go off
                    panel = null
                } else {
                    val e = result.exceptionOrNull()
                    val reset = (e as? OpenSubtitlesException)?.takeIf { it.reason == OpenSubtitlesException.Reason.QUOTA }?.resetUtc?.let(PlaybackErrors::utcClock)
                    subtitleNotice = if (reset != null) quotaNotice.format(reset) else failedNotice
                }
            }
        }
    }
    /** Another subtitle choice wins over a download still on its way (which is kept for next time). */
    fun cancelOnline() {
        onlineJob?.cancel()
        onlineJob = null
        subtitleBusy = false
    }
    val onlineLabel = stringResource(R.string.player_subtitles_online)

    val offLabel = stringResource(R.string.player_subtitles_off)
    val trackFallback = stringResource(R.string.player_track_number)
    val ui = PlayerUi(
        title = item.title,
        isLive = item.isLive,
        isPlaying = isPlaying,
        positionMs = positionMs,
        bufferedMs = bufferedMs,
        durationMs = durationMs,
        subtitles = remember(tracks, offLabel, onlineFound, needsOnline, onlineAll != null) {
            val own = trackChoices(tracks, C.TRACK_TYPE_TEXT, offLabel) { trackFallback.format(it) }
                // While OpenSubtitles is on, the file's tracks are off but "Off" isn't the choice.
                .map { if (onlineAll != null && it.ref == null) it.copy(selected = false) else it }
            val found = onlineFound
            if (needsOnline && found != null) own + TrackChoice(onlineLabel, selected = onlineAll != null, ref = null, online = true) else own
        },
        audio = remember(tracks) { trackChoices(tracks, C.TRACK_TYPE_AUDIO, null) { trackFallback.format(it) } },
        clock = DateFormat.getTimeFormat(context).format(Date(now)),
        endsAt = if (!item.isLive && durationMs > 0) {
            stringResource(R.string.player_ends_at, DateFormat.getTimeFormat(context).format(Date(now + (durationMs - positionMs).coerceAtLeast(0))))
        } else {
            null
        },
        onlineSubtitleOffsetMs = if (onlineAll != null) onlineOffsetMs else null,
        subtitleNotice = subtitleNotice,
        subtitleBusy = subtitleBusy,
    )

    // Growing from (or shrinking back to) the guide's panel: only the picture shows until it fills the screen.
    val latestGrowth by rememberUpdatedState(growth)
    val grown by remember(growFrom) { derivedStateOf { growFrom == null || latestGrowth() >= 1f } }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // Playing, or buffering on the way to it: the TV's screensaver mustn't start. Paused or stopped, it may.
    val keepScreenOn = error == null && wantsToPlay && (isPlaying || isBuffering)

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (growFrom == null) Modifier.background(Color.Black)
                else Modifier.onPlaced { origin = it.positionInWindow() }.drawBehind { drawRect(Color.Black, alpha = latestGrowth().coerceIn(0f, 1f)) },
            )
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key != Key.Back) { lastInteraction = System.currentTimeMillis(); controlsInUse = true }
                // Taken here, before Compose's own Back handling moves focus out of the panel or
                // controls instead (one press per layer would otherwise be swallowed).
                if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyUp) back()
                    return@onPreviewKeyEvent true
                }
                false
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || error != null) return@onKeyEvent false
                // nativeKeyCode is the android.view.KeyEvent code; Key.keyCode packs it into the upper 32 bits.
                // While Up next shows, the D-pad stays with its buttons; media keys still work.
                val action = PlayerKeys.actionFor(event.key.nativeKeyCode, item.isLive, (controlsVisible && controlsFocused) || showUpNext)
                    ?: return@onKeyEvent false
                when (action) {
                    PlayerAction.SHOW_CONTROLS -> Unit
                    PlayerAction.TOGGLE_PLAY -> togglePlay()
                    PlayerAction.PLAY -> player.play()
                    PlayerAction.PAUSE -> player.pause()
                    PlayerAction.SEEK_FORWARD -> player.seekForward()
                    PlayerAction.SEEK_BACK -> player.seekBack()
                    PlayerAction.SCRUB_FORWARD -> scrub(true, event.nativeKeyEvent.repeatCount)
                    PlayerAction.SCRUB_BACK -> scrub(false, event.nativeKeyEvent.repeatCount)
                }
                if (!showUpNext) controlsVisible = true
                true
            }
            .focusRequester(rootFocus)
            .onPlaced { if (!focusRequested) { focusRequested = true; rootFocus.requestFocus() } }
            .focusable(),
    ) {
        // Its own empty child, so switching it on and off leaves the screen's own modifiers alone.
        if (keepScreenOn) Box(Modifier.keepScreenOn())
        // Full screen, or (Up next) a 16:9 window top-left with the next episode around it.
        val shrink by animateFloatAsState(if (showUpNext) 1f else 0f, tween(450), label = "upNext")
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val small = maxWidth * UpNextLayout.VIDEO_WIDTH_FRACTION
            val margin = lerp(0.dp, UpNextLayout.Margin, shrink)
            // The picture keeps its real shape (pixel aspect ratio included) inside the frame, with black bars
            // where it doesn't fill it, instead of being stretched to the screen's 16:9.
            val width = lerp(maxWidth, small, shrink)
            val height = lerp(maxHeight, small * 9f / 16f, shrink)
            Box(
                if (growFrom == null) Modifier.offset(margin, margin).size(width, height)
                else with(LocalDensity.current) { Modifier.growing(growFrom, { origin }, latestGrowth, margin.roundToPx(), width.roundToPx(), height.roundToPx()) },
                contentAlignment = Alignment.Center,
            ) {
                PlayerSurface(
                    player = player,
                    surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                    modifier = Modifier.resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp).testTag("video_surface"),
                )
            }
            val prompt = upNext
            if (prompt != null) {
                AnimatedVisibility(visible = showUpNext, enter = fadeIn(tween(300, delayMillis = 150)), exit = fadeOut(tween(200))) {
                    UpNextScreen(
                        upNext = prompt,
                        remainingMs = upNextLeftMs,
                        screenWidth = maxWidth,
                        onPlayNow = playNextOnce,
                        onBackToCredits = { upNextDismissed = true },
                    )
                }
            }
        }

        val showControls = controlsVisible && error == null && !showUpNext && grown
        val captionInset by animateDpAsState(if (showControls) (if (item.isLive) 150.dp else 210.dp) else 64.dp, label = "captions")
        if (!showUpNext && grown) SubtitleLayer(if (onlineAll != null) onlineCues else cues, bottomInset = captionInset)

        if (isBuffering && error == null && grown) Spinner(Modifier.align(Alignment.Center))

        // Below the top bar's title and clock; it stays when the controls fade, until Info is pressed again.
        if (showInfo && !showUpNext && grown) PlaybackInfoBox(info, Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 56.dp))

        AnimatedVisibility(visible = showControls, enter = fadeIn(tween(180)), exit = fadeOut(tween(320))) {
            PlayerControls(
                modifier = Modifier.onFocusChanged { controlsFocused = it.hasFocus },
                ui = ui,
                scrubMs = scrubMs,
                panel = panel,
                onTogglePlay = ::togglePlay,
                onScrub = ::scrub,
                onSeekBarSelect = {
                    val target = scrubMs
                    if (target != null) { player.seekTo(target); positionMs = target; scrubMs = null } else togglePlay()
                },
                onOpenPanel = { panel = it; subtitleNotice = null },
                infoOn = showInfo,
                onToggleInfo = { showInfo = !showInfo },
                onSubtitleTiming = { delta ->
                    onlineOffsetMs = (onlineOffsetMs + delta).coerceIn(-SubtitleTiming.LIMIT_MS, SubtitleTiming.LIMIT_MS)
                    val saved = onlineOffsetMs
                    scope.launch { onlineSubtitles?.setOffsetMs(saved) }
                },
                onSelectTrack = { p, choice ->
                    if (choice.online) {
                        onlineFound?.let(::chooseOnline) // the panel closes once the download is in
                        return@PlayerControls
                    }
                    if (p == PlayerPanel.SUBTITLES) { cancelOnline(); onlineAll = null }
                    player.selectTrack(if (p == PlayerPanel.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT, choice.ref)
                    panel = null
                },
            )
        }

        error?.takeIf { grown }?.let { e ->
            PlaybackErrorCard(
                error = e,
                onRetry = { error = null; isBuffering = true; player.prepare(); player.play() },
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * Lays the picture out between [from] (window bounds, made local with [origin]) at [growth] 0 and its full-screen
 * place ([offset], [width] × [height] px) at 1. Read while laying out, so the animation never recomposes the screen.
 */
private fun Modifier.growing(from: Rect, origin: () -> Offset, growth: () -> Float, offset: Int, width: Int, height: Int) = layout { measurable, _ ->
    val t = growth().coerceIn(0f, 1f)
    val start = from.topLeft - origin()
    val w = (from.width + (width - from.width) * t).toInt().coerceAtLeast(0)
    val h = (from.height + (height - from.height) * t).toInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(width, height) {
        placeable.place(IntOffset((start.x + (offset - start.x) * t).toInt(), (start.y + (offset - start.y) * t).toInt()))
    }
}

/** A thin rotating arc; quieter than a full progress wheel. */
@Composable
internal fun Spinner(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Canvas(modifier.size(size)) {
        drawArc(Color.White.copy(alpha = 0.9f), turn, 100f, useCenter = false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Says what went wrong in plain words, with Try again (focused) and Back. */
@Composable
private fun PlaybackErrorCard(error: PlaybackException, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val retryFocus = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }
    Column(
        modifier.width(560.dp).background(Color(0xFF0B1220).copy(alpha = 0.94f), RoundedCornerShape(24.dp)).padding(36.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(stringResource(R.string.player_error_title), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(PlaybackErrors.messageRes(error.errorCode)), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(6.dp))
        Text(PlaybackErrors.detail(error.errorCodeName), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.45f))
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus).onPlaced { if (!requested) { requested = true; retryFocus.requestFocus() } }) {
                Text(stringResource(R.string.player_try_again))
            }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.player_back)) }
        }
    }
}

/** Plain-language reasons for playback failures, by Media3 error-code range. */
object PlaybackErrors {
    fun messageRes(code: Int): Int = when (code) {
        in 2000..2999 -> R.string.player_error_network
        in 3000..3999 -> R.string.player_error_format
        in 4000..4999 -> R.string.player_error_decoding
        else -> R.string.player_error_generic
    }

    /** "2026-10-22T00:00:00.000Z" → "00:00" (UTC), for the OpenSubtitles limit notice; null if unreadable. */
    fun utcClock(iso: String): String? = runCatching {
        java.time.Instant.parse(iso).atOffset(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()

    /** "ERROR_CODE_DECODING_FAILED" → "decoding failed": small print for support. */
    fun detail(codeName: String): String = codeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
}
