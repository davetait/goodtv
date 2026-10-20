package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.progress.ContinueItem
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgressRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.PosterCard
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.player.PlayerFormats
import com.keyspaceapps.goodtv.ui.tmdb.POSTER_SETTLE_MS
import com.keyspaceapps.goodtv.ui.tmdb.rememberTmdbMeta
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Continue Watching for the Home tab; null until loaded so the rows don't shuffle under the user. */
class ContinueWatchingViewModel(private val progress: WatchProgressRepository) : ViewModel() {
    val items: StateFlow<List<ContinueItem>?> = progress.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Takes [item] off the row and resets its progress, as if it was never started. */
    fun remove(item: ContinueItem) {
        viewModelScope.launch { progress.remove(item) }
    }
}

/**
 * Where selecting a card goes: straight back into playback at the saved spot, or the series page
 * when the last episode was finished (it offers the next one). Rows saved before playback details
 * were stored fall back to the detail page.
 */
fun ContinueItem.navKey(): GoodTvKey {
    val p = progress
    val r = p.ref
    val info = p.info
    return when (r.kind) {
        WatchKind.MOVIE ->
            if (info != null) PlayerKey(r.sourceId, PlayerKey.KIND_MOVIE, r.itemId, info.ext, info.title, startPositionMs = p.positionMs)
            else MovieDetailKey(r.sourceId, r.itemId)
        WatchKind.EPISODE ->
            if (info != null && p.resumable) {
                PlayerKey(r.sourceId, PlayerKey.KIND_EPISODE, r.itemId, info.ext, info.title, p.positionMs, r.seriesId, info.season, info.episode)
            } else {
                SeriesDetailKey(r.sourceId, itemId)
            }
    }
}

@Composable
private fun ContinueItem.status(): String {
    val p = progress
    val left = (p.durationMs - p.positionMs).takeIf { p.durationMs > 0 }?.let { (it / 60_000).coerceAtLeast(1) }
    val where = if (left != null) stringResource(R.string.minutes_left, left) else stringResource(R.string.resume_from, PlayerFormats.clock(p.positionMs))
    val ep = p.info?.let { i -> if (i.season != null && i.episode != null) "S${i.season} E${i.episode}" else null }
    return when {
        kind == WatchKind.MOVIE -> where
        p.finished -> if (ep != null) stringResource(R.string.up_next_after, ep) else stringResource(R.string.up_next)
        ep != null -> "$ep · $where"
        else -> where
    }
}

/**
 * The Continue Watching rail. Holding Select (or the remote's Menu key) on a card offers to remove
 * it. Focus goes to [initialFocus]'s first card when it is still unclaimed (unless [claimInitialFocus]
 * is false: another row's remembered card is coming back); after a removal it moves to the
 * neighbouring card, or is handed back ([onEmptied]) when the row empties. Focused cards are noted
 * in [focus].
 */
@Composable
fun ContinueWatchingRow(
    items: List<ContinueItem>,
    initialFocus: MutableState<Boolean>,
    onOpen: (GoodTvKey) -> Unit,
    onRemove: (ContinueItem) -> Unit,
    onEmptied: () -> Unit,
    focus: FocusMemory? = null,
    claimInitialFocus: Boolean = true,
) {
    var menuFor by remember { mutableStateOf<ContinueItem?>(null) }
    // After a removal: (removed card's key, neighbour's key). The neighbour takes focus once the
    // removed card is really gone, so the card on its way out can't claim it.
    var refocus by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(Modifier.padding(start = 48.dp, end = 48.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.home_continue_watching), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.continue_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                val requester = remember { FocusRequester() }
                val kind = if (item.kind == WatchKind.MOVIE) TmdbKind.MOVIE else TmdbKind.TV
                val tmdb = rememberTmdbMeta(kind, item.tmdbId, item.lookupName, POSTER_SETTLE_MS)
                val p = item.progress
                PosterCard(
                    title = item.title,
                    imageUrl = item.imageUrl,
                    subtitle = item.status(),
                    rating = tmdb?.reliableRating,
                    progress = if (p.durationMs > 0 && !p.finished) p.positionMs.toFloat() / p.durationMs else null,
                    onClick = { onOpen(item.navKey()) },
                    onLongClick = { menuFor = item },
                    modifier = Modifier
                        .focusRequester(requester)
                        .onPlaced {
                            val r = refocus
                            when {
                                r != null && r.second == item.key && items.none { it.key == r.first } -> { refocus = null; requester.requestFocus() }
                                index == 0 && claimInitialFocus && !initialFocus.value -> { initialFocus.value = true; requester.requestFocus() }
                            }
                        }
                        .onFocusChanged { if (it.isFocused) focus?.key = "continue:${item.key}" }
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { menuFor = item; true } else false
                        },
                )
            }
        }
    }

    menuFor?.let { item ->
        RemoveDialog(
            item = item,
            onRemove = {
                val index = items.indexOf(item)
                menuFor = null
                if (items.size == 1) onEmptied() else refocus = item.key to items[if (index < items.lastIndex) index + 1 else index - 1].key
                onRemove(item)
            },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun RemoveDialog(item: ContinueItem, onRemove: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        var requested by remember { mutableStateOf(false) }
        Column(
            Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (item.kind == WatchKind.MOVIE) R.string.remove_continue_movie else R.string.remove_continue_series),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onRemove,
                    modifier = Modifier.focusRequester(first).onPlaced { if (!requested) { requested = true; first.requestFocus() } },
                ) { Text(stringResource(R.string.remove_continue)) }
                Button(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
