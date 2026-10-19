package com.keyspaceapps.goodtv.ui.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.domain.PlayableItem
import kotlinx.coroutines.delay

private const val OVERLAY_HIDE_MS = 3_000L

/**
 * Full-screen player. Live: title overlay only. On demand: Select toggles play/pause, left/right
 * seek, and a position overlay appears on any key press and hides after a few seconds.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    item: PlayableItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isBuffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var positionText by remember { mutableStateOf("") }
    var overlayVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focusRequester = remember { FocusRequester() }
    var focusRequested by remember { mutableStateOf(false) }

    val player = remember(item.url) {
        PlayerFactory.create(context).apply {
            setMediaItem(MediaItem.fromUri(item.url))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase() }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    if (!item.isLive) {
        LaunchedEffect(player) {
            while (true) {
                val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                positionText = PlayerFormats.position(player.currentPosition, duration)
                delay(500)
            }
        }
    }

    LaunchedEffect(lastInteraction) {
        delay(OVERLAY_HIDE_MS)
        overlayVisible = false
    }

    BackHandler(onBack = onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onPlaced { if (!focusRequested) { focusRequested = true; focusRequester.requestFocus() } }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                lastInteraction = System.currentTimeMillis()
                overlayVisible = true
                // On Android, Key.keyCode is the android.view.KeyEvent keycode.
                when (PlayerKeys.actionFor(event.key.keyCode.toInt(), item.isLive)) {
                    PlayerAction.TOGGLE_PLAY -> if (player.isPlaying) player.pause() else player.play()
                    PlayerAction.SEEK_FORWARD -> player.seekForward()
                    PlayerAction.SEEK_BACK -> player.seekBack()
                    null -> return@onKeyEvent false
                }
                true
            },
    ) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.fillMaxSize(),
        )
        val showOverlay = overlayVisible || isBuffering || error != null || (!item.isLive && !isPlaying && !isBuffering)
        if (showOverlay) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                if (item.isLive) {
                    Text(stringResource(R.string.live_badge), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                } else {
                    if (!isPlaying && !isBuffering && error == null) {
                        Text(stringResource(R.string.paused), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(positionText, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val centre = error?.let { stringResource(R.string.playback_error, it) } ?: if (isBuffering) stringResource(R.string.player_loading) else null
        if (centre != null) {
            Text(centre, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.Center))
        }
    }
}
