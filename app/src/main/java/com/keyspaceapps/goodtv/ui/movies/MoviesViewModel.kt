package com.keyspaceapps.goodtv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbGenreFetcher
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.CategoryRow
import com.keyspaceapps.goodtv.ui.browse.BrowseEmptyState
import com.keyspaceapps.goodtv.ui.browse.EmptyStates
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.GENRE_LIMIT
import com.keyspaceapps.goodtv.ui.browse.GenreFilter
import com.keyspaceapps.goodtv.ui.browse.ROW_LIMIT
import com.keyspaceapps.goodtv.ui.browse.RowItem
import com.keyspaceapps.goodtv.ui.browse.isMultiSource
import com.keyspaceapps.goodtv.ui.browse.rowsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.keyspaceapps.goodtv.domain.label

/** [genreFill] is the background TMDB genre fill's state, for the progress note; null in tests. */
class MoviesViewModel(repo: LibraryRepository, genreFill: StateFlow<TmdbGenreFetcher.State>? = null) : ViewModel() {
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
        repo.moviesByCategory(ROW_LIMIT + 1).map { grouped -> grouped.mapValues { (_, list) -> list.map { RowItem(it.sourceId, it.id, it.label, it.posterUrl, it.tmdbId, lookupName = it.name) } } },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val genres = GenreFilter(viewModelScope, repo.genreCounts(ContentKind.MOVIE)) { g ->
        repo.moviesByGenre(g, GENRE_LIMIT).map { list -> list.map { RowItem(it.sourceId, it.id, it.label, it.posterUrl, it.tmdbId, lookupName = it.name) } }
    }

    val genreFill: StateFlow<TmdbGenreFetcher.State> = genreFill ?: MutableStateFlow(TmdbGenreFetcher.State.Idle)
}
