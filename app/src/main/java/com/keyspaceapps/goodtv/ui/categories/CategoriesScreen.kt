package com.keyspaceapps.goodtv.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.AssistChip
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import androidx.tv.material3.TriStateCheckbox
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.CategoriesKey
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class) // AssistChip
@Composable
fun CategoriesScreen(key: CategoriesKey) {
    val vm = goodTvViewModel(key = "categories-${key.sourceId}") { CategoriesViewModel(it.repository, key.sourceId) }
    val nickname by vm.nickname.collectAsState()
    val synced by vm.synced.collectAsState()
    val kind by vm.kind.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val groups by vm.groups.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Set by a chip; the matching header requests focus once it is placed (no timing guesses).
    val pendingHeaderFocus = remember { mutableStateOf<String?>(null) }
    val firstRowFocus = remember { FocusRequester() }
    val initialFocusDone = remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text(nickname, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = ContentKind.entries.indexOf(kind)) {
            ContentKind.entries.forEach { k ->
                val tab = tabs.firstOrNull { it.kind == k }
                Tab(selected = k == kind, onFocus = {}, onClick = { vm.selectKind(k) }) {
                    Text(
                        stringResource(R.string.categories_tab, stringResource(k.labelRes), tab?.shown ?: 0, tab?.total ?: 0),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (!synced) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.categories_refresh_first), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            item(key = "select-all") { Button(onClick = vm::selectAll) { Text(stringResource(R.string.select_all)) } }
            item(key = "clear-all") { Button(onClick = vm::clearAll) { Text(stringResource(R.string.clear_all)) } }
            items(groups, key = { "chip:${it.prefix}" }) { g ->
                AssistChip(onClick = {
                    val index = groups.takeWhile { it.prefix != g.prefix }.sumOf { 1 + it.categories.size }
                    pendingHeaderFocus.value = g.prefix
                    scope.launch { listState.scrollToItem(index) }
                }) { Text(g.prefix) }
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            groups.forEachIndexed { groupIndex, g ->
                item(key = "h:${g.prefix}") {
                    val requester = remember(g.prefix) { FocusRequester() }
                    ListItem(
                        selected = false,
                        onClick = { vm.toggleGroup(g) },
                        headlineContent = { Text(stringResource(R.string.categories_group_header, g.prefix, g.shownCount, g.categories.size)) },
                        trailingContent = { TriStateCheckbox(state = g.state, onClick = null) },
                        modifier = Modifier
                            .focusRequester(requester)
                            .onPlaced {
                                if (pendingHeaderFocus.value == g.prefix) {
                                    pendingHeaderFocus.value = null
                                    requester.requestFocus()
                                }
                            }
                            .padding(top = if (groupIndex == 0) 0.dp else 16.dp, bottom = 4.dp),
                    )
                }
                items(g.categories, key = { "c:${it.id}" }) { c ->
                    val isFirst = groupIndex == 0 && c.id == g.categories.first().id
                    val focusModifier = if (isFirst) {
                        Modifier.focusRequester(firstRowFocus).onPlaced {
                            if (!initialFocusDone.value) { initialFocusDone.value = true; firstRowFocus.requestFocus() }
                        }
                    } else {
                        Modifier
                    }
                    ListItem(
                        selected = false,
                        onClick = { vm.toggle(c.id) },
                        headlineContent = { Text(c.name) },
                        trailingContent = { Checkbox(checked = c.id !in g.hiddenIds, onCheckedChange = null) },
                        modifier = focusModifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

private val ContentKind.labelRes: Int
    get() = when (this) {
        ContentKind.LIVE -> R.string.tab_live
        ContentKind.MOVIE -> R.string.tab_movies
        ContentKind.SERIES -> R.string.tab_series
    }
