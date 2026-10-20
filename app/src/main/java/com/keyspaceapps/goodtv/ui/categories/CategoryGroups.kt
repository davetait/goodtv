package com.keyspaceapps.goodtv.ui.categories

import androidx.compose.ui.state.ToggleableState
import com.keyspaceapps.goodtv.domain.Category

/** A block of categories sharing a name prefix, plus which of them are hidden. */
data class CategoryGroup(val prefix: String, val categories: List<Category>, val hiddenIds: Set<String>) {
    val shownCount: Int get() = categories.size - hiddenIds.size
    val state: ToggleableState
        get() = when (hiddenIds.size) {
            0 -> ToggleableState.On
            categories.size -> ToggleableState.Off
            else -> ToggleableState.Indeterminate
        }
}

/**
 * Providers name categories `AR| BEIN SPORTS`, `FR - NETFLIX FILMS` or `GERMANY NETFLIX`. The prefix
 * is the text before the first `|` or ` - ` (space-dash-space, so `DOCU-MOVIES` survives), else the
 * first word. `:` is deliberately not a separator. Prefixes shared by one category only collapse
 * into a trailing "Other" group.
 */
object CategoryGroups {
    const val OTHER = "Other"
    private val separators = listOf("|", " - ")

    fun prefixOf(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val cut = separators.mapNotNull { sep -> trimmed.indexOf(sep).takeIf { it > 0 } }.minOrNull()
        val head = if (cut != null) trimmed.substring(0, cut) else trimmed.split(Regex("\\s+")).first()
        return head.trim().uppercase().ifEmpty { null }
    }

    fun group(categories: List<Category>, hidden: Set<String>): List<CategoryGroup> {
        val byPrefix = categories.groupBy { prefixOf(it.name) ?: OTHER }
        val named = byPrefix.filter { (prefix, members) -> prefix != OTHER && members.size > 1 }
        val other = categories.filter { c -> (prefixOf(c.name) ?: OTHER).let { it == OTHER || byPrefix.getValue(it).size == 1 } }
        fun make(prefix: String, members: List<Category>) =
            CategoryGroup(prefix, members, members.map { it.id }.filter { it in hidden }.toSet())
        return named.keys.sorted().map { make(it, named.getValue(it)) } +
            (if (other.isEmpty()) emptyList() else listOf(make(OTHER, other)))
    }
}
