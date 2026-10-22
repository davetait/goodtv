package com.keyspaceapps.goodtv.ui.live

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.guide.GuideBoard
import com.keyspaceapps.goodtv.data.guide.GuideFilter
import com.keyspaceapps.goodtv.data.guide.GuideSource
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A board and the filter it was built for; the previous one stays on screen while the next loads. */
data class GuideView(val filter: GuideFilter, val board: GuideBoard)

object GuideTime {
    const val SLOT_MS = 30 * 60_000L
    const val COLUMNS = 6
    const val WINDOW_MS = COLUMNS * SLOT_MS
    /** The furthest the window starts: its end stays within the ~48 hours the guide covers. */
    const val MAX_AHEAD_MS = 48 * 3_600_000L - WINDOW_MS

    /** [now] floored to the local :00 or :30. */
    fun slotOf(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val offset = zone.getOffset(now)
        return Math.floorDiv(now + offset, SLOT_MS) * SLOT_MS - offset
    }
}

/**
 * The guide's chip, time window and expanded "More NBC" rows, and the cell focused last (kept across
 * the player, like the window). The window starts at the current half-hour and moves half an hour at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(private val source: GuideSource, private val clock: () -> Long) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)
    private val _filter = MutableStateFlow(GuideFilter.ALL)
    val filter: StateFlow<GuideFilter> = _filter
    private val windowStart = MutableStateFlow(GuideTime.slotOf(clock()))
    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    /** Titles of the network sections whose "More" row is open. */
    val expanded: StateFlow<Set<String>> = _expanded

    val view: StateFlow<GuideView?> = combine(_filter, windowStart, ::Pair)
        .flatMapLatest { (f, start) -> source.board(f, start).map { GuideView(f, it) } }
        .stateIn(viewModelScope, sharing, null)

    /** The clock, re-read every 30 s for the now line and "min left". */
    val now: StateFlow<Long> = flow { while (true) { emit(clock()); delay(30_000) } }.stateIn(viewModelScope, sharing, clock())

    val focus = FocusMemory()

    /** A new chip starts again at now, at the top. */
    fun select(f: GuideFilter) {
        focus.key = null
        windowStart.value = GuideTime.slotOf(clock())
        _filter.value = f
    }

    /** Moves the window half an hour; null at either end (never before the current half-hour, never past [GuideTime.MAX_AHEAD_MS]). */
    fun page(forward: Boolean): Long? {
        val first = GuideTime.slotOf(clock())
        val old = windowStart.value
        val next = (old + if (forward) GuideTime.SLOT_MS else -GuideTime.SLOT_MS).coerceIn(first, first + GuideTime.MAX_AHEAD_MS)
        if (next == old) return null
        windowStart.value = next
        return next
    }

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
