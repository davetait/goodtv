package com.keyspaceapps.goodtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.domain.PlayableItem

/**
 * Channel list. The first row takes focus as soon as it is laid out, so the D-pad works
 * immediately. Focus is requested from onPlaced rather than a LaunchedEffect because a request
 * made before layout fails whenever the Compose view has not yet received View focus (true in
 * instrumented tests, and on real devices when content is set after the first window traversal).
 */
@Composable
fun HomeScreen(
    channels: List<PlayableItem>,
    onChannelSelected: (PlayableItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstItemFocus = remember { FocusRequester() }
    var initialFocusRequested by remember(channels) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(channels, key = { _, channel -> channel.url }) { index, channel ->
                val itemModifier = if (index == 0) {
                    Modifier
                        .focusRequester(firstItemFocus)
                        .onPlaced {
                            if (!initialFocusRequested) {
                                initialFocusRequested = true
                                firstItemFocus.requestFocus()
                            }
                        }
                } else {
                    Modifier
                }
                ListItem(
                    selected = false,
                    onClick = { onChannelSelected(channel) },
                    headlineContent = { Text(channel.title) },
                    supportingContent = { Text(if (channel.isLive) "Live" else "On demand") },
                    modifier = itemModifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}
