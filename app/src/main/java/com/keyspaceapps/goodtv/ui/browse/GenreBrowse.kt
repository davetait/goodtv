package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.ui.home.HomeSearchBar
import java.text.NumberFormat

/**
 * A genre dropdown and a search bar over a browse screen. "All genres" shows [rows] (the category
 * rails); a genre shows its titles in a grid below. Typing in the search bar shows matches within
 * the chosen genre instead, and clearing it brings the rows (or genre grid) back. [status] is an
 * optional note under the bar (the movie genre fill's progress). The dropdown is hidden while no
 * genre has any titles; the search bar always shows.
 */
@Composable
fun GenreBrowse(
    kind: ContentKind,
    filter: GenreFilter,
    status: String?,
    onItem: (RowItem) -> Unit,
    rows: @Composable () -> Unit,
) {
    val selected by filter.selected.collectAsState()
    val counts by filter.counts.collectAsState()
    val items by filter.items.collectAsState()
    val query by filter.query.collectAsState()
    val found by filter.searchResults.collectAsState()
    val requested = remember { mutableStateOf(false) }
    val searchRequested = remember { mutableStateOf(false) }
    val numbers = remember { NumberFormat.getIntegerInstance() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (counts.isNotEmpty() || selected != null) {
                val labels = Genre.entries.associateWith { stringResource(it.labelRes) }
                val options = listOf<Genre?>(null) + Genre.entries.filter { (counts[it] ?: 0) > 0 }.sortedBy { labels[it] }
                TvDropdown(
                    options = options,
                    selected = selected,
                    label = { g ->
                        if (g == null) stringResource(R.string.genre_all)
                        else stringResource(R.string.genre_with_count, labels.getValue(g), numbers.format(counts[g] ?: 0))
                    },
                    onSelect = filter::select,
                    modifier = Modifier.width(320.dp),
                    listWidth = 320.dp,
                )
                Spacer(Modifier.width(16.dp))
            }
            HomeSearchBar(
                query = query,
                onQueryChange = filter::setQuery,
                hint = stringResource(if (kind == ContentKind.SERIES) R.string.browse_search_series else R.string.browse_search_movies),
                modifier = Modifier.weight(1f),
            )
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, top = 6.dp))
        }
        Box(Modifier.weight(1f)) {
            val genre = selected
            val list = items
            val matches = found
            when {
                matches != null && matches.isEmpty() -> Text(
                    stringResource(if (genre != null) R.string.genre_search_no_results else R.string.search_no_results, query.trim()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, top = 24.dp),
                )
                // New results never pull focus from the search field; Back from a result returns to it.
                matches != null -> PosterGrid(
                    matches, kind, filter.searchFocus, searchRequested, onItem,
                    modifier = Modifier.fillMaxSize(),
                    focusFirst = false,
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 48.dp),
                )
                genre == null -> rows()
                list == null -> Unit
                list.isEmpty() -> Text(
                    stringResource(R.string.genre_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, top = 24.dp),
                )
                else -> Column {
                    val total = counts[genre] ?: 0
                    if (total > list.size) {
                        val note = if (kind == ContentKind.MOVIE) R.string.genre_limited else R.string.genre_limited_by_name
                        Text(
                            stringResource(note, numbers.format(list.size), numbers.format(total)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 48.dp, top = 12.dp),
                        )
                    }
                    PosterGrid(
                        list, kind, filter.focus, requested, onItem,
                        modifier = Modifier.fillMaxSize(),
                        focusFirst = false,
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 48.dp),
                    )
                }
            }
        }
    }
}

val Genre.labelRes: Int
    get() = when (this) {
        Genre.ACTION -> R.string.genre_action
        Genre.ADVENTURE -> R.string.genre_adventure
        Genre.ANIMATION -> R.string.genre_animation
        Genre.COMEDY -> R.string.genre_comedy
        Genre.CRIME -> R.string.genre_crime
        Genre.DOCUMENTARY -> R.string.genre_documentary
        Genre.DRAMA -> R.string.genre_drama
        Genre.FAMILY -> R.string.genre_family
        Genre.FANTASY -> R.string.genre_fantasy
        Genre.HISTORY -> R.string.genre_history
        Genre.HORROR -> R.string.genre_horror
        Genre.KIDS -> R.string.genre_kids
        Genre.MUSIC -> R.string.genre_music
        Genre.MYSTERY -> R.string.genre_mystery
        Genre.NEWS -> R.string.genre_news
        Genre.POLITICS -> R.string.genre_politics
        Genre.REALITY -> R.string.genre_reality
        Genre.ROMANCE -> R.string.genre_romance
        Genre.SCIFI -> R.string.genre_scifi
        Genre.SOAP -> R.string.genre_soap
        Genre.SPORT -> R.string.genre_sport
        Genre.TALK -> R.string.genre_talk
        Genre.THRILLER -> R.string.genre_thriller
        Genre.TV_MOVIE -> R.string.genre_tv_movie
        Genre.WAR -> R.string.genre_war
        Genre.WESTERN -> R.string.genre_western
    }

/** The movie genre fill's progress or failure as a note for [GenreBrowse]; null when there's nothing to say. */
@Composable
fun genreFillStatus(state: com.keyspaceapps.goodtv.data.tmdb.TmdbGenreFetcher.State): String? {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    return when (state) {
        is com.keyspaceapps.goodtv.data.tmdb.TmdbGenreFetcher.State.Running ->
            stringResource(R.string.genre_loading, numbers.format(state.done), numbers.format(state.total))
        is com.keyspaceapps.goodtv.data.tmdb.TmdbGenreFetcher.State.Stopped -> stringResource(R.string.genre_stopped, state.reason)
        com.keyspaceapps.goodtv.data.tmdb.TmdbGenreFetcher.State.Idle -> null
    }
}
