package com.keyspaceapps.goodtv.ui.settings

import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.settings.KeyStatus
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.SourceSummary
import com.keyspaceapps.goodtv.ui.home.savedTab
import com.keyspaceapps.goodtv.ui.nav.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStatusTest {
    private fun source(id: Long, lastSyncAt: Long?) = SourceSummary(id, "S$id", SourceType.XTREAM, 0, 0, 0, lastSyncAt, null)

    @Test
    fun latestSyncIsTheMostRecentAndIgnoresUnsynced() {
        assertEquals(300L, latestSync(listOf(source(1, 100), source(2, null), source(3, 300))))
        assertNull(latestSync(listOf(source(1, null))))
        assertNull(latestSync(emptyList()))
    }

    @Test
    fun bothRejectionsReadAsRejected() {
        assertEquals(R.string.integration_not_set, statusPartRes(KeyStatus.NOT_SET))
        assertEquals(R.string.integration_unchecked, statusPartRes(KeyStatus.UNCHECKED))
        assertEquals(R.string.integration_connected, statusPartRes(KeyStatus.CONNECTED))
        assertEquals(R.string.integration_rejected, statusPartRes(KeyStatus.REJECTED_KEY))
        assertEquals(R.string.integration_rejected, statusPartRes(KeyStatus.REJECTED_LOGIN))
    }

    @Test
    fun aSavedSourcesTabOpensSettings() {
        assertEquals(HomeTab.SETTINGS, savedTab("SOURCES"))
        assertEquals(HomeTab.SETTINGS, savedTab("SETTINGS"))
        assertEquals(HomeTab.LIVE, savedTab("LIVE"))
        assertNull(savedTab("NOPE"))
    }
}
