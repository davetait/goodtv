package com.keyspaceapps.goodtv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesException
import com.keyspaceapps.goodtv.data.subtitles.OpenSubtitlesLogin
import com.keyspaceapps.goodtv.data.subtitles.SubtitleQuery
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.PopularMoviesQuery
import com.keyspaceapps.goodtv.data.tmdb.PopularRepository
import com.keyspaceapps.goodtv.data.tmdb.PopularStore
import com.keyspaceapps.goodtv.data.tmdb.TmdbException
import com.keyspaceapps.goodtv.data.tmdb.TmdbKind
import com.keyspaceapps.goodtv.data.tmdb.TmdbRepository
import com.keyspaceapps.goodtv.testing.InMemoryTmdbCache
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The API clients wired to [Integrations] the way AppGraph wires them. */
@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationFeedbackTest {
    private val server = MockWebServer()
    private val none = BuildDefaults("", "", "", "")
    private val movie = """{"id":603,"title":"The Matrix"}"""

    /** Counts writes, to show an unchanged status isn't written again. */
    private class CountingStore(private val inner: DataStore<Preferences> = InMemoryDataStore()) : DataStore<Preferences> by inner {
        var writes = 0
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences { writes++; return inner.updateData(transform) }
    }

    private fun TestScope.integrations(store: DataStore<Preferences> = InMemoryDataStore(), defaults: BuildDefaults = none) =
        Integrations(store, defaults, CoroutineScope(StandardTestDispatcher(testScheduler)))

    private fun TestScope.tmdb(integrations: Integrations, disabled: Boolean = false) = integratedTmdbApi(
        OkHttpClient(), integrations, CoroutineScope(StandardTestDispatcher(testScheduler)), disabled = { disabled },
        baseUrl = server.url("/3/"), io = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.openSubtitles(integrations: Integrations, disabled: Boolean = false) = integratedOpenSubtitlesApi(
        OkHttpClient(), integrations, CoroutineScope(StandardTestDispatcher(testScheduler)), disabled = { disabled },
        baseUrl = server.url("/api/v1/"), io = StandardTestDispatcher(testScheduler), loginScheme = "http",
    )

    private suspend fun expectTmdbAuth(block: suspend () -> Unit) {
        try { block(); fail("expected AUTH") } catch (e: TmdbException) { assertEquals(TmdbException.Reason.AUTH, e.reason) }
    }

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    @Test fun rejectedCallMarksStatusRejected() = runTest {
        val i = integrations()
        i.saveTmdb("typed-key", KeyStatus.CONNECTED)
        server.enqueue(MockResponse(code = 401, body = """{"success":false,"status_code":7}"""))
        expectTmdbAuth { tmdb(i).movie(603, "en-US") }
        advanceUntilIdle()
        assertEquals(KeyStatus.REJECTED_KEY, i.tmdb.value.status)
        assertEquals("typed-key", server.takeRequest().url.queryParameter("api_key"))
    }

    @Test fun successAfterUncheckedMarksConnected() = runTest {
        val store = CountingStore()
        val i = integrations(store)
        i.saveTmdb("typed-key", KeyStatus.UNCHECKED)
        val api = tmdb(i)
        repeat(3) { server.enqueue(MockResponse(code = 200, body = movie)) }
        api.movie(603, "en-US")
        advanceUntilIdle()
        assertEquals(KeyStatus.CONNECTED, i.tmdb.value.status)
        val writes = store.writes
        api.movie(603, "en-US")
        api.movie(603, "en-US")
        advanceUntilIdle()
        assertEquals("an unchanged status isn't written again", writes, store.writes)
    }

    @Test fun requestsWaitForTheSavedKeyNotTheBuildDefault() = runTest {
        val inner = InMemoryDataStore()
        integrations(inner).saveTmdb("typed-key", KeyStatus.UNCHECKED)
        val slow = object : DataStore<Preferences> by inner {
            override val data = flow { delay(1_000); emitAll(inner.data) }
        }
        val i = integrations(slow, BuildDefaults("build-key", "", "", ""))
        assertEquals("build-key", i.tmdb.value.key)
        server.enqueue(MockResponse(code = 200, body = movie))
        tmdb(i).movie(603, "en-US")
        assertEquals("typed-key", server.takeRequest().url.queryParameter("api_key"))
    }

    @Test fun theTestSwitchKeepsTmdbOffEvenWithATypedKey() = runTest {
        val i = integrations()
        i.saveTmdb("typed-key", KeyStatus.CONNECTED)
        advanceUntilIdle()
        val api = tmdb(i, disabled = true)
        assertFalse(api.isConfigured)
        expectTmdbAuth { api.movie(603, "en-US") }
        assertEquals(0, server.requestCount)
        assertTrue(tmdb(i).isConfigured)
    }

    @Test fun openSubtitlesLoginRejectionIsRecorded() = runTest {
        val i = integrations()
        val access = OpenSubtitlesAccess("os-key", OpenSubtitlesLogin("user", "wrong"))
        i.saveOpenSubtitles(access, KeyStatus.CONNECTED)
        server.enqueue(MockResponse(code = 401, body = """{"message":"Invalid username/password"}"""))
        try { openSubtitles(i).search(SubtitleQuery(603)); fail() } catch (e: OpenSubtitlesException) { assertEquals(OpenSubtitlesException.Reason.AUTH, e.reason) }
        advanceUntilIdle()
        assertEquals(KeyStatus.REJECTED_LOGIN, i.openSubtitles.value.status)
    }

    /** Review R5: Save's check found the key bad; a later live login refusal mustn't relabel it a login problem. */
    @Test fun aStoredRejectedKeyIsNotReplacedByALiveLoginRejection() = runTest {
        val i = integrations()
        val access = OpenSubtitlesAccess("os-key", OpenSubtitlesLogin("user", "pass"))
        i.saveOpenSubtitles(access, KeyStatus.REJECTED_KEY)
        server.enqueue(MockResponse(code = 401, body = """{"message":"Invalid username/password"}"""))
        try { openSubtitles(i).search(SubtitleQuery(603)); fail() } catch (e: OpenSubtitlesException) { assertEquals(OpenSubtitlesException.Reason.AUTH, e.reason) }
        advanceUntilIdle()
        assertEquals(KeyStatus.REJECTED_KEY, i.openSubtitles.value.status)
    }

    /** Review R6: until the saved values load, "configured" can't be known, so callers go ahead and the request waits. */
    @Test fun configuredBeforeLoadIsTrueAndAfterLoadFollowsTheValue() = runTest {
        val inner = InMemoryDataStore()
        integrations(inner).apply {
            saveTmdb("typed-key", KeyStatus.CONNECTED)
            saveOpenSubtitles(OpenSubtitlesAccess("os-key", null), KeyStatus.CONNECTED)
        }
        val slow = object : DataStore<Preferences> by inner {
            override val data = flow { delay(1_000); emitAll(inner.data) }
        }
        val typed = integrations(slow)
        assertFalse(typed.loaded.value)
        assertTrue(tmdb(typed).isConfigured)
        assertTrue(openSubtitles(typed).isConfigured)
        assertFalse("the test switch still wins", tmdb(typed, disabled = true).isConfigured)
        assertFalse(openSubtitles(typed, disabled = true).isConfigured)
        advanceUntilIdle()
        assertTrue(tmdb(typed).isConfigured)

        val nothing = integrations()
        advanceUntilIdle()
        assertTrue(nothing.loaded.value)
        assertFalse(tmdb(nothing).isConfigured)
        assertFalse(openSubtitles(nothing).isConfigured)
    }

    /** R6 follow-up: a lookup made before loading, with no key anywhere, is "TMDB off", not a remembered failure. */
    @Test fun aStartupLookupWithNoKeyAnywhereIsTmdbOff() = runTest {
        val slow = object : DataStore<Preferences> by InMemoryDataStore() {
            override val data = flow { delay(1_000); emitAll(InMemoryDataStore().data) }
        }
        val i = integrations(slow)
        val api = tmdb(i)
        val repo = TmdbRepository(api, InMemoryTmdbCache(), language = { "en-US" })
        val popular = PopularRepository(api, object : PopularStore {
            override suspend fun fetchedAt(listKey: String): Long? = null
            override suspend fun replace(listKey: String, tmdbIds: List<Long>, fetchedAt: Long) = Unit
            override fun observeMovies(listKey: String, limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.keyspaceapps.goodtv.domain.Movie>())
            override fun observeSeries(listKey: String, limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.keyspaceapps.goodtv.domain.Series>())
        }, PopularMoviesQuery, { "en-US" })
        assertTrue(api.isConfigured)
        assertEquals(Result.success(null), repo.lookup(TmdbKind.MOVIE, 603, "The Matrix"))
        assertTrue(popular.refreshIfStale().isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test fun openSubtitlesSuccessMarksConnectedAndTheSwitchKeepsItOff() = runTest {
        val i = integrations()
        i.saveOpenSubtitles(OpenSubtitlesAccess("os-key", null), KeyStatus.UNCHECKED)
        advanceUntilIdle()
        assertFalse(openSubtitles(i, disabled = true).isConfigured)
        server.enqueue(MockResponse(code = 200, body = """{"total_count":0,"data":[]}"""))
        openSubtitles(i).search(SubtitleQuery(603))
        advanceUntilIdle()
        assertEquals(KeyStatus.CONNECTED, i.openSubtitles.value.status)
        assertEquals("os-key", server.takeRequest().headers["Api-Key"])
    }
}
