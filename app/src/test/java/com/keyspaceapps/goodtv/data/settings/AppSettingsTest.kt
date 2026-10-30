package com.keyspaceapps.goodtv.data.settings

import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun livePreviewsDefaultsOffAndPersists() = runTest {
        val store = InMemoryDataStore()
        val settings = AppSettings(store)
        assertFalse(settings.livePreviews.first())
        settings.setLivePreviews(true)
        assertTrue(settings.livePreviews.first())
        assertTrue(AppSettings(store).livePreviews.first())
    }
}
