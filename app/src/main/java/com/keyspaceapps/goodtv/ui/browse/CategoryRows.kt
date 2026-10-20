package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import com.keyspaceapps.goodtv.ui.tmdb.POSTER_SETTLE_MS
import com.keyspaceapps.goodtv.ui.tmdb.rememberTmdbMeta
import com.keyspaceapps.goodtv.ui.tmdb.tmdbKind

const val ROW_LIMIT = 30

data class RowItem(val sourceId: Long, val id: String, val title: String, val imageUrl: String?, val tmdbId: Long? = null)

data class CategoryRow(val category: CategoryWithSource, val items: List<RowItem>, val hasMore: Boolean)

fun rowKey(row: CategoryRow) = "${row.category.category.sourceId}:${row.category.category.id}"

/**
 * Vertical stack of category rails. Focus goes to the item the user last had focused (remembered in
 * [focus] across the player push), else to the first poster of the first row.
 */
@Composable
fun CategoryRows(
    rows: List<CategoryRow>,
    focus: FocusMemory,
    multiSource: Boolean,
    onItem: (CategoryRow, RowItem) -> Unit,
    onSeeAll: (CategoryRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requested = remember { mutableStateOf(false) }
    val present = rows.flatMap { r -> r.items.map { "${rowKey(r)}:${it.id}" } + (if (r.hasMore) listOf("${rowKey(r)}:see-all") else emptyList()) }
    val default = rows.firstOrNull { it.items.isNotEmpty() }?.let { "${rowKey(it)}:${it.items.first().id}" }
    val target = focus.targetIn(present, default)

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp)) {
        itemsIndexed(rows, key = { _, r -> rowKey(r) }) { _, row ->
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    Labels.categoryLabel(row.category.category.name, row.category.sourceNickname, multiSource),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(row.items, key = { _, it -> it.id }) { _, item ->
                        val key = "${rowKey(row)}:${item.id}"
                        val tmdb = rememberTmdbMeta(row.category.category.kind.tmdbKind(), item.tmdbId, item.title, POSTER_SETTLE_MS)
                        PosterCard(
                            title = item.title, imageUrl = item.imageUrl, onClick = { onItem(row, item) }, rating = tmdb?.reliableRating,
                            modifier = Modifier.rememberedFocus(focus, key, isTarget = key == target, requested = requested),
                        )
                    }
                    if (row.hasMore) {
                        item(key = "see-all") {
                            val key = "${rowKey(row)}:see-all"
                            PosterCard(
                                title = stringResource(R.string.see_all), imageUrl = null, onClick = { onSeeAll(row) }, placeholderLabel = "→",
                                modifier = Modifier.rememberedFocus(focus, key, isTarget = key == target, requested = requested),
                            )
                        }
                    }
                }
            }
        }
    }
}
