package com.keyspaceapps.goodtv.ui.browse

import com.keyspaceapps.goodtv.domain.Genre
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import com.keyspaceapps.goodtv.ui.home.SEARCH_DEBOUNCE_MS

/** Most titles a genre view shows; Action alone can have tens of thousands. */
const val GENRE_LIMIT = 1_000

/** Most matches a browse screen's search shows. */
const val BROWSE_SEARCH_LIMIT = 120

/**
 * Genre filter and search state for a browse screen, held by its ViewModel so the choice, the
 * query and the focused result survive opening a title and coming back. [selected] null = no
 * filter (show the rows). [items] is null while the chosen genre's results load. [searchResults]
 * is null while the query is blank; otherwise matches within the chosen genre ([search]).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GenreFilter(
    scope: CoroutineScope,
    counts: Flow<Map<Genre, Int>>,
    items: (Genre) -> Flow<List<RowItem>>,
    search: (query: String, genre: Genre?) -> Flow<List<RowItem>> = { _, _ -> flowOf(emptyList()) },
) {
    val focus = FocusMemory()
    /** The search result focused last, apart from the genre grid's. */
    val searchFocus = FocusMemory()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _selected = MutableStateFlow<Genre?>(null)
    val selected: StateFlow<Genre?> = _selected

    /** Titles per genre; genres with none are absent. Conflated: the background genre fill updates it often. */
    val counts: StateFlow<Map<Genre, Int>> = counts.conflate().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val items: StateFlow<List<RowItem>?> = _selected
        .flatMapLatest { g -> if (g == null) flowOf(null) else items(g) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val searchResults: StateFlow<List<RowItem>?> =
        combine(_query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }, _selected) { q, g -> q.trim() to g }
            .flatMapLatest { (q, g) -> if (q.isEmpty()) flowOf(null) else search(q, g) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(value: String) {
        if (value.trim() != _query.value.trim()) searchFocus.key = null
        _query.value = value
    }

    fun select(genre: Genre?) {
        if (genre != _selected.value) searchFocus.key = null
        if (genre != _selected.value) focus.key = null // a new genre starts at the top, not at an old result
        _selected.value = genre
    }
}
