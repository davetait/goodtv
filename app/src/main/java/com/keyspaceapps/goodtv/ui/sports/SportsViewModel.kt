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

    /** The league chosen in the dropdown; null means all. */
    val filter: StateFlow<League?> = combine(this.board, selected) { b, s -> s?.takeIf { l -> b?.leagues?.any { it.league == l } == true } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val shown: StateFlow<SportsBoard?> = combine(this.board, filter) { b, f -> if (b == null || f == null) b else SportsBoard(b.leagues.filter { it.league == f }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectLeague(league: League?) { selected.value = league }

    val focus = FocusMemory()
}

object SportsFormats {
    /** "7:30 PM" (or "19:30"), in the device's time zone and format. */
    fun startTime(context: Context, at: Instant): String = DateFormat.getTimeFormat(context).format(Date(at.toEpochMilli()))
}
