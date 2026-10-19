package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.Labels
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.HomeTab
import com.keyspaceapps.goodtv.ui.sources.SourcesScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repo: LibraryRepository, private val settings: AppSettings) : ViewModel() {
    private val _tab = MutableStateFlow<HomeTab?>(null)
    /** null until the initial tab is resolved (Sources when there are no sources, else the last used tab). */
    val tab: StateFlow<HomeTab?> = _tab

    val liveCategoryLabels: StateFlow<List<String>> = repo.categories(ContentKind.LIVE)
        .map { cats ->
            val multi = cats.map { it.category.sourceId }.distinct().size > 1
            cats.map { Labels.categoryLabel(it.category.name, it.sourceNickname, multi) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val count = repo.enabledSourceCount().first()
            val last = settings.lastTab.first()?.let { name -> HomeTab.entries.firstOrNull { it.name == name } }
            _tab.value = if (count == 0) HomeTab.SOURCES else last ?: HomeTab.LIVE
        }
    }

    fun selectTab(tab: HomeTab) {
        if (_tab.value == tab) return
        _tab.value = tab
        viewModelScope.launch { settings.setLastTab(tab.name) }
    }
}

@Composable
fun HomeScreen(onNavigate: (GoodTvKey) -> Unit) {
    val vm = goodTvViewModel { HomeViewModel(it.repository, it.settings) }
    val tab by vm.tab.collectAsState()
    val current = tab ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val tabs = HomeTab.entries
        TabRow(
            selectedTabIndex = tabs.indexOf(current),
            modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 16.dp),
        ) {
            tabs.forEach { t ->
                // Switch on click, not focus: after returning from a pushed screen, initial focus lands on
                // the first tab, and focus-switching would silently move the user off the tab they were on.
                Tab(
                    selected = t == current,
                    onFocus = {},
                    onClick = { vm.selectTab(t) },
                ) {
                    Text(text = stringResource(t.labelRes), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (current) {
                HomeTab.LIVE -> LiveCategoriesPreview(vm)
                HomeTab.MOVIES -> Placeholder(stringResource(R.string.coming_soon))
                HomeTab.SERIES -> Placeholder(stringResource(R.string.coming_soon))
                HomeTab.SOURCES -> SourcesScreen(onNavigate = onNavigate)
            }
        }
    }
}

private val HomeTab.labelRes: Int
    get() = when (this) {
        HomeTab.LIVE -> R.string.tab_live
        HomeTab.MOVIES -> R.string.tab_movies
        HomeTab.SERIES -> R.string.tab_series
        HomeTab.SOURCES -> R.string.tab_sources
    }

/** Minimal Live body until the real Live screen lands: one line per category. */
@Composable
private fun LiveCategoriesPreview(vm: HomeViewModel) {
    val labels by vm.liveCategoryLabels.collectAsState()
    if (labels.isEmpty()) {
        Placeholder(stringResource(R.string.no_sources_yet))
    } else {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
            items(labels) { label -> Text(label, modifier = Modifier.padding(vertical = 8.dp)) }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
