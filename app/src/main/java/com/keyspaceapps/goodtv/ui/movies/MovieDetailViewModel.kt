package com.keyspaceapps.goodtv.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.data.progress.Loaded
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.WatchProgressRepository
import com.keyspaceapps.goodtv.data.progress.WatchRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Provider details first; TMDB rating and summary follow in [tmdb] when [tmdbRepo] finds the title. */
class MovieDetailViewModel(
    private val repo: LibraryRepository,
    val key: MovieDetailKey,
    private val tmdbRepo: TmdbRepository? = null,
    progressRepo: WatchProgressRepository? = null,
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

    /** Where the user stopped last time (null until loaded); updates live as the player saves. */
    val progress: StateFlow<Loaded<WatchProgress?>?> = (progressRepo?.observe(WatchRef(key.sourceId, WatchKind.MOVIE, key.movieId)) ?: flowOf(null))
        .map { Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
