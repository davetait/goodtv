package com.keyspaceapps.goodtv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.nav.CategoryGridKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class CategoryGridViewModel(repo: LibraryRepository, val key: CategoryGridKey) : ViewModel() {
    val items: StateFlow<List<RowItem>> = when (key.kind) {
        ContentKind.MOVIE -> repo.movies(key.sourceId, key.categoryId, Int.MAX_VALUE).map { list -> list.map { RowItem(it.sourceId, it.id, it.name, it.posterUrl) } }
        ContentKind.SERIES -> repo.series(key.sourceId, key.categoryId, Int.MAX_VALUE).map { list -> list.map { RowItem(it.sourceId, it.id, it.name, it.coverUrl) } }
        ContentKind.LIVE -> flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
