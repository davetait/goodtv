package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R

/**
 * Button showing the current [scope]; Select opens a list of scopes below it with focus on the
 * current one. Choosing one, or BACK, closes the list and focus returns to the button.
 */
@Composable
fun SearchScopeDropdown(scope: SearchScope, onScope: (SearchScope) -> Unit, modifier: Modifier = Modifier) {
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
                Text(stringResource(scope.labelRes), style = MaterialTheme.typography.bodyLarge)
                Text("▾", style = MaterialTheme.typography.bodyLarge)
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
                Column(Modifier.width(220.dp).background(colors.surface, shape).padding(8.dp)) {
                    SearchScope.entries.forEach { s ->
                        val focus = if (s == scope) {
                            Modifier.focusRequester(current).onPlaced { if (!requested) { requested = true; current.requestFocus() } }
                        } else {
                            Modifier
                        }
                        ListItem(
                            selected = s == scope,
                            onClick = { onScope(s); open = false },
                            headlineContent = { Text(stringResource(s.labelRes)) },
                            trailingContent = if (s == scope) { { Text("✓") } } else null,
                            modifier = focus.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

val SearchScope.labelRes: Int
    get() = when (this) {
        SearchScope.ALL -> R.string.search_scope_all
        SearchScope.LIVE -> R.string.tab_live
        SearchScope.MOVIES -> R.string.tab_movies
        SearchScope.SERIES -> R.string.tab_series
    }

val SearchScope.hintRes: Int
    get() = when (this) {
        SearchScope.ALL -> R.string.home_search_hint
        SearchScope.LIVE -> R.string.search_hint_live
        SearchScope.MOVIES -> R.string.search_hint_movies
        SearchScope.SERIES -> R.string.search_hint_series
    }
