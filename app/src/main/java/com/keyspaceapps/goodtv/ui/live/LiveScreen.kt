package com.keyspaceapps.goodtv.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.ui.browse.Labels
import com.keyspaceapps.goodtv.ui.browse.rememberedFocus
import com.keyspaceapps.goodtv.ui.browse.targetIn
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.PlayerKey

/**
 * Live TV: category sidebar on the left (selection follows focus), channels of the selected
 * category on the right. Focus returns to the channel the user last had (e.g. after the player
 * pops), else the first channel takes it once on load.
 */
@Composable
fun LiveScreen(onPlay: (PlayerKey) -> Unit) {
    val vm = goodTvViewModel { LiveViewModel(it.repository) }
    val categories by vm.categories.collectAsState()
    val selected by vm.selected.collectAsState()
    val channels by vm.channels.collectAsState()
    val multiSource by vm.multiSource.collectAsState()
    val requested = remember { mutableStateOf(false) }
    val target = vm.focus.targetIn(channels.map { it.id }, channels.firstOrNull()?.id)

    if (categories.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_sources_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Row(Modifier.fillMaxSize().padding(start = 48.dp)) {
        LazyColumn(Modifier.width(320.dp).fillMaxHeight()) {
            items(categories, key = { "${it.category.sourceId}:${it.category.id}" }) { c ->
                ListItem(
                    selected = c == selected,
                    onClick = { vm.select(c) },
                    headlineContent = { Text(Labels.categoryLabel(c.category.name, c.sourceNickname, multiSource)) },
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .onFocusChanged { if (it.isFocused) vm.select(c) },
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 48.dp)) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { _, ch ->
                val focusModifier = Modifier.rememberedFocus(vm.focus, ch.id, isTarget = ch.id == target, requested = requested)
                ListItem(
                    selected = false,
                    onClick = { onPlay(PlayerKey(ch.sourceId, PlayerKey.KIND_LIVE, ch.id, null, ch.name)) },
                    headlineContent = { Text(ch.name) },
                    supportingContent = ch.number?.let { n -> { Text(n.toString()) } },
                    leadingContent = {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            if (ch.logoUrl != null) AsyncImage(model = ch.logoUrl, contentDescription = null, modifier = Modifier.size(40.dp))
                        }
                    },
                    modifier = focusModifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
