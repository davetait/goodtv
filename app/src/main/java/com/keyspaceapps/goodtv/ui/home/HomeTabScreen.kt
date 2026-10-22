package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.progress.ContinueItem
import com.keyspaceapps.goodtv.data.watchlist.WatchListItem
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey

/**
 * The Home tab: a scope dropdown and search bar over Continue Watching, Popular Movies and Popular
 * Shows rails. While a search is active the rails give way to a grid of results.
 * Initial focus goes to the first tile of the first row on screen (Continue Watching when there is
 * anything to continue), so UP reaches the search row, then the tabs. Coming back from a popular
 * title lands on its card again. A popular row is hidden when TMDB is off or none of its titles are
 * in the library; with no rows at all a hint shows and the scope dropdown takes focus.
 */
@Composable
fun HomeTabScreen(onNavigate: (GoodTvKey) -> Unit) {
    val vm = goodTvViewModel { HomeSearchViewModel(it.repository, it.people) }
    val continueVm = goodTvViewModel { ContinueWatchingViewModel(it.watchProgress) }
    val continueItems by continueVm.items.collectAsState()
    val popularVm = goodTvViewModel { PopularViewModel(it.popularMovies, it.popularShows, it.popularStore, it.appScope) }
    val watchVm = goodTvViewModel { WatchListViewModel(it.watchList) }
    val watchItems by watchVm.items.collectAsState()
    val popularMovies by popularVm.movies.collectAsState()
    val popularShows by popularVm.shows.collectAsState()
    var searchEditing by remember { mutableStateOf(false) }
    val scopeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { popularVm.refresh() }
    // Hoisted so showing the rails again after a search doesn't pull focus out of the search field.
    val rowsRequested = remember { mutableStateOf(false) }

    SearchPane(vm, onNavigate, scopeFocus = scopeFocus, onEditingChange = { searchEditing = it }) {
        // Wait for both stored rows so the first row (and its focus) doesn't change under the user.
        val items = continueItems
        val saved = watchItems
        val movies = popularMovies
        val shows = popularShows
        if (items != null && saved != null && movies != null && shows != null) {
            HomeRows(
                items, saved, movies, shows, popularVm.focus, rowsRequested, searchEditing, scopeFocus, onNavigate,
                onRemove = continueVm::remove, onRemoveSaved = watchVm::remove,
            )
        }
    }
}

@Composable
private fun HomeRows(
    continueItems: List<ContinueItem>,
    watchItems: List<WatchListItem>,
    popularMovies: List<PopularCard>,
    popularShows: List<PopularCard>,
    focus: FocusMemory,
    requested: MutableState<Boolean>,
    searchEditing: Boolean,
    scopeFocus: FocusRequester,
    onNavigate: (GoodTvKey) -> Unit,
    onRemove: (ContinueItem) -> Unit,
    onRemoveSaved: (WatchListRef) -> Unit,
) {
    // Set when focus should move down to the first card of the first row present: the last Continue
    // Watching card was removed, or a row arrived above where focus started. Kept apart from
    // [requested] so a vanishing card can't claim it back.
    val handOff = remember { mutableStateOf(false) }
    // Coming back from a title opened from the Watch list or a popular row: that card takes focus, not the first row's.
    val comingBack = focus.key in (popularMovies + popularShows).map { it.key } + watchItems.map { watchListFocusKey(it.ref) }
    val listState = rememberLazyListState()

    // Rankings can land after Home opened (fresh install: they wait for the first sync). When a row
    // arrives above the card focus started on (or into an empty Home) and the user hasn't moved or
    // started typing, follow it to the top.
    val firstPopularKey = (popularMovies + popularShows).firstOrNull()?.key
    val lastFirstKey = remember { mutableStateOf(firstPopularKey) }
    LaunchedEffect(firstPopularKey) {
        val before = lastFirstKey.value
        lastFirstKey.value = firstPopularKey
        if (firstPopularKey != null && firstPopularKey != before && continueItems.isEmpty() && watchItems.isEmpty() && !searchEditing && (focus.key == null || focus.key == before)) {
            handOff.value = true
            listState.scrollToItem(0)
        }
    }

    // Saving from a popular card adds the Watch list row above it, pushing the focused card down,
    // possibly off screen: bring its row back into view.
    val watchShown = watchItems.isNotEmpty()
    val lastWatchShown = remember { mutableStateOf(watchShown) }
    LaunchedEffect(watchShown) {
        val before = lastWatchShown.value
        lastWatchShown.value = watchShown
        if (!watchShown || before) return@LaunchedEffect
        val rowsAbove = (if (continueItems.isNotEmpty()) 1 else 0) + 1
        val index = when (focus.key) {
            in popularMovies.map { it.key } -> rowsAbove
            in popularShows.map { it.key } -> rowsAbove + (if (popularMovies.isNotEmpty()) 1 else 0)
            else -> return@LaunchedEffect
        }
        listState.animateScrollToItem(index)
    }

    val nothingToShow = continueItems.isEmpty() && watchItems.isEmpty() && popularMovies.isEmpty() && popularShows.isEmpty()
    LaunchedEffect(nothingToShow) {
        if (nothingToShow && (!requested.value || handOff.value)) {
            requested.value = true
            handOff.value = false
            withFrameNanos { } // let the dropdown be placed first
            scopeFocus.requestFocus()
        }
    }
    if (nothingToShow) {
        Text(
            stringResource(R.string.home_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp, top = 24.dp),
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
        if (continueItems.isNotEmpty()) item(key = "continue") {
            ContinueWatchingRow(
                continueItems, requested, onNavigate, onRemove, onEmptied = { handOff.value = true },
                focus = focus, claimInitialFocus = !comingBack, handOff = handOff,
            )
        }
        if (watchItems.isNotEmpty()) item(key = "watch-list") {
            WatchListRow(
                watchItems, focus, requested, handOff, isFirstRow = continueItems.isEmpty(), comingBack = comingBack,
                onOpen = onNavigate, onRemove = onRemoveSaved, onEmptied = { handOff.value = true },
            )
        }
        if (popularMovies.isNotEmpty()) item(key = "popular-movies") {
            PopularRow(
                R.string.home_popular_movies, popularMovies, focus, requested, handOff,
                isFirstRow = continueItems.isEmpty() && watchItems.isEmpty(), comingBack = comingBack, onOpen = onNavigate,
            )
        }
        if (popularShows.isNotEmpty()) item(key = "popular-shows") {
            PopularRow(
                R.string.home_popular_shows, popularShows, focus, requested, handOff,
                isFirstRow = continueItems.isEmpty() && watchItems.isEmpty() && popularMovies.isEmpty(), comingBack = comingBack, onOpen = onNavigate,
            )
        }
    }
}
