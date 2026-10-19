package com.keyspaceapps.goodtv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.CategoryRow
import com.keyspaceapps.goodtv.ui.browse.RowItem
import com.keyspaceapps.goodtv.ui.browse.isMultiSource
import com.keyspaceapps.goodtv.ui.browse.rowsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MoviesViewModel(repo: LibraryRepository) : ViewModel() {
    private val categories = repo.categories(ContentKind.MOVIE)

    val multiSource: StateFlow<Boolean> = categories.map { it.isMultiSource() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val rows: StateFlow<List<CategoryRow>> = rowsFlow(categories) { c, limit ->
        repo.movies(c.category.sourceId, c.category.id, limit).map { list -> list.map { RowItem(it.sourceId, it.id, it.name, it.posterUrl) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
