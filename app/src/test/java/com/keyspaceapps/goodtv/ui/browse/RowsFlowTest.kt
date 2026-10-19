package com.keyspaceapps.goodtv.ui.browse

import com.keyspaceapps.goodtv.data.repo.CategoryKey
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.CategoryWithSource
import com.keyspaceapps.goodtv.domain.ContentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowsFlowTest {
    private fun cat(id: String, name: String, sid: Long = 1) = CategoryWithSource(Category(sid, ContentKind.MOVIE, id, name), "A")
    private fun items(prefix: String, n: Int) = (1..n).map { RowItem(1, "$prefix$it", "$prefix $it", null) }

    @Test
    fun rowsFollowCategoryOrderAndFlagSeeAllOnlyWhenMoreThanLimit() = runTest {
        val categories = MutableStateFlow(listOf(cat("act", "Action"), cat("dra", "Drama"), cat("empty", "Empty")))
        val grouped = MutableStateFlow(
            mapOf(
                CategoryKey(1, "act") to items("a", ROW_LIMIT + 1),
                CategoryKey(1, "dra") to items("d", 3),
            ),
        )

        val rows = rowsFlow(categories, grouped).first()

        assertEquals(listOf("Action", "Drama", "Empty"), rows.map { it.category.category.name })
        assertEquals(ROW_LIMIT, rows[0].items.size)
        assertTrue(rows[0].hasMore)
        assertEquals(3, rows[1].items.size)
        assertFalse(rows[1].hasMore)
        assertTrue(rows[2].items.isEmpty())
    }

    @Test
    fun noCategoriesGivesEmptyRows() = runTest {
        val rows = rowsFlow(MutableStateFlow(emptyList()), MutableStateFlow(emptyMap())).first()
        assertTrue(rows.isEmpty())
    }
}
