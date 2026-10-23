package com.keyspaceapps.goodtv.ui.sources

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.repo.SyncState
import com.keyspaceapps.goodtv.domain.SourceSummary
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.CategoriesKey
import com.keyspaceapps.goodtv.ui.nav.EditSourceKey
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey

@Composable
fun SourcesScreen(onNavigate: (GoodTvKey) -> Unit) {
    val vm = goodTvViewModel { SourcesViewModel(it.repository) }
    val sources by vm.sources.collectAsState()
    val syncState by vm.syncState.collectAsState()
    val addFocus = remember { FocusRequester() }
    var addFocusRequested by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        Button(
            onClick = { onNavigate(EditSourceKey(null)) },
            modifier = Modifier
                .focusRequester(addFocus)
                .onPlaced {
                    if (sources.isEmpty() && !addFocusRequested) {
                        addFocusRequested = true
                        addFocus.requestFocus()
                    }
                },
        ) { Text(stringResource(R.string.add_source)) }
        Spacer(Modifier.height(16.dp))
        if (sources.isEmpty()) {
            Text(stringResource(R.string.no_sources_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(sources, key = { it.id }) { source ->
                SourceRow(
                    source = source,
                    syncing = (syncState as? SyncState.Syncing)?.sourceIds?.contains(source.id) == true,
                    onRefresh = { vm.refresh(source.id) },
                    onEdit = { onNavigate(EditSourceKey(source.id)) },
                    onCategories = { onNavigate(CategoriesKey(source.id)) },
                    onRemove = { vm.remove(source.id) },
                )
            }
        }
    }
}

@Composable
private fun SourceRow(source: SourceSummary, syncing: Boolean, onRefresh: () -> Unit, onEdit: () -> Unit, onCategories: () -> Unit, onRemove: () -> Unit) {
    var confirming by remember(source.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(source.nickname, style = MaterialTheme.typography.titleLarge)
        Text(statusLine(source, syncing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (confirming) {
                Text(stringResource(R.string.remove_confirm, source.nickname), modifier = Modifier.padding(end = 8.dp))
                Button(onClick = { confirming = false; onRemove() }) { Text(stringResource(R.string.confirm)) }
                Button(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            } else {
                Button(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                Button(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                Button(onClick = onCategories) { Text(stringResource(R.string.categories)) }
                Button(onClick = { confirming = true }) { Text(stringResource(R.string.remove)) }
            }
        }
    }
}

/** How long ago a sync was, as Sources and Settings show it ("5 minutes ago"). */
internal fun syncedAgo(at: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

private fun statusLine(s: SourceSummary, syncing: Boolean): String {
    val counts = "%,d live · %,d movies · %,d series".format(s.liveCount, s.movieCount, s.seriesCount)
    val state = when {
        syncing -> "syncing…"
        s.lastError != null -> "error: ${s.lastError}"
        s.lastSyncAt == null -> "not synced yet"
        else -> "synced " + syncedAgo(s.lastSyncAt)
    }
    val hidden = if (s.hiddenCount > 0) " · ${s.hiddenCount} categories hidden" else ""
    return "$counts · $state$hidden"
}
