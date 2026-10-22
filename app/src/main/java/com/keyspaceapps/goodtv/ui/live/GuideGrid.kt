package com.keyspaceapps.goodtv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.guide.GuideBoard
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.guide.GuideRow
import com.keyspaceapps.goodtv.data.guide.Network

internal val ChannelWidth = 220.dp
private val RowHeight = 44.dp
private val CellGap = 3.dp
private val MinCell = 8.dp
private val LogoTile = Color(0xFFECEEF2)
private val CellColor = Color(0xFF1B2133)
private val ChannelColor = Color(0xFF0D111B)
private val DimColor = Color(0xFF11151F)
private val NowRed = Color(0xFFE11D48)
private val Star = Color(0xFFF5C518)

/** One line of the grid: a section title, a channel, or a network's "More NBC" toggle. */
internal sealed interface GuideLine { val key: String }
internal data class SectionLine(override val key: String, val title: String, val network: Network?) : GuideLine
internal data class ChannelLine(override val key: String, val row: GuideRow) : GuideLine {
    val channelId: String get() = "$key|ch"
}
internal data class MoreLine(override val key: String, val section: String, val count: Int, val open: Boolean) : GuideLine {
    val id: String get() = "$key|more"
}

/** A row may sit in two sections (Favourites and its own), so a line's key carries its section. An open "More" row's stations follow it. */
internal fun linesOf(board: GuideBoard, expanded: Set<String>): List<GuideLine> = buildList {
    for (s in board.sections) {
        add(SectionLine("head|${s.title}", s.title, s.network))
        s.rows.forEach { add(ChannelLine("${s.title}/${it.key}", it)) }
        if (s.network != null && s.more.isNotEmpty()) {
            val open = s.title in expanded
            add(MoreLine("more/${s.title}", s.title, s.more.size, open))
            if (open) s.more.forEach { add(ChannelLine("${s.title}/${it.key}", it)) }
        }
    }
}

internal enum class CellKind { PROGRAMME, LOOP, NO_GUIDE }

/** A programme clipped to the window ([fromMs], [untilMs]); a loop or a row without a guide is one cell spanning it. */
internal data class GuideCell(val id: String, val kind: CellKind, val programme: GuideProgramme?, val fromMs: Long, val untilMs: Long)

internal fun cellsOf(line: ChannelLine, windowStart: Long): List<GuideCell> {
    val end = windowStart + GuideTime.WINDOW_MS
    val row = line.row
    if (row.loopShow != null) return listOf(GuideCell("${line.key}|all", CellKind.LOOP, null, windowStart, end))
    var cursor = windowStart
    // An overlapping programme starts where the one before it ends.
    val cells = row.programmes.filter { it.endMs > windowStart && it.startMs < end }.sortedBy { it.startMs }.mapNotNull { p ->
        val from = maxOf(p.startMs, cursor)
        val until = minOf(p.endMs, end)
        if (until <= from) null else GuideCell("${line.key}|${p.startMs}", CellKind.PROGRAMME, p, from, until).also { cursor = until }
    }
    return cells.ifEmpty { listOf(GuideCell("${line.key}|all", CellKind.NO_GUIDE, null, windowStart, end)) }
}

/** The cell showing [t], else the first after it, else the last. */
internal fun cellAt(cells: List<GuideCell>, t: Long): GuideCell =
    cells.firstOrNull { t >= it.fromMs && t < it.untilMs } ?: cells.firstOrNull { it.fromMs >= t } ?: cells.last()

/** The first row's programme on now (else its first cell), or the first "More" toggle when no section has rows. */
internal fun defaultTarget(lines: List<GuideLine>, windowStart: Long, now: Long): String? {
    val first = lines.firstOrNull { it is ChannelLine || it is MoreLine } ?: return null
    if (first is MoreLine) return first.id
    val cells = cellsOf(first as ChannelLine, windowStart)
    return cellAt(cells, if (now >= windowStart && now < windowStart + GuideTime.WINDOW_MS) now else windowStart).id
}

/** [id] if it is still on the grid (a channel, a cell or a toggle), else null. */
internal fun presentTarget(lines: List<GuideLine>, windowStart: Long, id: String?): String? {
    if (id == null) return null
    val line = lines.firstOrNull { it.key == id.substringBeforeLast('|') } ?: return null
    return when (line) {
        is ChannelLine -> id.takeIf { it == line.channelId || cellsOf(line, windowStart).any { c -> c.id == id } }
        is MoreLine -> id.takeIf { it == line.id }
        is SectionLine -> null
    }
}

/**
 * Every focusable in the grid registers here so the screen can move focus after paging or closing the
 * picker; [initial] is requested once, from `onPlaced` (a `LaunchedEffect` request is lost in tests).
 */
internal class GuideFocus {
    val requesters = HashMap<String, FocusRequester>()
    var initial: String? = null
    var initialDone = false
}

@Composable
private fun Modifier.guideFocusable(focus: GuideFocus, id: String, onFocused: () -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(id) {
        focus.requesters[id] = requester
        onDispose { if (focus.requesters[id] === requester) focus.requesters.remove(id) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) onFocused() }
        .onPlaced {
            if (!focus.initialDone && id == focus.initial) {
                focus.initialDone = true
                requester.requestFocus()
            }
        }
}

/** What the grid tells the screen. [onPage] returns whether it handled the key (it moved the window). */
internal class GuideActions(
    val onFocused: (id: String, row: GuideRow?, cell: GuideCell?) -> Unit,
    val onChannel: (ChannelLine) -> Unit,
    val onMenu: (GuideRow) -> Unit,
    val onCell: (ChannelLine, GuideCell) -> Unit,
    val onMore: (MoreLine) -> Unit,
    val onPage: (forward: Boolean, ChannelLine) -> Boolean,
)

/**
 * Time across, channels down: a header of six half-hour columns, the rows (lazily, each composing only the
 * window's few cells) and the red now line when now is in the window.
 */
@Composable
internal fun GuideGrid(
    lines: List<GuideLine>,
    windowStart: Long,
    now: Long,
    listState: LazyListState,
    focus: GuideFocus,
    actions: GuideActions,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val column = (maxWidth - ChannelWidth) / GuideTime.COLUMNS
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.height(24.dp)) {
                Spacer(Modifier.width(ChannelWidth))
                repeat(GuideTime.COLUMNS) { i ->
                    val start = windowStart + i * GuideTime.SLOT_MS
                    Text(
                        timeLabel(start),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (now >= start && now < start + GuideTime.SLOT_MS) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(column).testTag("guide_time_$i").padding(start = 6.dp),
                    )
                }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(lines, key = { it.key }, contentType = { it::class }) { line ->
                    when (line) {
                        is SectionLine -> SectionTitle(line)
                        is ChannelLine -> ChannelRow(line, windowStart, column, focus, actions)
                        is MoreLine -> MoreRow(line, focus, actions)
                    }
                }
            }
        }
        if (now >= windowStart && now < windowStart + GuideTime.WINDOW_MS) {
            val x = ChannelWidth + column * ((now - windowStart).toFloat() / GuideTime.SLOT_MS)
            Box(Modifier.offset(x = x).padding(top = 20.dp).width(2.dp).fillMaxHeight().background(NowRed.copy(alpha = 0.85f)).testTag("guide_now_line"))
        }
    }
}

@Composable
private fun SectionTitle(line: SectionLine) {
    Text(
        if (line.network != null) stringResource(R.string.guide_network_section, line.title) else line.title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun ChannelRow(line: ChannelLine, windowStart: Long, column: Dp, focus: GuideFocus, actions: GuideActions) {
    val cells = remember(line, windowStart) { cellsOf(line, windowStart) }
    Row(Modifier.height(RowHeight)) {
        ChannelCell(line, focus, actions)
        Spacer(Modifier.width(CellGap))
        Box(Modifier.width(column * GuideTime.COLUMNS).fillMaxHeight()) {
            cells.forEachIndexed { i, cell ->
                key(cell.id) {
                    val left = column * ((cell.fromMs - windowStart).toFloat() / GuideTime.SLOT_MS)
                    val width = (column * ((cell.untilMs - cell.fromMs).toFloat() / GuideTime.SLOT_MS) - CellGap).coerceAtLeast(MinCell)
                    ProgrammeCell(
                        cell,
                        line.row.loopShow,
                        onClick = { actions.onCell(line, cell) },
                        modifier = Modifier.offset(x = left).width(width).fillMaxHeight()
                            .guideFocusable(focus, cell.id) { actions.onFocused(cell.id, line.row, cell) }
                            // Right from the last cell and Left from the first move the window; Compose moves between cells.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    e.key == Key.DirectionRight && i == cells.lastIndex -> actions.onPage(true, line)
                                    e.key == Key.DirectionLeft && i == 0 -> actions.onPage(false, line)
                                    else -> false
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(line: ChannelLine, focus: GuideFocus, actions: GuideActions) {
    val row = line.row
    Surface(
        onClick = { actions.onChannel(line) },
        onLongClick = { actions.onMenu(row) },
        modifier = Modifier.width(ChannelWidth - CellGap).fillMaxHeight()
            .guideFocusable(focus, line.channelId) { actions.onFocused(line.channelId, row, null) }
            // Holding Select can't be done on every remote (or in tests); Menu opens the same menu.
            .onKeyEvent { e -> if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) { actions.onMenu(row); true } else false },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = ChannelColor, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (row.logoUrl != null) {
                // Dark logos stay readable on a light tile.
                Box(Modifier.size(width = 44.dp, height = 26.dp).background(LogoTile, RoundedCornerShape(5.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    AsyncImage(model = row.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(8.dp))
            }
            if (row.favourite) Text("★ ", color = Star, style = MaterialTheme.typography.bodySmall)
            Text(row.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProgrammeCell(cell: GuideCell, loopShow: String?, onClick: () -> Unit, modifier: Modifier) {
    val dim = cell.kind == CellKind.NO_GUIDE
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (dim) DimColor else CellColor,
            contentColor = if (dim) Color(0xFF8A8F9C) else Color(0xFFE8E8EE),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                when (cell.kind) {
                    CellKind.PROGRAMME -> cell.programme!!.title
                    CellKind.LOOP -> stringResource(R.string.guide_all_day, loopShow.orEmpty())
                    CellKind.NO_GUIDE -> stringResource(R.string.guide_no_info)
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (dim) FontWeight.Normal else FontWeight.Medium,
                    fontStyle = if (dim) FontStyle.Italic else FontStyle.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MoreRow(line: MoreLine, focus: GuideFocus, actions: GuideActions) {
    Surface(
        onClick = { actions.onMore(line) },
        modifier = Modifier.fillMaxWidth().height(36.dp).guideFocusable(focus, line.id) { actions.onFocused(line.id, null, null) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = ChannelColor, contentColor = Color(0xFFBBBBBB), focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                if (line.open) stringResource(R.string.guide_fewer, line.section)
                else stringResource(R.string.guide_with_count, stringResource(R.string.guide_more, line.section), line.count),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
