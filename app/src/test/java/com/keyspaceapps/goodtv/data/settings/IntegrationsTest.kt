package com.keyspaceapps.goodtv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesLogin
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationsTest {
    private val none = BuildDefaults("", "", "", "")
    private val built = BuildDefaults("build-tmdb", "build-os", "build-user", "build-pass")

    private fun TestScopeHolder.make(defaults: BuildDefaults, store: DataStore<Preferences> = InMemoryDataStore()) =
        Integrations(store, defaults, CoroutineScope(UnconfinedTestDispatcher(scheduler)))

    private class TestScopeHolder(val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler)

    private fun kotlinx.coroutines.test.TestScope.holder() = TestScopeHolder(testScheduler)

    @Test fun buildDefaultsApplyWhenNothingTyped() = runTest {
        val i = holder().make(built)
        assertEquals(TmdbState("build-tmdb", true, KeyStatus.UNCHECKED), i.tmdb.value)
        val os = i.openSubtitles.value
        assertEquals(OpenSubtitlesAccess("build-os", OpenSubtitlesLogin("build-user", "build-pass")), os.access)
        assertTrue(os.fromBuild)
        assertEquals(KeyStatus.UNCHECKED, os.status)
    }

    @Test fun noDefaultsAndNothingTypedIsNotSet() = runTest {
        val i = holder().make(none)
        assertEquals(TmdbState(null, false, KeyStatus.NOT_SET), i.tmdb.value)
        assertEquals(OpenSubtitlesState(null, false, KeyStatus.NOT_SET), i.openSubtitles.value)
    }

    @Test fun typedValueWinsOverBuild() = runTest {
        val i = holder().make(built)
        i.saveTmdb("typed-tmdb", KeyStatus.CONNECTED)
        assertEquals(TmdbState("typed-tmdb", false, KeyStatus.CONNECTED), i.tmdb.value)
        i.saveOpenSubtitles(OpenSubtitlesAccess("typed-os", null), KeyStatus.REJECTED_KEY)
        assertEquals(OpenSubtitlesState(OpenSubtitlesAccess("typed-os", null), false, KeyStatus.REJECTED_KEY), i.openSubtitles.value)
    }

    @Test fun removeFallsBackToBuildValue() = runTest {
        val i = holder().make(built)
        i.saveTmdb("typed-tmdb", KeyStatus.CONNECTED)
        i.saveOpenSubtitles(OpenSubtitlesAccess("typed-os", null), KeyStatus.CONNECTED)
        i.removeTmdb()
        i.removeOpenSubtitles()
        assertEquals(TmdbState("build-tmdb", true, KeyStatus.UNCHECKED), i.tmdb.value)
        assertEquals("build-os", i.openSubtitles.value.access?.apiKey)
        assertTrue(i.openSubtitles.value.fromBuild)
        assertEquals(KeyStatus.UNCHECKED, i.openSubtitles.value.status)
    }

    @Test fun removeWithoutBuildValueIsNotSet() = runTest {
        val i = holder().make(none)
        i.saveTmdb("typed-tmdb", KeyStatus.CONNECTED)
        i.removeTmdb()
        assertEquals(TmdbState(null, false, KeyStatus.NOT_SET), i.tmdb.value)
    }

    @Test fun statusFollowsTheValueItWasRecordedFor() = runTest {
        val i = holder().make(none)
        i.saveTmdb("key-one", KeyStatus.UNCHECKED)
        i.recordTmdbStatus("key-one", KeyStatus.CONNECTED)
        assertEquals(KeyStatus.CONNECTED, i.tmdb.value.status)
        i.saveTmdb("key-two", KeyStatus.REJECTED_KEY)
        assertEquals(KeyStatus.REJECTED_KEY, i.tmdb.value.status)
        i.recordTmdbStatus("key-two", KeyStatus.CONNECTED)
        i.saveTmdb("key-one", KeyStatus.UNCHECKED)
        assertEquals(KeyStatus.UNCHECKED, i.tmdb.value.status)
    }

    @Test fun recordForABuildValueSticksUntilItChanges() = runTest {
        val store = InMemoryDataStore()
        val h = holder()
        val i = h.make(built, store)
        i.recordTmdbStatus("build-tmdb", KeyStatus.CONNECTED)
        assertEquals(KeyStatus.CONNECTED, i.tmdb.value.status)
        val changed = h.make(built.copy(tmdbKey = "other-build"), store)
        assertEquals(KeyStatus.UNCHECKED, changed.tmdb.value.status)
    }

    @Test fun recordForAStaleValueIsIgnored() = runTest {
        val i = holder().make(none)
        i.saveTmdb("current", KeyStatus.UNCHECKED)
        i.recordTmdbStatus("old", KeyStatus.CONNECTED)
        assertEquals(KeyStatus.UNCHECKED, i.tmdb.value.status)
        val access = OpenSubtitlesAccess("os-key", null)
        i.saveOpenSubtitles(access, KeyStatus.UNCHECKED)
        i.recordOpenSubtitlesStatus(OpenSubtitlesAccess("os-key", OpenSubtitlesLogin("u", "p")), KeyStatus.REJECTED_LOGIN)
        assertEquals(KeyStatus.UNCHECKED, i.openSubtitles.value.status)
        i.recordOpenSubtitlesStatus(access, KeyStatus.CONNECTED)
        assertEquals(KeyStatus.CONNECTED, i.openSubtitles.value.status)
    }

    @Test fun recordWhenNothingIsInEffectIsIgnored() = runTest {
        val i = holder().make(none)
        i.recordTmdbStatus("ghost", KeyStatus.CONNECTED)
        assertEquals(KeyStatus.NOT_SET, i.tmdb.value.status)
    }

    @Test fun openSubtitlesBlankLoginMeansKeyOnly() = runTest {
        val i = holder().make(none)
        i.saveOpenSubtitles(OpenSubtitlesAccess("k", OpenSubtitlesLogin("", "pw")), KeyStatus.UNCHECKED)
        assertNull(i.openSubtitles.value.access?.login)
        i.saveOpenSubtitles(OpenSubtitlesAccess("k", OpenSubtitlesLogin("user", "  ")), KeyStatus.UNCHECKED)
        assertNull(i.openSubtitles.value.access?.login)
        i.saveOpenSubtitles(OpenSubtitlesAccess("k", OpenSubtitlesLogin("user", "pw")), KeyStatus.UNCHECKED)
        assertEquals(OpenSubtitlesLogin("user", "pw"), i.openSubtitles.value.access?.login)
    }

    @Test fun blankBuildLoginMeansKeyOnly() = runTest {
        val i = holder().make(BuildDefaults("", "os", "user", ""))
        assertEquals(OpenSubtitlesAccess("os", null), i.openSubtitles.value.access)
    }

    @Test fun valuesSurviveANewInstance() = runTest {
        val store = InMemoryDataStore()
        val h = holder()
        val first = h.make(built, store)
        first.saveTmdb("typed-tmdb", KeyStatus.CONNECTED)
        first.saveOpenSubtitles(OpenSubtitlesAccess("typed-os", OpenSubtitlesLogin("u", "p")), KeyStatus.REJECTED_LOGIN)
        val second = h.make(built, store)
        assertEquals(first.tmdb.value, second.tmdb.value)
        assertEquals(first.openSubtitles.value, second.openSubtitles.value)
        assertEquals(KeyStatus.REJECTED_LOGIN, second.openSubtitles.value.status)
    }

    @Test fun loadedIsFalseUntilTheSavedValuesArrive() = runTest {
        val inner = InMemoryDataStore()
        Integrations(inner, built, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            .also { it.saveTmdb("typed-tmdb", KeyStatus.CONNECTED) }
        val slow = object : DataStore<Preferences> by inner {
            override val data = kotlinx.coroutines.flow.flow { kotlinx.coroutines.delay(1000); emitAll(inner.data) }
        }
        val i = Integrations(slow, built, CoroutineScope(StandardTestDispatcher(testScheduler)))
        var returned = false
        val waiter = CoroutineScope(StandardTestDispatcher(testScheduler)).launch { i.awaitLoaded(); returned = true }
        advanceTimeBy(500)
        assertFalse(i.loaded.value)
        assertFalse(returned)
        advanceUntilIdle()
        assertTrue(i.loaded.value)
        assertTrue(returned)
        assertEquals(TmdbState("typed-tmdb", false, KeyStatus.CONNECTED), i.tmdb.value)
        i.awaitLoaded()
        waiter.cancel()
    }

    /** Review R4: a store that can't be read counts as empty, so whoever waits on [Integrations.loaded] isn't stuck. */
    @Test fun unreadableStoreStillLoadsWithTheBuildValues() = runTest {
        val warnings = mutableListOf<String>()
        val broken = object : DataStore<Preferences> by InMemoryDataStore() {
            override val data = kotlinx.coroutines.flow.flow<Preferences> { throw java.io.IOException("disk says no secret-value") }
        }
        val i = Integrations(broken, built, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), warn = { warnings += it })
        assertTrue(i.loaded.value)
        i.awaitLoaded()
        assertEquals(TmdbState("build-tmdb", true, KeyStatus.UNCHECKED), i.tmdb.value)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("IOException"))
        assertFalse(warnings.single().contains("secret"))
    }

    @Test fun toStringsNeverShowValues() {
        val text = listOf(
            built.toString(),
            OpenSubtitlesAccess("secret-key", OpenSubtitlesLogin("secret-user", "secret-pass")).toString(),
            TmdbState("secret-key", false, KeyStatus.CONNECTED).toString(),
            OpenSubtitlesState(OpenSubtitlesAccess("secret-key", null), false, KeyStatus.CONNECTED).toString(),
        ).joinToString()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("build-"))
    }
}
