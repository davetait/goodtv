package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.data.sports.GameState
import com.keyspaceapps.goodtv.data.sports.GameWithChannels
import com.keyspaceapps.goodtv.data.sports.League
import com.keyspaceapps.goodtv.data.sports.LeagueGames
import com.keyspaceapps.goodtv.data.sports.SportsBoard
import com.keyspaceapps.goodtv.data.sports.SportsGame
import com.keyspaceapps.goodtv.data.sports.Team
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.sports.SportsScreen
import com.keyspaceapps.goodtv.ui.sports.SportsFormats
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The Sports tab with a fixed board (no network). */
@OptIn(ExperimentalTestApi::class)
class SportsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private var played: PlayerKey? = null

    private val sharks = Team("San Jose Sharks", "Sharks", "San Jose", "SJ", null)
    private val habs = Team("Montreal Canadiens", "Canadiens", "Montreal", "MTL", null)
    private val knicks = Team("New York Knicks", "Knicks", "New York", "NY", null)
    private val sixers = Team("Philadelphia 76ers", "76ers", "Philadelphia", "PHI", null)
    private val start = Instant.parse("2026-10-21T00:30:00Z")

    private fun ch(id: String, name: String, category: String) = ChannelCandidate(LiveChannel(7, id, "c", name, null, null), category)

    private val live = SportsGame(League.NHL, "1", Instant.parse("2026-10-20T23:00:00Z"), GameState.LIVE, sharks, habs, "2", "1", "13:16 - 2nd", emptyList())
    private val later = SportsGame(League.NBA, "2", start, GameState.SCHEDULED, knicks, sixers, null, null, null, emptyList())
    private val unmatched = SportsGame(League.NBA, "3", start, GameState.SCHEDULED, Team("Detroit Pistons", "Pistons", "Detroit", "DET", null),
        Team("Boston Celtics", "Celtics", "Boston", "BOS", null), null, null, null, listOf("ESPN+", "NBC Sports BA"))
    private val nothingKnown = unmatched.copy(id = "4", away = Team("Utah Jazz", "Jazz", "Utah", "UTA", null), broadcasters = emptyList())

    private val viaplay = ch("v3", "LIVE | NHL MONTREAL - SAN JOSE | Tue 20 Oct 19:05 EDT (US) | 8K EXCLUSIVE | US: VIAPLAY PPV 3", "US| VIAPLAY NHL PPV")
    private val sweden = ch("se", "LIVE | NHL 76ERS - KNICKS | Wed 21 Oct 02:30 CEST (SE)", "SE| VIAPLAY PPV")
    private val peacock = ch("p5", "US (Peacock 005) | 76ers vs. Knicks (2026-10-20 18:30:05)", "US| PEACOCK PPV")
    private val nbaPass = ch("n2", "LIVE | NYK - KNICKS VS PHI - 76ERS | Tue 20 Oct 20:30 EDT (US)", "US| NBA PASS PPV")

    private val board = SportsBoard(listOf(
        LeagueGames(League.NHL, listOf(GameWithChannels(live, listOf(viaplay))), failed = false),
        LeagueGames(League.NBA, listOf(
            GameWithChannels(later, listOf(sweden, peacock, nbaPass)),
            GameWithChannels(unmatched, emptyList()),
            GameWithChannels(nothingKnown, emptyList()),
        ), failed = false),
    ))

    private fun show(b: SportsBoard = board) = composeRule.setContent { GoodTvTheme { SportsScreen(onPlay = { played = it }, board = flowOf(b)) } }
    private fun waitFor(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitFocused(text: String) =
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText(text, substring = true)).assertIsFocused() }.isSuccess }
    private fun keys(vararg k: String) = composeRule.onRoot().performKeyInput {
        k.forEach { pressKey(when (it) { "R" -> Key.DirectionRight; "L" -> Key.DirectionLeft; "D" -> Key.DirectionDown; "U" -> Key.DirectionUp; "B" -> Key.Back; else -> Key.DirectionCenter }) }
    }

    @Test fun leagueRowsWithLiveBadgeScoreAndStartTime() {
        show()
        waitFor("NHL")
        composeRule.onNodeWithText("NBA").assertIsDisplayed()
        composeRule.onNodeWithText("LIVE", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("2 – 1").assertIsDisplayed()
        composeRule.onNodeWithText("13:16 - 2nd").assertIsDisplayed()
        composeRule.onAllNodes(hasText(SportsFormats.startTime(composeRule.activity, start))).fetchSemanticsNodes().isNotEmpty().let { assertEquals(true, it) }
        waitFocused("Sharks")
    }

    @Test fun oneChannelPlaysAtOnce() {
        show()
        waitFocused("Sharks")
        keys("C")
        composeRule.waitUntil(5_000) { played != null }
        assertEquals(PlayerKey(7, PlayerKey.KIND_LIVE, "v3", null, viaplay.channel.title), played)
    }

    @Test fun severalChannelsOpenThePickerInOrder() {
        show()
        waitFocused("Sharks")
        keys("D")
        waitFocused("On 3 channels")
        keys("C")
        waitFor("Knicks @ 76ers")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size >= 1 }
        // North America first, LIVE before plain; Sweden last.
        waitFocused("NYK - KNICKS")
        keys("D", "C")
        composeRule.waitUntil(5_000) { played != null }
        assertEquals("p5", played!!.refId)
    }

    @Test fun backClosesThePickerOntoTheCard() {
        show()
        waitFocused("Sharks")
        keys("D", "C")
        waitFor("Knicks @ 76ers")
        keys("B")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Knicks @ 76ers")).fetchSemanticsNodes().isEmpty() }
        waitFocused("On 3 channels")
        assertNull(played)
    }

    @Test fun unmatchedGameShowsBroadcasterAndDoesNothing() {
        show()
        waitFor("ESPN+ · NBC Sports BA")
        composeRule.onNodeWithText("Channel not known").assertIsDisplayed()
        waitFocused("Sharks")
        keys("D", "R")
        waitFocused("ESPN+ · NBC Sports BA")
        keys("C")
        composeRule.waitForIdle()
        assertNull(played)
    }

    @Test fun failedLeagueSaysSo() {
        show(SportsBoard(listOf(LeagueGames(League.NHL, emptyList(), failed = true), board.leagues[1])))
        waitFor("Couldn’t load NHL schedule")
        composeRule.onNodeWithText("NBA").assertIsDisplayed()
    }

    @Test fun waitingForSchedulesSaysLoading() {
        composeRule.setContent { GoodTvTheme { SportsScreen(onPlay = { played = it }, board = flow { awaitCancellation() }) } }
        waitFor("Loading today’s games")
    }

    @Test fun nothingTodaySaysSo() {
        show(SportsBoard(emptyList()))
        waitFor("No games on today")
        composeRule.onAllNodes(hasText("NHL")).assertCountEquals(0)
    }

    @Test fun noLeagueSwitchedOnPointsToSettings() {
        show(SportsBoard(emptyList(), anyEnabled = false))
        waitFor("No leagues switched on. Choose some in Settings → Sports.")
        composeRule.onAllNodes(hasText("No games on today")).assertCountEquals(0)
    }

    /** Leaving for the player and coming back rebuilds the screen; focus returns to the card that was opened. */
    @Test fun backFromThePlayerRefocusesTheCard() {
        var showing by mutableStateOf(true)
        composeRule.setContent { GoodTvTheme { if (showing) SportsScreen(onPlay = { played = it; showing = false }, board = flowOf(board)) } }
        waitFocused("Sharks")
        keys("D", "C")
        waitFor("Knicks @ 76ers")
        keys("C")
        composeRule.waitUntil(5_000) { played != null }
        showing = true
        waitFocused("On 3 channels")
    }

    private val atlanta = Team("Atlanta United FC", null, "Atlanta", "ATL", null)
    private val toronto = Team("Toronto FC", null, "Toronto", "TOR", null)
    private val mlsGame = SportsGame(League.MLS, "m1", start, GameState.LIVE, toronto, atlanta, "0", "2", "54'", emptyList())
    private val withMls = SportsBoard(listOf(board.leagues[0], LeagueGames(League.MLS, listOf(GameWithChannels(mlsGame, emptyList())), failed = false)))

    @Test fun filterShowsOneLeague() {
        show(withMls)
        waitFocused("Sharks")
        keys("U")
        waitFocused("All leagues")
        keys("C")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        // All leagues, NHL · 1, MLS · 1: the popup is its own window and takes the keys.
        composeRule.onAllNodes(isRoot())[1].performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Sharks", substring = true)).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithText("Atlanta United FC", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodes(hasText("NHL")).assertCountEquals(0)
        composeRule.onNodeWithTag("sports_league_filter").assertIsDisplayed()
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText("Atlanta United FC", substring = true)).assertIsFocused() }.isSuccess ||
            runCatching { composeRule.onNode(hasText("MLS · ", substring = true)).assertIsFocused() }.isSuccess }
    }

    @Test fun soccerCardReadsHomeVAway() {
        show(withMls)
        waitFor("Atlanta United FC")
        val home = composeRule.onNodeWithText("Atlanta United FC", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        val away = composeRule.onNodeWithText("Toronto FC", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        val v = composeRule.onNodeWithText("v", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        assertEquals(true, home < v && v < away)
        composeRule.onNodeWithText("2 – 0", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("@", useUnmergedTree = true).assertIsDisplayed()
    }
}
