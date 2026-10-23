package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.live.GuideScreen
import com.keyspaceapps.goodtv.ui.movies.MoviesScreen
import com.keyspaceapps.goodtv.ui.series.SeriesScreen
import com.keyspaceapps.goodtv.ui.sports.SportsScreen
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.HomeTab
import com.keyspaceapps.goodtv.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(private val repo: LibraryRepository, private val settings: AppSettings) : ViewModel() {
    private val _tab = MutableStateFlow<HomeTab?>(null)
    /** null until the initial tab is resolved (Settings when there are no sources, else the last used tab). */
    val tab: StateFlow<HomeTab?> = _tab

    init {
        viewModelScope.launch {
            val count = repo.enabledSourceCount().first()
            val last = settings.lastTab.first()?.let(::savedTab)
            _tab.value = if (count == 0) HomeTab.SETTINGS else last ?: HomeTab.HOME
        }
    }

    fun selectTab(tab: HomeTab) {
        if (_tab.value == tab) return
        _tab.value = tab
        viewModelScope.launch { settings.setLastTab(tab.name) }
    }
}

/** A saved tab name; older installs saved "SOURCES", which Settings replaced. */
internal fun savedTab(name: String): HomeTab? =
    if (name == "SOURCES") HomeTab.SETTINGS else HomeTab.entries.firstOrNull { it.name == name }

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
                HomeTab.HOME -> HomeTabScreen(onNavigate = onNavigate)
                HomeTab.LIVE -> GuideScreen(onPlay = onNavigate)
                HomeTab.SPORTS -> SportsScreen(onPlay = onNavigate)
                HomeTab.MOVIES -> MoviesScreen(onMovie = onNavigate, onSeeAll = onNavigate)
                HomeTab.SERIES -> SeriesScreen(onSeries = onNavigate, onSeeAll = onNavigate)
                HomeTab.SETTINGS -> SettingsScreen(onNavigate = onNavigate)
            }
        }
    }
}

private val HomeTab.labelRes: Int
    get() = when (this) {
        HomeTab.HOME -> R.string.tab_home
        HomeTab.LIVE -> R.string.tab_live
        HomeTab.SPORTS -> R.string.tab_sports
        HomeTab.MOVIES -> R.string.tab_movies
        HomeTab.SERIES -> R.string.tab_series
        HomeTab.SETTINGS -> R.string.tab_settings
    }

