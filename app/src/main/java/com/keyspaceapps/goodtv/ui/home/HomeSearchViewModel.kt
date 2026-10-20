package com.keyspaceapps.goodtv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the search covers. ALL is the default. */
enum class SearchScope(val kinds: List<ContentKind>) {
    ALL(listOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.SERIES)),
    LIVE(listOf(ContentKind.LIVE)),
    MOVIES(listOf(ContentKind.MOVIE)),
    SERIES(listOf(ContentKind.SERIES)),
}

/** One search hit; [key] is unique across kinds and sources. */
data class SearchResult(val kind: ContentKind, val sourceId: Long, val id: String, val title: String, val imageUrl: String?) {
    val key: String get() = "${kind.name}:$sourceId:$id"
}

/** Results per kind; a search over everything shows up to three times this. */
const val SEARCH_LIMIT_PER_KIND = 60

/** Wait this long after the last keystroke before querying. */
const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Search state for the Home tab. Lives in the Home nav entry, so the query and results survive
 * opening a result and coming back. [results] is null while the query is blank (show the rows),
 * else the hits grouped live, then movies, then series.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeSearchViewModel(private val repo: LibraryRepository) : ViewModel() {
    val focus = FocusMemory()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _scope = MutableStateFlow(SearchScope.ALL)
    val scope: StateFlow<SearchScope> = _scope

    val results: StateFlow<List<SearchResult>?> =
        combine(_query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }, _scope) { q, s -> q.trim() to s }
            .flatMapLatest { (q, s) -> if (q.isEmpty()) flowOf(null) else search(q, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(value: String) { _query.value = value }
    fun setScope(value: SearchScope) { _scope.value = value }

    private fun search(query: String, scope: SearchScope): Flow<List<SearchResult>> {
        val perKind = scope.kinds.map { kind -> resultsOf(kind, query) }
        return combine(perKind) { lists -> lists.toList().flatten() }
    }

    private fun resultsOf(kind: ContentKind, query: String): Flow<List<SearchResult>> = when (kind) {
        ContentKind.LIVE -> repo.searchLive(query, SEARCH_LIMIT_PER_KIND).map { list ->
            list.map { SearchResult(kind, it.sourceId, it.id, it.name, it.logoUrl) }
        }
        ContentKind.MOVIE -> repo.searchMovies(query, SEARCH_LIMIT_PER_KIND).map { list ->
            list.map { SearchResult(kind, it.sourceId, it.id, it.name, it.posterUrl) }
        }
        ContentKind.SERIES -> repo.searchSeries(query, SEARCH_LIMIT_PER_KIND).map { list ->
            list.map { SearchResult(kind, it.sourceId, it.id, it.name, it.coverUrl) }
        }
    }
}
