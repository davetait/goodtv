package com.keyspaceapps.goodtv.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.subtitles.OnlineSubtitleSource
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.CategoryGridScreen
import com.keyspaceapps.goodtv.ui.categories.CategoriesScreen
import com.keyspaceapps.goodtv.ui.home.HomeScreen
import com.keyspaceapps.goodtv.ui.home.PersonSearchScreen
import com.keyspaceapps.goodtv.ui.movies.MovieDetailScreen
import com.keyspaceapps.goodtv.ui.series.NextEpisode
import com.keyspaceapps.goodtv.ui.series.NextEpisodes
import com.keyspaceapps.goodtv.ui.series.SeriesDetailScreen
import com.keyspaceapps.goodtv.ui.player.PlayerScreen
import com.keyspaceapps.goodtv.ui.sources.EditSourceScreen
import com.keyspaceapps.goodtv.ui.watchlist.WatchListHost
import kotlinx.coroutines.launch

@Composable
fun GoodTvNavigation() {
    val backStack = rememberNavBackStack(HomeKey)
    val pop: () -> Unit = { backStack.removeLastOrNull() }

    // Posters everywhere get the watch list's bookmark and hold-Select menu.
    WatchListHost(LocalContext.current.appGraph().watchList) {
        NavDisplay(
            backStack = backStack,
            onBack = pop,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<HomeKey> { HomeScreen(onNavigate = { backStack.add(it) }) }
                entry<EditSourceKey> { key -> EditSourceScreen(sourceId = key.sourceId, onDone = pop) }
                entry<CategoriesKey> { key -> CategoriesScreen(key = key) }
                entry<PlayerKey> { key ->
                    val graph = LocalContext.current.appGraph()
                    var item by remember(key) { mutableStateOf<PlayableItem?>(null) }
                    var next by remember(key) { mutableStateOf<NextEpisode?>(null) }
                    LaunchedEffect(key) { item = graph.repository.playable(key.sourceId, key.toRef(), key.title) }
                    LaunchedEffect(key) { next = NextEpisodes.after(key, graph.repository, graph.watchProgress) }
                    var online by remember(key) { mutableStateOf<OnlineSubtitleSource?>(null) }
                    LaunchedEffect(key) {
                        online = graph.onlineSubtitles?.let { subtitles -> SubtitleQueries.forKey(key, graph.repository)?.let(subtitles::forQuery) }
                    }
                    val current = item
                    if (current == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
                    } else {
                        val ref = key.watchRef()
                        // Guide downloads wait while the player is on screen.
                        DisposableEffect(Unit) {
                            graph.playback.enter()
                            onDispose { graph.playback.exit() }
                        }
                        PlayerScreen(
                            item = current,
                            onBack = pop,
                            startPositionMs = key.startPositionMs,
                            // App scope, so the save on leaving the player outlives the player screen.
                            onProgress = ref?.let { r -> { pos, dur -> graph.appScope.launch { graph.watchProgress.record(r, pos, dur, key.playbackInfo()) } } },
                            upNext = next?.prompt,
                            // The next episode replaces this one, so Back still goes where this one came from.
                            onPlayNext = { next?.key?.let { k -> backStack.removeLastOrNull(); backStack.add(k) } },
                            onlineSubtitles = online,
                        )
                    }
                }
                entry<MovieDetailKey> { key -> MovieDetailScreen(key = key, onPlay = { backStack.add(it) }, onPerson = { backStack.add(it) }) }
                entry<SeriesDetailKey> { key -> SeriesDetailScreen(key = key, onPlay = { backStack.add(it) }, onPerson = { backStack.add(it) }) }
                entry<PersonSearchKey> { key -> PersonSearchScreen(key = key, onNavigate = { backStack.add(it) }) }
                entry<CategoryGridKey> { key ->
                    CategoryGridScreen(key = key, onItem = { item ->
                        backStack.add(if (key.kind == ContentKind.SERIES) SeriesDetailKey(item.sourceId, item.id) else MovieDetailKey(item.sourceId, item.id))
                    })
                }
            },
        )
    }
}
