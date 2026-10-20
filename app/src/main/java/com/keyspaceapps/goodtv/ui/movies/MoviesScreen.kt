package com.keyspaceapps.goodtv.ui.movies

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.CategoryRows
import com.keyspaceapps.goodtv.ui.browse.GenreBrowse
import com.keyspaceapps.goodtv.ui.browse.genreFillStatus
import com.keyspaceapps.goodtv.ui.browse.Labels
import com.keyspaceapps.goodtv.ui.browse.messageRes
import com.keyspaceapps.goodtv.ui.goodTvViewModel
import com.keyspaceapps.goodtv.ui.nav.CategoryGridKey
import com.keyspaceapps.goodtv.ui.nav.MovieDetailKey

@Composable
fun MoviesScreen(onMovie: (MovieDetailKey) -> Unit, onSeeAll: (CategoryGridKey) -> Unit) {
    val vm = goodTvViewModel { MoviesViewModel(it.repository, it.genreFetcher.state) }
    val rows by vm.rows.collectAsState()
    val multiSource by vm.multiSource.collectAsState()
    val emptyState by vm.emptyState.collectAsState()

    val loaded = rows ?: return // still loading: show nothing rather than a misleading empty state
    if (loaded.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(emptyState.messageRes), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val status = genreFillStatus(vm.genreFill.collectAsState().value)
    GenreBrowse(
        kind = ContentKind.MOVIE,
        filter = vm.genres,
        status = status,
        onItem = { onMovie(MovieDetailKey(it.sourceId, it.id)) },
    ) {
        CategoryRows(
            rows = loaded,
            focus = vm.focus,
            multiSource = multiSource,
            onItem = { _, item -> onMovie(MovieDetailKey(item.sourceId, item.id)) },
            onSeeAll = { row ->
                val c = row.category
                onSeeAll(CategoryGridKey(c.category.sourceId, ContentKind.MOVIE, c.category.id, Labels.categoryLabel(c.category.name, c.sourceNickname, multiSource)))
            },
        )
    }
}
