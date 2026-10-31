package com.keyspaceapps.goodtv.ui.sports

import com.keyspaceapps.goodtv.data.sports.League
import com.keyspaceapps.goodtv.data.sports.LeagueGames
import com.keyspaceapps.goodtv.data.sports.SportsBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun row(l: League) = LeagueGames(l, emptyList(), failed = false)
    private val both = SportsBoard(listOf(row(League.MLS), row(League.NHL)))

    @Test fun allIsTheDefault() = runTest {
        val vm = SportsViewModel(MutableStateFlow(both))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.shown.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.filter.collect {} }
        assertNull(vm.filter.value)
        assertEquals(both, vm.shown.value)
    }

    @Test fun filterLimitsToOneLeague() = runTest {
        val vm = SportsViewModel(MutableStateFlow(both))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.shown.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.filter.collect {} }
        vm.selectLeague(League.MLS)
        assertEquals(League.MLS, vm.filter.value)
        assertEquals(SportsBoard(listOf(row(League.MLS))), vm.shown.value)
        vm.selectLeague(null)
        assertEquals(both, vm.shown.value)
    }

    @Test fun filterGoesBackToAllWhenItsLeagueLeaves() = runTest {
        val board = MutableStateFlow(both)
        val vm = SportsViewModel(board)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.shown.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.filter.collect {} }
        vm.selectLeague(League.MLS)
        val next = SportsBoard(listOf(row(League.NHL), row(League.NBA)))
        board.value = next
        assertNull(vm.filter.value)
        assertEquals(next, vm.shown.value)
    }
}
