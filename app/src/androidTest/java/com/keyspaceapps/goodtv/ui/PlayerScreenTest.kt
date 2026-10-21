package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.ui.player.PlayerScreen
import com.keyspaceapps.goodtv.ui.player.UpNext
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The player's controls against a real file (assets/player_tracks.mkv: 20 s, English stereo and
 * German mono audio tagged "eng"/"ger", English and French SRT subtitles with a cue every 2 s).
 */
@OptIn(ExperimentalTestApi::class)
class PlayerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()
    private lateinit var url: String
    private var backs = 0
    private var nexts = 0

    @Before
    fun copyMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "player_tracks.mkv")
        instrumentation.context.assets.open("player_tracks.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        url = file.toURI().toString()
    }

    private fun launch(upNext: UpNext? = null) = composeRule.setContent {
        GoodTvTheme { PlayerScreen(PlayableItem("Test pattern", url, isLive = false), onBack = { backs++ }, upNext = upNext, onPlayNext = { nexts++ }) }
    }

    // A 20-second file is "in its credits" from the start (the last 2 minutes count), so Up next shows at once.
    private val next = UpNext("S1 E4 · The Long Night", "Test Show", meta = "52m", summary = "A message arrives that changes everything.")

    private fun keys(k: List<Key>) = composeRule.onRoot().performKeyInput { k.forEach { pressKey(it) } }
    private fun keys(k: Key) = keys(listOf(k))
    private fun waitFor(timeout: Long = 10_000, node: () -> SemanticsNodeInteraction) =
        composeRule.waitUntil(timeout) { runCatching { node().assertExists() }.isSuccess }
    private fun waitFocused(node: () -> SemanticsNodeInteraction) =
        composeRule.waitUntil(5_000) { runCatching { node().assertIsFocused() }.isSuccess }

    /** Opens with the controls up and the seek bar focused; the remote's Pause key keeps them up. */
    private fun launchPaused() {
        launch()
        waitFocused { composeRule.onNodeWithContentDescription("Seek") }
        keys(Key.MediaPause)
        // Both audio tracks known means the file is loaded (only then does the Audio button show).
        waitFor(15_000) { composeRule.onNodeWithContentDescription("Audio") }
        waitFor { composeRule.onNodeWithContentDescription("Play") }
    }

    private fun captionsShown(text: String) =
        composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** The file marks English as its default subtitle track, so it starts on; switch to French, then off. */
    @Test
    fun subtitlesFollowTheFileDefaultAndCanBeSwitchedOrTurnedOff() {
        launchPaused()
        keys(listOf(Key.DirectionDown, Key.DirectionRight))
        composeRule.onNodeWithContentDescription("Subtitles").assertIsFocused()
        keys(Key.DirectionCenter)

        waitFor { composeRule.onNodeWithText("French") }
        composeRule.onNodeWithText("English").assertIsSelected()
        composeRule.onNodeWithText("English").assertIsFocused()
        composeRule.onNodeWithText("Off").assertIsNotSelected()

        keys(listOf(Key.DirectionDown, Key.DirectionCenter))
        // The panel closes onto the button that opened it; play again and the French captions appear.
        waitFocused { composeRule.onNodeWithContentDescription("Subtitles") }
        keys(listOf(Key.DirectionLeft, Key.DirectionCenter))
        composeRule.waitUntil(10_000) { captionsShown("Bonjour de GoodTV") }
        composeRule.onAllNodes(hasText("Hello from GoodTV", substring = true)).fetchSemanticsNodes().let { assertEquals(0, it.size) }

        keys(listOf(Key.DirectionRight, Key.DirectionCenter))
        waitFor { composeRule.onNodeWithText("Off") }
        composeRule.onNodeWithText("French").assertIsSelected()
        keys(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionCenter))
        composeRule.waitUntil(5_000) { !captionsShown("Bonjour de GoodTV") && !captionsShown("Hello from GoodTV") }
    }

    @Test
    fun audioTracksAreNamedByLanguageAndLayout() {
        launchPaused()
        keys(listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionRight))
        composeRule.onNodeWithContentDescription("Audio").assertIsFocused()
        keys(Key.DirectionCenter)
        waitFor { composeRule.onNodeWithText("English · Stereo") }
        composeRule.onNodeWithText("English · Stereo").assertIsSelected()
        // "ger" is MKV's older code for German.
        keys(listOf(Key.DirectionDown, Key.DirectionCenter))
        keys(Key.DirectionCenter)
        waitFor { composeRule.onNodeWithText("German · Mono") }
        composeRule.onNodeWithText("German · Mono").assertIsSelected()
    }

    @Test
    fun leftRightOnTheSeekBarPreviewThenJump() {
        launchPaused()
        composeRule.onNodeWithContentDescription("Seek").assertIsFocused()
        keys(Key.DirectionRight)
        // The preview bubble and the elapsed time show the target before the jump.
        waitFor { composeRule.onNode(hasText("00:10") and !hasContentDescription("Seek")) }
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithContentDescription("Seek").fetchSemanticsNode().config
                .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { "" } == "00:10"
        }
    }

    @Test
    fun backLeavesAtOnceWhenTheControlsOnlyShowedByThemselves() {
        launch()
        waitFocused { composeRule.onNodeWithContentDescription("Seek") }
        keys(Key.Back)
        composeRule.waitUntil(5_000) { backs == 1 }
    }

    @Test
    fun backClosesThePanelThenHidesTheControlsThenLeaves() {
        launchPaused()
        keys(listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionCenter))
        waitFor { composeRule.onNodeWithText("Off") }

        keys(Key.Back)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Off")).fetchSemanticsNodes().isEmpty() }
        waitFocused { composeRule.onNodeWithContentDescription("Subtitles") }
        assertEquals(0, backs)

        keys(Key.Back)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription("Subtitles")).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, backs)

        keys(Key.Back)
        composeRule.waitUntil(5_000) { backs == 1 }
    }

    @Test
    fun upNextPlaysTheNextEpisodeOnSelect() {
        launch(next)
        waitFor(15_000) { composeRule.onNodeWithText("UP NEXT") }
        composeRule.onNodeWithText("S1 E4 · The Long Night").assertExists()
        composeRule.onNodeWithText("A message arrives that changes everything.").assertExists()
        composeRule.onAllNodes(hasContentDescription("Seek")).assertCountEquals(0)
        waitFocused { composeRule.onNode(hasText("Play now", substring = true)) }
        keys(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { nexts == 1 }
    }

    @Test
    fun upNextCountsDownTenSecondsThenPlaysTheNextEpisode() {
        launch(next)
        waitFor(15_000) { composeRule.onNodeWithText("UP NEXT") }
        Thread.sleep(5_000)
        assertEquals("not yet", 0, nexts)
        composeRule.waitUntil(12_000) { nexts == 1 }
        assertEquals(0, backs)
    }

    @Test
    fun backDismissesUpNextAndTheEndOfTheEpisodeLeaves() {
        launch(next)
        waitFor(15_000) { composeRule.onNodeWithText("UP NEXT") }
        keys(Key.Back)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("UP NEXT")).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, backs)
        // It doesn't come back; when the 20 s file ends the player leaves instead of moving on.
        composeRule.waitUntil(30_000) { backs == 1 }
        assertEquals(0, nexts)
        composeRule.onAllNodes(hasText("UP NEXT")).assertCountEquals(0)
    }
}

