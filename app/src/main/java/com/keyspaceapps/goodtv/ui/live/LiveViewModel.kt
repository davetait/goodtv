package com.keyspaceapps.goodtv.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(repo: LibraryRepository) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)
    val focus = FocusMemory()

    val hasSources: StateFlow<Boolean> = repo.enabledSourceCount().map { it > 0 }.stateIn(viewModelScope, sharing, false)

    val categories: StateFlow<List<CategoryWithSource>> =
        repo.categories(ContentKind.LIVE).stateIn(viewModelScope, sharing, emptyList())

    val multiSource: StateFlow<Boolean> = categories
        .map { list -> list.map { it.category.sourceId }.distinct().size > 1 }
        .stateIn(viewModelScope, sharing, false)

    private val chosen = MutableStateFlow<CategoryWithSource?>(null)

    /** The chosen category if it still exists, else the first one. */
    val selected: StateFlow<CategoryWithSource?> = combine(categories, chosen) { cats, c ->
        c?.takeIf { it in cats } ?: cats.firstOrNull()
    }.stateIn(viewModelScope, sharing, null)

    val channels: StateFlow<List<LiveChannel>> = selected
        .flatMapLatest { c -> if (c == null) flowOf(emptyList()) else repo.liveChannels(c.category.sourceId, c.category.id) }
        .stateIn(viewModelScope, sharing, emptyList())

    fun select(category: CategoryWithSource) { chosen.value = category }
}
