package com.keyspaceapps.goodtv.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncState
import com.keyspaceapps.goodtv.domain.SourceSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SourcesViewModel(private val repo: LibraryRepository) : ViewModel() {
    val sources: StateFlow<List<SourceSummary>> =
        repo.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncState: StateFlow<SyncState> = repo.syncState

    fun refresh(id: Long) { repo.refresh(id) }
    fun remove(id: Long) { viewModelScope.launch { repo.removeSource(id) } }
}
