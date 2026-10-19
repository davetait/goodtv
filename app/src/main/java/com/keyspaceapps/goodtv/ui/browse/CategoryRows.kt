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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.domain.CategoryWithSource

const val ROW_LIMIT = 30

data class RowItem(val sourceId: Long, val id: String, val title: String, val imageUrl: String?)

data class CategoryRow(val category: CategoryWithSource, val items: List<RowItem>, val hasMore: Boolean)

/** Vertical stack of category rails. The first poster of the first row takes focus once on load. */
@Composable
fun CategoryRows(
    rows: List<CategoryRow>,
    multiSource: Boolean,
    onItem: (CategoryRow, RowItem) -> Unit,
    onSeeAll: (CategoryRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp)) {
        itemsIndexed(rows, key = { _, r -> "${r.category.category.sourceId}:${r.category.category.id}" }) { rowIndex, row ->
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
                    itemsIndexed(row.items, key = { _, it -> it.id }) { index, item ->
                        val focusModifier = if (rowIndex == 0 && index == 0) {
                            Modifier.focusRequester(firstFocus).onPlaced {
                                if (!initialFocusDone) {
                                    initialFocusDone = true
                                    firstFocus.requestFocus()
                                }
                            }
                        } else {
                            Modifier
                        }
                        PosterCard(title = item.title, imageUrl = item.imageUrl, onClick = { onItem(row, item) }, modifier = focusModifier)
                    }
                    if (row.hasMore) {
                        item(key = "see-all") {
                            PosterCard(title = stringResource(R.string.see_all), imageUrl = null, onClick = { onSeeAll(row) }, placeholderLabel = "→")
                        }
                    }
                }
            }
        }
    }
}
