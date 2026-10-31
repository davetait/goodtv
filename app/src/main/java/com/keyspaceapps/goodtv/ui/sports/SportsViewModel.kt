package com.keyspaceapps.goodtv.ui.sports

import android.content.Context
import android.text.format.DateFormat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.sports.League
import com.keyspaceapps.goodtv.data.sports.SportsBoard
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The Sports tab's board (null until the first one arrives), the league it is filtered to, and the
 * card focused last. [shown] is the board limited to [filter]; a filter whose league has left the
 * board counts as "all".
 */
class SportsViewModel(board: Flow<SportsBoard>) : ViewModel() {
    val board: StateFlow<SportsBoard?> = board.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val selected = MutableStateFlow<League?>(null)

    /** The effective filter and the board it limits, computed together from one (board, selected) pair. */
    private val view: StateFlow<Pair<League?, SportsBoard?>> = combine(this.board, selected) { b, s ->
        val f = s?.takeIf { l -> b?.leagues?.any { it.league == l } == true }
        // A league with no row drops the choice for good: if it returns later, the screen stays on All.
        // Only if the choice is still s: a pick made since this pair was captured must survive.
        if (s != null && f == null && b != null) selected.compareAndSet(s, null)
        f to (if (b == null || f == null) b else SportsBoard(b.leagues.filter { it.league == f }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to null)

    /** The league chosen in the dropdown; null means all. */
    val filter: StateFlow<League?> = view.map { it.first }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val shown: StateFlow<SportsBoard?> = view.map { it.second }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectLeague(league: League?) { selected.value = league }

    val focus = FocusMemory()
}

object SportsFormats {
    /** "7:30 PM" (or "19:30"), in the device's time zone and format. */
    fun startTime(context: Context, at: Instant): String = DateFormat.getTimeFormat(context).format(Date(at.toEpochMilli()))
}
