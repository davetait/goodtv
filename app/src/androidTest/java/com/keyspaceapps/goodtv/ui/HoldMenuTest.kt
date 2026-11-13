package com.keyspaceapps.goodtv.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme
import com.keyspaceapps.goodtv.ui.watchlist.WatchListDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Holding Select on a poster opens its menu. The hold's own key repeats and its release must not
 * press the menu's first button: the menu waits for a new press.
 */
class HoldMenuTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private var actions by mutableIntStateOf(0)

    private fun show(startOpen: Boolean = false) = composeRule.setContent {
        GoodTvTheme {
            var open by remember { mutableStateOf(startOpen) }
            val card = remember { FocusRequester() }
            Box {
                Card(
                    onClick = {},
                    onLongClick = { open = true },
                    modifier = Modifier.size(200.dp).focusRequester(card).onPlaced { card.requestFocus() },
                ) { Text("Poster") }
            }
            if (open) WatchListDialog(title = "Poster", action = "Remove", onAction = { actions++; open = false }, onDismiss = { open = false })
        }
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun pressSelect() {
        val t = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        composeRule.waitForIdle()
    }

    private fun menuShown() = composeRule.onAllNodesWithText("Remove").fetchSemanticsNodes().isNotEmpty()

    /** The rest of a hold that began elsewhere: auto-repeats and the release, with no fresh press in this window. */
    private fun tailOfAHold() {
        val down = SystemClock.uptimeMillis() - 1_000
        for (repeat in 2..4) {
            val now = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(down, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat))
            Thread.sleep(50)
        }
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        composeRule.waitForIdle()
    }

    /** The menu has opened mid-hold: the hold's repeats and release reach it, and must choose nothing. */
    @Test fun theReleaseOfTheHoldThatOpenedTheMenuChoosesNothing() {
        show(startOpen = true)
        composeRule.waitUntil(5_000) { menuShown() }
        Thread.sleep(300)
        tailOfAHold()
        Thread.sleep(300)
        composeRule.waitForIdle()
        assertEquals("the hold's release chose nothing", 0, actions)
        assertEquals(true, menuShown())

        pressSelect()
        composeRule.waitUntil(5_000) { actions == 1 }
    }

    /** Opened with the Menu key (no hold), one ordinary press still chooses. */
    @Test fun anOrdinaryPressChoosesAtOnce() {
        show(startOpen = true)
        composeRule.waitUntil(5_000) { menuShown() }
        Thread.sleep(300)
        pressSelect()
        composeRule.waitUntil(5_000) { actions == 1 }
    }
}
