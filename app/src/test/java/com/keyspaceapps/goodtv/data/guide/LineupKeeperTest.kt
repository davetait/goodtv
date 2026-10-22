package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryLineupStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LineupKeeperTest {
    private val ENT = "US| ENTERTAINMENT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val NEWS = "US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"

    private val store = InMemoryLineupStore()
    private var channels = listOf(feed("US: CNN HD", NEWS), feed("US: A&E HD", ENT))
    /** Counts lineup reads: the keeper reads the lineup once per build. */
    private var reads = 0
    /** When set, reading the lineup fails (or, with [stall], never finishes). */
    private var failure: Exception? = null
    private var stall = false
    private val library: LibraryStore = object : LibraryStore by FakeLibraryStore() {
        override fun observeSportsCandidates() = flow<List<ChannelCandidate>> {
            reads++
            if (stall) delay(Long.MAX_VALUE)
            failure?.let { throw it }
            emit(channels)
        }
    }
    private val warnings = ArrayList<String>()

    /** Counts full reads of the saved lineup: a check needs only its stamp. */
    private var savedReads = 0
    /** When set, saving fails (a full disk); [generationFailures] is how many generation subscriptions fail first. */
    private var saveFailure: Exception? = null
    private var generationFailures = 0
    private val lineups: LineupStore = object : LineupStore by store {
        override suspend fun saved(): SavedLineup? { savedReads++; return store.saved() }
        override suspend fun save(lineup: SavedLineup) { saveFailure?.let { throw it }; store.save(lineup) }
        override fun observeGeneration() = flow {
            if (generationFailures > 0) { generationFailures--; throw java.io.IOException("database closed") }
            emitAll(store.observeGeneration())
        }
    }

    private fun feed(name: String, category: String) = ChannelCandidate(LiveChannel(1, name, null, name, null, null), category)

    private fun TestScope.keeper(debug: (String) -> Unit = {}) =
        LineupKeeper(library, lineups, StandardTestDispatcher(testScheduler), DEBOUNCE, warn = { warnings += it }, debug = debug)

    /** The keeper runs in backgroundScope, which advanceUntilIdle ignores: move virtual time well past the debounce instead. */
    private fun TestScope.settle() {
        advanceTimeBy(DEBOUNCE * 5)
        runCurrent()
    }

    private val old = SavedLineup(emptyList(), generation = -1, rulesVersion = LineupBuilder.RULES_VERSION)

    @Test fun keeperSavesOnFirstStart() = runTest {
        keeper().start(backgroundScope)
        settle()
        val saved = store.saved()!!
        assertEquals(LineupBuilder.build(channels), saved.rows)
        assertEquals(0L, saved.generation)
        assertEquals(LineupBuilder.RULES_VERSION, saved.rulesVersion)
        assertEquals(1, reads)
        assertEquals(0, savedReads)
    }

    @Test fun firstCheckRunsAtOnce() = runTest {
        keeper().start(backgroundScope)
        runCurrent()
        assertEquals(1, reads)
        assertEquals(0L, store.saved()!!.generation)
    }

    @Test fun currentLineupIsLeftAlone() = runTest {
        store.save(SavedLineup(emptyList(), generation = 0, rulesVersion = LineupBuilder.RULES_VERSION))
        keeper().start(backgroundScope)
        settle()
        store.bumpGeneration()
        store.save(SavedLineup(emptyList(), generation = 1, rulesVersion = LineupBuilder.RULES_VERSION))
        settle()
        assertEquals(0, reads)
        // Deciding there is nothing to do reads the stamp, never the ~9k saved rows.
        assertEquals(0, savedReads)
        assertEquals(emptyList<SavedRow>(), store.saved()!!.rows)
    }

    @Test fun generationChangeRebuildsOnce() = runTest {
        keeper().start(backgroundScope)
        settle()
        channels = channels + feed("US: BBC NEWS", NEWS)
        repeat(5) {
            store.bumpGeneration()
            advanceTimeBy(DEBOUNCE / 4)
        }
        assertEquals("still settling", 1, reads)
        settle()
        assertEquals(2, reads)
        assertEquals(5L, store.saved()!!.generation)
        assertEquals(LineupBuilder.build(channels), store.saved()!!.rows)
    }

    @Test fun newRulesVersionRebuilds() = runTest {
        store.save(SavedLineup(emptyList(), generation = 0, rulesVersion = LineupBuilder.RULES_VERSION - 1))
        keeper().start(backgroundScope)
        settle()
        assertEquals(1, reads)
        assertEquals(LineupBuilder.RULES_VERSION, store.saved()!!.rulesVersion)
        assertEquals(LineupBuilder.build(channels), store.saved()!!.rows)
    }

    @Test fun generationMovedDuringTheBuildBuildsAgain() = runTest {
        var bumped = false
        val bumping: LibraryStore = object : LibraryStore by library {
            override fun observeSportsCandidates() = flow {
                reads++
                // A sync writes while the keeper is reading the lineup it started from.
                if (!bumped) { bumped = true; store.bumpGeneration() }
                emit(channels)
            }
        }
        LineupKeeper(bumping, lineups, StandardTestDispatcher(testScheduler), DEBOUNCE, debug = {}).start(backgroundScope)
        settle()
        assertEquals(2, reads)
        assertEquals(1L, store.saved()!!.generation)
    }

    @Test fun cancelledBuildKeepsTheOldLineup() = runTest {
        store.save(old)
        stall = true
        val job = keeper().start(backgroundScope)
        advanceTimeBy(DEBOUNCE * 10)
        assertEquals(1, reads)
        job.cancel()
        settle()
        assertSame(old, store.saved())
    }

    /** Cancelled after the build but before the save (the app killed mid-way): the old lineup stays. */
    @Test fun cancelledAfterTheBuildSavesNothing() = runTest {
        store.save(old)
        lateinit var job: Job
        job = keeper(debug = { if (it.startsWith("rows")) job.cancel() }).start(backgroundScope)
        settle()
        assertEquals(1, reads)
        assertTrue(job.isCancelled)
        assertSame(old, store.saved())
    }

    @Test fun cancelledBuildWithNothingSavedSavesNothing() = runTest {
        stall = true
        val job = keeper().start(backgroundScope)
        advanceTimeBy(DEBOUNCE * 10)
        job.cancel()
        settle()
        assertNull(store.saved())
    }

    @Test fun buildFailureKeepsTheOldLineupAndWarns() = runTest {
        store.save(old)
        failure = java.io.IOException("secret path /data/x")
        keeper().start(backgroundScope)
        settle()
        assertSame(old, store.saved())
        assertEquals(listOf("Lineup build failed: IOException"), warnings)
        assertEquals(1, reads)

        // Not retried until the lineup changes; then it is.
        advanceTimeBy(DEBOUNCE * 10)
        assertEquals(1, reads)
        failure = null
        store.bumpGeneration()
        settle()
        assertEquals(2, reads)
        assertEquals(1L, store.saved()!!.generation)
        assertTrue(store.saved()!!.rows.isNotEmpty())
    }

    @Test fun saveFailureWarnsAndRetriesOnTheNextChange() = runTest {
        store.save(old)
        saveFailure = IllegalStateException("disk full at /data/secret")
        keeper().start(backgroundScope)
        settle()
        assertSame(old, store.saved())
        assertEquals(listOf("Lineup build failed: IllegalStateException"), warnings)

        saveFailure = null
        store.bumpGeneration()
        settle()
        assertEquals(2, reads)
        assertEquals(1L, store.saved()!!.generation)
    }

    /** An error thrown (not just an exception) by a build is survived too. */
    @Test fun buildErrorIsSurvived() = runTest {
        store.save(old)
        failure = null
        val erring: LibraryStore = object : LibraryStore by library {
            override fun observeSportsCandidates() = flow<List<ChannelCandidate>> { reads++; if (reads == 1) throw OutOfMemoryError("big"); emit(channels) }
        }
        LineupKeeper(erring, lineups, StandardTestDispatcher(testScheduler), DEBOUNCE, warn = { warnings += it }, debug = {}).start(backgroundScope)
        settle()
        assertEquals(listOf("Lineup build failed: OutOfMemoryError"), warnings)
        store.bumpGeneration()
        settle()
        assertEquals(1L, store.saved()!!.generation)
    }

    @Test fun generationFlowFailureRecovers() = runTest {
        generationFailures = 2
        keeper().start(backgroundScope)
        settle()
        settle()
        assertEquals(listOf("Lineup generation failed: IOException", "Lineup generation failed: IOException"), warnings)
        assertEquals(0L, store.saved()!!.generation)
        store.bumpGeneration()
        settle()
        assertEquals(1L, store.saved()!!.generation)
    }

    private companion object {
        const val DEBOUNCE = 2_000L
    }
}
