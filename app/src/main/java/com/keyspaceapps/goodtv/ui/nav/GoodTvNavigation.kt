package com.keyspaceapps.goodtv.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.CategoryGridScreen
import com.keyspaceapps.goodtv.ui.categories.CategoriesScreen
import com.keyspaceapps.goodtv.ui.home.HomeScreen
import com.keyspaceapps.goodtv.ui.movies.MovieDetailScreen
import com.keyspaceapps.goodtv.ui.series.SeriesDetailScreen
import com.keyspaceapps.goodtv.ui.player.PlayerScreen
import com.keyspaceapps.goodtv.ui.sources.EditSourceScreen
import kotlinx.coroutines.launch

@Composable
fun GoodTvNavigation() {
    val backStack = rememberNavBackStack(HomeKey)
    val pop: () -> Unit = { backStack.removeLastOrNull() }

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
                LaunchedEffect(key) { item = graph.repository.playable(key.sourceId, key.toRef(), key.title) }
                val current = item
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
                } else {
                    val ref = key.watchRef()
                    PlayerScreen(
                        item = current,
                        onBack = pop,
                        startPositionMs = key.startPositionMs,
                        // App scope, so the save on leaving the player outlives the player screen.
                        onProgress = ref?.let { r -> { pos, dur -> graph.appScope.launch { graph.watchProgress.record(r, pos, dur, key.playbackInfo()) } } },
                    )
                }
            }
            entry<MovieDetailKey> { key -> MovieDetailScreen(key = key, onPlay = { backStack.add(it) }) }
            entry<SeriesDetailKey> { key -> SeriesDetailScreen(key = key, onPlay = { backStack.add(it) }) }
            entry<CategoryGridKey> { key ->
                CategoryGridScreen(key = key, onItem = { item ->
                    backStack.add(if (key.kind == ContentKind.SERIES) SeriesDetailKey(item.sourceId, item.id) else MovieDetailKey(item.sourceId, item.id))
                })
            }
        },
    )
}
