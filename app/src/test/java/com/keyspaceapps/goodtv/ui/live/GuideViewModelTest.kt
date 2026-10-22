package com.keyspaceapps.goodtv.ui.live

import com.keyspaceapps.goodtv.data.guide.GuideBoard
import com.keyspaceapps.goodtv.data.guide.GuideFilter
import com.keyspaceapps.goodtv.data.guide.GuideSearchHit
import com.keyspaceapps.goodtv.data.guide.GuideSource
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The guide's time window: half-hour pages between the current half-hour and 45 hours on, following the clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModelTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val savedZone = TimeZone.getDefault()
    private val slot = GuideTime.SLOT_MS
    private val start = 1_792_000_800_000L // a :00 in every whole-hour zone
    private var now = start + 10 * 60_000L // 10 past

    private val queries = mutableListOf<String>()
    private var eventRefreshes = 0
    private val source = object : GuideSource {
        override fun board(filter: GuideFilter, windowStartMs: Long): Flow<GuideBoard> = flowOf(GuideBoard(emptyList(), windowStartMs))
        override fun search(query: String): Flow<List<GuideSearchHit>> { queries += query; return flowOf(emptyList()) }
        override suspend fun setFavourite(rowKey: String, favourite: Boolean) = Unit
        override suspend fun refreshEventSlots() { eventRefreshes++ }
    }
    private val main = UnconfinedTestDispatcher()

    @Before fun setUp() {
        TimeZone.setDefault(utc)
        Dispatchers.setMain(main)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(savedZone)
    }

    private fun vm() = GuideViewModel(source, { now })

    @Test fun windowStartsAtTheCurrentHalfHour() = assertEquals(start, vm().window)

    @Test fun leftAtTheCurrentHalfHourDoesNothing() {
        val vm = vm()
        assertNull(vm.page(forward = false))
        assertEquals(start, vm.window)
    }

    @Test fun pagesHalfAnHourEachWayButNeverBeforeNow() {
        val vm = vm()
        assertEquals(start + slot, vm.page(forward = true))
        assertEquals(start, vm.page(forward = false))
        assertNull(vm.page(forward = false))
    }

    @Test fun rightStopsWhereTheWindowEndsTwoDaysOn() {
        val vm = vm()
        var pages = 0
        while (vm.page(forward = true) != null) pages++
        assertEquals(start + 45 * 3_600_000L, vm.window)
        assertEquals(90, pages)
        assertEquals(start + 48 * 3_600_000L, vm.window + GuideTime.WINDOW_MS)
    }

    @Test fun afterAnHourTheWindowFollowsTheClockAndLeftNeverJumpsForward() {
        val vm = vm()
        now += 3_600_000L // 11 past the next hour
        // Left from a window left behind by the clock goes to the channel; the window moves up to now.
        assertNull(vm.page(forward = false))
        assertEquals(start + 2 * slot, vm.window)
        assertEquals(start + 3 * slot, vm.page(forward = true))
    }

    @Test fun theClockTickMovesTheWindowOn() {
        val vm = vm()
        now += 3 * 3_600_000L
        // A new subscriber (the screen coming back) reads the clock at once.
        val job = CoroutineScope(Dispatchers.Main).launch { vm.now.collect { } }
        assertEquals(start + 6 * slot, vm.window)
        job.cancel()
    }

    @Test fun searchWaitsForTypingToSettle() = runTest(main) {
        val vm = vm()
        val results = mutableListOf<List<GuideSearchHit>?>()
        backgroundScope.launch { vm.results.collect { results += it } }
        vm.setQuery("h")
        advanceTimeBy(100)
        vm.setQuery("ha")
        advanceTimeBy(100)
        vm.setQuery("han ")
        advanceTimeBy(249)
        assertEquals(emptyList<String>(), queries)
        advanceTimeBy(2)
        assertEquals(listOf("han"), queries)
        assertEquals(emptyList<GuideSearchHit>(), results.last())
        // Clearing shows the grid again at once.
        vm.setQuery("")
        runCurrent()
        assertNull(results.last())
    }

    @Test fun theEventsChipRefreshesEventSlots() {
        val vm = vm()
        vm.select(GuideFilter.NEWS)
        assertEquals(0, eventRefreshes)
        vm.select(GuideFilter.EVENTS)
        assertEquals(1, eventRefreshes)
    }
}
