package com.keyspaceapps.goodtv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Provider details first; TMDB rating and summary follow in [tmdb] when [tmdbRepo] finds the title. */
class MovieDetailViewModel(
    private val repo: LibraryRepository,
    val key: MovieDetailKey,
    private val tmdbRepo: TmdbRepository? = null,
) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Ready(val detail: MovieDetail) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private val _tmdb = MutableStateFlow<TmdbMeta?>(null)
    val tmdb: StateFlow<TmdbMeta?> = _tmdb

    init {
        viewModelScope.launch {
            _state.value = repo.movieDetail(key.sourceId, key.movieId).fold(
                onSuccess = { State.Ready(it) },
                onFailure = { State.Error(it.message ?: "Could not load movie") },
            )
        }
        tmdbRepo?.let { t ->
            viewModelScope.launch {
                val row = repo.catalogMovie(key.sourceId, key.movieId) ?: return@launch
                _tmdb.value = t.lookup(TmdbKind.MOVIE, row.tmdbId, row.name).getOrNull()
            }
        }
    }
}
