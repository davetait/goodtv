package com.keyspaceapps.goodtv.ui.categories

import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.ContentKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One source's categories, grouped by prefix, with every toggle written straight to the store. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel(private val repo: LibraryRepository, val sourceId: Long) : ViewModel() {
    data class TabState(val kind: ContentKind, val shown: Int, val total: Int)

    private val sharing = SharingStarted.WhileSubscribed(5_000)
    private val summary = repo.sources.map { list -> list.firstOrNull { it.id == sourceId } }

    val nickname: StateFlow<String> = summary.map { it?.nickname ?: "" }.stateIn(viewModelScope, sharing, "")
    val synced: StateFlow<Boolean> = summary.map { it?.lastSyncAt != null }.stateIn(viewModelScope, sharing, true)

    private val _kind = MutableStateFlow(ContentKind.LIVE)
    val kind: StateFlow<ContentKind> = _kind

    private val hidden = repo.hiddenCategories(sourceId)

    val tabs: StateFlow<List<TabState>> = combine(
        repo.allCategories(sourceId, ContentKind.LIVE),
        repo.allCategories(sourceId, ContentKind.MOVIE),
        repo.allCategories(sourceId, ContentKind.SERIES),
        hidden,
    ) { live, movies, series, h ->
        listOf(ContentKind.LIVE to live, ContentKind.MOVIE to movies, ContentKind.SERIES to series).map { (k, cats) ->
            TabState(k, shown = cats.count { (k to it.id) !in h }, total = cats.size)
        }
    }.stateIn(viewModelScope, sharing, emptyList())

    val groups: StateFlow<List<CategoryGroup>> = combine(
        _kind.flatMapLatest { k -> repo.allCategories(sourceId, k).map { k to it } },
        hidden,
    ) { (k, cats), h ->
        CategoryGroups.group(cats, h.filter { it.first == k }.map { it.second }.toSet())
    }.stateIn(viewModelScope, sharing, emptyList())

    fun selectKind(k: ContentKind) { _kind.value = k }

    fun toggle(categoryId: String) {
        val currentlyHidden = groups.value.any { categoryId in it.hiddenIds }
        setHidden(listOf(categoryId), hidden = !currentlyHidden)
    }

    /** Pruning-first: a shown or mixed block hides in one press; only a fully hidden block shows all again. */
    fun toggleGroup(group: CategoryGroup) {
        setHidden(group.categories.map { it.id }, hidden = group.state != ToggleableState.Off)
    }

    fun selectAll() = setHidden(groups.value.flatMap { g -> g.categories.map { it.id } }, hidden = false)
    fun clearAll() = setHidden(groups.value.flatMap { g -> g.categories.map { it.id } }, hidden = true)

    private fun setHidden(ids: List<String>, hidden: Boolean) {
        val k = _kind.value
        viewModelScope.launch { repo.setCategoriesHidden(sourceId, k, ids, hidden) }
    }
}
