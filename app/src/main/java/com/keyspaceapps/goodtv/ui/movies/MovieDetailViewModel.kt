package com.keyspaceapps.goodtv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MovieDetailViewModel(private val repo: LibraryRepository, val key: MovieDetailKey) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Ready(val detail: MovieDetail) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            _state.value = repo.movieDetail(key.sourceId, key.movieId).fold(
                onSuccess = { State.Ready(it) },
                onFailure = { State.Error(it.message ?: "Could not load movie") },
            )
        }
    }
}
