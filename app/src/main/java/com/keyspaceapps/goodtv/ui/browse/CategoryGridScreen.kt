package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

/** Every item of one category, six per row. */
@Composable
fun CategoryGridScreen(key: CategoryGridKey, onItem: (RowItem) -> Unit) {
    val vm = goodTvViewModel(key = "grid-${key.sourceId}-${key.kind}-${key.categoryId}") { CategoryGridViewModel(it.repository, key) }
    val items by vm.items.collectAsState()
    val requested = remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text(key.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))
        PosterGrid(items, key.kind, vm.focus, requested, onItem)
    }
}
