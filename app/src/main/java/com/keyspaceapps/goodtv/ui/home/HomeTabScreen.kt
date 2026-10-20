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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
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

private const val PLACEHOLDER_COUNT = 8

private data class HomeRow(val titleRes: Int, val items: List<String>)

private fun placeholders(prefix: String) = (1..PLACEHOLDER_COUNT).map { "$prefix $it" }

// TODO: hook up each row (watch progress, popular movies, popular series).
private val rows = listOf(
    HomeRow(R.string.home_continue_watching, placeholders("Continue")),
    HomeRow(R.string.home_popular_movies, placeholders("Movie")),
    HomeRow(R.string.home_popular_shows, placeholders("Show")),
)

/**
 * The Home tab: a scope dropdown and search bar over Continue Watching, Popular Movies and Popular
 * Shows rails. While a search is active the rails give way to a grid of results.
 * Initial focus goes to the first Continue Watching tile, so UP reaches the search row, then the tabs.
 */
@Composable
fun HomeTabScreen(onNavigate: (GoodTvKey) -> Unit) {
    val vm = goodTvViewModel { HomeSearchViewModel(it.repository) }
    val query by vm.query.collectAsState()
    val scope by vm.scope.collectAsState()
    val results by vm.results.collectAsState()
    // Hoisted so showing the rails again after a search doesn't pull focus out of the search field.
    val rowsRequested = remember { mutableStateOf(false) }
    val resultsRequested = remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchScopeDropdown(scope = scope, onScope = vm::setScope, modifier = Modifier.width(180.dp))
            Spacer(Modifier.width(16.dp))
            HomeSearchBar(query = query, onQueryChange = vm::setQuery, hint = stringResource(scope.hintRes), modifier = Modifier.weight(1f))
        }
        val found = results
        when {
            found == null -> PlaceholderRows(rowsRequested)
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { it.key }) { r ->
            val tmdb = rememberTmdbMeta(r.kind.tmdbKind(), r.tmdbId, r.lookupName, POSTER_SETTLE_MS)
            PosterCard(
                title = r.title,
                imageUrl = r.imageUrl,
                onClick = { onResult(r) },
                subtitle = if (showKind) stringResource(r.kind.labelRes) else null,
                fitImage = r.kind == ContentKind.LIVE,
                rating = tmdb?.reliableRating,
                modifier = Modifier.rememberedFocus(focus, r.key, isTarget = r.key == target, requested = requested),
            )
        }
    }
}

@Composable
private fun PlaceholderRows(requested: MutableState<Boolean>) {
    val firstItem = remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        itemsIndexed(rows, key = { _, r -> r.titleRes }) { rowIndex, row ->
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    stringResource(row.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(row.items, key = { _, title -> title }) { index, title ->
                        val focus = if (rowIndex == 0 && index == 0) {
                            Modifier.focusRequester(firstItem).onPlaced {
                                if (!requested.value) { requested.value = true; firstItem.requestFocus() }
                            }
                        } else {
                            Modifier
                        }
                        PosterCard(title = title, imageUrl = null, onClick = {}, modifier = focus)
                    }
                }
            }
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
