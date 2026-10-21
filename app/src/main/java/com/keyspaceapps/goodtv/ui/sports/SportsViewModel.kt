package com.keyspaceapps.goodtv.ui.sports

import android.content.Context
import android.text.format.DateFormat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.sports.SportsBoard
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** The Sports tab's board (null until the first one arrives) and the card focused last. */
class SportsViewModel(board: Flow<SportsBoard>) : ViewModel() {
    val board: StateFlow<SportsBoard?> = board.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val focus = FocusMemory()
}

object SportsFormats {
    /** "7:30 PM" (or "19:30"), in the device's time zone and format. */
    fun startTime(context: Context, at: Instant): String = DateFormat.getTimeFormat(context).format(Date(at.toEpochMilli()))
}
