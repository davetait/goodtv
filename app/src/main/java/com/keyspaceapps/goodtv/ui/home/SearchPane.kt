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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.pluralStringResource
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
 * The scope dropdown and search bar over a results area: person sections first, then the title
 * results. With nothing searched it shows [idle] (Home's rails).
 *
 * On Home ([claimFocus] false) results never take focus from the search field; focus is only
 * pulled into the grid to restore the remembered result after coming back from it. With
 * [claimFocus] (the person search screen) the search bar takes focus when placed, and the clicked
 * person's first poster takes it when it arrives, unless the user is typing.
 */
@Composable
fun SearchPane(
    vm: HomeSearchViewModel,
    onNavigate: (GoodTvKey) -> Unit,
    modifier: Modifier = Modifier,
    scopeFocus: FocusRequester = remember { FocusRequester() },
    onEditingChange: (Boolean) -> Unit = {},
    claimFocus: Boolean = false,
    idle: @Composable () -> Unit = {},
) {
    val query by vm.query.collectAsState()
    val scope by vm.scope.collectAsState()
    val results by vm.results.collectAsState()
    val people by vm.people.collectAsState()
    var editing by remember { mutableStateOf(false) }
    // Hoisted so showing [idle] again after a search doesn't pull focus out of the search field.
    val resultsRequested = remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val searchRequested = remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchScopeDropdown(scope = scope, onScope = vm::setScope, modifier = Modifier.width(180.dp).focusRequester(scopeFocus))
            Spacer(Modifier.width(16.dp))
            HomeSearchBar(
                query = query, onQueryChange = vm::setQuery, hint = stringResource(scope.hintRes),
                onEditingChange = { editing = it; onEditingChange(it) },
                modifier = Modifier.weight(1f).focusRequester(searchFocus).onPlaced {
                    if (claimFocus && !searchRequested.value) {
                        searchRequested.value = true
                        searchFocus.requestFocus()
                    }
                },
            )
        }
        val found = results
        when (searchContent(found, people)) {
            SearchContent.IDLE -> idle()
            SearchContent.NO_RESULTS -> Text(
                stringResource(R.string.search_no_results, query.trim()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, top = 24.dp),
            )
            SearchContent.RESULTS -> {
                val first = if (claimFocus && !editing) (people.firstOrNull() as? PersonSection.Found)?.let { personKey(it.personId, it.titles.first()) } else null
                SearchResultsGrid(
                    people, found.orEmpty(), vm.focus, resultsRequested, defaultTarget = first,
                    showKind = scope == SearchScope.ALL, onResult = { onNavigate(it.navKey()) },
                )
            }
        }
    }
}

/** What the area under the search bar shows. */
internal enum class SearchContent { IDLE, NO_RESULTS, RESULTS }

/** [IDLE] with nothing searched; [NO_RESULTS] only when no title matches and there is no person section (or note) to show. */
internal fun searchContent(results: List<SearchResult>?, people: List<PersonSection>): SearchContent = when {
    results == null && people.isEmpty() -> SearchContent.IDLE
    results.isNullOrEmpty() && people.isEmpty() -> SearchContent.NO_RESULTS
    else -> SearchContent.RESULTS
}

/** Person posters can show a copy that is also an ordinary result, so their keys (and remembered focus) are kept apart. */
private fun personKey(personId: Long, result: SearchResult) = "person:$personId:${result.key}"

/**
 * Six per row: each person section under a full-width header (or a note), then the title results
 * on a row of their own. Focus goes to the remembered poster when it is on screen, else to
 * [defaultTarget] if any, once per composition.
 */
@Composable
private fun SearchResultsGrid(
    people: List<PersonSection>,
    results: List<SearchResult>,
    focus: FocusMemory,
    requested: MutableState<Boolean>,
    defaultTarget: String?,
    showKind: Boolean,
    onResult: (SearchResult) -> Unit,
) {
    val keys = people.flatMap { s -> (s as? PersonSection.Found)?.let { f -> f.titles.map { personKey(f.personId, it) } }.orEmpty() } + results.map { it.key }
    val target = focus.targetIn(keys, default = defaultTarget)
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        people.forEachIndexed { i, section ->
            when (section) {
                is PersonSection.Found -> {
                    fullWidth("person-head:$i:${section.personId}") {
                        Text(
                            pluralStringResource(R.plurals.person_titles, section.titles.size, section.name, section.titles.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(section.titles, key = { personKey(section.personId, it) }) { r ->
                        val key = personKey(section.personId, r)
                        ResultPoster(r, showKind, onResult, Modifier.rememberedFocus(focus, key, isTarget = key == target, requested = requested))
                    }
                }
                is PersonSection.NoTitles -> fullWidth("person-note:$i") { Note(stringResource(R.string.person_no_titles, section.name)) }
                is PersonSection.Offline -> fullWidth("person-note:$i") { Note(stringResource(R.string.person_offline)) }
                is PersonSection.Loading -> fullWidth("person-note:$i") { Note(stringResource(R.string.person_loading, section.name)) }
            }
        }
        // Title results start on a row of their own, below any person section.
        if (people.isNotEmpty() && results.isNotEmpty()) fullWidth("results-start") { Spacer(Modifier) }
        items(results, key = { it.key }) { r ->
            ResultPoster(r, showKind, onResult, Modifier.rememberedFocus(focus, r.key, isTarget = r.key == target, requested = requested))
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Note(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ResultPoster(r: SearchResult, showKind: Boolean, onResult: (SearchResult) -> Unit, modifier: Modifier) {
    val watchList = LocalWatchList.current
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
        modifier = modifier,
    )
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
