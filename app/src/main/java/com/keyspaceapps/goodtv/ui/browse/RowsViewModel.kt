package com.keyspaceapps.goodtv.ui.browse

import com.keyspaceapps.goodtv.data.repo.CategoryKey
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Joins the category list with one grouped item query into [CategoryRow]s. The grouped query is
 * asked for one item more than a row shows so "See all" appears only when there is more.
 */
fun rowsFlow(
    categories: Flow<List<CategoryWithSource>>,
    grouped: Flow<Map<CategoryKey, List<RowItem>>>,
): Flow<List<CategoryRow>> = combine(categories.distinctUntilChanged(), grouped) { cats, map ->
    cats.map { c ->
        val items = map[CategoryKey(c.category.sourceId, c.category.id)] ?: emptyList()
        CategoryRow(c, items.take(ROW_LIMIT), hasMore = items.size > ROW_LIMIT)
    }
}

fun List<CategoryWithSource>.isMultiSource(): Boolean = map { it.category.sourceId }.distinct().size > 1
