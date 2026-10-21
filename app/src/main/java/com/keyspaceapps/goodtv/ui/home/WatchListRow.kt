package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListItem
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.data.watchlist.WatchListRepository
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.PosterCard
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.tmdb.POSTER_SETTLE_MS
import com.keyspaceapps.goodtv.ui.tmdb.rememberTmdbMeta
import com.keyspaceapps.goodtv.ui.watchlist.WatchListDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Home's Watch list row: saved titles, newest first (null until loaded). */
class WatchListViewModel(private val repo: WatchListRepository) : ViewModel() {
    val items: StateFlow<List<WatchListItem>?> = repo.row().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(ref: WatchListRef) {
        viewModelScope.launch { repo.remove(ref) }
    }
}

/** [focus] key of a Watch list card, so coming back from its page lands on it again. */
fun watchListFocusKey(ref: WatchListRef) = "watchlist:${ref.key}"

/**
 * The Watch list rail. Selecting a card opens the title's page; holding Select (or Menu) offers to
 * remove it. Focus follows [PopularRow]'s rules: while [initialFocus] is unclaimed the remembered
 * card takes it when [comingBack], else the first card when [isFirstRow]; a [handOff] goes to the
 * first card when [isFirstRow]. After a removal focus moves to the neighbouring card, or is handed
 * on ([onEmptied]) when the row empties.
 */
@Composable
fun WatchListRow(
    items: List<WatchListItem>,
    focus: FocusMemory,
    initialFocus: MutableState<Boolean>,
    handOff: MutableState<Boolean>,
    isFirstRow: Boolean,
    comingBack: Boolean,
    onOpen: (GoodTvKey) -> Unit,
    onRemove: (WatchListRef) -> Unit,
    onEmptied: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<WatchListItem?>(null) }
    // After a removal: (removed card's key, neighbour's key), as in Continue Watching.
    var refocus by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Set when the last card is removed: the hand-off is for the next row, not the card on its way out.
    var emptying by remember { mutableStateOf(false) }
    val firstKey = items.firstOrNull()?.let { watchListFocusKey(it.ref) }
    val initialTarget = if (comingBack) focus.key else firstKey.takeIf { isFirstRow }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(Modifier.padding(start = 48.dp, end = 48.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.home_watch_list), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.continue_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(items, key = { _, it -> it.ref.key }) { _, item ->
                val key = watchListFocusKey(item.ref)
                val requester = remember { FocusRequester() }
                val kind = if (item.ref.kind == WatchListKind.MOVIE) TmdbKind.MOVIE else TmdbKind.TV
                val tmdb = rememberTmdbMeta(kind, item.tmdbId, item.lookupName, POSTER_SETTLE_MS)
                PosterCard(
                    title = item.title,
                    imageUrl = item.imageUrl,
                    rating = tmdb?.reliableRating,
                    onClick = { onOpen(item.ref.navKey()) },
                    onLongClick = { menuFor = item },
                    modifier = Modifier
                        .focusRequester(requester)
                        .onFocusChanged { if (it.isFocused) focus.key = key }
                        .onPlaced {
                            val r = refocus
                            when {
                                r != null && r.second == key && items.none { it.ref.key == r.first } -> { refocus = null; requester.requestFocus() }
                                handOff.value && isFirstRow && !emptying && key == firstKey -> { handOff.value = false; initialFocus.value = true; requester.requestFocus() }
                                !initialFocus.value && key == initialTarget -> { initialFocus.value = true; requester.requestFocus() }
                            }
                        },
                )
            }
        }
    }

    menuFor?.let { item ->
        WatchListDialog(
            title = item.title,
            action = stringResource(R.string.watch_list_remove),
            onAction = {
                val index = items.indexOf(item)
                menuFor = null
                if (items.size == 1) { emptying = true; onEmptied() } else refocus = item.ref.key to watchListFocusKey(items[if (index < items.lastIndex) index + 1 else index - 1].ref)
                onRemove(item.ref)
            },
            onDismiss = { menuFor = null },
        )
    }
}

private fun WatchListRef.navKey(): GoodTvKey = when (kind) {
    WatchListKind.MOVIE -> MovieDetailKey(sourceId, itemId)
    WatchListKind.SERIES -> SeriesDetailKey(sourceId, itemId)
}
