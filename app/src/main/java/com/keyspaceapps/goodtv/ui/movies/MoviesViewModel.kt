package com.keyspaceapps.goodtv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.CategoryRow
import com.keyspaceapps.goodtv.ui.browse.BrowseEmptyState
import com.keyspaceapps.goodtv.ui.browse.EmptyStates
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.ROW_LIMIT
import com.keyspaceapps.goodtv.ui.browse.RowItem
import com.keyspaceapps.goodtv.ui.browse.isMultiSource
import com.keyspaceapps.goodtv.ui.browse.rowsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MoviesViewModel(repo: LibraryRepository) : ViewModel() {
    private val categories = repo.categories(ContentKind.MOVIE)
    val focus = FocusMemory()

    val emptyState: StateFlow<BrowseEmptyState> = combine(repo.enabledSourceCount(), repo.hiddenCount(ContentKind.MOVIE)) { n, hidden ->
        EmptyStates.forBrowse(hasSources = n > 0, hiddenCount = hidden)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseEmptyState.NOTHING_YET)

    val multiSource: StateFlow<Boolean> = categories.map { it.isMultiSource() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** null until the first query result arrives, so the screen can tell "loading" from "empty". */
    val rows: StateFlow<List<CategoryRow>?> = rowsFlow(
        categories,
        repo.moviesByCategory(ROW_LIMIT + 1).map { grouped -> grouped.mapValues { (_, list) -> list.map { RowItem(it.sourceId, it.id, it.name, it.posterUrl) } } },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
