package com.keyspaceapps.goodtv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
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
import com.keyspaceapps.goodtv.data.sports.League
import com.keyspaceapps.goodtv.data.sports.LeagueGroup
import kotlinx.coroutines.launch

private fun LeagueGroup.headingRes(): Int = when (this) {
    LeagueGroup.NORTH_AMERICA -> R.string.sports_group_north_america
    LeagueGroup.WORLD_SOCCER -> R.string.sports_group_world_soccer
    LeagueGroup.CRICKET -> R.string.sports_group_cricket
}

/** The leagues of [group], by label, ignoring case. */
internal fun leaguesIn(group: LeagueGroup): List<League> =
    League.entries.filter { it.group == group }.sortedBy { it.label.lowercase() }

/** The Sports settings: one switch per league under three headings; the first league is focused on open. Select flips it. */
@Composable
fun SportsSettingsScreen() {
    val graph = LocalContext.current.appGraph()
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    // null only in the moments after launch before the first read: switches draw off, and Select waits for it.
    val setting by graph.sportsLeaguesSetting.collectAsState()
    val focus = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }
    val firstLeague = leaguesIn(LeagueGroup.entries.first()).first()

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Text(stringResource(R.string.sports_settings_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            for (group in LeagueGroup.entries) {
                item(key = "heading_${group.name}") {
                    Text(
                        stringResource(group.headingRes()),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
                items(leaguesIn(group), key = { it.name }) { league ->
                    val on = setting?.contains(league) == true
                    ListItem(
                        selected = false,
                        onClick = { setting?.let { current -> scope.launch { settings.setLeagueOn(league, league !in current) } } },
                        headlineContent = { Text(league.label) },
                        trailingContent = { Switch(checked = on, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .widthIn(max = 760.dp)
                            // The switch's own state, for accessibility (and tests): it draws only, with no semantics of its own.
                            .semantics { toggleableState = ToggleableState(on) }
                            .then(
                                if (league == firstLeague) {
                                    Modifier.focusRequester(focus).onPlaced { if (!requested) { requested = true; focus.requestFocus() } }
                                } else Modifier,
                            ),
                    )
                }
            }
        }
    }
}
