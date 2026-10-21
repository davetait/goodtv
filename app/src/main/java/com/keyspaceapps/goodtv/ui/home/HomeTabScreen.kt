package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.progress.ContinueItem
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.PosterCard
import com.keyspaceapps.goodtv.ui.browse.rememberedFocus
import com.keyspaceapps.goodtv.ui.browse.targetIn
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.tmdb.POSTER_SETTLE_MS
import com.keyspaceapps.goodtv.ui.tmdb.rememberTmdbMeta
import com.keyspaceapps.goodtv.ui.tmdb.tmdbKind
import com.keyspaceapps.goodtv.ui.watchlist.LocalWatchList
import com.keyspaceapps.goodtv.ui.watchlist.rememberWatchTarget

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
    val vm = goodTvViewModel { HomeSearchViewModel(it.repository) }
    val continueVm = goodTvViewModel { ContinueWatchingViewModel(it.watchProgress) }
    val continueItems by continueVm.items.collectAsState()
    val popularVm = goodTvViewModel { PopularViewModel(it.popularMovies, it.popularShows, it.popularStore, it.appScope) }
    val popularMovies by popularVm.movies.collectAsState()
    val popularShows by popularVm.shows.collectAsState()
    var searchEditing by remember { mutableStateOf(false) }
    val scopeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { popularVm.refresh() }
    val query by vm.query.collectAsState()
    val scope by vm.scope.collectAsState()
    val results by vm.results.collectAsState()
    // Hoisted so showing the rails again after a search doesn't pull focus out of the search field.
    val rowsRequested = remember { mutableStateOf(false) }
    val resultsRequested = remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchScopeDropdown(scope = scope, onScope = vm::setScope, modifier = Modifier.width(180.dp).focusRequester(scopeFocus))
            Spacer(Modifier.width(16.dp))
            HomeSearchBar(
                query = query, onQueryChange = vm::setQuery, hint = stringResource(scope.hintRes),
                onEditingChange = { searchEditing = it }, modifier = Modifier.weight(1f),
            )
        }
        val found = results
        when {
            // Wait for both stored rows so the first row (and its focus) doesn't change under the user.
            found == null -> {
                val items = continueItems
                val movies = popularMovies
                val shows = popularShows
                if (items != null && movies != null && shows != null) {
                    HomeRows(items, movies, shows, popularVm.focus, rowsRequested, searchEditing, scopeFocus, onNavigate, onRemove = continueVm::remove)
                }
            }
            found.isEmpty() -> Text(
                stringResource(R.string.search_no_results, query.trim()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, top = 24.dp),
            )
            else -> SearchResultsGrid(found, vm.focus, resultsRequested, showKind = scope == SearchScope.ALL, onResult = { onNavigate(it.navKey()) })
        }
    }
}

/**
 * Results six per row. Focus is only pulled here to restore the remembered result after coming
 * back from it; new results never take focus from the search field.
 */
@Composable
private fun SearchResultsGrid(
    results: List<SearchResult>,
    focus: FocusMemory,
    requested: MutableState<Boolean>,
    showKind: Boolean,
    onResult: (SearchResult) -> Unit,
) {
    val target = focus.targetIn(results.map { it.key }, default = null)
    val watchList = LocalWatchList.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { it.key }) { r ->
            val tmdb = rememberTmdbMeta(r.kind.tmdbKind(), r.tmdbId, r.lookupName, POSTER_SETTLE_MS)
            val watchTarget = rememberWatchTarget(r.kind, r.sourceId, r.id, r.title)
            PosterCard(
                title = r.title,
                imageUrl = r.imageUrl,
                onClick = { onResult(r) },
                saved = watchTarget != null && watchTarget.ref in watchList.saved,
                onLongClick = watchTarget?.let { t -> { watchList.openMenu(t) } },
                subtitle = if (showKind) stringResource(r.kind.labelRes) else null,
                fitImage = r.kind == ContentKind.LIVE,
                rating = tmdb?.reliableRating,
                modifier = Modifier.rememberedFocus(focus, r.key, isTarget = r.key == target, requested = requested),
            )
        }
    }
}

@Composable
private fun HomeRows(
    continueItems: List<ContinueItem>,
    popularMovies: List<PopularCard>,
    popularShows: List<PopularCard>,
    focus: FocusMemory,
    requested: MutableState<Boolean>,
    searchEditing: Boolean,
    scopeFocus: FocusRequester,
    onNavigate: (GoodTvKey) -> Unit,
    onRemove: (ContinueItem) -> Unit,
) {
    // Set when focus should move down to the first card of the first row present: the last Continue
    // Watching card was removed, or a row arrived above where focus started. Kept apart from
    // [requested] so a vanishing card can't claim it back.
    val handOff = remember { mutableStateOf(false) }
    val popularComingBack = focus.key in (popularMovies + popularShows).map { it.key }
    val listState = rememberLazyListState()

    // Rankings can land after Home opened (fresh install: they wait for the first sync). When a row
    // arrives above the card focus started on (or into an empty Home) and the user hasn't moved or
    // started typing, follow it to the top.
    val firstPopularKey = (popularMovies + popularShows).firstOrNull()?.key
    val lastFirstKey = remember { mutableStateOf(firstPopularKey) }
    LaunchedEffect(firstPopularKey) {
        val before = lastFirstKey.value
        lastFirstKey.value = firstPopularKey
        if (firstPopularKey != null && firstPopularKey != before && continueItems.isEmpty() && !searchEditing && (focus.key == null || focus.key == before)) {
            handOff.value = true
            listState.scrollToItem(0)
        }
    }

    val nothingToShow = continueItems.isEmpty() && popularMovies.isEmpty() && popularShows.isEmpty()
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
                focus = focus, claimInitialFocus = !popularComingBack,
            )
        }
        if (popularMovies.isNotEmpty()) item(key = "popular-movies") {
            PopularRow(
                R.string.home_popular_movies, popularMovies, focus, requested, handOff,
                isFirstRow = continueItems.isEmpty(), comingBack = popularComingBack, onOpen = onNavigate,
            )
        }
        if (popularShows.isNotEmpty()) item(key = "popular-shows") {
            PopularRow(
                R.string.home_popular_shows, popularShows, focus, requested, handOff,
                isFirstRow = continueItems.isEmpty() && popularMovies.isEmpty(), comingBack = popularComingBack, onOpen = onNavigate,
            )
        }
    }
}

private fun SearchResult.navKey(): GoodTvKey = when (kind) {
    ContentKind.LIVE -> PlayerKey(sourceId, PlayerKey.KIND_LIVE, id, null, title)
    ContentKind.MOVIE -> MovieDetailKey(sourceId, id)
    ContentKind.SERIES -> SeriesDetailKey(sourceId, id)
}

private val ContentKind.labelRes: Int
    get() = when (this) {
        ContentKind.LIVE -> R.string.search_kind_live
        ContentKind.MOVIE -> R.string.search_kind_movie
        ContentKind.SERIES -> R.string.search_kind_series
    }
