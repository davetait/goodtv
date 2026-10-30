package com.keyspaceapps.goodtv.ui.series

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.tmdb.TmdbMeta
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.domain.Episode
import com.keyspaceapps.goodtv.data.tmdb.TmdbPerson
import com.keyspaceapps.goodtv.ui.nav.PersonSearchKey
import com.keyspaceapps.goodtv.ui.tmdb.PeopleRow
import com.keyspaceapps.goodtv.ui.tmdb.TmdbCredit
import com.keyspaceapps.goodtv.ui.tmdb.detailRating
import com.keyspaceapps.goodtv.domain.SeriesDetail
import com.keyspaceapps.goodtv.ui.browse.Formats
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.nav.SeriesDetailKey
import com.keyspaceapps.goodtv.domain.DisplayTitle
import com.keyspaceapps.goodtv.ui.player.PlayerFormats
import com.keyspaceapps.goodtv.data.progress.WatchProgress
import com.keyspaceapps.goodtv.ui.browse.bleed
import com.keyspaceapps.goodtv.ui.browse.rowBleedPadding
import com.keyspaceapps.goodtv.ui.watchlist.WatchListButton
import kotlin.math.roundToInt

@Composable
fun SeriesDetailScreen(key: SeriesDetailKey, onPlay: (PlayerKey) -> Unit, onPerson: (PersonSearchKey) -> Unit = {}) {
    val vm = goodTvViewModel(key = "series-${key.sourceId}-${key.seriesId}") { SeriesDetailViewModel(it.repository, key, it.tmdb, it.watchProgress, it.people) }
    val state by vm.state.collectAsState()
    val tmdb by vm.tmdb.collectAsState()
    val people by vm.people.collectAsState()
    val progress by vm.progress.collectAsState()
    val returnToPerson = remember { vm.takeOpenedPerson() }

    when (val s = state) {
        SeriesDetailViewModel.State.Loading -> Centered(stringResource(R.string.player_loading))
        is SeriesDetailViewModel.State.Error -> Centered(s.message)
        is SeriesDetailViewModel.State.Ready -> SeriesDetailContent(
            s, tmdb, people, progress, onSelectSeason = vm::selectSeason, onPlay = onPlay, returnToPerson = returnToPerson,
            onPerson = { vm.openedPerson = it.personId; onPerson(it) },
        )
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/**
 * "E3 · Pilot · 45m · Resume from 12:40"; the episode title loses the series name and "S01E03" panels
 * prefix it with, and [status] (resume point or "Watched") goes last.
 */
fun episodeLine(e: Episode, seriesTitle: String, status: String? = null): String =
    Formats.metaLine(listOf("E${e.number}", DisplayTitle.episodeTitle(e.title, seriesTitle), Formats.duration(e.durationSecs), status))

fun episodePlayerKey(detail: SeriesDetail, e: Episode, startPositionMs: Long = 0) = PlayerKey(
    e.sourceId, PlayerKey.KIND_EPISODE, e.id, e.containerExtension,
    DisplayTitle.parse(detail.series.name).let { d ->
        listOfNotNull(d.label, "S${e.season}E${e.number}", DisplayTitle.episodeTitle(e.title, d.title)).joinToString(" · ")
    },
    startPositionMs = startPositionMs,
    seriesId = detail.series.id,
    season = e.season,
    episode = e.number,
)

@Composable
private fun SeriesDetailContent(
    state: SeriesDetailViewModel.State.Ready,
    tmdb: TmdbMeta?,
    people: List<TmdbPerson>,
    progress: List<WatchProgress>?,
    onSelectSeason: (Int) -> Unit,
    onPlay: (PlayerKey) -> Unit,
    returnToPerson: Long?,
    onPerson: (PersonSearchKey) -> Unit,
) {
    val detail = state.detail
    // Back from a person's search: their name takes focus, so the resume button and first episode don't.
    val refocusPerson = remember { returnToPerson?.takeIf { id -> people.any { it.id == id } } }
    val display = DisplayTitle.parse(detail.series.name)
    val episodes = state.episodes()
    val firstEpisodeFocus = remember { FocusRequester() }
    val resumeFocus = remember { FocusRequester() }
    val watchListFocus = remember { FocusRequester() }
    // While the seasons or an episode have focus, the list takes the space of the people and buttons.
    var listFocused by remember { mutableStateOf(false) }
    var focusedForSeason by remember { mutableIntStateOf(if (refocusPerson != null) state.selectedSeason else Int.MIN_VALUE) }
    val byEpisode = progress.orEmpty().associateBy { it.ref.itemId }
    val resume = progress?.let { SeriesResumes.of(detail.episodes, it) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        detail.backdropUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.25f, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxSize().padding(48.dp)) {
            Column(Modifier.width(220.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    detail.series.coverUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
            Column(Modifier.padding(start = 40.dp).weight(1f)) {
                Text(display.labelWithoutYear, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(
                    Formats.metaLine(listOf(Formats.year(detail.releaseDate), detail.genre, detailRating(tmdb, detail.series.rating))),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (tmdb?.overview ?: detail.plot ?: detail.series.plot)?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                // Folded to nothing (but still there to take focus) while the list is in use; UP from the seasons
                // goes to its first button, and it opens again.
                // Clipped only while folding or folded: open, a focused button grows past its edges and mustn't be cut.
                val open by animateFloatAsState(if (listFocused) 0f else 1f, label = "fold")
                Column(
                    // The clip goes outside the fold, so it trims to the folded height, not the full one.
                    (if (open < 1f) Modifier.clipToBounds() else Modifier)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val shown = (placeable.height * open).roundToInt()
                            // Slides up as it folds, so its buttons never sit between the episodes and the seasons,
                            // where the D-pad would find them on the way up.
                            layout(placeable.width, shown) { placeable.place(0, shown - placeable.height) }
                        },
                ) {
                    TmdbCredit(tmdb, hasPeople = people.isNotEmpty())
                    PeopleRow(people, onPerson = { onPerson(PersonSearchKey(it.id, it.name)) }, Modifier.padding(top = 16.dp), focusId = refocusPerson)
                    val watchRef = WatchListRef(detail.series.sourceId, WatchListKind.SERIES, detail.series.id)
                    if (resume != null) {
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val e = resume.episode
                            Button(
                                onClick = { onPlay(episodePlayerKey(detail, e, resume.startMs)) },
                                modifier = Modifier.focusRequester(resumeFocus).onPlaced {
                                    // The resume button, not the first episode, takes focus when the page opens.
                                    if (focusedForSeason == Int.MIN_VALUE) { focusedForSeason = state.selectedSeason; resumeFocus.requestFocus() }
                                },
                            ) {
                                Text(
                                    if (resume.resume) stringResource(R.string.resume_episode, e.season, e.number, PlayerFormats.clock(resume.startMs))
                                    else stringResource(R.string.play_episode, e.season, e.number),
                                )
                            }
                            if (resume.resume) Button(onClick = { onPlay(episodePlayerKey(detail, e, 0)) }) { Text(stringResource(R.string.start_over)) }
                            WatchListButton(watchRef)
                        }
                    } else {
                        Spacer(Modifier.height(20.dp))
                        WatchListButton(watchRef, Modifier.focusRequester(watchListFocus))
                }
                }
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxSize().onFocusChanged { listFocused = it.hasFocus }) {
                    LazyRow(contentPadding = rowBleedPadding, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.bleed().testTag("season_row")) {
                        items(state.seasonNumbers(), key = { it }) { n ->
                            val name = detail.seasons.firstOrNull { it.number == n }?.name ?: "Season $n"
                            // Click, not focus, switches season so the D-pad can pass over chips without changing the list.
                            val selected = n == state.selectedSeason
                            Button(
                                onClick = { onSelectSeason(n) },
                                modifier = Modifier.focusProperties { up = if (resume != null) resumeFocus else watchListFocus },
                                colors = if (selected) {
                                    ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    ButtonDefaults.colors()
                                },
                            ) { Text(name) }
                        }
                }
                Spacer(Modifier.height(16.dp))
                // Wait for progress so each row shows its resume point and focus goes to the right place.
                if (progress != null) LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(episodes, key = { _, e -> e.id }) { index, e ->
                        val focusModifier = if (index == 0) {
                            Modifier.focusRequester(firstEpisodeFocus).onPlaced {
                                if (focusedForSeason != state.selectedSeason && !(focusedForSeason == Int.MIN_VALUE && resume != null)) {
                                    focusedForSeason = state.selectedSeason
                                    firstEpisodeFocus.requestFocus()
                                }
                            }
                        } else {
                            Modifier
                        }
                        val saved = byEpisode[e.id]
                        val status = when {
                            saved?.resumable == true -> stringResource(R.string.resume_from, PlayerFormats.clock(saved.positionMs))
                            saved?.finished == true -> stringResource(R.string.watched)
                            else -> null
                        }
                        ListItem(
                            selected = false,
                            // A part-watched episode carries on; anything else starts from the beginning.
                            onClick = { onPlay(episodePlayerKey(detail, e, saved?.takeIf { it.resumable }?.positionMs ?: 0)) },
                            headlineContent = { Text(episodeLine(e, display.title, status)) },
                            supportingContent = e.plot?.let { p -> { Text(p, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            modifier = focusModifier.padding(vertical = 2.dp),
                        )
                    }
                }
                }
            }
        }
    }
}
