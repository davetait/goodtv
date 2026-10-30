package com.keyspaceapps.goodtv.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
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
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.ui.live.ExoPreviewEngine
import com.keyspaceapps.goodtv.ui.live.GuideScreen
import com.keyspaceapps.goodtv.ui.live.PreviewEngine
import com.keyspaceapps.goodtv.ui.live.PreviewHandle
import com.keyspaceapps.goodtv.ui.live.PreviewStatus
import com.keyspaceapps.goodtv.ui.nav.GROW_MS
import com.keyspaceapps.goodtv.ui.nav.PlayerEntry
import com.keyspaceapps.goodtv.ui.nav.PlayerKey
import com.keyspaceapps.goodtv.ui.nav.handOffTransition
import com.keyspaceapps.goodtv.ui.player.PlayerFactory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
                override fun resume() = Unit
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

    // Going full screen: the guide on a back stack with the app's player entry and transitions.

    private object GuideHere

    private val backStack = mutableStateListOf<Any>(GuideHere)

    private fun showWithPlayer() {
        runBlocking { graph.settings.setLivePreviews(true) }
        val previews = graph.livePreviews
        composeRule.setContent {
            GoodTvTheme {
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    transitionSpec = handOffTransition(previews, defaultTransitionSpec()),
                    popTransitionSpec = handOffTransition(previews, defaultPopTransitionSpec()),
                    entryProvider = entryProvider {
                        entry<GuideHere> { GuideScreen(onPlay = { backStack.add(it) }, source = guide, clock = { now }) }
                        entry<PlayerKey> { key ->
                            PlayerEntry(key, onBack = { backStack.removeLastOrNull() }, onPlayNext = {}, resolve = { PlayableItem(it.title, media, isLive = true) })
                        }
                    },
                )
            }
        }
    }

    /** The app's preview players (real ExoPlayers on the local file), counted. */
    private class CountingExoEngine(context: android.content.Context) : PreviewEngine {
        private val real = ExoPreviewEngine(context)
        val handles = mutableListOf<PreviewHandle>()
        override fun open(url: String): PreviewHandle = real.open(url).also { handles += it }
    }

    private fun countingEngine() = CountingExoEngine(composeRule.activity).also { graph.previewEngineOverride = it }

    private fun waitFullScreen() = try {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasTestTag("video_surface"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        composeRule.onRoot(useUnmergedTree = true).printToLog("GoodTvTree"); throw e
    }

    private fun released(handle: PreviewHandle) = composeRule.runOnUiThread { handle.player!!.isReleased }

    /** Lets [ms] pass on the real clock with the UI idle in between, for anything that might open late. */
    private fun idleFor(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            Thread.sleep(100)
        }
    }

    @Test fun selectingAPlayingPreviewDoesNotOpenASecondStream() {
        val engine = countingEngine()
        showWithPlayer()
        waitFocused("Special Report")
        waitPlaying("fox-news")
        val handle = current()!!.second
        val made = PlayerFactory.created.get()
        press(Key.DirectionCenter)
        waitFullScreen()
        assertTrue(backStack.last() is PlayerKey)
        // The player screen plays the preview's player: nothing new made, nothing new opened.
        assertEquals(made, PlayerFactory.created.get())
        assertEquals(1, engine.handles.size)
        assertSame(handle, engine.handles.single())
        assertTrue(graph.livePreviews.handingOff)
        assertNull(current())
        assertEquals(PreviewStatus.PLAYING, handle.state.value)
        assertFalse(released(handle))
        // However long full screen lasts, the guide underneath starts nothing.
        idleFor(2_500)
        assertEquals(1, engine.handles.size)
        assertEquals(made, PlayerFactory.created.get())
    }

    @Test fun backReturnsTheStreamToThePanel() {
        val engine = countingEngine()
        showWithPlayer()
        waitFocused("Special Report")
        waitPlaying("fox-news")
        val handle = current()!!.second
        val made = PlayerFactory.created.get()
        press(Key.DirectionCenter)
        waitFullScreen()
        idleFor(1_000)
        press(Key.Back)
        composeRule.waitUntil(5_000) { backStack.size == 1 }
        waitFocused("Special Report")
        composeRule.waitUntil(5_000) { current()?.second === handle }
        assertEquals("fox-news", current()!!.first.rowKey)
        assertEquals(PreviewStatus.PLAYING, handle.state.value)
        assertFalse(released(handle))
        assertFalse(graph.livePreviews.handingOff)
        assertEquals(1, engine.handles.size)
        assertEquals(made, PlayerFactory.created.get())
        // Kept: no restart a moment later either.
        idleFor(2_500)
        assertSame(handle, current()?.second)
        assertEquals(PreviewStatus.PLAYING, handle.state.value)
        assertEquals(1, engine.handles.size)
    }

    @Test fun selectingWithoutAPreviewOpensNormally() {
        val engine = countingEngine()
        showWithPlayer()
        waitFocused("Special Report")
        val made = PlayerFactory.created.get()
        // Before the second's rest is up.
        press(Key.DirectionCenter)
        waitFullScreen()
        assertEquals(made + 1, PlayerFactory.created.get())
        assertFalse(graph.livePreviews.handingOff)
        idleFor(2_500)
        assertEquals(0, engine.handles.size)
        assertNull(current())
        // Back on the guide, the row rests again once the player has gone.
        press(Key.Back)
        composeRule.waitUntil(5_000) { backStack.size == 1 }
        waitFocused("Special Report")
        waitPlaying("fox-news")
        assertEquals(1, engine.handles.size)
    }

    @Test fun pickingAnotherCopyStopsThePreviewAndOpensItsOwn() {
        val engine = countingEngine()
        showWithPlayer()
        waitFocused("Special Report")
        press(Key.DirectionDown)
        waitFocused("The Situation Room")
        waitPlaying("cnn")
        val preview = current()!!.second
        val made = PlayerFactory.created.get()
        press(Key.DirectionCenter)
        waitFocused("US: CNN FHD")
        press(Key.DirectionDown)
        waitFocused("US: CNN HD")
        press(Key.DirectionCenter)
        waitFullScreen()
        assertEquals("cn1", (backStack.last() as PlayerKey).refId)
        assertEquals(made + 1, PlayerFactory.created.get())
        assertNull(current())
        assertFalse(graph.livePreviews.handingOff)
        // The preview stopped for it, and nothing opens while full screen is up.
        assertTrue(released(preview))
        idleFor(2_500)
        assertEquals(1, engine.handles.size)
    }

    private fun boundsOf(tag: String) = composeRule.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun has(tag: String) = composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The picture grows out of the panel with the guide drawn beneath it, and shrinks back into it on Back. */
    @Test fun thePictureGrowsFromThePanelAndShrinksBack() {
        countingEngine()
        showWithPlayer()
        waitFocused("Special Report")
        waitPlaying("fox-news")
        val panel = boundsOf("guide_preview")
        val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        composeRule.mainClock.autoAdvance = false
        try {
            press(Key.DirectionCenter)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeByFrame()
            // At the start the picture sits in the panel, and the guide is still there around it.
            assertTrue(has("video_surface"))
            assertTrue(has("guide_details"))
            val start = boundsOf("video_surface")
            assertTrue("$start in $panel", start.width <= panel.width + 2 && start.left >= panel.left - 2 && start.bottom <= panel.bottom + 2)
            // Half way: bigger than the panel, smaller than the screen, the guide still beneath.
            composeRule.mainClock.advanceTimeBy(GROW_MS / 2L)
            val mid = boundsOf("video_surface")
            assertTrue("$mid", mid.width > panel.width + 10 && mid.width < screen.width - 10)
            assertTrue(has("guide_details"))
            // Done: full screen, and the guide has gone.
            composeRule.mainClock.advanceTimeBy(GROW_MS + 1_000L)
            assertTrue(has("video_surface"))
            assertFalse(has("guide_details"))
            assertTrue(boundsOf("video_surface").width > screen.width * 0.6f)

            press(Key.Back)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(GROW_MS / 2L)
            // Shrinking: the guide is back beneath, the picture on its way to the panel.
            assertTrue(has("guide_details"))
            val back = boundsOf("video_surface")
            assertTrue("$back", back.width > panel.width + 10 && back.width < screen.width - 10)
            composeRule.mainClock.advanceTimeBy(GROW_MS + 1_000L)
            assertFalse(has("video_surface"))
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitUntil(5_000) { current()?.first?.rowKey == "fox-news" }
    }

    /** Paused in full screen, then Back: the panel's preview plays again (a still frame would hold the gate for nothing). */
    @Test fun pausingInFullScreenThenBackPlaysInThePanel() {
        val engine = countingEngine()
        showWithPlayer()
        waitFocused("Special Report")
        waitPlaying("fox-news")
        val handle = current()!!.second
        press(Key.DirectionCenter)
        waitFullScreen()
        idleFor(1_000)
        press(Key.MediaPause)
        composeRule.waitUntil(5_000) { !composeRule.runOnUiThread { handle.player!!.playWhenReady } }
        // The pause brought up the controls in use: the first Back hides them, the second leaves.
        press(Key.Back)
        composeRule.waitForIdle()
        assertEquals(2, backStack.size)
        press(Key.Back)
        composeRule.waitUntil(5_000) { backStack.size == 1 }
        composeRule.waitUntil(5_000) { current()?.second === handle }
        composeRule.waitUntil(5_000) { composeRule.runOnUiThread { handle.player!!.isPlaying } }
        assertEquals(PreviewStatus.PLAYING, handle.state.value)
        assertEquals(1, engine.handles.size)
    }
}
