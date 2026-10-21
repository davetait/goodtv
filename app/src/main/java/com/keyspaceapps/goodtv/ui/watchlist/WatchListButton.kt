package com.keyspaceapps.goodtv.ui.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.data.watchlist.WatchListRepository
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether one title is saved, and switching it. */
class WatchListToggleViewModel(private val repo: WatchListRepository, private val ref: WatchListRef) : ViewModel() {
    /** Null until known, so the button doesn't flash the wrong label. */
    val saved: StateFlow<Boolean?> = repo.saved().map { ref in it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle() {
        val now = saved.value ?: return
        viewModelScope.launch { if (now) repo.remove(ref) else repo.add(ref) }
    }
}

/** The detail page's "＋ Watch list" / "✓ On watch list" button for [ref]. It never takes initial focus. */
@Composable
fun WatchListButton(ref: WatchListRef) {
    val vm = goodTvViewModel(key = "watchlist-${ref.key}") { WatchListToggleViewModel(it.watchList, ref) }
    val saved by vm.saved.collectAsState()
    Button(onClick = vm::toggle) {
        Text(stringResource(if (saved == true) R.string.watch_list_on else R.string.watch_list_button))
    }
}
