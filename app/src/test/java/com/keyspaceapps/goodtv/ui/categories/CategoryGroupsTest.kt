package com.keyspaceapps.goodtv.ui.categories

import androidx.compose.ui.state.ToggleableState
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryGroupsTest {
    private fun cat(id: String, name: String) = Category(1, ContentKind.LIVE, id, name)

    @Test
    fun pipeAndDashSeparators() {
        assertEquals("AR", CategoryGroups.prefixOf("AR| BEIN SPORTS ◉ ⚽"))
        assertEquals("FR", CategoryGroups.prefixOf("FR - NETFLIX FILMS"))
        assertEquals("4K", CategoryGroups.prefixOf("4K| 24/7 ᵁᴴᴰ"))
    }

    @Test
    fun firstWordFallbackAndUpperCase() {
        assertEquals("GERMANY", CategoryGroups.prefixOf("Germany Netflix"))
        assertEquals("ESPAÑA", CategoryGroups.prefixOf("ESPAÑA SERIES"))
        assertEquals("SKY", CategoryGroups.prefixOf("Sky Sports: Premier League"))
        assertNull(CategoryGroups.prefixOf("   "))
    }

    @Test
    fun docuMoviesHyphenIsNotSplit() = assertEquals("DE", CategoryGroups.prefixOf("DE - DOCU-MOVIES"))

    @Test
    fun singleMemberPrefixesGoToOtherAndOtherIsLast() {
        val groups = CategoryGroups.group(
            listOf(cat("1", "UK| BBC"), cat("2", "2026 رمضانك"), cat("3", "AR| MBC"), cat("4", "UK| ITV"), cat("5", "AR| LBC")),
            hidden = emptySet(),
        )
        assertEquals(listOf("AR", "UK", CategoryGroups.OTHER), groups.map { it.prefix })
        assertEquals(listOf("3", "5"), groups[0].categories.map { it.id })
        assertEquals(listOf("1", "4"), groups[1].categories.map { it.id })
        assertEquals(listOf("2"), groups[2].categories.map { it.id })
    }

    @Test
    fun groupStateReflectsHiddenIds() {
        val cats = listOf(cat("1", "UK| BBC"), cat("2", "UK| ITV"), cat("3", "UK| C4"))
        assertEquals(ToggleableState.On, CategoryGroups.group(cats, emptySet()).single().state)
        assertEquals(ToggleableState.Indeterminate, CategoryGroups.group(cats, setOf("2")).single().state)
        assertEquals(ToggleableState.Off, CategoryGroups.group(cats, setOf("1", "2", "3")).single().state)
        val g = CategoryGroups.group(cats, setOf("2")).single()
        assertEquals(2, g.shownCount)
        assertEquals(setOf("2"), g.hiddenIds)
    }

    @Test
    fun hiddenIdsOnlyCountMembersOfTheGroup() {
        val cats = listOf(cat("1", "UK| BBC"), cat("2", "UK| ITV"), cat("3", "AR| MBC"), cat("4", "AR| LBC"))
        val groups = CategoryGroups.group(cats, setOf("3", "9"))
        assertEquals(setOf("3"), groups.first { it.prefix == "AR" }.hiddenIds)
        assertEquals(emptySet<String>(), groups.first { it.prefix == "UK" }.hiddenIds)
    }
}
