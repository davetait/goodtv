package com.keyspaceapps.goodtv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.guide.GuideSearchHit
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.targetIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

/** A hit's identity in the results: a programme can be on several rows, and a row in several hits. */
internal val GuideSearchHit.key: String get() = "${row.key}|${programme?.guideId}|${programme?.startMs}"

/** The id a result registers under in [GuideFocus.requesters] (so the feed picker can hand focus back). */
internal fun searchId(hit: GuideSearchHit) = "search|${hit.key}"

/** A programme hit as the details panel's cell; null for a channel or loop hit (the panel then describes the row). */
internal fun focusedOf(hit: GuideSearchHit) = GuideFocused(
    hit.row,
    hit.programme?.let { GuideCell(searchId(hit), CellKind.PROGRAMME, it, it.startMs, it.endMs) },
)

/**
 * The search results, in the guide's order (on now first, then by start, then channels and loops): logo,
 * title, channel and when. Focus only comes here on Down from the field, or to the result focused last
 * when the screen comes back from the player; new results never take focus from the field.
 */
@Composable
internal fun GuideSearchResults(
    hits: List<GuideSearchHit>,
    now: Long,
    timeLabel: (Long) -> String,
    focus: GuideFocus,
    memory: FocusMemory,
    requested: MutableState<Boolean>,
    onFocused: (GuideSearchHit) -> Unit,
    onSelect: (GuideSearchHit) -> Unit,
    onUpFromFirst: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val target = memory.targetIn(hits.map { it.key }, default = null)
    LazyColumn(modifier, contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(hits, key = { _, it -> it.key }) { i, hit ->
            val requester = remember { FocusRequester() }
            val id = searchId(hit)
            DisposableEffect(id) {
                focus.requesters[id] = requester
                onDispose { if (focus.requesters[id] === requester) focus.requesters.remove(id) }
            }
            SearchResult(
                hit,
                now,
                timeLabel,
                onClick = { onSelect(hit) },
                modifier = Modifier.focusRequester(requester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            memory.key = hit.key
                            requested.value = true
                            onFocused(hit)
                        }
                    }
                    // Up from the top goes back to the field (spatially it would land on a chip).
                    .onPreviewKeyEvent { e -> i == 0 && e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown && onUpFromFirst() }
                    .onPlaced {
                        if (hit.key == target && !requested.value) {
                            requested.value = true
                            requester.requestFocus()
                        }
                    },
            )
        }
    }
}

@Composable
private fun SearchResult(hit: GuideSearchHit, now: Long, timeLabel: (Long) -> String, onClick: () -> Unit, modifier: Modifier) {
    val row = hit.row
    val title = hit.programme?.title ?: row.name
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(60.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = ChannelColor, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Dark logos stay readable on a light tile; without a logo the tile carries the channel's name.
            Box(Modifier.size(width = 80.dp, height = 44.dp).background(LogoTile, RoundedCornerShape(5.dp)).padding(4.dp), contentAlignment = Alignment.Center) {
                if (row.logoUrl != null) {
                    AsyncImage(model = row.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Text(row.name, color = Color.Black, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (title != row.name) Text(row.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            whenOf(hit, now, timeLabel)?.let {
                Spacer(Modifier.width(14.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "On now · 25 min left", "Today 9:00 PM", "Tomorrow 9:00 PM" (days by the device's calendar), "30 Rock · all day"; null for a channel. */
@Composable
private fun whenOf(hit: GuideSearchHit, now: Long, timeLabel: (Long) -> String): String? {
    val p = hit.programme ?: return hit.row.loopShow?.let { stringResource(R.string.guide_all_day, it) }
    if (p.startMs <= now && now < p.endMs) return stringResource(R.string.guide_on_now_left, minutesLeft(p, now))
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(p.startMs).atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), day)) {
        0L -> stringResource(R.string.guide_today_at, timeLabel(p.startMs))
        1L -> stringResource(R.string.guide_tomorrow_at, timeLabel(p.startMs))
        else -> stringResource(R.string.guide_day_at, day.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0]), timeLabel(p.startMs))
    }
}
