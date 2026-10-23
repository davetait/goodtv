package com.keyspaceapps.goodtv.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.MainActivity
import com.keyspaceapps.goodtv.appGraph
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.clearDatabaseForTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings → Sources → Categories: everything checked by default, unchecking hides from Live, a group header clears its block. */
class CategoriesScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var sourceId = 0L

    @After
    fun tearDown() { scenario?.close() }

    private fun seedAndLaunch(liveCategories: List<Pair<String, String>>) {
        runBlocking {
            val graph = ApplicationProvider.getApplicationContext<android.content.Context>().appGraph()
            graph.clearDatabaseForTest()
            graph.settings.setLastTab("SOURCES")
            sourceId = graph.store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, System.currentTimeMillis(), null))
            graph.store.replaceCatalog(
                sourceId,
                Catalog(
                    categories = liveCategories.map { (id, name) -> Category(sourceId, ContentKind.LIVE, id, name) },
                    live = liveCategories.map { (id, name) -> LiveChannel(sourceId, "ch-$id", id, "Channel of $name", null, 1) },
                    movies = emptyList(), series = emptyList(),
                ),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // A saved SOURCES tab opens Settings; its Sources row opens the sources list.
        waitFor("Sources")
        tvClick("Sources")
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun tvClick(text: String) = composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    private fun hiddenNow(): Set<Pair<ContentKind, String>> = runBlocking {
        ApplicationProvider.getApplicationContext<android.content.Context>().appGraph().store.observeHiddenCategories(sourceId).first()
    }

    @Test
    fun categoriesButtonOpensListWithEverythingCheckedAndFirstRowFocused() {
        // 900 categories across 30 prefixes, like a real provider
        val cats = (0 until 900).map { i -> "c$i" to "%s| Cat %03d".format("P%02d".format(i % 30), i) }
        seedAndLaunch(cats)
        waitFor("Categories")
        tvClick("Categories")
        waitFor("Live · 900 of 900")
        composeRule.onNodeWithText("P00 · 30 of 30").assertIsDisplayed()
        composeRule.onNodeWithText("P00| Cat 000").assertIsFocused()

        // prefix chip jumps to that block's header and focuses it (chips are a lazy row: scroll it first)
        composeRule.onNode(hasScrollToKeyAction() and hasAnyChild(hasText("Select all"))).performScrollToKey("chip:P29")
        tvClick("P29")
        waitFor("P29 · 30 of 30")
        composeRule.onNodeWithText("P29 · 30 of 30").assertIsFocused()
    }

    @Test
    fun uncheckingHidesFromLiveAndCountsUpdate() {
        seedAndLaunch(listOf("1" to "AR| Sports", "2" to "AR| News", "3" to "UK| BBC", "4" to "UK| ITV"))
        waitFor("Categories")
        tvClick("Categories")
        waitFor("Live · 4 of 4")

        tvClick("AR| Sports")
        waitFor("Live · 3 of 4")
        assertEquals(setOf(ContentKind.LIVE to "1"), hiddenNow())

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("1 categories hidden", substring = true)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // The Live tab's guide lists the channels of visible categories only.
        waitFor("Live")
        tvClick("Live")
        waitFor("Channel of AR| News", substring = true)
        assertEquals(0, composeRule.onAllNodes(hasText("Channel of AR| Sports", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun groupHeaderClearsItsBlock() {
        seedAndLaunch(listOf("1" to "AR| Sports", "2" to "AR| News", "3" to "UK| BBC", "4" to "UK| ITV"))
        waitFor("Categories")
        tvClick("Categories")
        waitFor("Live · 4 of 4")

        tvClick("AR · 2 of 2")
        waitFor("Live · 2 of 4")
        assertEquals(setOf(ContentKind.LIVE to "1", ContentKind.LIVE to "2"), hiddenNow())

        tvClick("AR · 0 of 2") // off → show all again
        waitFor("Live · 4 of 4")
        assertEquals(emptySet<Pair<ContentKind, String>>(), hiddenNow())
    }

    @Test
    fun clearAllThenSelectAll() {
        seedAndLaunch(listOf("1" to "AR| Sports", "2" to "UK| BBC", "3" to "UK| ITV"))
        waitFor("Categories")
        tvClick("Categories")
        waitFor("Live · 3 of 3")
        tvClick("Clear all")
        waitFor("Live · 0 of 3")
        tvClick("Select all")
        waitFor("Live · 3 of 3")
    }
}
