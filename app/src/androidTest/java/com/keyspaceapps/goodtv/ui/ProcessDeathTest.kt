package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Activity recreation saves and restores the Navigation 3 back stack, which serialises every
 * GoodTvKey. A pushed screen must survive it (same mechanism as process-death restore).
 */
class ProcessDeathTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() { scenario?.close() }

    @Test
    fun pushedScreenSurvivesRecreate() {
        runBlocking { ApplicationProvider.getApplicationContext<android.content.Context>().appGraph().db.clearAllTables() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // No sources: the app opens on Settings; its Sources row opens the sources list.
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Sources")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Sources").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Add source")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Add source").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Test & save")).fetchSemanticsNodes().isNotEmpty() }

        scenario!!.recreate()

        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Test & save")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Test & save").assertIsDisplayed()
    }
}
