package com.keyspaceapps.goodtv.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Provider details first; TMDB rating and summary follow in [tmdb] when [tmdbRepo] finds the show. */
class SeriesDetailViewModel(
    private val repo: LibraryRepository,
    val key: SeriesDetailKey,
    private val tmdbRepo: TmdbRepository? = null,
) : ViewModel() {
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

    private val _tmdb = MutableStateFlow<TmdbMeta?>(null)
    val tmdb: StateFlow<TmdbMeta?> = _tmdb

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
        tmdbRepo?.let { t ->
            viewModelScope.launch {
                val row = repo.catalogSeries(key.sourceId, key.seriesId) ?: return@launch
                _tmdb.value = t.lookup(TmdbKind.TV, row.tmdbId, row.name).getOrNull()
            }
        }
    }

    fun selectSeason(number: Int) {
        _state.update { s -> if (s is State.Ready) s.copy(selectedSeason = number) else s }
    }
}
