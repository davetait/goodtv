package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.data.sports.LiveRefresher
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryGuideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class GuideRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = Instant.parse("2026-10-21T18:30:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val windowStart = Instant.parse("2026-10-21T18:00:00Z").toEpochMilli()

    private val lineup = MutableStateFlow<List<ChannelCandidate>>(emptyList())
    private val fake = FakeLibraryStore()
    private val library: LibraryStore = object : LibraryStore by fake {
        override fun observeSportsCandidates() = lineup
    }
    private val guide = InMemoryGuideStore()
    private val repo by lazy { repoFor(library) }

    private fun repoFor(lib: LibraryStore, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined) = GuideRepository(
        lib, guide,
        GuideRefresher(guide, lib, SourceRegistry(emptyList()), EpgShareSource(OkHttpClient()), PlaybackState(), { now }, dispatcher, tmp.newFolder()),
        LiveRefresher(lib, SourceRegistry(listOf(FakeChannelSource())), { false }, { now }),
        { now }, dispatcher,
    )

    private var nextId = 0
    private fun feed(name: String, category: String, logo: String? = null): ChannelCandidate =
        ChannelCandidate(LiveChannel(1, "${nextId++}", null, name, logo, null), category)

    private val NBC = "US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val NEWS = "US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val ENT = "US| ENTERTAINMENT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val LOOP = "US| 24/7 ACTION/ADVENTURE ᴿᴬᵂ ⁶⁰ᶠᵖˢ"

    private fun programme(guideId: String, title: String, start: Long, end: Long = start + hour) =
        GuideProgramme(guideId, start, end, title, null, emptyList(), null)

    private fun link(c: ChannelCandidate, guideId: String) = ChannelGuideLink(c.channel.sourceId, c.channel.id, guideId)

    private fun board(filter: GuideFilter = GuideFilter.ALL) = runBlocking { repo.board(filter, windowStart).first() }
    private fun GuideBoard.section(title: String) = sections.first { it.title == title }
    private fun GuideBoard.titles() = sections.map { it.title }

    @Test fun favouritesFirstThenNetworksThenGenres() = runBlocking {
        val wnbc = feed("US: NBC 4 (WNBC) NEW YORK (A) ᴿᴬᵂ", NBC)
        val cnn = feed("US: CNN HD", NEWS)
        val ae = feed("US: A&E HD", ENT)
        lineup.value = listOf(ae, cnn, wnbc)
        assertEquals(listOf("NBC", "News", "Entertainment"), board().titles())
        repo.setFavourite(cnn.let { ChannelIdentity.of(it.channel.name, it.categoryName).rowKey }, true)
        val b = board()
        assertEquals(listOf("Favourites", "NBC", "News", "Entertainment"), b.titles())
        assertEquals(listOf("CNN"), b.section("Favourites").rows.map { it.name })
        assertTrue(b.section("News").rows.single().favourite)
        assertEquals(listOf("CNN"), board(GuideFilter.FAVOURITES).sections.single().rows.map { it.name })
    }

    @Test fun defaultMarketsShownOthersUnderMore() {
        lineup.value = listOf(
            feed("US: NBC 4 (WNBC) NEW YORK (A) ᴿᴬᵂ", NBC),
            feed("US: NBC 5 (WRAL) RALEIGH/DURHAM (A) ᴿᴬᵂ", NBC),
        )
        val nbc = board().section("NBC")
        assertEquals(Network.NBC, nbc.network)
        assertEquals(1, nbc.rows.size)
        assertTrue(nbc.rows.single().name.contains("NEW YORK", ignoreCase = true))
        assertTrue(nbc.more.single().name.contains("RALEIGH", ignoreCase = true))
    }

    @Test fun copiesMergeIntoOneRowWithFeeds() {
        lineup.value = listOf(feed("US: A&E HD", ENT), feed("TV: A&E ᴿᴬᵂ", "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        val row = board().section("Entertainment").rows.single()
        assertEquals("A&E", row.name)
        assertEquals(2, row.feeds.size)
    }

    @Test fun eventsOnlyUnderEventsFilter() {
        lineup.value = listOf(feed("US: A&E HD", ENT), feed("NBA: CHICAGO BULLS VS BOSTON CELTICS", "US| NBA PPV ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals(listOf("Entertainment"), board(GuideFilter.ALL).titles())
        val events = board(GuideFilter.EVENTS)
        assertEquals(listOf("Events"), events.titles())
        assertEquals(1, events.sections.single().rows.size)
        assertEquals(LiveGenre.EVENTS, events.sections.single().rows.single().genre)
    }

    @Test fun hiddenRowsNeverAppear() {
        lineup.value = listOf(feed("## 24/7 ACTION/ADVENTURE ᴿᴬᵂ ⁶⁰ᶠᵖˢ ##", LOOP), feed("US: A&E HD", ENT))
        val rows = board().sections.flatMap { it.rows + it.more }
        assertEquals(listOf("A&E"), rows.map { it.name })
        assertTrue(runBlocking { repo.search("action").first() }.isEmpty())
    }

    @Test fun loopShowRowsHaveNoProgrammes() = runBlocking {
        val rock = feed("24/7: 30 ROCK", LOOP)
        lineup.value = listOf(rock)
        guide.replaceGuide(GuideOrigin.PROVIDER, listOf(GuideChannel("P:1", GuideOrigin.PROVIDER, null)), sequenceOf(programme("P:1", "30 Rock", windowStart)))
        guide.replaceLinks(listOf(link(rock, "P:1")))
        val row = board().sections.flatMap { it.rows }.single()
        assertEquals("30 Rock", row.loopShow)
        assertTrue(row.programmes.isEmpty())
    }

    @Test fun logoPrefersGuideThenRealProviderThenNone() = runBlocking {
        val withGuide = feed("US: CNN HD", NEWS, "http://provider/cnn.png")
        val withProvider = feed("US: A&E HD", ENT, "http://provider/ae.png")
        val placeholder = (1..PlaceholderLogos.MIN_CHANNELS).map { feed("US: PLAIN $it", ENT, "http://provider/badge.png") }
        lineup.value = listOf(withGuide, withProvider) + placeholder
        guide.replaceGuide(GuideOrigin.EPGSHARE, listOf(GuideChannel("CNN.us2", GuideOrigin.EPGSHARE, "http://epg/cnn.png")), emptySequence())
        guide.replaceLinks(listOf(link(withGuide, "CNN.us2")))
        val rows = board().sections.flatMap { it.rows }.associateBy { it.name }
        assertEquals("http://epg/cnn.png", rows.getValue("CNN").logoUrl)
        assertEquals("http://provider/ae.png", rows.getValue("A&E").logoUrl)
        assertNull(rows.getValue("PLAIN 1").logoUrl)
    }

    @Test fun searchFindsLaterShowAndLoopAndChannel() = runBlocking {
        val fox = feed("US: FOX NEWS HD", NEWS)
        val cnn = feed("US: CNN HD", NEWS)
        val rock = feed("24/7: 30 ROCK", LOOP)
        lineup.value = listOf(fox, cnn, rock)
        guide.replaceGuide(
            GuideOrigin.EPGSHARE,
            listOf(GuideChannel("FOX.us2", GuideOrigin.EPGSHARE, null)),
            sequenceOf(programme("FOX.us2", "Hannity", now + 2 * hour + 30 * 60_000)),
        )
        guide.replaceLinks(listOf(link(fox, "FOX.us2")))

        val hannity = repo.search("hannity").first().single()
        assertEquals("FOX NEWS", hannity.row.name.uppercase())
        assertEquals("Hannity", hannity.programme?.title)
        val loop = repo.search("30 rock").first().single()
        assertEquals("30 Rock", loop.row.loopShow)
        assertNull(loop.programme)
        val channel = repo.search("cnn").first().single()
        assertEquals("CNN", channel.row.name)
        assertNull(channel.programme)
        assertTrue(repo.search("  ").first().isEmpty())
    }

    @Test fun searchPutsOnNowFirstThenByStartThenChannels() = runBlocking {
        val a = feed("US: A&E HD", ENT)
        val b = feed("US: CNN HD", NEWS)
        val c = feed("US: NEWSMAX HD", NEWS)
        lineup.value = listOf(a, b, c)
        guide.replaceGuide(
            GuideOrigin.EPGSHARE,
            listOf("A", "B").map { GuideChannel(it, GuideOrigin.EPGSHARE, null) },
            sequenceOf(programme("A", "News Hour", now + hour), programme("B", "News Hour", now - 20 * 60_000)),
        )
        guide.replaceLinks(listOf(link(a, "A"), link(b, "B")))
        val hits = repo.search("news").first()
        assertEquals(listOf("CNN", "A&E", "NEWSMAX"), hits.map { it.row.name })
        assertEquals(listOf(true, true, false), hits.map { it.programme != null })
    }

    @Test fun programmeCrossingWindowEdgeIsClipped() = runBlocking {
        val cnn = feed("US: CNN HD", NEWS)
        lineup.value = listOf(cnn)
        guide.replaceGuide(
            GuideOrigin.EPGSHARE, listOf(GuideChannel("CNN", GuideOrigin.EPGSHARE, null)),
            sequenceOf(
                programme("CNN", "Early", windowStart - hour - 30 * 60_000, windowStart - hour),
                programme("CNN", "Crossing", windowStart - 30 * 60_000, windowStart + hour),
                programme("CNN", "Last", windowStart + 2 * hour, windowStart + 3 * hour),
                programme("CNN", "After", windowStart + 3 * hour, windowStart + 4 * hour),
            ),
        )
        guide.replaceLinks(listOf(link(cnn, "CNN")))
        assertEquals(listOf("Crossing", "Last"), board().section("News").rows.single().programmes.map { it.title })
    }

    private val fixture by lazy {
        javaClass.classLoader!!.getResourceAsStream("live/visible_channels.tsv")!!.bufferedReader().readLines().filter { it.isNotEmpty() }
            .map { it.split('\t') }.mapIndexed { i, r -> ChannelCandidate(LiveChannel(1, "$i", null, r[0], r[2], null), r[1]) }
    }

    @Test fun fullLineupBoardIsCleanAndFast() = runBlocking {
        lineup.value = fixture
        val started = System.nanoTime()
        val b = repoFor(library, Dispatchers.Default).board(GuideFilter.ALL, windowStart).first()
        val ms = (System.nanoTime() - started) / 1_000_000
        val rows = b.sections.flatMap { it.rows + it.more }
        assertTrue("took ${ms}ms", ms < 2_000)
        assertTrue(rows.none { r -> r.name.contains("##") || r.name.contains("NO EVENT", ignoreCase = true) })
        assertTrue(rows.none { it.genre == LiveGenre.EVENTS })
        val nbc = b.section("NBC")
        val ids = nbc.rows.map { r -> r.key to ChannelIdentity.of(r.feeds.first().channel.name, r.feeds.first().categoryName) }
        for ((key, id) in ids) assertTrue("$key: ${id.displayName}", (id.callSign != null && id.market in DEFAULT_MARKETS) || (id.callSign == null && id.network == Network.NBC))
        assertFalse(nbc.rows.isEmpty())
    }
}
