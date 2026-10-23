package com.keyspaceapps.goodtv.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.settings.Integrations
import com.keyspaceapps.goodtv.data.settings.KeyStatus
import com.keyspaceapps.goodtv.domain.SourceSummary
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.rememberedFocus
import com.keyspaceapps.goodtv.ui.browse.targetIn
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.IntegrationsKey
import com.keyspaceapps.goodtv.ui.nav.SourcesKey
import com.keyspaceapps.goodtv.ui.sources.syncedAgo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

private const val SOURCES_ROW = "sources"
private const val INTEGRATIONS_ROW = "integrations"

/** The Settings tab's rows: sources (null until first read), the integrations in effect, and the row focused last. */
class SettingsViewModel(repo: LibraryRepository, integrations: Integrations) : ViewModel() {
    val sources: StateFlow<List<SourceSummary>?> = repo.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tmdb = integrations.tmdb
    val openSubtitles = integrations.openSubtitles
    val loaded = integrations.loaded
    val focus = FocusMemory()
}

/** The most recent sync across [sources], or null if none has synced. */
internal fun latestSync(sources: List<SourceSummary>): Long? = sources.mapNotNull { it.lastSyncAt }.maxOrNull()

/** The Settings status-line part for a service in [status]; it takes the service's name. */
@StringRes
internal fun statusPartRes(status: KeyStatus): Int = when (status) {
    KeyStatus.NOT_SET -> R.string.integration_not_set
    KeyStatus.UNCHECKED -> R.string.integration_unchecked
    KeyStatus.CONNECTED -> R.string.integration_connected
    KeyStatus.REJECTED_KEY, KeyStatus.REJECTED_LOGIN -> R.string.integration_rejected
}

/**
 * Two rows, Sources and Integrations, each opening its screen on top. Focus goes back to the row
 * that opened a screen; otherwise to Sources.
 */
@Composable
fun SettingsScreen(onNavigate: (GoodTvKey) -> Unit) {
    val vm = goodTvViewModel { SettingsViewModel(it.repository, it.integrations) }
    val sources by vm.sources.collectAsState()
    val tmdb by vm.tmdb.collectAsState()
    val os by vm.openSubtitles.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val requested = remember { mutableStateOf(false) }
    val target = vm.focus.targetIn(listOf(SOURCES_ROW, INTEGRATIONS_ROW), SOURCES_ROW)

    val integrationsStatus = if (!loaded) "" else listOf(
        stringResource(statusPartRes(tmdb.status), stringResource(R.string.tmdb_name)),
        stringResource(statusPartRes(os.status), stringResource(R.string.os_name)),
    ).joinToString(" · ")

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsRow(
            title = stringResource(R.string.settings_sources),
            status = sources?.let { sourcesStatus(it) }.orEmpty(),
            onClick = { onNavigate(SourcesKey) },
            modifier = Modifier.rememberedFocus(vm.focus, SOURCES_ROW, isTarget = target == SOURCES_ROW, requested = requested),
        )
        SettingsRow(
            title = stringResource(R.string.settings_integrations),
            status = integrationsStatus,
            onClick = { onNavigate(IntegrationsKey) },
            modifier = Modifier.rememberedFocus(vm.focus, INTEGRATIONS_ROW, isTarget = target == INTEGRATIONS_ROW, requested = requested),
        )
    }
}

@Composable
private fun sourcesStatus(sources: List<SourceSummary>): String {
    if (sources.isEmpty()) return stringResource(R.string.settings_sources_none)
    val count = pluralStringResource(R.plurals.settings_sources_count, sources.size, sources.size)
    val latest = latestSync(sources) ?: return count
    return stringResource(R.string.settings_sources_synced, count, syncedAgo(latest))
}

@Composable
private fun SettingsRow(title: String, status: String, onClick: () -> Unit, modifier: Modifier) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(status) },
        trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.widthIn(max = 640.dp),
    )
}
