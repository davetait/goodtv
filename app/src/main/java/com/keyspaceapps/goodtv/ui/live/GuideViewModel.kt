package com.keyspaceapps.goodtv.ui.live

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.guide.GuideBoard
import com.keyspaceapps.goodtv.data.guide.GuideFilter
import com.keyspaceapps.goodtv.data.guide.GuideSearchHit
import com.keyspaceapps.goodtv.data.guide.GuideSource
import com.keyspaceapps.goodtv.ui.browse.BrowseEmptyState
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A board and the filter it was built for; the previous one stays on screen while the next loads. */
data class GuideView(val filter: GuideFilter, val board: GuideBoard)

/** Wait this long after the last keystroke before searching the guide. */
const val GUIDE_SEARCH_DEBOUNCE_MS = 250L

object GuideTime {
    const val SLOT_MS = 30 * 60_000L
    const val COLUMNS = 6
    const val WINDOW_MS = COLUMNS * SLOT_MS
    /** The furthest the window starts: its end stays within the ~48 hours the guide covers. */
    const val MAX_AHEAD_MS = 48 * 3_600_000L - WINDOW_MS
    /** An event slot's assumed length: names give a start, never an end. */
    const val EVENT_MS = 3 * 3_600_000L

    /** [now] floored to the local :00 or :30. */
    fun slotOf(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val offset = zone.getOffset(now)
        return Math.floorDiv(now + offset, SLOT_MS) * SLOT_MS - offset
    }
}

/**
 * The guide's chip, time window, expanded "More NBC" rows and search, and the cell (or result) focused
 * last (kept across the player, like the window). The window starts at the current half-hour, moves
 * half an hour at a time and never starts before the current half-hour: it moves on with the clock.
 * [emptyState] says why a lineup is empty (no sources, everything hidden, nothing yet).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GuideViewModel(
    private val source: GuideSource,
    private val clock: () -> Long,
    emptyState: Flow<BrowseEmptyState> = flowOf(BrowseEmptyState.NOTHING_YET),
) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)
    private val _filter = MutableStateFlow(GuideFilter.ALL)
    val filter: StateFlow<GuideFilter> = _filter
    private val windowStart = MutableStateFlow(GuideTime.slotOf(clock()))
    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    /** Titles of the network sections whose "More" row is open. */
    val expanded: StateFlow<Set<String>> = _expanded

    /** Kept five minutes after the screen leaves (another tab, the player), like the guide's shared lineup. */
    val view: StateFlow<GuideView?> = combine(_filter, windowStart, ::Pair)
        .flatMapLatest { (f, start) -> source.board(f, start).map { GuideView(f, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5 * 60_000L), null)

    /**
     * The clock, re-read every 30 s (and whenever the screen comes back) for the now line and "min left";
     * each reading also moves a window left behind by the clock up to the current half-hour.
     */
    val now: StateFlow<Long> = flow {
        while (true) {
            val t = clock()
            followClock(t)
            emit(t)
            delay(30_000)
        }
    }.stateIn(viewModelScope, sharing, clock())

    private val _query = MutableStateFlow("")
    /** What's typed in the search field; while it isn't blank the results replace the grid. */
    val query: StateFlow<String> = _query

    /** Null while the query is blank, else the guide's hits for it, asked for once typing has paused [GUIDE_SEARCH_DEBOUNCE_MS]. */
    val results: StateFlow<List<GuideSearchHit>?> = _query.debounce { if (it.isBlank()) 0L else GUIDE_SEARCH_DEBOUNCE_MS }
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q -> if (q.isEmpty()) flowOf(null) else source.search(q).map { hits -> hits.distinctBy { it.key } } }
        .stateIn(viewModelScope, sharing, null)

    val emptyState: StateFlow<BrowseEmptyState> = emptyState.stateIn(viewModelScope, sharing, BrowseEmptyState.NOTHING_YET)

    /** The focused cell's id and the time it starts on screen (null on a channel or a "More" row). */
    val focus = FocusMemory()
    @Volatile var focusTimeMs: Long? = null
    /** The search result focused last ([GuideSearchHit.key]). */
    val searchFocus = FocusMemory()

    private fun followClock(t: Long) = windowStart.update { maxOf(it, GuideTime.slotOf(t)) }

    /** A new chip starts again at now, at the top. Events first fetches the provider's latest slots (at most every 30 min). */
    fun select(f: GuideFilter) {
        focus.key = null
        focusTimeMs = null
        windowStart.value = GuideTime.slotOf(clock())
        _filter.value = f
        if (f == GuideFilter.EVENTS) {
            viewModelScope.launch {
                try {
                    source.refreshEventSlots()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The slots stay as they were; the next opening tries again.
                    Log.w("GoodTV", "Event slots not refreshed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    fun setQuery(value: String) { _query.value = value }

    /** Moves the window half an hour; null at either end (never before the current half-hour, never past [GuideTime.MAX_AHEAD_MS]). */
    fun page(forward: Boolean): Long? {
        val t = clock()
        followClock(t)
        val first = GuideTime.slotOf(t)
        val old = windowStart.value
        val next = (old + if (forward) GuideTime.SLOT_MS else -GuideTime.SLOT_MS).coerceIn(first, first + GuideTime.MAX_AHEAD_MS)
        if (next == old) return null
        windowStart.value = next
        return next
    }

    /** The window the screen asked for (the board on screen may still be the previous one). */
    val window: Long get() = windowStart.value

    fun toggleMore(section: String) = _expanded.update { if (section in it) it - section else it + section }

    fun setFavourite(rowKey: String, favourite: Boolean) {
        viewModelScope.launch {
            try {
                source.setFavourite(rowKey, favourite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GoodTV", "Favourite not saved: ${e.javaClass.simpleName}")
            }
        }
    }
}
