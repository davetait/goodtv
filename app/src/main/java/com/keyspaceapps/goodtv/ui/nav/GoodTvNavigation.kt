package com.keyspaceapps.goodtv.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.subtitles.OnlineSubtitleSource
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.CategoryGridScreen
import com.keyspaceapps.goodtv.ui.categories.CategoriesScreen
import com.keyspaceapps.goodtv.ui.home.HomeScreen
import com.keyspaceapps.goodtv.ui.home.PersonSearchScreen
import com.keyspaceapps.goodtv.ui.live.LivePreviews
import com.keyspaceapps.goodtv.ui.movies.MovieDetailScreen
import com.keyspaceapps.goodtv.ui.series.NextEpisode
import com.keyspaceapps.goodtv.ui.settings.IntegrationsScreen
import com.keyspaceapps.goodtv.ui.settings.LiveTvSettingsScreen
import com.keyspaceapps.goodtv.ui.series.NextEpisodes
import com.keyspaceapps.goodtv.ui.series.SeriesDetailScreen
import com.keyspaceapps.goodtv.ui.player.PlayerScreen
import com.keyspaceapps.goodtv.ui.sources.EditSourceScreen
import com.keyspaceapps.goodtv.ui.sources.SourcesScreen
import com.keyspaceapps.goodtv.ui.watchlist.WatchListHost
import kotlinx.coroutines.launch

@Composable
fun GoodTvNavigation() {
    val backStack = rememberNavBackStack(HomeKey)
    val pop: () -> Unit = { backStack.removeLastOrNull() }
    val stackKey: (Any) -> Any = { stackContentKey(backStack, it) }

    // Posters everywhere get the watch list's bookmark and hold-Select menu.
    WatchListHost(LocalContext.current.appGraph().watchList) {
        val previews = LocalContext.current.appGraph().livePreviews
        NavDisplay(
            backStack = backStack,
            onBack = pop,
            transitionSpec = handOffTransition(previews, defaultTransitionSpec()),
            popTransitionSpec = handOffTransition(previews, defaultPopTransitionSpec()),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<HomeKey> { HomeScreen(onNavigate = { backStack.add(it) }) }
                entry<EditSourceKey>(stackKey) { key -> EditSourceScreen(sourceId = key.sourceId, onDone = pop) }
                entry<CategoriesKey>(stackKey) { key -> CategoriesScreen(key = key) }
                // A full screen now, not a tab: its own top padding replaces the tab row's.
                entry<SourcesKey>(stackKey) { Box(Modifier.padding(top = 24.dp)) { SourcesScreen(onNavigate = { backStack.add(it) }) } }
                entry<IntegrationsKey>(stackKey) { IntegrationsScreen() }
                entry<LiveTvSettingsKey>(stackKey) { LiveTvSettingsScreen() }
                entry<PlayerKey>(stackKey) { key ->
                    // The next episode replaces this one, so Back still goes where this one came from.
                    PlayerEntry(key, onBack = pop, onPlayNext = { k -> backStack.removeLastOrNull(); backStack.add(k) })
                }
                entry<MovieDetailKey>(stackKey) { key -> MovieDetailScreen(key = key, onPlay = { backStack.add(it) }, onPerson = { backStack.add(it) }) }
                entry<SeriesDetailKey>(stackKey) { key -> SeriesDetailScreen(key = key, onPlay = { backStack.add(it) }, onPerson = { backStack.add(it) }) }
                entry<PersonSearchKey>(stackKey) { key -> PersonSearchScreen(key = key, onNavigate = { backStack.add(it) }) }
                entry<CategoryGridKey>(stackKey) { key ->
                    CategoryGridScreen(key = key, onItem = { item ->
                        backStack.add(if (key.kind == ContentKind.SERIES) SeriesDetailKey(item.sourceId, item.id) else MovieDetailKey(item.sourceId, item.id))
                    })
                }
            },
        )
    }
}

/**
 * Pushing or popping a player that has a guide preview's player (see [PlayerEntry]): the screen underneath stays
 * drawn, untouched, while the player grows out of the panel or shrinks back into it; otherwise [fallback].
 */
internal fun <T : Any> handOffTransition(
    previews: LivePreviews,
    fallback: AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform,
): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    if (previews.handingOff) EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished else fallback()
}

/** How long the picture takes to grow from the guide's panel to full screen, and to shrink back. */
internal const val GROW_MS = 450

/**
 * A [PlayerKey]: resolves the item, holds the guide's downloads back while playing, and plays it. A live channel
 * whose preview the guide handed over ([LivePreviews.park]) adopts that player, so the stream carries on without a
 * reconnect, grows out of the panel's bounds, and goes back to the guide on closing. Every player covers the
 * preview session while it's up, so the guide underneath never starts a second stream. [resolve] replaces the
 * library in tests.
 */
@Composable
internal fun PlayerEntry(
    key: PlayerKey,
    onBack: () -> Unit,
    onPlayNext: (PlayerKey) -> Unit,
    resolve: (suspend (PlayerKey) -> PlayableItem)? = null,
) {
    val graph = LocalContext.current.appGraph()
    val previews = graph.livePreviews
    // Taken while composing, so the first frame already has the stream; a composition that never commits gives it back.
    val claim = remember(key) {
        if (key.refKind == PlayerKey.KIND_LIVE) HandOffClaim.take(previews, key.sourceId, key.refId) else HandOffClaim(previews, null)
    }
    val handOff = claim.handOff
    DisposableEffect(previews) {
        previews.cover()
        onDispose { previews.uncover() }
    }
    // Back to the guide once, whether the player screen closes it or never opened (Back while resolving).
    val giveBack: () -> Unit = claim::giveBack
    DisposableEffect(claim) { onDispose { claim.giveBack() } }
    val growth: (() -> Float)? = if (handOff?.from != null) {
        val transition = LocalNavAnimatedContentScope.current.transition
        val grow = transition.animateFloat(transitionSpec = { tween(GROW_MS) }, label = "grow") { if (it == EnterExitState.Visible) 1f else 0f }
        remember(grow) { { grow.value } }
    } else {
        null
    }

    val adopted = handOff?.handle?.player
    // A handed-over player already has its stream, so the screen is there from the first frame, under the growing picture.
    var item by remember(key) {
        mutableStateOf(adopted?.let { PlayableItem(key.title, it.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty(), isLive = true) })
    }
    var next by remember(key) { mutableStateOf<NextEpisode?>(null) }
    LaunchedEffect(key) { if (item == null) item = resolve?.invoke(key) ?: graph.repository.playable(key.sourceId, key.toRef(), key.title) }
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
            onBack = onBack,
            startPositionMs = key.startPositionMs,
            // App scope, so the save on leaving the player outlives the player screen.
            onProgress = ref?.let { r -> { pos, dur -> graph.appScope.launch { graph.watchProgress.record(r, pos, dur, key.playbackInfo()) } } },
            upNext = next?.prompt,
            onPlayNext = { next?.key?.let(onPlayNext) },
            onlineSubtitles = online,
            adopted = adopted,
            onAdoptedClosed = { giveBack() },
            growFrom = handOff?.from.takeIf { growth != null },
            growth = growth ?: { 1f },
        )
    }
}

/**
 * A [PlayerEntry]'s hold on the guide's parked preview ([LivePreviews.takeParked]), remembered with it. [giveBack]
 * returns it to the guide once, however often it's called. Taken during composition, so if that composition is
 * abandoned (never committed, so no effect ever runs to close it) it goes back then, rather than playing on unowned.
 */
internal class HandOffClaim(private val previews: LivePreviews, val handOff: LivePreviews.HandOff?) : RememberObserver {
    private var given = false

    fun giveBack() {
        val h = handOff ?: return
        if (given) return
        given = true
        previews.giveBack(h.target, h.handle)
    }

    override fun onRemembered() = Unit

    // Committed: the player screen closing it, or the entry's dispose, gives it back.
    override fun onForgotten() = Unit

    override fun onAbandoned() = giveBack()

    companion object {
        fun take(previews: LivePreviews, sourceId: Long, channelId: String) = HandOffClaim(previews, previews.takeParked(sourceId, channelId))
    }
}

/**
 * The same page can be on the back stack twice, a screen apart (Top Gun → Tom Cruise → Top Gun).
 * Equal keys would share one content key, and with it their saved state and view models, so each
 * later copy gets its own: the key plus how many equal keys sit below it. An entry's count
 * never changes while it is on the stack (only the top is pushed or popped), and a popped entry
 * still counts the same copies below it.
 */
internal fun stackContentKey(backStack: List<Any>, key: Any): Any {
    val below = backStack.takeWhile { it !== key }.count { it == key }
    return "${key.javaClass.name}:$key#$below"
}
