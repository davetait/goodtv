package com.keyspaceapps.goodtv.ui.settings

import com.keyspaceapps.goodtv.R
import com.keyspaceapps.goodtv.data.settings.BuildDefaults
import com.keyspaceapps.goodtv.data.settings.Integrations
import com.keyspaceapps.goodtv.data.settings.KeyStatus
import com.keyspaceapps.goodtv.data.settings.OpenSubtitlesAccess
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesException
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesLogin
import com.keyspaceapps.goodtv.data.tmdb.TmdbException
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val none = BuildDefaults("", "", "", "")
    private val checks = FakeChecks()
    /** Stands in for AppGraph.appScope: outlives the view model. */
    private val appScope = CoroutineScope(dispatcher)

    /** Made-up values only. */
    private class FakeChecks : IntegrationChecks {
        var tmdbResult: suspend () -> Boolean = { true }
        var osResult: suspend () -> KeyStatus = { KeyStatus.CONNECTED }
        val tmdbCalls = mutableListOf<String>()
        val osCalls = mutableListOf<OpenSubtitlesAccess>()
        override suspend fun tmdb(key: String): Boolean { tmdbCalls += key; return tmdbResult() }
        override suspend fun openSubtitles(access: OpenSubtitlesAccess): KeyStatus { osCalls += access; return osResult() }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun integrations(defaults: BuildDefaults = none) = Integrations(InMemoryDataStore(), defaults, CoroutineScope(dispatcher))

    private fun access(key: String, username: String? = null, password: String? = null) =
        OpenSubtitlesAccess(key, if (username != null && password != null) OpenSubtitlesLogin(username, password) else null)

    @Test fun acceptedKeySavesConnected() = runTest(dispatcher) {
        val i = integrations()
        val gate = CompletableDeferred<Boolean>()
        checks.tmdbResult = { gate.await() }
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()
        assertEquals(Badge.NOT_SET, vm.tmdb.value.badge)

        vm.saveTmdb("  typed-key-1234 ")
        runCurrent()
        assertEquals(Badge.CHECKING, vm.tmdb.value.badge)
        assertTrue(vm.tmdb.value.busy)

        gate.complete(true)
        advanceUntilIdle()
        assertEquals(listOf("typed-key-1234"), checks.tmdbCalls)
        assertEquals("typed-key-1234", i.tmdb.value.key)
        assertEquals(KeyStatus.CONNECTED, i.tmdb.value.status)
        val ui = vm.tmdb.value
        assertEquals(Badge.CONNECTED, ui.badge)
        assertEquals("••••1234", ui.maskedKey)
        assertNull(ui.message)
        assertFalse(ui.busy)
        assertFalse(ui.fromBuild)
        assertTrue(ui.removable)
        assertEquals(1, ui.saves)

        // OpenSubtitles: the key and the login are checked together and saved together.
        vm.saveOpenSubtitles(" os-key-5678 ", " someone ", "secret-pw")
        advanceUntilIdle()
        assertEquals(listOf(access("os-key-5678", "someone", "secret-pw")), checks.osCalls)
        assertEquals(access("os-key-5678", "someone", "secret-pw"), i.openSubtitles.value.access)
        assertEquals(KeyStatus.CONNECTED, i.openSubtitles.value.status)
        val os = vm.openSubtitles.value
        assertEquals(Badge.CONNECTED, os.badge)
        assertEquals("someone", os.username)
        assertTrue(os.maskedPassword)
    }

    @Test fun rejectedKeySavesNothingAndSaysSo() = runTest(dispatcher) {
        val i = integrations()
        checks.tmdbResult = { false }
        checks.osResult = { KeyStatus.REJECTED_KEY }
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveTmdb("bad-key-0000")
        vm.saveOpenSubtitles("bad-os-key-1", "", "")
        advanceUntilIdle()

        assertNull(i.tmdb.value.key)
        assertNull(i.openSubtitles.value.access)
        assertEquals(Badge.NOT_SET, vm.tmdb.value.badge)
        assertEquals(R.string.tmdb_rejected, vm.tmdb.value.message)
        assertNull(vm.tmdb.value.maskedKey)
        assertEquals(0, vm.tmdb.value.saves)
        assertEquals(Badge.NOT_SET, vm.openSubtitles.value.badge)
        assertEquals(R.string.os_key_rejected, vm.openSubtitles.value.message)
        assertEquals(0, vm.openSubtitles.value.saves)
    }

    @Test fun offlineSaveKeepsValuesAndSaysCouldntCheck() = runTest(dispatcher) {
        val i = integrations()
        checks.tmdbResult = { throw TmdbException("offline", TmdbException.Reason.NETWORK) }
        checks.osResult = { throw OpenSubtitlesException("offline", OpenSubtitlesException.Reason.NETWORK) }
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveTmdb("typed-key-1234")
        vm.saveOpenSubtitles("os-key-5678", "someone", "secret-pw")
        advanceUntilIdle()

        assertEquals("typed-key-1234", i.tmdb.value.key)
        assertEquals(KeyStatus.UNCHECKED, i.tmdb.value.status)
        assertEquals(Badge.UNCHECKED, vm.tmdb.value.badge)
        assertEquals(R.string.tmdb_unreachable, vm.tmdb.value.message)
        assertEquals(1, vm.tmdb.value.saves)
        assertEquals(access("os-key-5678", "someone", "secret-pw"), i.openSubtitles.value.access)
        assertEquals(KeyStatus.UNCHECKED, i.openSubtitles.value.status)
        assertEquals(Badge.UNCHECKED, vm.openSubtitles.value.badge)
        assertEquals(R.string.os_unreachable, vm.openSubtitles.value.message)
    }

    @Test fun goodKeyBadLoginSavesNothing() = runTest(dispatcher) {
        val i = integrations()
        checks.osResult = { KeyStatus.REJECTED_LOGIN }
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveOpenSubtitles("os-key-5678", "someone", "wrong-pw")
        advanceUntilIdle()

        assertNull(i.openSubtitles.value.access)
        assertEquals(Badge.NOT_SET, vm.openSubtitles.value.badge)
        assertEquals(R.string.os_login_rejected, vm.openSubtitles.value.message)
        assertEquals(0, vm.openSubtitles.value.saves)
    }

    @Test fun loginNeedsBothFields() = runTest(dispatcher) {
        val i = integrations()
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveOpenSubtitles("os-key-5678", "someone", "  ")
        advanceUntilIdle()
        assertEquals(R.string.os_need_both, vm.openSubtitles.value.message)

        vm.saveOpenSubtitles("os-key-5678", "", "secret-pw")
        advanceUntilIdle()
        assertEquals(R.string.os_need_both, vm.openSubtitles.value.message)

        assertTrue(checks.osCalls.isEmpty())
        assertNull(i.openSubtitles.value.access)
        assertEquals(Badge.NOT_SET, vm.openSubtitles.value.badge)
    }

    @Test fun loginWithoutKeyAsksForKey() = runTest(dispatcher) {
        val i = integrations()
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveOpenSubtitles("", "someone", "secret-pw")
        advanceUntilIdle()
        assertEquals(R.string.os_need_key, vm.openSubtitles.value.message)
        assertTrue(checks.osCalls.isEmpty())

        // With a saved key, the login is added to it.
        i.saveOpenSubtitles(access("saved-key-0000"), KeyStatus.CONNECTED)
        advanceUntilIdle()
        vm.saveOpenSubtitles("", "someone", "secret-pw")
        advanceUntilIdle()
        assertEquals(listOf(access("saved-key-0000", "someone", "secret-pw")), checks.osCalls)
        assertEquals(access("saved-key-0000", "someone", "secret-pw"), i.openSubtitles.value.access)
        assertNull(vm.openSubtitles.value.message)
    }

    @Test fun emptyFieldKeepsSavedValue() = runTest(dispatcher) {
        val i = integrations()
        i.saveTmdb("saved-key-0000", KeyStatus.CONNECTED)
        i.saveOpenSubtitles(access("saved-os-key", "someone", "secret-pw"), KeyStatus.CONNECTED)
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveTmdb("   ")
        advanceUntilIdle()
        assertTrue(checks.tmdbCalls.isEmpty())
        assertEquals("saved-key-0000", i.tmdb.value.key)

        vm.saveOpenSubtitles("", "", "")
        advanceUntilIdle()
        assertTrue(checks.osCalls.isEmpty())

        // A new key keeps the saved login.
        vm.saveOpenSubtitles("new-os-key-1111", "", "")
        advanceUntilIdle()
        assertEquals(listOf(access("new-os-key-1111", "someone", "secret-pw")), checks.osCalls)
        assertEquals(access("new-os-key-1111", "someone", "secret-pw"), i.openSubtitles.value.access)
    }

    @Test fun uncheckedBuildValueIsCheckedOnce() = runTest(dispatcher) {
        val i = integrations(BuildDefaults("build-tmdb-key", "build-os-key", "builduser", "buildpass"))
        IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        assertEquals(listOf("build-tmdb-key"), checks.tmdbCalls)
        assertEquals(listOf(access("build-os-key", "builduser", "buildpass")), checks.osCalls)
        assertEquals(KeyStatus.CONNECTED, i.tmdb.value.status)
        assertEquals(KeyStatus.CONNECTED, i.openSubtitles.value.status)

        // Opening the screen again doesn't check again: the result was recorded.
        val again = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()
        assertEquals(1, checks.tmdbCalls.size)
        assertEquals(1, checks.osCalls.size)
        assertEquals(Badge.CONNECTED, again.tmdb.value.badge)
        assertTrue(again.tmdb.value.fromBuild)
        assertFalse(again.tmdb.value.removable)
        assertEquals("••••-key", again.tmdb.value.maskedKey)
    }

    @Test fun uncheckedTypedValueIsNotCheckedJustForOpening() = runTest(dispatcher) {
        val i = integrations()
        i.saveTmdb("typed-key-1234", KeyStatus.UNCHECKED)
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()
        assertTrue(checks.tmdbCalls.isEmpty())
        assertEquals(Badge.UNCHECKED, vm.tmdb.value.badge)
        assertNull("the saved-offline message belongs to the Save that couldn't check", vm.tmdb.value.message)
    }

    @Test fun removeFallsBackToBuild() = runTest(dispatcher) {
        val i = integrations(BuildDefaults("build-tmdb-key", "", "", ""))
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveTmdb("typed-key-9999")
        advanceUntilIdle()
        assertFalse(vm.tmdb.value.fromBuild)
        assertTrue(vm.tmdb.value.removable)

        vm.removeTmdb()
        advanceUntilIdle()
        assertEquals("build-tmdb-key", i.tmdb.value.key)
        assertTrue(vm.tmdb.value.fromBuild)
        assertFalse(vm.tmdb.value.removable)
        assertEquals("••••-key", vm.tmdb.value.maskedKey)
        // The build value's earlier result went with the typed value, so it is checked again.
        assertEquals(listOf("build-tmdb-key", "typed-key-9999", "build-tmdb-key"), checks.tmdbCalls)
        assertEquals(Badge.CONNECTED, vm.tmdb.value.badge)

        // With no build value, Remove turns the service off.
        vm.saveOpenSubtitles("os-key-5678", "", "")
        advanceUntilIdle()
        vm.removeOpenSubtitles()
        advanceUntilIdle()
        assertNull(i.openSubtitles.value.access)
        assertEquals(Badge.NOT_SET, vm.openSubtitles.value.badge)
        assertFalse(vm.openSubtitles.value.removable)
    }

    /** Final review: a value OkHttp can't send in a header is refused at Save, before any network call. */
    @Test fun unsendableCharactersAreRefusedWithoutACheck() = runTest(dispatcher) {
        val i = integrations()
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveTmdb("eyJmade.up\u200b.token")
        vm.saveOpenSubtitles("made-up\u200bkey", "", "")
        advanceUntilIdle()
        assertEquals(R.string.tmdb_rejected, vm.tmdb.value.message)
        assertEquals(R.string.os_key_rejected, vm.openSubtitles.value.message)

        vm.saveOpenSubtitles("os-key-5678", "some\none", "secret-pw")
        advanceUntilIdle()
        assertEquals(R.string.os_login_rejected, vm.openSubtitles.value.message)
        vm.saveOpenSubtitles("os-key-5678", "someone", "s\u00e9cret")
        advanceUntilIdle()
        assertEquals(R.string.os_login_rejected, vm.openSubtitles.value.message)

        assertTrue(checks.tmdbCalls.isEmpty())
        assertTrue(checks.osCalls.isEmpty())
        assertNull(i.tmdb.value.key)
        assertNull(i.openSubtitles.value.access)
        assertEquals(0, vm.tmdb.value.saves)
        assertEquals(0, vm.openSubtitles.value.saves)
    }

    /** Review R7: with a login saved, typing only one half of it fills the other half from the saved login. */
    @Test fun oneLoginFieldFillsTheOtherFromTheSavedLogin() = runTest(dispatcher) {
        val i = integrations()
        i.saveOpenSubtitles(access("saved-os-key", "someone", "old-pw"), KeyStatus.CONNECTED)
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveOpenSubtitles("", "", "new-pw")
        advanceUntilIdle()
        assertEquals(listOf(access("saved-os-key", "someone", "new-pw")), checks.osCalls)
        assertEquals(access("saved-os-key", "someone", "new-pw"), i.openSubtitles.value.access)
        assertNull(vm.openSubtitles.value.message)

        vm.saveOpenSubtitles("", "someone-else", "")
        advanceUntilIdle()
        assertEquals(access("saved-os-key", "someone-else", "new-pw"), checks.osCalls.last())
        assertEquals(access("saved-os-key", "someone-else", "new-pw"), i.openSubtitles.value.access)
    }

    /** Review R10a: Back during "Checking…" ends the screen, not the Save. */
    @Test fun saveCompletesAfterTheScreenCloses() = runTest(dispatcher) {
        val i = integrations()
        val tmdbGate = CompletableDeferred<Boolean>()
        val osGate = CompletableDeferred<KeyStatus>()
        checks.tmdbResult = { tmdbGate.await() }
        checks.osResult = { osGate.await() }
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        vm.saveTmdb("typed-key-1234")
        vm.saveOpenSubtitles("os-key-5678", "", "")
        runCurrent()
        assertTrue(vm.tmdb.value.busy)
        vm.viewModelScope.cancel()
        tmdbGate.complete(true)
        osGate.complete(KeyStatus.CONNECTED)
        advanceUntilIdle()

        assertEquals("typed-key-1234", i.tmdb.value.key)
        assertEquals(KeyStatus.CONNECTED, i.tmdb.value.status)
        assertEquals(access("os-key-5678"), i.openSubtitles.value.access)
        assertEquals(KeyStatus.CONNECTED, i.openSubtitles.value.status)
    }

    /** Review R10b: a rejected Save leaves the badge on the saved value, which still works. */
    @Test fun rejectedSaveKeepsTheSavedValuesBadge() = runTest(dispatcher) {
        val i = integrations(BuildDefaults("build-tmdb-key", "build-os-key", "", ""))
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()
        assertEquals(Badge.CONNECTED, vm.tmdb.value.badge)

        checks.tmdbResult = { false }
        checks.osResult = { KeyStatus.REJECTED_KEY }
        vm.saveTmdb("bad-key-0000")
        vm.saveOpenSubtitles("bad-os-key-1", "", "")
        advanceUntilIdle()

        assertEquals(Badge.CONNECTED, vm.tmdb.value.badge)
        assertTrue(vm.tmdb.value.fromBuild)
        assertEquals(R.string.tmdb_rejected, vm.tmdb.value.message)
        assertEquals(Badge.CONNECTED, vm.openSubtitles.value.badge)
        assertEquals(R.string.os_key_rejected, vm.openSubtitles.value.message)
    }

    /** Review R10c: "Couldn't reach… Saved" is for a Save; a build value checked in the background says nothing. */
    @Test fun backgroundCheckThatCantReachSaysNothing() = runTest(dispatcher) {
        val i = integrations(BuildDefaults("build-tmdb-key", "build-os-key", "", ""))
        checks.tmdbResult = { throw TmdbException("offline", TmdbException.Reason.NETWORK) }
        checks.osResult = { throw OpenSubtitlesException("offline", OpenSubtitlesException.Reason.NETWORK) }
        val vm = IntegrationsViewModel(i, checks, appScope)
        advanceUntilIdle()

        assertEquals(1, checks.tmdbCalls.size)
        assertEquals(Badge.UNCHECKED, vm.tmdb.value.badge)
        assertNull(vm.tmdb.value.message)
        assertEquals(Badge.UNCHECKED, vm.openSubtitles.value.badge)
        assertNull(vm.openSubtitles.value.message)
    }

    @Test fun masking() {
        assertEquals("••••wxyz", maskKey("abcd1234wxyz"))
        assertEquals("•••••", maskKey("short"))
        assertEquals("••••5678", maskKey("12345678"))
        assertEquals("•••••••", maskKey("1234567"))
    }
}
