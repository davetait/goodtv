package com.keyspaceapps.goodtv.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R

/** Everything the controls show; the screen owns the player and rebuilds this as it changes. */
@Immutable
data class PlayerUi(
    val title: String,
    val isLive: Boolean,
    val isPlaying: Boolean,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
    /** "Off" first, then each subtitle track. */
    val subtitles: List<TrackChoice> = emptyList(),
    val audio: List<TrackChoice> = emptyList(),
    /** Time of day, e.g. "21:14". */
    val clock: String = "",
    /** "Ends at 22:41", null when unknown or live. */
    val endsAt: String? = null,
    /** The OpenSubtitles timing while those subtitles are on (shows the Timing row); null otherwise. */
    val onlineSubtitleOffsetMs: Long? = null,
    /** A message for the Subtitles panel, e.g. the daily download limit was reached. */
    val subtitleNotice: String? = null,
    /** OpenSubtitles subtitles are being downloaded. */
    val subtitleBusy: Boolean = false,
)

enum class PlayerPanel { SUBTITLES, AUDIO }

private val SidePadding = 56.dp
private val Scrim = Color.Black

/**
 * The player's controls, drawn over the video: title and clock at the top; seek bar, times and the
 * transport row at the bottom; and the Subtitles / Audio side panel. Focus starts on the seek bar
 * (live: the play button). On the seek bar left/right move the preview point ([onScrub]) and
 * Select commits it, or plays/pauses when nothing is pending ([onSeekBarSelect]).
 */
@Composable
fun PlayerControls(
    ui: PlayerUi,
    scrubMs: Long?,
    panel: PlayerPanel?,
    onTogglePlay: () -> Unit,
    onScrub: (forward: Boolean, repeatCount: Int) -> Unit,
    onSeekBarSelect: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
    onSelectTrack: (PlayerPanel, TrackChoice) -> Unit,
    modifier: Modifier = Modifier,
    onSubtitleTiming: (deltaMs: Long) -> Unit = {},
) {
    val seekFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    // Coming back from a panel, focus returns to the button that opened it.
    var lastPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    LaunchedEffect(panel) {
        if (panel == null) lastPanel?.let { (if (it == PlayerPanel.AUDIO) audioFocus else subtitlesFocus).requestFocus() }
        lastPanel = panel
    }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.75f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim.copy(alpha = 0.85f)))),
        )

        TopBar(ui, Modifier.align(Alignment.TopStart).padding(start = SidePadding, end = SidePadding, top = 40.dp))

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = SidePadding, end = SidePadding, bottom = 36.dp)
                .focusProperties { canFocus = panel == null },
        ) {
            if (!ui.isLive) {
                SeekBar(
                    positionMs = ui.positionMs, bufferedMs = ui.bufferedMs, durationMs = ui.durationMs, scrubMs = scrubMs,
                    onScrub = onScrub, onSelect = onSeekBarSelect,
                    modifier = Modifier.focusRequester(seekFocus)
                        .focusProperties { down = playFocus }
                        .onPlaced { if (!initialFocusDone) { initialFocusDone = true; seekFocus.requestFocus() } },
                )
                Spacer(Modifier.height(6.dp))
                val shown = scrubMs ?: ui.positionMs
                Row(Modifier.fillMaxWidth()) {
                    Text(PlayerFormats.clock(shown), style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    if (ui.durationMs > 0) {
                        Text(
                            "−" + PlayerFormats.clock((ui.durationMs - shown).coerceAtLeast(0)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ControlButton(
                    icon = if (ui.isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                    description = stringResource(if (ui.isPlaying) R.string.player_pause else R.string.player_play),
                    onClick = onTogglePlay,
                    large = true,
                    modifier = Modifier.focusRequester(playFocus)
                        .focusProperties { if (!ui.isLive) up = seekFocus }
                        .onPlaced { if (ui.isLive && !initialFocusDone) { initialFocusDone = true; playFocus.requestFocus() } },
                )
                Spacer(Modifier.weight(1f))
                ControlButton(
                    icon = PlayerIcons.Subtitles,
                    description = stringResource(R.string.player_subtitles),
                    onClick = { onOpenPanel(PlayerPanel.SUBTITLES) },
                    active = ui.subtitles.any { it.selected && (it.ref != null || it.online) },
                    modifier = Modifier.focusRequester(subtitlesFocus).focusProperties { if (!ui.isLive) up = seekFocus },
                )
                if (ui.audio.size > 1) {
                    ControlButton(
                        icon = PlayerIcons.Audio,
                        description = stringResource(R.string.player_audio),
                        onClick = { onOpenPanel(PlayerPanel.AUDIO) },
                        modifier = Modifier.focusRequester(audioFocus).focusProperties { if (!ui.isLive) up = seekFocus },
                    )
                }
            }
        }

        if (panel != null) {
            val subtitles = panel == PlayerPanel.SUBTITLES
            TrackPanel(
                title = stringResource(if (panel == PlayerPanel.AUDIO) R.string.player_audio else R.string.player_subtitles),
                choices = if (panel == PlayerPanel.AUDIO) ui.audio else ui.subtitles,
                emptyText = if (subtitles) ui.subtitleNotice ?: stringResource(R.string.player_no_subtitles).takeIf { ui.subtitles.size <= 1 } else null,
                onSelect = { onSelectTrack(panel, it) },
                busy = subtitles && ui.subtitleBusy,
                timingMs = if (subtitles) ui.onlineSubtitleOffsetMs else null,
                onTiming = onSubtitleTiming,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun TopBar(ui: PlayerUi, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                ui.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.isLive) {
                    Text(
                        stringResource(R.string.live_badge),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.background(Color(0xFFE11D48), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                ui.endsAt?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f)) }
            }
        }
        Spacer(Modifier.width(24.dp))
        Text(ui.clock, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
    }
}

/**
 * A thin track that thickens and grows a thumb when focused. Played part in the accent colour,
 * buffered part lighter; while scrubbing, the preview point and a time bubble show where Select
 * (or a short pause) will jump to.
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    scrubMs: Long?,
    onScrub: (forward: Boolean, repeatCount: Int) -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(if (focused) 8.dp else 4.dp, label = "track")
    val thumb by animateDpAsState(if (focused) 18.dp else 0.dp, label = "thumb")
    val accent = MaterialTheme.colorScheme.primary
    fun fraction(ms: Long) = if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = scrubMs ?: positionMs
    val seekDescription = stringResource(R.string.player_seek)

    BoxWithConstraints(
        modifier.fillMaxWidth().height(48.dp)
            .semantics {
                contentDescription = seekDescription
                stateDescription = PlayerFormats.clock(shown)
                progressBarRangeInfo = ProgressBarRangeInfo(fraction(shown), 0f..1f)
            }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { onScrub(false, e.nativeKeyEvent.repeatCount); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { onScrub(true, e.nativeKeyEvent.repeatCount); true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> { onSelect(); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Canvas(Modifier.fillMaxWidth().height(thumb.coerceAtLeast(trackHeight))) {
            val h = trackHeight.toPx()
            val top = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            fun bar(f: Float, color: Color) {
                if (f > 0f) drawRoundRect(color, Offset(0f, top), Size(size.width * f, h), r)
            }
            bar(1f, Color.White.copy(alpha = 0.22f))
            bar(fraction(bufferedMs), Color.White.copy(alpha = 0.4f))
            if (scrubMs != null) bar(fraction(positionMs), accent.copy(alpha = 0.45f))
            bar(fraction(shown), accent)
            if (thumb > 0.dp) drawCircle(Color.White, thumb.toPx() / 2, Offset(size.width * fraction(shown), size.height / 2))
        }
        if (scrubMs != null && durationMs > 0) {
            val bubble = 96.dp
            val x = (width * fraction(scrubMs) - bubble / 2).coerceIn(0.dp, (width - bubble).coerceAtLeast(0.dp))
            Box(Modifier.offset(x = x, y = (-40).dp).width(bubble), contentAlignment = Alignment.Center) {
                Text(
                    PlayerFormats.clock(scrubMs),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.Black,
                    modifier = Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** A round icon button: plain white icon at rest, white disc with a dark icon when focused. [active] adds an accent dot. */
@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    active: Boolean = false,
) {
    Box {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(if (large) 60.dp else 48.dp),
            colors = IconButtonDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(if (large) 30.dp else 24.dp))
        }
        if (active) {
            Box(Modifier.align(Alignment.BottomCenter).offset(y = 10.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
        }
    }
}

/** The Subtitles / Audio chooser: a translucent sheet on the right, focus on the current choice. */
@Composable
private fun TrackPanel(
    title: String,
    choices: List<TrackChoice>,
    emptyText: String?,
    onSelect: (TrackChoice) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    timingMs: Long? = null,
    onTiming: (Long) -> Unit = {},
) {
    val selectedIndex = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    val first = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxHeight().width(440.dp)
            .background(Color(0xFF0B1220).copy(alpha = 0.94f), RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
            .padding(horizontal = 28.dp, vertical = 40.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        emptyText?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(choices) { index, choice ->
                ListItem(
                    // Selected looks like any other row; the check mark shows which choice is current.
                    selected = choice.selected,
                    onClick = { onSelect(choice) },
                    headlineContent = { Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (choice.online && busy) {
                        { Text(stringResource(R.string.player_subtitles_downloading)) }
                    } else {
                        null
                    },
                    leadingContent = {
                        Box(Modifier.size(24.dp)) { if (choice.selected) Icon(PlayerIcons.Check, contentDescription = null) }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                        selectedContainerColor = Color.Transparent,
                        selectedContentColor = Color.White,
                    ),
                    modifier = if (index == selectedIndex) {
                        Modifier.focusRequester(first).onPlaced { if (!requested) { requested = true; first.requestFocus() } }
                    } else {
                        Modifier
                    },
                )
            }
            if (timingMs != null) item(key = "timing") { TimingRow(timingMs, onTiming) }
        }
    }
}

/** "Timing   ‹ +0.5 s ›": left/right move OpenSubtitles subtitles earlier/later by half a second. */
@Composable
private fun TimingRow(offsetMs: Long, onTiming: (Long) -> Unit) {
    ListItem(
        selected = false,
        onClick = {},
        headlineContent = { Text(stringResource(R.string.player_subtitles_timing)) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹")
                Text(SubtitleTiming.format(offsetMs), fontWeight = FontWeight.SemiBold)
                Text("›")
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        modifier = Modifier.padding(top = 12.dp).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { onTiming(-SubtitleTiming.STEP_MS); true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { onTiming(SubtitleTiming.STEP_MS); true }
                else -> false
            }
        },
    )
}

/** The OpenSubtitles timing adjustment: half-second steps within ±30 s, shown as "+0.5 s" / "−0.5 s". */
object SubtitleTiming {
    const val STEP_MS = 500L
    const val LIMIT_MS = 30_000L

    fun format(offsetMs: Long): String = when {
        offsetMs == 0L -> "0.0 s"
        offsetMs > 0 -> "+%.1f s".format(offsetMs / 1000.0)
        else -> "−%.1f s".format(-offsetMs / 1000.0)
    }
}
