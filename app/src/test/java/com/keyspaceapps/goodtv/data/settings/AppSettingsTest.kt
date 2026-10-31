package com.keyspaceapps.goodtv.data.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.keyspaceapps.goodtv.data.sports.League
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private val defaults = setOf(League.MLB, League.NBA, League.NFL, League.NHL, League.PWHL, League.WNBA)

    @Test
    fun sportsLeaguesDefaultToTheOriginalSix() = runTest {
        assertEquals(defaults, AppSettings(InMemoryDataStore()).sportsLeagues.first())
    }

    @Test
    fun setLeagueOnAddsToTheDefaults() = runTest {
        val settings = AppSettings(InMemoryDataStore())
        settings.setLeagueOn(League.MLS, true)
        assertEquals(defaults + League.MLS, settings.sportsLeagues.first())
    }

    @Test
    fun setLeagueOffRemoves() = runTest {
        val settings = AppSettings(InMemoryDataStore())
        settings.setLeagueOn(League.NHL, false)
        assertEquals(defaults - League.NHL, settings.sportsLeagues.first())
    }

    @Test
    fun unknownStoredLeagueIsIgnored() = runTest {
        val store = InMemoryDataStore()
        store.edit { it[stringSetPreferencesKey("sports_leagues")] = setOf("NHL", "CFL_LEGACY") }
        assertEquals(setOf(League.NHL), AppSettings(store).sportsLeagues.first())
    }

    @Test
    fun everyLeagueSwitchedOffStaysEmpty() = runTest {
        val store = InMemoryDataStore()
        val settings = AppSettings(store)
        for (l in defaults) settings.setLeagueOn(l, false)
        assertEquals(emptySet<League>(), settings.sportsLeagues.first())
        assertEquals(emptySet<League>(), AppSettings(store).sportsLeagues.first())
    }
}
