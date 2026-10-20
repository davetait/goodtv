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
import com.keyspaceapps.goodtv.domain.MovieDetail
import com.keyspaceapps.goodtv.ui.tmdb.TmdbCredit
import com.keyspaceapps.goodtv.ui.tmdb.detailRating
import com.keyspaceapps.goodtv.ui.browse.Formats
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.domain.DisplayTitle

@Composable
fun MovieDetailScreen(key: MovieDetailKey, onPlay: (PlayerKey) -> Unit) {
    val vm = goodTvViewModel(key = "movie-${key.sourceId}-${key.movieId}") { MovieDetailViewModel(it.repository, key, it.tmdb) }
    val state by vm.state.collectAsState()
    val tmdb by vm.tmdb.collectAsState()

    when (val s = state) {
        MovieDetailViewModel.State.Loading -> Centered(stringResource(R.string.player_loading))
        is MovieDetailViewModel.State.Error -> Centered(s.message)
        is MovieDetailViewModel.State.Ready -> MovieDetailContent(s.detail, tmdb, onPlay)
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun MovieDetailContent(detail: MovieDetail, tmdb: TmdbMeta?, onPlay: (PlayerKey) -> Unit) {
    val playFocus = remember { FocusRequester() }
    var focusRequested by remember { mutableStateOf(false) }
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
                    Formats.metaLine(listOf(Formats.year(detail.releaseDate), detail.genre, Formats.duration(detail.durationSecs), detailRating(tmdb, movie.rating))),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { onPlay(PlayerKey(movie.sourceId, PlayerKey.KIND_MOVIE, movie.id, movie.containerExtension, display.label)) },
                    modifier = Modifier.focusRequester(playFocus).onPlaced {
                        if (!focusRequested) { focusRequested = true; playFocus.requestFocus() }
                    },
                ) { Text(stringResource(R.string.play)) }
                Spacer(Modifier.height(20.dp))
                (tmdb?.overview ?: detail.plot)?.let { Text(it, maxLines = 8, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(12.dp))
                detail.cast?.let { Text("Cast: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                detail.director?.let { Text("Director: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TmdbCredit(tmdb)
            }
        }
    }
}
