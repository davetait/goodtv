package com.keyspaceapps.goodtv.ui.browse

enum class BrowseEmptyState { NO_SOURCES, ALL_HIDDEN, NOTHING_YET }

/** What an empty Live/Movies/Series screen should say. "Everything is hidden" only when something really is. */
object EmptyStates {
    fun forBrowse(hasSources: Boolean, hiddenCount: Int): BrowseEmptyState = when {
        !hasSources -> BrowseEmptyState.NO_SOURCES
        hiddenCount > 0 -> BrowseEmptyState.ALL_HIDDEN
        else -> BrowseEmptyState.NOTHING_YET
    }
}

val BrowseEmptyState.messageRes: Int
    get() = when (this) {
        BrowseEmptyState.NO_SOURCES -> com.keyspaceapps.goodtv.R.string.no_sources_yet
        BrowseEmptyState.ALL_HIDDEN -> com.keyspaceapps.goodtv.R.string.everything_hidden
        BrowseEmptyState.NOTHING_YET -> com.keyspaceapps.goodtv.R.string.nothing_yet
    }
