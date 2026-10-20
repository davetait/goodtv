package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * TV dropdown: a button showing [label] of [selected]; Select opens [options] in a popup below it,
 * scrolled to and focused on the current one. Long lists scroll within [maxListHeight]. Choosing an
 * option, or BACK, closes the list and focus returns to the button.
 */
@Composable
fun <T> TvDropdown(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    listWidth: Dp = 220.dp,
    maxListHeight: Dp = 360.dp,
) {
    var open by remember { mutableStateOf(false) }
    var wasOpen by remember { mutableStateOf(false) }
    val button = remember { FocusRequester() }
    var buttonHeight by remember { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)

    // Closing the popup drops focus to the window's first focusable (the tabs); put it back on the button.
    LaunchedEffect(open) {
        if (open) wasOpen = true else if (wasOpen) { wasOpen = false; button.requestFocus() }
    }

    Box(modifier) {
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().focusRequester(button).onSizeChanged { buttonHeight = it.height },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = colors.surfaceVariant, contentColor = colors.onSurface),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label(selected), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("▾", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (open) {
            Popup(
                offset = IntOffset(0, buttonHeight),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val current = remember { FocusRequester() }
                var requested by remember { mutableStateOf(false) }
                val list = rememberLazyListState(initialFirstVisibleItemIndex = options.indexOf(selected).coerceAtLeast(0))
                LazyColumn(
                    state = list,
                    modifier = Modifier.width(listWidth).heightIn(max = maxListHeight).background(colors.surface, shape).padding(8.dp),
                ) {
                    itemsIndexed(options) { _, option ->
                        val focus = if (option == selected) {
                            Modifier.focusRequester(current).onPlaced { if (!requested) { requested = true; current.requestFocus() } }
                        } else {
                            Modifier
                        }
                        ListItem(
                            selected = option == selected,
                            onClick = { onSelect(option); open = false },
                            headlineContent = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = if (option == selected) { { Text("✓") } } else null,
                            modifier = focus.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
