package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.CategoryGridKey
import com.keyspaceapps.goodtv.ui.tmdb.POSTER_SETTLE_MS
import com.keyspaceapps.goodtv.ui.tmdb.rememberTmdbMeta
import com.keyspaceapps.goodtv.ui.tmdb.tmdbKind

/** Every item of one category, six per row. */
@Composable
fun CategoryGridScreen(key: CategoryGridKey, onItem: (RowItem) -> Unit) {
    val vm = goodTvViewModel(key = "grid-${key.sourceId}-${key.kind}-${key.categoryId}") { CategoryGridViewModel(it.repository, key) }
    val items by vm.items.collectAsState()
    val requested = remember { mutableStateOf(false) }
    val target = vm.focus.targetIn(items.map { it.id }, items.firstOrNull()?.id)

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text(key.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { _, item ->
                val tmdb = rememberTmdbMeta(key.kind.tmdbKind(), item.tmdbId, item.title, POSTER_SETTLE_MS)
                PosterCard(
                    title = item.title, imageUrl = item.imageUrl, onClick = { onItem(item) }, rating = tmdb?.reliableRating,
                    modifier = Modifier.rememberedFocus(vm.focus, item.id, isTarget = item.id == target, requested = requested),
                )
            }
        }
    }
}
