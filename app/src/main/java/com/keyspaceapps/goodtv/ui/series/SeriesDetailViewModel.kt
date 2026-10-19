package com.keyspaceapps.goodtv.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SeriesDetailViewModel(private val repo: LibraryRepository, val key: SeriesDetailKey) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Ready(val detail: SeriesDetail, val selectedSeason: Int) : State {
            fun episodes(): List<Episode> = detail.episodes.filter { it.season == selectedSeason }.sortedBy { it.number }
            /** Season numbers in order, taken from the seasons list or, if empty, from the episodes. */
            fun seasonNumbers(): List<Int> =
                (detail.seasons.map { it.number }.ifEmpty { detail.episodes.map { it.season } }).distinct().sorted()
        }
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            _state.value = repo.seriesDetail(key.sourceId, key.seriesId).fold(
                onSuccess = { d ->
                    val first = (d.seasons.map { it.number }.ifEmpty { d.episodes.map { it.season } }).minOrNull() ?: 1
                    State.Ready(d, first)
                },
                onFailure = { State.Error(it.message ?: "Could not load series") },
            )
        }
    }

    fun selectSeason(number: Int) {
        _state.update { s -> if (s is State.Ready) s.copy(selectedSeason = number) else s }
    }
}
