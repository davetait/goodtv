package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.tmdb.POSTER_SETTLE_MS
import com.keyspaceapps.goodtv.ui.tmdb.rememberTmdbMeta
import com.keyspaceapps.goodtv.ui.tmdb.tmdbKind
import com.keyspaceapps.goodtv.ui.watchlist.LocalWatchList
import com.keyspaceapps.goodtv.ui.watchlist.rememberWatchTarget

/**
 * Posters six per row with TMDB rating badges. Focus goes to the item remembered in [focus]; when
 * there is none, to the first item only if [focusFirst] (a filter's results must not pull focus
 * away from the filter).
 */
@Composable
fun PosterGrid(
    items: List<RowItem>,
    kind: ContentKind,
    focus: FocusMemory,
    requested: MutableState<Boolean>,
    onItem: (RowItem) -> Unit,
    modifier: Modifier = Modifier,
    focusFirst: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(bottom = 48.dp),
) {
    val keys = items.map { "${it.sourceId}:${it.id}" }
    val target = focus.targetIn(keys, if (focusFirst) keys.firstOrNull() else null)
    val watchList = LocalWatchList.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        itemsIndexed(items, key = { i, _ -> keys[i] }) { i, item ->
            val tmdb = rememberTmdbMeta(kind.tmdbKind(), item.tmdbId, item.lookupName, POSTER_SETTLE_MS)
            val watchTarget = rememberWatchTarget(kind, item.sourceId, item.id, item.title)
            PosterCard(
                title = item.title, imageUrl = item.imageUrl, onClick = { onItem(item) }, rating = tmdb?.reliableRating,
                saved = watchTarget != null && watchTarget.ref in watchList.saved, onLongClick = watchTarget?.let { t -> { watchList.openMenu(t) } },
                modifier = Modifier.rememberedFocus(focus, keys[i], isTarget = keys[i] == target, requested = requested),
            )
        }
    }
}
