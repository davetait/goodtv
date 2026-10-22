package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryLineupStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

    private fun feed(name: String, category: String) = ChannelCandidate(LiveChannel(1, name, null, name, null, null), category)

    private fun TestScope.keeper() = LineupKeeper(library, store, StandardTestDispatcher(testScheduler), DEBOUNCE, warn = { warnings += it }, debug = {})

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
    }

    @Test fun currentLineupIsLeftAlone() = runTest {
        store.save(SavedLineup(emptyList(), generation = 0, rulesVersion = LineupBuilder.RULES_VERSION))
        keeper().start(backgroundScope)
        settle()
        assertEquals(0, reads)
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
        LineupKeeper(bumping, store, StandardTestDispatcher(testScheduler), DEBOUNCE, debug = {}).start(backgroundScope)
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

    private companion object {
        const val DEBOUNCE = 2_000L
    }
}
