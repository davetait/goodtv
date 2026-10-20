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
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.WatchProgressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Provider details first; TMDB rating and summary follow in [tmdb] when [tmdbRepo] finds the show. */
class SeriesDetailViewModel(
    private val repo: LibraryRepository,
    val key: SeriesDetailKey,
    private val tmdbRepo: TmdbRepository? = null,
    progressRepo: WatchProgressRepository? = null,
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

    /** Progress of this series' episodes, newest first; null until loaded. */
    val progress: StateFlow<List<WatchProgress>?> = (progressRepo?.observeSeries(key.sourceId, key.seriesId) ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            _state.value = repo.seriesDetail(key.sourceId, key.seriesId).fold(
                onSuccess = { d ->
                    val numbers = d.seasons.map { it.number }.ifEmpty { d.episodes.map { it.season } }
                    // Skip seasons with no episodes (panels often list an empty "Specials" season 0).
                    val withEpisodes = d.episodes.mapTo(HashSet()) { it.season }
                    val first = numbers.filter { it in withEpisodes }.minOrNull() ?: numbers.minOrNull() ?: 1
                    // Open on the season the user is part-way through.
                    val resumeSeason = progressRepo?.observeSeries(key.sourceId, key.seriesId)?.first()
                        ?.let { SeriesResumes.of(d.episodes, it)?.episode?.season }
                    State.Ready(d, resumeSeason ?: first)
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
