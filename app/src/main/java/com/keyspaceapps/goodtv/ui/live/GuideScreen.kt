package com.keyspaceapps.goodtv.ui.live

import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.guide.GuideFilter
import com.keyspaceapps.goodtv.data.guide.GuideRow
import com.keyspaceapps.goodtv.data.guide.GuideSource
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.label
import com.keyspaceapps.goodtv.ui.browse.EmptyStates
import com.keyspaceapps.goodtv.ui.browse.messageRes
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.watchlist.WatchListDialog
import java.util.Date
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Live tab: an on-screen TV guide. Genre chips above; channels down and half-hour columns across a
 * three-hour window that pages half an hour at a time (never before now); the focused programme's
 * details below. Select plays what's on now (through the feed picker when a channel has several feeds);
 * on a later programme it only shows its details. Holding Select (or Menu) on a channel adds or removes
 * it from Favourites. Back from the player returns to the cell that was played. [source] and [clock]
 * replace the real guide and time in tests.
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

    val current = view
    val board = current?.board
    val lines = remember(board, expanded) { board?.let { linesOf(it, expanded) }.orEmpty() }
    val windowStart = board?.windowStartMs ?: vm.window
    focus.lines = lines
    focus.windowStart = windowStart
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
        if (!focus.initialDone || lines.isEmpty() || picking != null || menuFor != null) return@LaunchedEffect
        val paged = focus.pending
        val id = if (paged != null) {
            if (paged.windowStart != windowStart) return@LaunchedEffect
            focus.pending = null
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

    fun play(c: ChannelCandidate) = latestOnPlay(PlayerKey(c.channel.sourceId, PlayerKey.KIND_LIVE, c.channel.id, null, c.channel.label))

    // Focus goes back to the cell before the picker leaves; removing the focused row first would let
    // Compose move focus elsewhere (and remember that instead).
    fun closePicker(fromId: String) {
        focus.requesters[fromId]?.requestFocus()
        picking = null
    }

    val actions = remember(vm) {
        fun playRow(fromId: String, row: GuideRow) = when (row.feeds.size) {
            0 -> Unit
            1 -> play(row.feeds.single())
            else -> picking = fromId to row
        }

        GuideActions(
            onFocused = { id, row, cell ->
                vm.focus.key = id
                vm.focusTimeMs = cell?.fromMs
                focus.outside = false
                if (row != null) focused.value = GuideFocused(row, cell)
            },
            onChannel = { line -> playRow(line.channelId, line.row) },
            onMenu = { row -> menuFor = row },
            onCell = { line, cell ->
                val p = cell.programme
                val t = clock()
                // A later programme only shows its details, which already follow focus.
                if (p == null || (p.startMs <= t && t < p.endMs)) playRow(cell.id, line.row)
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
            GuideChips(
                selected = filter,
                // Up from the grid, not Compose rehoming focus after the focused cell left the board.
                onFocus = { focus.outside = vm.focus.key?.let { isOnGrid(focus.lines, focus.windowStart, it) } ?: true },
                // Down from the chips goes to what's on now in the first row.
                onDown = { defaultTarget(lines, windowStart, now)?.let { focus.requesters[it] }?.let { it.requestFocus(); true } ?: false },
                onSelect = { f ->
                    vm.select(f)
                    scope.launch {
                        vm.view.first { it?.filter == f }
                        listState.scrollToItem(0)
                    }
                },
            )
            when {
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
                else -> GuideGrid(lines, windowStart, now, listState, focus, actions, timeLabel, Modifier.weight(1f).fillMaxWidth())
            }
            GuideDetails(focused, now, timeLabel, Modifier.padding(top = 8.dp, bottom = 24.dp).height(114.dp))
        }
        picking?.let { (fromId, row) ->
            FeedPicker(row.name, row.feeds, onPick = { closePicker(fromId); play(it) }, onClose = { closePicker(fromId) }, modifier = Modifier.align(Alignment.CenterEnd))
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

/** The genre chips; selecting one (Select, not focus) narrows the grid. */
@OptIn(ExperimentalTvMaterial3Api::class) // FilterChip
@Composable
private fun GuideChips(selected: GuideFilter, onFocus: () -> Unit, onDown: () -> Boolean, onSelect: (GuideFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp)
            .onFocusChanged { if (it.hasFocus) onFocus() }
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
