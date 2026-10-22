package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import com.keyspaceapps.goodtv.data.guide.GuideBoard
import com.keyspaceapps.goodtv.data.guide.GuideFilter
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.guide.GuideRow
import com.keyspaceapps.goodtv.data.guide.GuideSearchHit
import com.keyspaceapps.goodtv.data.guide.GuideSection
import com.keyspaceapps.goodtv.data.guide.GuideSource
import com.keyspaceapps.goodtv.data.guide.LiveGenre
import com.keyspaceapps.goodtv.data.guide.Network
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.label
import com.keyspaceapps.goodtv.ui.live.GuideScreen
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.sports.SportsFormats
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The Live tab's guide with a fake guide (any filter, any window) and the clock fixed at 18:10 today. */
@OptIn(ExperimentalTestApi::class)
class GuideScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    private var played: PlayerKey? = null

    private val day = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0)
    private fun at(h: Int, m: Int = 0) = day.withHour(h).withMinute(m).toInstant().toEpochMilli()
    private val now = at(18, 10)

    private fun feed(id: String, name: String, category: String = "US| NEWS") = ChannelCandidate(LiveChannel(7, id, "c", name, null, null), category)
    private fun p(guide: String, from: Long, to: Long, title: String, desc: String? = null) =
        GuideProgramme(guide, from, to, title, desc, listOf("News"), null)

    private val foxFeed = feed("fx", "US: FOX NEWS")
    private val fox = GuideRow(
        "fox-news", "Fox News", null, LiveGenre.NEWS, listOf(foxFeed),
        listOf(
            p("fn", at(17, 30), at(19), "Special Report", "The day's top political news."),
            p("fn", at(19), at(20), "The Ingraham Angle", "Opinion and interviews."),
            p("fn", at(20), at(21), "Jesse Watters Primetime"),
            p("fn", at(21), at(22), "Hannity"),
        ),
        loopShow = null, favourite = false,
    )
    private val cnnFeeds = listOf(feed("cn1", "US: CNN HD"), feed("cn2", "US: CNN FHD"))
    private val cnn = GuideRow(
        "cnn", "CNN", null, LiveGenre.NEWS, cnnFeeds,
        listOf(p("cn", at(18), at(19), "The Situation Room"), p("cn", at(19), at(21), "Anderson Cooper 360")),
        loopShow = null, favourite = false,
    )
    private val msnbcFeed = feed("ms", "US: MSNBC")
    private val msnbc = GuideRow(
        "msnbc", "MSNBC", null, LiveGenre.NEWS, listOf(msnbcFeed),
        listOf(p("ms", at(18), at(19), "Deadline: White House"), p("ms", at(19), at(22), "All In")),
        loopShow = null, favourite = false,
    )
    private fun station(key: String, name: String) = GuideRow(
        key, name, null, LiveGenre.NETWORK, listOf(feed(key, name, "US| NBC")),
        listOf(p(key, at(18), at(18, 30), "Nightly News"), p(key, at(18, 30), at(19), "Access Hollywood")),
        loopShow = null, favourite = false,
    )
    private val rock = GuideRow("30 rock", "30 Rock", null, LiveGenre.COMEDY, listOf(feed("r", "24/7: 30 ROCK", "24/7 COMEDY")), emptyList(), "30 Rock", false)
    private val silent = GuideRow("some channel", "Some Channel", null, LiveGenre.ENTERTAINMENT, listOf(feed("s", "SOME CHANNEL", "US| TV")), emptyList(), null, false)

    private val news = GuideSection("News", null, listOf(fox, cnn, msnbc), emptyList())
    private val nbc = GuideSection("NBC", Network.NBC, listOf(station("wnbc", "NBC 4 New York")), listOf(station("wral", "NBC 5 Raleigh"), station("wxia", "NBC 11 Atlanta")))

    /** Any filter gets [sections]; each window keeps only the programmes overlapping it. Favourite changes are recorded. */
    private inner class FakeGuide(initial: List<GuideSection>) : GuideSource {
        val sections = MutableStateFlow(initial)
        val favourites = mutableListOf<Pair<String, Boolean>>()
        val windows = MutableStateFlow<List<Long>>(emptyList())
        override fun board(filter: GuideFilter, windowStartMs: Long): Flow<GuideBoard> {
            windows.value = windows.value + windowStartMs
            val end = windowStartMs + 3 * 3_600_000L
            fun clip(r: GuideRow) = r.copy(programmes = r.programmes.filter { it.endMs > windowStartMs && it.startMs < end })
            return sections.map { all -> GuideBoard(all.map { s -> s.copy(rows = s.rows.map(::clip), more = s.more.map(::clip)) }, windowStartMs) }
        }
        override fun search(query: String): Flow<List<GuideSearchHit>> = flowOf(emptyList())
        override suspend fun setFavourite(rowKey: String, favourite: Boolean) { favourites += rowKey to favourite }
        override suspend fun refreshEventSlots() = Unit
    }

    private fun show(guide: FakeGuide = FakeGuide(listOf(news, nbc))): FakeGuide {
        composeRule.setContent { GoodTvTheme { GuideScreen(onPlay = { played = it }, source = guide, clock = { now }) } }
        return guide
    }

    private fun time(h: Int, m: Int) = SportsFormats.startTime(composeRule.activity, Instant.ofEpochMilli(at(h, m)))

    private fun waitFor(text: String) = try {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
    }
    private fun waitFocused(text: String) = try {
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText(text)).assertIsFocused() }.isSuccess }
    } catch (e: Throwable) {
        composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
    }
    private fun keys(vararg k: String) = composeRule.onRoot().performKeyInput {
        k.forEach { pressKey(when (it) { "R" -> Key.DirectionRight; "L" -> Key.DirectionLeft; "D" -> Key.DirectionDown; "U" -> Key.DirectionUp; "B" -> Key.Back; "M" -> Key.Menu; else -> Key.DirectionCenter }) }
    }
    private fun firstHeader(text: String) =
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("guide_time_0").assertTextEquals(text) }.isSuccess }

    @Test fun chipsAndSectionsShow() {
        show()
        waitFor("Special Report")
        for (chip in listOf("All", "Favourites", "Network", "News", "Sports", "Entertainment", "Comedy", "Movies", "Kids", "Documentary", "Events")) {
            assertTrue(chip, composeRule.onAllNodes(hasText(chip)).fetchSemanticsNodes().isNotEmpty())
        }
        composeRule.onNodeWithText("Network · NBC").assertIsDisplayed()
        composeRule.onNodeWithText("NBC 4 New York").assertIsDisplayed()
        composeRule.onNodeWithText("Fox News").assertIsDisplayed()
        firstHeader(time(18, 0))
    }

    @Test fun focusStartsOnTheProgrammeOnNowInTheFirstRow() {
        show()
        waitFocused("Special Report")
        // The details panel follows focus.
        waitFor("Special Report · Fox News")
        composeRule.onNodeWithText("The day's top political news.").assertIsDisplayed()
        composeRule.onNodeWithText("50 min left", substring = true).assertIsDisplayed()
    }

    @Test fun rightPastTheLastColumnPagesForwardHalfAnHour() {
        val guide = show()
        waitFocused("Special Report")
        keys("R")
        waitFocused("The Ingraham Angle")
        keys("R")
        waitFocused("Jesse Watters Primetime")
        keys("R")
        firstHeader(time(18, 30))
        waitFocused("Hannity")
        assertEquals(at(18, 30), guide.windows.value.last())
    }

    @Test fun leftNeverGoesBeforeNow() {
        show()
        waitFocused("Special Report")
        keys("R", "R", "R")
        firstHeader(time(18, 30))
        waitFocused("Hannity")
        keys("L", "L", "L")
        waitFocused("Special Report")
        keys("L")
        firstHeader(time(18, 0))
        waitFocused("Special Report")
        // At the current half-hour, Left goes to the channel, never further back in time.
        keys("L")
        waitFocused("Fox News")
        firstHeader(time(18, 0))
    }

    @Test fun selectOnNowPlaysTheSingleFeed() {
        show()
        waitFocused("Special Report")
        keys("C")
        composeRule.waitUntil(5_000) { played != null }
        assertEquals(PlayerKey(7, PlayerKey.KIND_LIVE, "fx", null, foxFeed.channel.label), played)
    }

    @Test fun selectWithSeveralFeedsOpensThePicker() {
        show()
        waitFocused("Special Report")
        keys("D")
        waitFocused("The Situation Room")
        keys("C")
        waitFor("US: CNN HD")
        // Ordered as Sports orders feeds: North American and English first, then by name.
        waitFocused("US: CNN FHD")
        assertNull(played)
        keys("D", "C")
        composeRule.waitUntil(5_000) { played != null }
        assertEquals("cn1", played!!.refId)
    }

    @Test fun selectOnALaterProgrammeOnlyShowsDetails() {
        show()
        waitFocused("Special Report")
        keys("R")
        waitFocused("The Ingraham Angle")
        keys("C")
        composeRule.waitForIdle()
        assertNull(played)
        waitFor("The Ingraham Angle · Fox News")
        composeRule.onNodeWithText("Opinion and interviews.").assertIsDisplayed()
        composeRule.onNodeWithText("${time(19, 0)} – ${time(20, 0)}", substring = true).assertIsDisplayed()
    }

    @Test fun menuOnChannelOffersFavourite() {
        val guide = show()
        waitFocused("Special Report")
        keys("L")
        waitFocused("Fox News")
        keys("M")
        waitFor("Add to favourites")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText("Add to favourites")).assertIsFocused() }.isSuccess }
        composeRule.onAllNodes(isRoot())[1].performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { guide.favourites.isNotEmpty() }
        assertEquals(listOf("fox-news" to true), guide.favourites)
    }

    @Test fun moreRowExpands() {
        show(FakeGuide(listOf(nbc)))
        waitFocused("Nightly News")
        assertEquals(0, composeRule.onAllNodes(hasText("NBC 5 Raleigh")).fetchSemanticsNodes().size)
        keys("D")
        waitFocused("More NBC · 2")
        keys("C")
        waitFor("NBC 5 Raleigh")
        composeRule.onNodeWithText("NBC 11 Atlanta").assertIsDisplayed()
        waitFocused("Fewer NBC")
        keys("C")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("NBC 5 Raleigh")).fetchSemanticsNodes().isEmpty() }
        waitFocused("More NBC · 2")
    }

    @Test fun noGuideRowSaysSo() {
        show(FakeGuide(listOf(GuideSection("Entertainment", null, listOf(silent), emptyList()))))
        waitFor("No guide info")
        waitFocused("No guide info")
        keys("C")
        composeRule.waitUntil(5_000) { played != null }
        assertEquals("s", played!!.refId)
    }

    @Test fun loopRowShowsAllDay() {
        show(FakeGuide(listOf(GuideSection("Comedy", null, listOf(rock), emptyList()))))
        waitFor("30 Rock · all day")
        waitFocused("30 Rock · all day")
    }

    /** Leaving for the player and coming back rebuilds the screen; focus returns to the cell that was played. */
    @Test fun backFromThePlayerReturnsToTheSameCell() {
        val guide = FakeGuide(listOf(news, nbc))
        var showing by mutableStateOf(true)
        composeRule.setContent { GoodTvTheme { if (showing) GuideScreen(onPlay = { played = it; showing = false }, source = guide, clock = { now }) } }
        waitFocused("Special Report")
        keys("D", "D")
        waitFocused("Deadline: White House")
        keys("C")
        composeRule.waitUntil(5_000) { played != null }
        assertEquals("ms", played!!.refId)
        showing = true
        waitFocused("Deadline: White House")
    }

    @Test fun programmeCrossingWindowEdgeIsClipped() {
        show()
        waitFocused("Special Report")
        val header = composeRule.onNodeWithTag("guide_time_0").fetchSemanticsNode()
        val second = composeRule.onNodeWithTag("guide_time_1").fetchSemanticsNode()
        val cell = composeRule.onNode(hasText("Special Report")).fetchSemanticsNode()
        val column = second.positionInRoot.x - header.positionInRoot.x
        // 17:30–19:00 starts at the window's left edge (18:00) and spans two half-hour columns.
        assertTrue("left ${cell.positionInRoot.x} vs ${header.positionInRoot.x}", abs(cell.positionInRoot.x - header.positionInRoot.x) < 4f)
        assertTrue("width ${cell.size.width} vs column $column", abs(cell.size.width - 2 * column) < 0.1f * column)
        composeRule.onNodeWithTag("guide_now_line").assertExists()
    }

    /** The guide download lands while a "No guide info" cell has focus: focus moves to what's on now in that row. */
    @Test fun focusSurvivesTheGuideArriving() {
        val guide = show(FakeGuide(listOf(GuideSection("News", null, listOf(cnn.copy(programmes = emptyList()), msnbc), emptyList()))))
        waitFocused("No guide info")
        guide.sections.value = listOf(news)
        waitFocused("The Situation Room")
    }

    @Test fun chipChangeResetsTheWindowAndDownGoesToNow() {
        val guide = show()
        waitFocused("Special Report")
        keys("R", "R", "R")
        firstHeader(time(18, 30))
        waitFocused("Hannity")
        keys("U")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Hannity") and isFocused()).fetchSemanticsNodes().isEmpty() }
        keys("C")
        // The chip keeps focus (flicking through chips), the window is back at now.
        firstHeader(time(18, 0))
        assertEquals(at(18, 0), guide.windows.value.last())
        assertEquals(0, composeRule.onAllNodes(isFocused() and (hasText("Special Report") or hasText("Hannity"))).fetchSemanticsNodes().size)
        keys("D")
        waitFocused("Special Report")
    }
}
