package com.keyspaceapps.goodtv.ui.sources

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceException
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.SourceInfo
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class EditSourceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeLibraryStore()
    private val source = FakeChannelSource()
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val registry = SourceRegistry(listOf(source))
        repo = LibraryRepository(store, registry, SyncManager(store, registry, kotlinx.coroutines.CoroutineScope(dispatcher), { 7L }), AppSettings(InMemoryDataStore()))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun testAndSaveShowsErrorOnFailure() = runTest(dispatcher) {
        source.validateResult = Result.failure(SourceException("Invalid username or password"))
        val vm = EditSourceViewModel(repo, sourceId = null)
        vm.update(server = "http://s", username = "u", password = "wrong")

        vm.testAndSave()
        advanceUntilIdle()

        assertEquals("Invalid username or password", vm.form.value.error)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.testing)
        assertTrue(store.sources.value.isEmpty())
    }

    @Test
    fun testAndSaveSavesWithAllowedFormatsAndDefaultNickname() = runTest(dispatcher) {
        source.validateResult = Result.success(SourceInfo("Active", 1_900_000_000_000L, 2, listOf("ts", "m3u8")))
        source.catalogResults[1L] = { Result.success(FakeChannelSource.catalog(1L, "A")) }
        val vm = EditSourceViewModel(repo, sourceId = null)
        vm.update(server = "panel.example:8080/", username = "u", password = "p")

        vm.testAndSave()
        advanceUntilIdle()

        val saved = store.sources.value.single()
        assertEquals("panel.example", saved.nickname)
        assertEquals(SourceConfig.Xtream("http://panel.example:8080", "u", "p", listOf("ts", "m3u8")), saved.config)
        assertTrue(vm.form.value.saved)
        assertNull(vm.form.value.error)
        assertTrue(vm.form.value.result!!, vm.form.value.result!!.startsWith("Active"))
    }

    @Test
    fun blankServerIsValidationError() = runTest(dispatcher) {
        val vm = EditSourceViewModel(repo, sourceId = null)
        vm.update(username = "u", password = "p")

        vm.testAndSave()
        advanceUntilIdle()

        assertEquals("Server URL is required", vm.form.value.error)
        assertEquals(0, source.validateCalls)
    }

    @Test
    fun loadsExistingSourceIntoFormAndUpdatesIt() = runTest(dispatcher) {
        val id = store.insertSource(SourceRecord(0, SourceType.XTREAM, "Old", SourceConfig.Xtream("http://old", "ou", "op", listOf("ts")), true, null, null))
        source.catalogResults[id] = { Result.success(FakeChannelSource.catalog(id, "A")) }
        val vm = EditSourceViewModel(repo, sourceId = id)
        advanceUntilIdle()
        assertEquals("Old", vm.form.value.nickname)
        assertEquals("http://old", vm.form.value.server)
        assertEquals("ou", vm.form.value.username)

        vm.update(nickname = "New")
        vm.testAndSave()
        advanceUntilIdle()

        assertEquals(listOf("New"), store.sources.value.map { it.nickname })
        assertTrue(vm.form.value.saved)
    }
}
