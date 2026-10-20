package com.keyspaceapps.goodtv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyStatesTest {
    @Test
    fun noSourcesWinsOverEverythingElse() =
        assertEquals(BrowseEmptyState.NO_SOURCES, EmptyStates.forBrowse(hasSources = false, hiddenCount = 3))

    @Test
    fun hiddenRowsForTheKindMeanEverythingHidden() =
        assertEquals(BrowseEmptyState.ALL_HIDDEN, EmptyStates.forBrowse(hasSources = true, hiddenCount = 1))

    @Test
    fun sourcesButNothingHiddenMeansStillLoadingOrEmptyProvider() =
        assertEquals(BrowseEmptyState.NOTHING_YET, EmptyStates.forBrowse(hasSources = true, hiddenCount = 0))
}
