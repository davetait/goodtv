package com.keyspaceapps.goodtv.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.data.tmdb.TmdbPerson
import com.keyspaceapps.goodtv.ui.nav.PersonSearchKey
import com.keyspaceapps.goodtv.ui.tmdb.PeopleRow
import com.keyspaceapps.goodtv.ui.tmdb.TmdbCredit
import com.keyspaceapps.goodtv.ui.tmdb.detailRating
import com.keyspaceapps.goodtv.ui.browse.Formats
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.domain.DisplayTitle
import com.keyspaceapps.goodtv.ui.player.PlayerFormats
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.data.progress.Loaded
import androidx.compose.foundation.layout.Arrangement
import com.keyspaceapps.goodtv.ui.watchlist.WatchListButton

@Composable
fun MovieDetailScreen(key: MovieDetailKey, onPlay: (PlayerKey) -> Unit, onPerson: (PersonSearchKey) -> Unit = {}) {
    val vm = goodTvViewModel(key = "movie-${key.sourceId}-${key.movieId}") { MovieDetailViewModel(it.repository, key, it.tmdb, it.watchProgress, it.people) }
    val state by vm.state.collectAsState()
    val tmdb by vm.tmdb.collectAsState()
    val people by vm.people.collectAsState()
    val progress by vm.progress.collectAsState()
    val returnToPerson = remember { vm.takeOpenedPerson() }

    when (val s = state) {
        MovieDetailViewModel.State.Loading -> Centered(stringResource(R.string.player_loading))
        is MovieDetailViewModel.State.Error -> Centered(s.message)
        is MovieDetailViewModel.State.Ready -> MovieDetailContent(
            s.detail, tmdb, people, progress, onPlay, returnToPerson,
            onPerson = { vm.openedPerson = it.personId; onPerson(it) },
        )
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun MovieDetailContent(
    detail: MovieDetail,
    tmdb: TmdbMeta?,
    people: List<TmdbPerson>,
    progress: Loaded<WatchProgress?>?,
    onPlay: (PlayerKey) -> Unit,
    returnToPerson: Long?,
    onPerson: (PersonSearchKey) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    // Which first button got focus: if the saved spot appears or goes (finished), the new first button takes it.
    var focusedFor by remember { mutableStateOf<Boolean?>(null) }
    // Back from a person's search: their name takes focus instead of the first button.
    val refocusPerson = remember { returnToPerson?.takeIf { id -> people.any { it.id == id } } }
    val movie = detail.movie
    val display = DisplayTitle.parse(movie.name)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        detail.backdropUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.25f, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxSize().padding(48.dp)) {
            Box(Modifier.width(220.dp).aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                movie.posterUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.padding(start = 40.dp).weight(1f)) {
                Text(display.labelWithoutYear, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(
                    Formats.metaLine(
                        listOf(
                            Formats.year(detail.releaseDate), detail.genre, Formats.duration(detail.durationSecs), detailRating(tmdb, movie.rating),
                            if (progress?.value?.finished == true) stringResource(R.string.watched) else null,
                        ),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                // Buttons wait for the saved position, so focus lands on the right one first time.
                if (progress != null) {
                    val saved = progress.value?.takeIf { it.resumable }
                    fun key(startMs: Long) = PlayerKey(movie.sourceId, PlayerKey.KIND_MOVIE, movie.id, movie.containerExtension, display.label, startPositionMs = startMs)
                    val first = Modifier.focusRequester(playFocus).onPlaced {
                        if (focusedFor != (saved != null)) {
                            val opening = focusedFor == null
                            focusedFor = saved != null
                            if (!(opening && refocusPerson != null)) playFocus.requestFocus()
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (saved != null) {
                            Button(onClick = { onPlay(key(saved.positionMs)) }, modifier = first) {
                                Text(stringResource(R.string.resume_from, PlayerFormats.clock(saved.positionMs)))
                            }
                            Button(onClick = { onPlay(key(0)) }) { Text(stringResource(R.string.start_over)) }
                        } else {
                            Button(onClick = { onPlay(key(0)) }, modifier = first) { Text(stringResource(R.string.play)) }
                        }
                        WatchListButton(WatchListRef(movie.sourceId, WatchListKind.MOVIE, movie.id))
                    }
                }
                Spacer(Modifier.height(20.dp))
                (tmdb?.overview ?: detail.plot)?.let { Text(it, maxLines = 8, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(12.dp))
                if (people.isNotEmpty()) {
                    // TMDB's clickable people replace the provider's plain text lines.
                    PeopleRow(people, onPerson = { onPerson(PersonSearchKey(it.id, it.name)) }, focusId = refocusPerson)
                } else {
                    detail.cast?.let { Text("Cast: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    detail.director?.let { Text("Director: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TmdbCredit(tmdb, hasPeople = people.isNotEmpty())
            }
        }
    }
}
