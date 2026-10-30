package com.keyspaceapps.goodtv.ui.live

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.guide.GuideRow
import com.keyspaceapps.goodtv.ui.player.Spinner

/** Whole minutes until [programme] ends, rounded up: "1 min left" in its last minute. */
internal fun minutesLeft(programme: GuideProgramme, now: Long): Int = ((programme.endMs - now + 59_999) / 60_000).toInt()

/** What the details panel describes: a row's cell, or (on the channel cell) the row and what's on it now. */
internal data class GuideFocused(val row: GuideRow, val cell: GuideCell?)

/**
 * The panel below the grid: picture, "title · channel", "time · genre · N min left", description. On a
 * channel it shows the programme on now; Select's hint shows only when Select would play.
 *
 * With [previewsOn] the picture is a 16:9 area at the panel's full inner height (the guide makes the panel
 * twice as tall), and it shows [preview] while that's the focused row's: the picture playing with a LIVE
 * badge, the logo with a spinner while it loads, or the logo with "Preview unavailable". [onPictureBounds] hears
 * where that picture is in the window, for the hand-off to full screen.
 */
@Composable
internal fun GuideDetails(
    focusedState: State<GuideFocused?>,
    now: Long,
    timeLabel: (Long) -> String,
    previewsOn: Boolean,
    preview: Pair<PreviewTarget, PreviewHandle>?,
    modifier: Modifier = Modifier,
    onPictureBounds: (Rect) -> Unit = {},
) {
    Row(modifier.fillMaxWidth().background(Color(0xFF0D111B), RoundedCornerShape(8.dp)).padding(12.dp)) {
        val focused = focusedState.value ?: return@Row
        val row = focused.row
        val programme: GuideProgramme? = when (focused.cell?.kind) {
            CellKind.PROGRAMME -> focused.cell.programme
            null -> row.programmes.firstOrNull { it.startMs <= now && now < it.endMs }
            else -> null
        }
        val starts = focused.cell?.startsMs?.takeUnless { GuideTime.eventPlayable(it, now) }
        val onNow = starts == null && (programme == null || (programme.startMs <= now && now < programme.endMs))
        val picture = programme?.imageUrl
        val still: @Composable () -> Unit = {
            (picture ?: row.logoUrl)?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = if (picture != null) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (!previewsOn) {
            Box(Modifier.size(width = 160.dp, height = 90.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A3045))) { still() }
        } else {
            Box(
                Modifier.fillMaxHeight().aspectRatio(16f / 9f).onGloballyPositioned { onPictureBounds(it.boundsInWindow()) }
                    .clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A3045)).testTag("guide_preview"),
                contentAlignment = Alignment.Center,
            ) {
                // A preview belongs to its row: moving along the row keeps it, while the text follows focus.
                val handle = preview?.takeIf { it.first.rowKey == row.key }?.second
                if (handle == null) still() else key(handle) { PreviewPicture(handle, row.logoUrl) }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            val title = when {
                programme != null -> "${programme.title} · ${row.name}"
                row.loopShow != null && row.loopShow != row.name -> "${row.loopShow} · ${row.name}"
                else -> row.name
            }
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = if (programme != null) {
                listOfNotNull(
                    "${timeLabel(programme.startMs)} – ${timeLabel(programme.endMs)}",
                    programme.genres.firstOrNull(),
                    if (onNow) stringResource(R.string.minutes_left, minutesLeft(programme, now)) else null,
                ).joinToString(" · ")
            } else starts?.let { eventStart(it, now, timeLabel) }
            meta?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(4.dp))
            programme?.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFCCCCCC), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onNow) Text(stringResource(R.string.guide_select_to_watch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A preview: the picture (fitted, behind the logo until it plays) and what state it's in. */
@OptIn(UnstableApi::class) // the fitted surface, as in the player
@Composable
private fun BoxScope.PreviewPicture(handle: PreviewHandle, logoUrl: String?) {
    val status by handle.state.collectAsState()
    val player = handle.player
    // The surface is there from the start, so the first frame has somewhere to go; the logo covers it until it plays.
    if (player != null && status != PreviewStatus.FAILED) {
        val presentation = rememberPresentationState(player)
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp),
        )
    }
    if (status != PreviewStatus.PLAYING) {
        Box(Modifier.fillMaxSize().background(Color(0xFF2A3045))) {
            logoUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
    when (status) {
        PreviewStatus.PLAYING -> Text(
            stringResource(R.string.live_badge),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xFFE11D48), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        PreviewStatus.LOADING -> Spinner(Modifier.align(Alignment.BottomEnd).padding(10.dp), size = 28.dp)
        PreviewStatus.FAILED -> Text(
            stringResource(R.string.preview_unavailable),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
