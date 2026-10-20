package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.tmdb.PopularMoviesRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.label
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.PosterCard
import com.keyspaceapps.goodtv.ui.browse.targetIn
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.tmdb.POSTER_SETTLE_MS
import com.keyspaceapps.goodtv.ui.tmdb.rememberTmdbMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Popular Movies for the Home tab; null until loaded so the rows don't shuffle under the user. */
class PopularMoviesViewModel(private val popular: PopularMoviesRepository, private val appScope: CoroutineScope) : ViewModel() {
    val movies: StateFlow<List<Movie>?> = popular.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Home card focused last, so coming back from a movie's page lands on it again. */
    val focus = FocusMemory()

    /** Fetches a new ranking when the stored one is a day old. The app scope lets it finish after leaving Home. */
    fun refresh() {
        appScope.launch { popular.refreshIfStale() }
    }
}

internal fun Movie.homeKey() = "popular:$sourceId:$id"

/**
 * The Popular Movies rail; selecting a card opens the movie's page. The remembered card takes focus
 * when coming back; otherwise the first card does when [takesDefaultFocus] (no row above it), the
 * first time ([initialFocus] unclaimed) or when the row above just emptied ([handOff]).
 */
@Composable
fun PopularMoviesRow(
    movies: List<Movie>,
    focus: FocusMemory,
    initialFocus: MutableState<Boolean>,
    handOff: MutableState<Boolean>,
    takesDefaultFocus: Boolean,
    onOpen: (GoodTvKey) -> Unit,
) {
    val keys = movies.map { it.homeKey() }
    val target = focus.targetIn(keys, default = if (takesDefaultFocus) keys.firstOrNull() else null)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            stringResource(R.string.home_popular_movies),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(movies, key = { it.homeKey() }) { movie ->
                val key = movie.homeKey()
                val requester = remember { FocusRequester() }
                val tmdb = rememberTmdbMeta(TmdbKind.MOVIE, movie.tmdbId, movie.name, POSTER_SETTLE_MS)
                PosterCard(
                    title = movie.label,
                    imageUrl = movie.posterUrl,
                    rating = tmdb?.reliableRating,
                    onClick = { onOpen(MovieDetailKey(movie.sourceId, movie.id)) },
                    modifier = Modifier
                        .focusRequester(requester)
                        .onFocusChanged { if (it.isFocused) focus.key = key }
                        .onPlaced {
                            if (key == target && (!initialFocus.value || handOff.value)) {
                                initialFocus.value = true
                                handOff.value = false
                                requester.requestFocus()
                            }
                        },
                )
            }
        }
    }
}
