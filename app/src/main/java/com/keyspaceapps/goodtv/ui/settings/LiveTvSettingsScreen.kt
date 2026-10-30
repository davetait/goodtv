package com.keyspaceapps.goodtv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.appGraph
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** The Live TV settings: one switch, Live previews, focused on open. Select flips it. */
@Composable
fun LiveTvSettingsScreen() {
    val graph = LocalContext.current.appGraph()
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    // Read since app start, so an "on" switch is never drawn off, and a quick Select never writes "on" again.
    // null only in the moments after launch before that first read: drawn off, and Select waits for it.
    val setting by graph.livePreviewsSetting.collectAsState()
    val on = setting == true
    val focus = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text(stringResource(R.string.live_tv_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        ListItem(
            selected = false,
            onClick = { setting?.let { current -> scope.launch { settings.setLivePreviews(!current) } } },
            headlineContent = { Text(stringResource(R.string.live_previews)) },
            supportingContent = { Text(stringResource(R.string.live_previews_summary)) },
            trailingContent = { Switch(checked = on, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .widthIn(max = 760.dp)
                // The switch's own state, for accessibility (and tests): it draws only, with no semantics of its own.
                .semantics { toggleableState = ToggleableState(on) }
                .focusRequester(focus)
                .onPlaced { if (!requested) { requested = true; focus.requestFocus() } },
        )
    }
}
