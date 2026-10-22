package com.keyspaceapps.goodtv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.data.sports.ChannelOrder
import com.keyspaceapps.goodtv.domain.label

/**
 * A side sheet titled [title] listing [channels] (North American and English first, [ChannelOrder]);
 * the first takes focus and Back closes it. Callers move focus back to whatever opened the sheet
 * before removing it, or Compose hands focus to the screen's first item.
 */
@Composable
fun FeedPicker(title: String, channels: List<ChannelCandidate>, onPick: (ChannelCandidate) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val sorted = remember(channels) { ChannelOrder.sort(channels) }
    val first = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxHeight().width(520.dp)
            .background(Color(0xFF0B1220).copy(alpha = 0.96f), RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
            .padding(horizontal = 28.dp, vertical = 36.dp)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) { if (e.type == KeyEventType.KeyUp) onClose(); true } else false
            },
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(sorted, key = { _, c -> "${c.channel.sourceId}:${c.channel.id}" }) { i, c ->
                ListItem(
                    selected = false,
                    onClick = { onPick(c) },
                    headlineContent = { Text(c.channel.label.ifBlank { c.channel.name }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(c.categoryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    modifier = if (i == 0) Modifier.focusRequester(first).onPlaced { if (!requested) { requested = true; first.requestFocus() } } else Modifier,
                )
            }
        }
    }
}
