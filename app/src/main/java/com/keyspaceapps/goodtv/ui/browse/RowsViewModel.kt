package com.keyspaceapps.goodtv.ui.browse

import com.keyspaceapps.goodtv.domain.CategoryWithSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Turns a category list plus a per-category item query into [CategoryRow]s, asking for one item more
 * than the row shows so "See all" appears only when there is more.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun rowsFlow(
    categories: Flow<List<CategoryWithSource>>,
    itemsFor: (CategoryWithSource, Int) -> Flow<List<RowItem>>,
): Flow<List<CategoryRow>> = categories.flatMapLatest { cats ->
    if (cats.isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(cats.map { c -> itemsFor(c, ROW_LIMIT + 1).map { items -> CategoryRow(c, items.take(ROW_LIMIT), hasMore = items.size > ROW_LIMIT) } }) { it.toList() }
    }
}

fun List<CategoryWithSource>.isMultiSource(): Boolean = map { it.category.sourceId }.distinct().size > 1
