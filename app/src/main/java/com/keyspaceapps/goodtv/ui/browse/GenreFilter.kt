package com.keyspaceapps.goodtv.ui.browse

import com.keyspaceapps.goodtv.domain.Genre
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Most titles a genre view shows; Action alone can have tens of thousands. */
const val GENRE_LIMIT = 1_000

/**
 * Genre filter state for a browse screen, held by its ViewModel so the choice and the focused
 * result survive opening a title and coming back. [selected] null = no filter (show the rows).
 * [items] is null while the chosen genre's results load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenreFilter(scope: CoroutineScope, counts: Flow<Map<Genre, Int>>, items: (Genre) -> Flow<List<RowItem>>) {
    val focus = FocusMemory()

    private val _selected = MutableStateFlow<Genre?>(null)
    val selected: StateFlow<Genre?> = _selected

    /** Titles per genre; genres with none are absent. Conflated: the background genre fill updates it often. */
    val counts: StateFlow<Map<Genre, Int>> = counts.conflate().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val items: StateFlow<List<RowItem>?> = _selected
        .flatMapLatest { g -> if (g == null) flowOf(null) else items(g) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(genre: Genre?) {
        if (genre != _selected.value) focus.key = null // a new genre starts at the top, not at an old result
        _selected.value = genre
    }
}
