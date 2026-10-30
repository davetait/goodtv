package com.keyspaceapps.goodtv.ui.live

import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.guide.GuideFilter
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.guide.GuideRow
import com.keyspaceapps.goodtv.data.guide.GuideSource
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.data.sports.ChannelOrder
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.label
import com.keyspaceapps.goodtv.ui.browse.EmptyStates
import com.keyspaceapps.goodtv.ui.browse.messageRes
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.home.HomeSearchBar
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.watchlist.WatchListDialog
import java.util.Date
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Live tab: an on-screen TV guide. Genre chips and a search field above; channels down and half-hour
 * columns across a three-hour window that pages half an hour at a time (never before now); the focused
 * programme's details below. Select plays what's on now (through the feed picker when a channel has
 * several feeds); on a later programme it only shows its details. Holding Select (or Menu) on a channel
 * adds or removes it from Favourites. Typing a search replaces the grid with results (Select works the
 * same there); clearing it brings the grid back, and Down from the field returns to the cell left. Back
 * from the player returns to the cell (or result) that was played. [source] and [clock] replace the real
 * guide and time in tests.
 *
 * With Live previews on, the details panel doubles and plays the row focus rests on ([LivePreviews]); the
 * guide tells the session where focus is only while it's on screen, and leaves it when it goes or stops.
 * Selecting the copy that's previewing hands its player to full screen ([LivePreviews.park]) instead of
 * opening a second stream; any other copy stops the preview first.
 */
@Composable
fun GuideScreen(onPlay: (PlayerKey) -> Unit, source: GuideSource? = null, clock: () -> Long = System::currentTimeMillis) {
    val vm = goodTvViewModel {
        val empty = combine(it.repository.enabledSourceCount(), it.repository.hiddenCount(ContentKind.LIVE)) { n, hidden -> EmptyStates.forBrowse(n > 0, hidden) }
        GuideViewModel(source ?: it.guide, clock, empty)
    }
    val emptyState by vm.emptyState.collectAsState()
    val view by vm.view.collectAsState()
    val filter by vm.filter.collectAsState()
    val expanded by vm.expanded.collectAsState()
    val now by vm.now.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    // The grid gives way as soon as anything is typed; the results follow once typing pauses.
    val searching = query.isNotBlank()
    // Hoisted so a result takes focus only when coming back from the player, never while typing.
    val resultRequested = remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val searchField = remember { FocusRequester() }
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    val timeLabel: (Long) -> String = remember(format) { { format.format(Date(it)) } }
    val scope = rememberCoroutineScope()
    val focus = remember { GuideFocus() }
    // Read only by the details panel, so moving focus doesn't recompose the grid.
    val focused = remember { mutableStateOf<GuideFocused?>(null) }
    var picking by remember { mutableStateOf<Pair<String, GuideRow>?>(null) }
    var menuFor by remember { mutableStateOf<GuideRow?>(null) }
    val latestOnPlay by rememberUpdatedState(onPlay)
    val previews = remember(context) { context.appGraph().livePreviews }
    // Read since app start, so the panel has its height from the first frame and never jumps. null only in
    // the moments after launch before the first read: the panel is today's until then, and previews are left as they are.
    val previewsSetting by remember(context) { context.appGraph().livePreviewsSetting }.collectAsState()
    val previewsOn = previewsSetting == true
    LaunchedEffect(previewsSetting) { previewsSetting?.let(previews::setEnabled) }
    // The row focus was last reported on, to report again when the guide comes back from the background.
    val previewFocus = remember { mutableStateOf<PreviewTarget?>(null) }
    // Where the panel's picture is in the window, for the grow to full screen. Only a select reads it.
    val pictureBounds = remember { mutableStateOf<Rect?>(null) }
    fun reportPreview(target: PreviewTarget?) {
        previewFocus.value = target
        previews.focus(target)
    }
    // Never report focus while the guide isn't seen, or the session would open a stream nobody watches.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, previews) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> previews.leave()
                Lifecycle.Event.ON_START -> previewFocus.value?.let(previews::focus)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            previews.leave()
        }
    }

    val current = view
    val board = current?.board
    val awaiting = remember(board) { board != null && awaitingProgrammes(board) }
    val lines = remember(board, expanded) { board?.let { linesOf(it, expanded, awaiting) }.orEmpty() }
    val windowStart = board?.windowStartMs ?: vm.window
    // Only while the window has no programmes: a download that's filling other windows says nothing here.
    val status = when {
        board == null || !awaiting -> null
        board.downloading -> stringResource(R.string.guide_downloading)
        else -> stringResource(R.string.guide_loading)
    }
    focus.lines = lines
    focus.windowStart = windowStart
    // A grid that appears when a search is cleared never pulls focus from the field.
    if (searching) focus.initialDone = true
    // Back from the player (or a first open): the remembered cell, or its row at now once it has ended, else the first row's now.
    if (focus.initial == null && lines.isNotEmpty()) {
        val key = vm.focus.key
        focus.initial = refocusTarget(lines, windowStart, now, key?.substringBeforeLast('|'), key, vm.focusTimeMs)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember { (lines.indexOfFirst { it.key == focus.initial?.substringBeforeLast('|') } - 1).coerceAtLeast(0) },
    )

    // A new board can drop the focused cell (a new window, the guide arriving, a favourite removed):
    // focus moves to the cell paging asked for, else the same row at the same time (see refocusTarget).
    LaunchedEffect(lines) {
        val before = focus.before
        focus.before = lines
        if (!focus.initialDone || lines.isEmpty() || picking != null || menuFor != null || searching) return@LaunchedEffect
        val paged = focus.pending
        val id = if (paged != null) {
            if (paged.windowStart != windowStart) return@LaunchedEffect
            focus.pending = null
            // Kept for when this board is still loading (see onFocused).
            vm.focusTimeMs = paged.timeMs
            refocusTarget(lines, windowStart, now, paged.lineKey, null, paged.timeMs, before)
        } else {
            val key = vm.focus.key
            if (key == null || focus.outside || isOnGrid(lines, windowStart, key)) return@LaunchedEffect
            refocusTarget(lines, windowStart, now, key.substringBeforeLast('|'), key, vm.focusTimeMs, before)
        } ?: return@LaunchedEffect
        repeat(FOCUS_FRAMES) {
            withFrameNanos { }
            focus.requesters[id]?.let { it.requestFocus(); return@LaunchedEffect }
        }
    }

    // Called only on an actual select (after the picker's choice): taking stops a preview of any other copy.
    fun play(c: ChannelCandidate, row: GuideRow) {
        val target = PreviewTarget(row.key, c.channel.sourceId, c.channel.id, c.channel.label)
        // The previewing copy: its player goes full screen as it is, growing out of the panel. Any other copy:
        // the preview stops (one stream at a time) and the player opens its own.
        val handle = previews.takeFor(target)
        if (handle?.player != null) previews.park(target, handle, pictureBounds.value) else handle?.release()
        latestOnPlay(PlayerKey(c.channel.sourceId, PlayerKey.KIND_LIVE, c.channel.id, null, c.channel.label))
    }

    // Focus goes back to the cell before the picker leaves; removing the focused row first would let
    // Compose move focus elsewhere (and remember that instead).
    fun closePicker(fromId: String) {
        focus.requesters[fromId]?.requestFocus()
        picking = null
    }

    fun playRow(fromId: String, row: GuideRow) = when (row.feeds.size) {
        0 -> Unit
        1 -> play(row.feeds.single(), row)
        else -> picking = fromId to row
    }

    // Select on what's on now (or a row without a guide) plays it; a later programme only shows its details, which follow focus.
    // The same reading the screen shows ("On now", the now line), so what looks on now plays.
    fun playIfOnNow(fromId: String, row: GuideRow, p: GuideProgramme?) {
        val t = vm.now.value
        if (p == null || (p.startMs <= t && t < p.endMs)) playRow(fromId, row)
    }

    val actions = remember(vm) {
        GuideActions(
            onFocused = { id, row, cell ->
                vm.focus.key = id
                // A loading cell spans the window: keep the time focus came for, where it lands once the programmes arrive.
                if (cell?.kind != CellKind.LOADING) vm.focusTimeMs = cell?.fromMs
                focus.outside = false
                if (row != null) focused.value = GuideFocused(row, cell)
                reportPreview(row?.let { previewTarget(it, cell, vm.now.value) })
            },
            onChannel = { line -> playRow(line.channelId, line.row) },
            onMenu = { row -> menuFor = row },
            // An event slot that isn't on (or about to be) only shows when it starts.
            onCell = { line, cell ->
                if (cell.startsMs == null || GuideTime.eventPlayable(cell.startsMs, vm.now.value)) playIfOnNow(cell.id, line.row, cell.programme)
            },
            onMore = { line -> vm.toggleMore(line.section) },
            onPage = { forward, line ->
                val old = vm.window
                val next = vm.page(forward)
                if (next == null) {
                    // At the end Right does nothing; at now, Left goes on to the channel.
                    forward
                } else {
                    // Forward lands on what follows the old window; back on what precedes it.
                    focus.pending = PendingFocus(line.key, if (forward) old + GuideTime.WINDOW_MS else old - 1, next)
                    true
                }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
            fun focusOn(id: String?): Boolean {
                val requester = id?.let { focus.requesters[it] } ?: return false
                keyboard?.hide()
                requester.requestFocus()
                return true
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    // Up from the grid, not Compose rehoming focus after the focused cell left the board.
                    .onFocusChanged {
                        if (it.hasFocus) {
                            focus.outside = vm.focus.key?.let { k -> isOnGrid(focus.lines, focus.windowStart, k) } ?: true
                            // The chips or the search field: the preview stops at once.
                            reportPreview(null)
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GuideChips(
                    selected = filter,
                    // Down from the chips goes to the first result, or to what's on now in the first row.
                    onDown = { focusOn(if (searching) results?.firstOrNull()?.let(::searchId) else defaultTarget(lines, windowStart, now)) },
                    onSelect = { f ->
                        // A chip is a way back from a search to the grid.
                        vm.setQuery("")
                        vm.select(f)
                        scope.launch {
                            vm.view.first { it?.filter == f }
                            listState.scrollToItem(0)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                // Down from the field goes to the first result, or back to the cell focused before the search.
                Box(
                    Modifier.width(SearchWidth).onPreviewKeyEvent { e ->
                        e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown && focusOn(
                            if (searching) results?.firstOrNull()?.let(::searchId)
                            else vm.focus.key?.let { refocusTarget(lines, windowStart, now, it.substringBeforeLast('|'), it, vm.focusTimeMs) }
                                ?: defaultTarget(lines, windowStart, now),
                        )
                    },
                ) {
                    HomeSearchBar(query = query, onQueryChange = vm::setQuery, hint = stringResource(R.string.guide_search_hint), modifier = Modifier.focusRequester(searchField).testTag("guide_search"))
                }
            }
            val hits = results
            when {
                searching -> when {
                    hits == null -> Spacer(Modifier.weight(1f))
                    hits.isEmpty() -> Message(stringResource(R.string.search_no_results, query.trim()), Modifier.weight(1f))
                    else -> GuideSearchResults(
                        hits, now, timeLabel, focus, vm.searchFocus, resultRequested,
                        onFocused = {
                            focused.value = focusedOf(it)
                            reportPreview(null)
                        },
                        onSelect = { playIfOnNow(searchId(it), it.row, it.programme) },
                        onUpFromFirst = { searchField.requestFocus(); true },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                current == null -> Message(stringResource(R.string.guide_loading), Modifier.weight(1f))
                lines.none { it is ChannelLine || it is MoreLine } -> Message(
                    stringResource(
                        when (current.filter) {
                            GuideFilter.FAVOURITES -> R.string.guide_no_favourites
                            GuideFilter.ALL -> emptyState.messageRes
                            else -> R.string.guide_empty
                        },
                    ),
                    Modifier.weight(1f),
                )
                else -> GuideGrid(lines, windowStart, now, listState, focus, actions, timeLabel, status, Modifier.weight(1f).fillMaxWidth())
            }
            PreviewedDetails(
                focused, now, timeLabel, previewsOn, previews,
                Modifier.padding(top = 8.dp, bottom = 24.dp).height(if (previewsOn) 228.dp else 114.dp).testTag("guide_details"),
                onPictureBounds = { pictureBounds.value = it },
            )
        }
        picking?.let { (fromId, row) ->
            FeedPicker(row.name, row.feeds, onPick = { closePicker(fromId); play(it, row) }, onClose = { closePicker(fromId) }, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }

    menuFor?.let { row ->
        WatchListDialog(
            title = row.name,
            action = stringResource(if (row.favourite) R.string.guide_remove_favourite else R.string.guide_add_favourite),
            onAction = {
                menuFor = null
                vm.setFavourite(row.key, !row.favourite)
            },
            onDismiss = { menuFor = null },
        )
    }
}

private const val FOCUS_FRAMES = 30

/** The details panel with the session's preview, read here so a preview starting or stopping redraws only the panel. */
@Composable
private fun PreviewedDetails(
    focused: State<GuideFocused?>,
    now: Long,
    timeLabel: (Long) -> String,
    previewsOn: Boolean,
    previews: LivePreviews,
    modifier: Modifier,
    onPictureBounds: (Rect) -> Unit,
) {
    val preview by previews.current.collectAsState()
    GuideDetails(focused, now, timeLabel, previewsOn, preview, modifier, onPictureBounds)
}

/**
 * What a preview of [row] plays with [cell] focused (null: the channel cell): the first copy in the feed
 * picker's order. None for a row without feeds, or an event slot that isn't on (or about to be).
 */
internal fun previewTarget(row: GuideRow, cell: GuideCell?, now: Long): PreviewTarget? {
    if (cell?.startsMs != null && !GuideTime.eventPlayable(cell.startsMs, now)) return null
    val first = ChannelOrder.sort(row.feeds).firstOrNull()?.channel ?: return null
    return PreviewTarget(row.key, first.sourceId, first.id, first.label)
}

private val Chips = listOf(
    GuideFilter.ALL to R.string.guide_chip_all,
    GuideFilter.FAVOURITES to R.string.guide_chip_favourites,
    GuideFilter.NETWORK to R.string.guide_chip_network,
    GuideFilter.NEWS to R.string.guide_chip_news,
    GuideFilter.SPORTS to R.string.guide_chip_sports,
    GuideFilter.ENTERTAINMENT to R.string.guide_chip_entertainment,
    GuideFilter.COMEDY to R.string.guide_chip_comedy,
    GuideFilter.MOVIES to R.string.guide_chip_movies,
    GuideFilter.KIDS to R.string.guide_chip_kids,
    GuideFilter.DOCUMENTARY to R.string.guide_chip_documentary,
    GuideFilter.EVENTS to R.string.guide_chip_events,
)

private val SearchWidth = 340.dp

/** The genre chips; selecting one (Select, not focus) narrows the grid. */
@OptIn(ExperimentalTvMaterial3Api::class) // FilterChip
@Composable
private fun GuideChips(selected: GuideFilter, onDown: () -> Boolean, onSelect: (GuideFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState())
            .onPreviewKeyEvent { e -> e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown && onDown() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chips.forEach { (f, label) ->
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
