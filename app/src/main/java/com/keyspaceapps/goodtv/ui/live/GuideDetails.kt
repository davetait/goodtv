package com.keyspaceapps.goodtv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.guide.GuideRow

/** Whole minutes until [programme] ends, rounded up: "1 min left" in its last minute. */
internal fun minutesLeft(programme: GuideProgramme, now: Long): Int = ((programme.endMs - now + 59_999) / 60_000).toInt()

/** What the details panel describes: a row's cell, or (on the channel cell) the row and what's on it now. */
internal data class GuideFocused(val row: GuideRow, val cell: GuideCell?)

/**
 * The panel below the grid: picture, "title · channel", "time · genre · N min left", description. On a
 * channel it shows the programme on now; Select's hint shows only when Select would play.
 */
@Composable
internal fun GuideDetails(focusedState: State<GuideFocused?>, now: Long, timeLabel: (Long) -> String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Color(0xFF0D111B), RoundedCornerShape(8.dp)).padding(12.dp)) {
        val focused = focusedState.value ?: return@Row
        val row = focused.row
        val programme: GuideProgramme? = when (focused.cell?.kind) {
            CellKind.PROGRAMME -> focused.cell.programme
            null -> row.programmes.firstOrNull { it.startMs <= now && now < it.endMs }
            else -> null
        }
        val starts = focused.cell?.startsMs
        val onNow = starts == null && (programme == null || (programme.startMs <= now && now < programme.endMs))
        Box(Modifier.size(width = 160.dp, height = 90.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A3045))) {
            val picture = programme?.imageUrl
            (picture ?: row.logoUrl)?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = if (picture != null) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
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
