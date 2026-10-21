package com.keyspaceapps.goodtv.ui.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.data.watchlist.WatchListRef
import com.keyspaceapps.goodtv.data.watchlist.WatchListRepository
import com.keyspaceapps.goodtv.domain.ContentKind
import kotlinx.coroutines.launch

/** A poster that can go on the watch list, with the title its menu shows. */
data class WatchTarget(val ref: WatchListRef, val title: String)

/** What posters need from the watch list: which titles are saved, and a way to open the Add/Remove menu. */
interface WatchListUi {
    val saved: Set<WatchListRef>
    fun openMenu(target: WatchTarget)

    /** No watch list: nothing saved, no menu (screens and tests without a [WatchListHost]). */
    object None : WatchListUi {
        override val saved: Set<WatchListRef> = emptySet()
        override fun openMenu(target: WatchTarget) = Unit
    }
}

val LocalWatchList = staticCompositionLocalOf<WatchListUi> { WatchListUi.None }

/** [ContentKind.MOVIE]/[ContentKind.SERIES] as a watch list kind; live channels have none. */
fun ContentKind.watchListKind(): WatchListKind? = when (this) {
    ContentKind.MOVIE -> WatchListKind.MOVIE
    ContentKind.SERIES -> WatchListKind.SERIES
    ContentKind.LIVE -> null
}

/** The saved state and menu action for one poster, or null when it can't be saved (a channel). */
@Composable
fun rememberWatchTarget(kind: ContentKind, sourceId: Long, id: String, title: String): WatchTarget? =
    remember(kind, sourceId, id, title) { kind.watchListKind()?.let { WatchTarget(WatchListRef(sourceId, it, id), title) } }

/**
 * Provides [LocalWatchList] to [content] and shows the Add/Remove dialog posters open by holding
 * Select (or the remote's Menu key).
 */
@Composable
fun WatchListHost(repo: WatchListRepository, content: @Composable () -> Unit) {
    val saved by remember(repo) { repo.saved() }.collectAsState(initial = emptySet())
    var menuFor by remember { mutableStateOf<WatchTarget?>(null) }
    val scope = rememberCoroutineScope()
    val ui = remember(saved) {
        object : WatchListUi {
            override val saved = saved
            override fun openMenu(target: WatchTarget) { menuFor = target }
        }
    }
    CompositionLocalProvider(LocalWatchList provides ui) { content() }

    menuFor?.let { target ->
        val isSaved = target.ref in saved
        WatchListDialog(
            title = target.title,
            action = stringResource(if (isSaved) R.string.watch_list_remove else R.string.watch_list_add),
            onAction = {
                menuFor = null
                scope.launch { if (isSaved) repo.remove(target.ref) else repo.add(target.ref) }
            },
            onDismiss = { menuFor = null },
        )
    }
}

/** The title, one action button (focused first) and Cancel. */
@Composable
fun WatchListDialog(title: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        var requested by remember { mutableStateOf(false) }
        Column(
            Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.focusRequester(first).onPlaced { if (!requested) { requested = true; first.requestFocus() } },
                ) { Text(action) }
                Button(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
