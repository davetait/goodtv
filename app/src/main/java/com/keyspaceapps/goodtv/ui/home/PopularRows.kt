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
import com.keyspaceapps.goodtv.data.tmdb.PopularRepository
import com.keyspaceapps.goodtv.data.tmdb.PopularStore
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.label
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.browse.PosterCard
import com.keyspaceapps.goodtv.ui.nav.GoodTvKey
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.ui.tmdb.POSTER_SETTLE_MS
import com.keyspaceapps.goodtv.ui.tmdb.rememberTmdbMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One card of a popular row: a movie or series copy from the library. */
data class PopularCard(
    val key: String,
    val title: String,
    val imageUrl: String?,
    val kind: TmdbKind,
    val tmdbId: Long?,
    val lookupName: String,
    val destination: GoodTvKey,
)

internal fun Movie.popularCard() =
    PopularCard("popular:movie:$sourceId:$id", label, posterUrl, TmdbKind.MOVIE, tmdbId, name, MovieDetailKey(sourceId, id))

internal fun Series.popularCard() =
    PopularCard("popular:tv:$sourceId:$id", label, coverUrl, TmdbKind.TV, tmdbId, name, SeriesDetailKey(sourceId, id))

/** Popular Movies and Popular Shows for the Home tab; each null until loaded so the rows don't shuffle under the user. */
class PopularViewModel(
    private val movieRanking: PopularRepository,
    private val showRanking: PopularRepository,
    store: PopularStore,
    private val appScope: CoroutineScope,
) : ViewModel() {
    val movies: StateFlow<List<PopularCard>?> = store.observeMovies(movieRanking.query.listKey, PopularRepository.ROW_SIZE)
        .map { list -> list.map { it.popularCard() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val shows: StateFlow<List<PopularCard>?> = store.observeSeries(showRanking.query.listKey, PopularRepository.ROW_SIZE)
        .map { list -> list.map { it.popularCard() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Home card focused last, so coming back from a title's page lands on it again. */
    val focus = FocusMemory()

    /** Fetches new rankings when the stored ones are a day old. The app scope lets them finish after leaving Home. */
    fun refresh() {
        appScope.launch { movieRanking.refreshIfStale() }
        appScope.launch { showRanking.refreshIfStale() }
    }
}

/**
 * A popular rail; selecting a card opens the title's page. While [initialFocus] is unclaimed (Home
 * just opened, or rebuilt after Back) the remembered card takes it when [comingBack], else this
 * row's first card when [isFirstRow]. A [handOff] (the row above emptied, or rows arrived above
 * where focus started) always goes to the first card of the first row.
 */
@Composable
fun PopularRow(
    titleRes: Int,
    cards: List<PopularCard>,
    focus: FocusMemory,
    initialFocus: MutableState<Boolean>,
    handOff: MutableState<Boolean>,
    isFirstRow: Boolean,
    comingBack: Boolean,
    onOpen: (GoodTvKey) -> Unit,
) {
    val firstKey = cards.firstOrNull()?.key
    val initialTarget = if (comingBack) focus.key else firstKey.takeIf { isFirstRow }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(cards, key = { it.key }) { card ->
                val requester = remember { FocusRequester() }
                val tmdb = rememberTmdbMeta(card.kind, card.tmdbId, card.lookupName, POSTER_SETTLE_MS)
                PosterCard(
                    title = card.title,
                    imageUrl = card.imageUrl,
                    rating = tmdb?.reliableRating,
                    onClick = { onOpen(card.destination) },
                    modifier = Modifier
                        .focusRequester(requester)
                        .onFocusChanged { if (it.isFocused) focus.key = card.key }
                        .onPlaced {
                            val handedHere = handOff.value && isFirstRow && card.key == firstKey
                            if (handedHere || (!initialFocus.value && card.key == initialTarget)) {
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
