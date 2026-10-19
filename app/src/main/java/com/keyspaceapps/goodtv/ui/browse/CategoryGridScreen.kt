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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.CategoryGridKey

/** Every item of one category, six per row. */
@Composable
fun CategoryGridScreen(key: CategoryGridKey, onItem: (RowItem) -> Unit) {
    val vm = goodTvViewModel(key = "grid-${key.sourceId}-${key.kind}-${key.categoryId}") { CategoryGridViewModel(it.repository, key) }
    val items by vm.items.collectAsState()
    val firstFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text(key.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                val focusModifier = if (index == 0) {
                    Modifier.focusRequester(firstFocus).onPlaced {
                        if (!initialFocusDone) { initialFocusDone = true; firstFocus.requestFocus() }
                    }
                } else {
                    Modifier
                }
                PosterCard(title = item.title, imageUrl = item.imageUrl, onClick = { onItem(item) }, modifier = focusModifier)
            }
        }
    }
}
