package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToLog
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.guide.GuideBoard
import com.keyspaceapps.goodtv.data.guide.GuideFilter
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.guide.GuideRow
import com.keyspaceapps.goodtv.data.guide.GuideSearchHit
import com.keyspaceapps.goodtv.data.guide.GuideSection
import com.keyspaceapps.goodtv.data.guide.GuideSource
import com.keyspaceapps.goodtv.data.guide.LiveGenre
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.ui.live.GuideScreen
import com.keyspaceapps.goodtv.ui.live.PreviewEngine
import com.keyspaceapps.goodtv.ui.live.PreviewHandle
import com.keyspaceapps.goodtv.ui.live.PreviewStatus
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import java.io.File
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The guide's details panel with Live previews on: it doubles, plays the row focus rests on (local test
 * media, never a provider) and stops it when focus moves on or the guide goes. The emulator draws video
 * black, so these check the session and the layout, not pixels. The setting lives in the emulator's real
 * settings, so it's put back off after each test, with the test hooks.
 */
@OptIn(ExperimentalTestApi::class)
class LivePreviewUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(90)

    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()

    private val day = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0)
    private fun at(h: Int, m: Int = 0) = day.withHour(h).withMinute(m).toInstant().toEpochMilli()
    private val now = at(18, 10)

    private fun feed(id: String, name: String) = ChannelCandidate(LiveChannel(7, id, "c", name, null, null), "US| NEWS")
    private fun p(guide: String, from: Long, to: Long, title: String) = GuideProgramme(guide, from, to, title, null, listOf("News"), null)

    private val fox = GuideRow(
        "fox-news", "Fox News", null, LiveGenre.NEWS, listOf(feed("fx", "US: FOX NEWS")),
        listOf(p("fn", at(17, 30), at(19), "Special Report"), p("fn", at(19), at(20), "The Ingraham Angle")),
        loopShow = null, favourite = false,
    )
    // The picker puts FHD first, so that's the copy the preview plays.
    private val cnn = GuideRow(
        "cnn", "CNN", null, LiveGenre.NEWS, listOf(feed("cn1", "US: CNN HD"), feed("cn2", "US: CNN FHD")),
        listOf(p("cn", at(18), at(19), "The Situation Room")),
        loopShow = null, favourite = false,
    )
    private val guide = object : GuideSource {
        override fun board(filter: GuideFilter, windowStartMs: Long): Flow<GuideBoard> {
            val end = windowStartMs + 3 * 3_600_000L
            fun clip(r: GuideRow) = r.copy(programmes = r.programmes.filter { it.endMs > windowStartMs && it.startMs < end })
            return flowOf(GuideBoard(listOf(GuideSection("News", null, listOf(fox, cnn).map(::clip), emptyList())), windowStartMs))
        }
        override fun search(query: String): Flow<List<GuideSearchHit>> = flowOf(emptyList())
        override suspend fun setFavourite(rowKey: String, favourite: Boolean) = Unit
        override suspend fun refreshEventSlots() = Unit
    }

    private lateinit var media: String

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "player_tracks.mkv")
        instrumentation.context.assets.open("player_tracks.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        media = file.toURI().toString()
        graph.previewResolverOverride = { media }
        graph.previewEngineOverride = null
        runBlocking { graph.settings.setLivePreviews(false) }
    }

    @After
    fun tearDown() {
        runBlocking { graph.settings.setLivePreviews(false) }
        graph.previewResolverOverride = null
        graph.previewEngineOverride = null
    }

    private var shown by mutableStateOf(true)

    private fun show(previews: Boolean) {
        runBlocking { graph.settings.setLivePreviews(previews) }
        composeRule.setContent { GoodTvTheme { if (shown) GuideScreen(onPlay = {}, source = guide, clock = { now }) } }
    }

    private fun current() = graph.livePreviews.current.value

    private fun waitFor(text: String) = try {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
    }
    private fun waitFocused(text: String) = try {
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNode(hasText(text)).assertIsFocused() }.isSuccess }
    } catch (e: Throwable) {
        composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
    }
    private fun press(key: Key) = composeRule.onRoot().performKeyInput { pressKey(key) }

    private fun waitPlaying(rowKey: String, timeoutMs: Long = 5_000) =
        composeRule.waitUntil(timeoutMs) { current()?.let { (t, h) -> t.rowKey == rowKey && h.state.value == PreviewStatus.PLAYING } == true }

    private fun panelHeightDp(): Float {
        val node = composeRule.onNode(hasTestTag("guide_details"), useUnmergedTree = true).fetchSemanticsNode()
        return with(composeRule.density) { node.size.height.toDp().value }
    }

    @Test fun panelDoublesWithPreviewsOn() {
        show(previews = true)
        waitFocused("Special Report")
        composeRule.waitUntil(5_000) { abs(panelHeightDp() - 228f) < 1f }
        // The picture is 16:9 at the panel's inner height (228 less 12 dp padding top and bottom).
        val picture = composeRule.onNode(hasTestTag("guide_preview"), useUnmergedTree = true).fetchSemanticsNode()
        val (w, h) = with(composeRule.density) { picture.size.width.toDp().value to picture.size.height.toDp().value }
        assertEquals(204f, h, 1f)
        assertEquals(204f * 16 / 9, w, 1f)

        runBlocking { graph.settings.setLivePreviews(false) }
        composeRule.waitUntil(5_000) { abs(panelHeightDp() - 114f) < 1f }
    }

    /** Back on Live TV (from another tab or the player), the panel is already 228 dp on the first frame: the rows never jump. */
    @Test fun panelIsFullHeightOnTheFirstFrameBack() {
        show(previews = true)
        waitFocused("Special Report")
        composeRule.waitUntil(5_000) { abs(panelHeightDp() - 228f) < 1f }
        shown = false
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        shown = true
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(228f, panelHeightDp(), 1f)
        composeRule.mainClock.autoAdvance = true
    }

    @Test fun restingOnARowPlaysItsPreview() {
        show(previews = true)
        waitFocused("Special Report")
        waitPlaying("fox-news")
        assertEquals("fx", current()!!.first.channelId)
        waitFor("LIVE")
        assertTrue(composeRule.onAllNodes(hasTestTag("guide_preview"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun movingAwayStopsThePreview() {
        show(previews = true)
        waitFocused("Special Report")
        waitPlaying("fox-news")
        press(Key.DirectionDown)
        waitFocused("The Situation Room")
        // For the new row or nothing, never the old one, from the moment focus moved.
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            assertNotEquals("fox-news", current()?.first?.rowKey)
            Thread.sleep(50)
        }
        waitPlaying("cnn")
        assertEquals("cn2", current()!!.first.channelId)
    }

    @Test fun failingStreamShowsUnavailable() {
        graph.previewResolverOverride = { File(media).resolveSibling("missing.mkv").toURI().toString() }
        show(previews = true)
        waitFocused("Special Report")
        waitFor("Preview unavailable")
        assertEquals(PreviewStatus.FAILED, current()!!.second.state.value)
    }

    @Test fun offShowsTodaysPanel() {
        show(previews = false)
        waitFocused("Special Report")
        composeRule.waitUntil(5_000) { abs(panelHeightDp() - 114f) < 1f }
        Thread.sleep(2_000)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(hasTestTag("guide_preview"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertNull(current())
    }

    /** Counts the preview players opened; each plays at once (no player, so the panel draws no surface). */
    private class CountingEngine : PreviewEngine {
        @Volatile var opens = 0
        override fun open(url: String): PreviewHandle {
            opens++
            return object : PreviewHandle {
                override val state: StateFlow<PreviewStatus> = MutableStateFlow(PreviewStatus.PLAYING)
                override val player: ExoPlayer? = null
                override fun release() = Unit
            }
        }
    }

    /** The guide gone (a pushed screen) with a rest pending: nothing opens later, and the playing preview stops. */
    @Test fun leavingTheGuideOpensNothingMore() {
        val engine = CountingEngine().also { graph.previewEngineOverride = it }
        show(previews = true)
        waitFocused("Special Report")
        waitPlaying("fox-news")
        assertEquals(1, engine.opens)
        // Rest on CNN for less than a second, then the guide goes.
        press(Key.DirectionDown)
        waitFocused("The Situation Room")
        shown = false
        composeRule.waitForIdle()
        assertNull(current())
        Thread.sleep(2_500)
        composeRule.waitForIdle()
        assertNull(current())
        assertEquals(1, engine.opens)
    }

    /** Home or background (ON_STOP): the preview stops and nothing opens while the guide isn't seen. */
    @Test fun stoppingTheActivityStopsThePreview() {
        val engine = CountingEngine().also { graph.previewEngineOverride = it }
        show(previews = true)
        waitFocused("Special Report")
        waitPlaying("fox-news")
        press(Key.DirectionDown)
        waitFocused("The Situation Room")
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(2_500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNull(current())
        assertEquals(1, engine.opens)
    }
}
