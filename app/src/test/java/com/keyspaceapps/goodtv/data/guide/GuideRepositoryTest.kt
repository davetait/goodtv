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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private val guide = InMemoryGuideStore()
    private val lineup = MutableStateFlow<List<ChannelCandidate>>(emptyList())
    private val fake = FakeLibraryStore()
    private var lineupCollections = 0
    private val library: LibraryStore = object : LibraryStore by fake {
        override fun observeSportsCandidates() = lineup.onStart { lineupCollections++ }
    }
    /** The refresher reads its own lineup, so its runs can be counted apart from the board's. */
    private var refreshes = 0
    private val refresherLibrary: LibraryStore = object : LibraryStore by fake {
        override fun observeSportsCandidates() = flow<List<ChannelCandidate>> { refreshes++; emit(emptyList()) }
    }
    /** Stands in for SQLite's limit on bind variables: a query with more ids than a chunk fails. */
    private val limited: GuideStore = object : GuideStore by guide {
        override fun observeProgrammes(guideIds: Set<String>, fromMs: Long, untilMs: Long): Flow<List<GuideProgramme>> {
            check(guideIds.size <= 500) { "${guideIds.size} ids in one query" }
            return guide.observeProgrammes(guideIds, fromMs, untilMs)
        }
    }
    private val repo by lazy { repoFor(library) }

    private fun repoFor(lib: LibraryStore, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined) = GuideRepository(
        lib, limited,
        GuideRefresher(guide, refresherLibrary, SourceRegistry(emptyList()), EpgShareSource(OkHttpClient()), PlaybackState(), { now }, dispatcher, tmp.newFolder()),
        LiveRefresher(lib, SourceRegistry(listOf(FakeChannelSource())), { false }, { now }),
        { now }, dispatcher, { warnings += it }, { timings += it },
    )
    private val warnings = ArrayList<String>()
    private val timings = java.util.concurrent.CopyOnWriteArrayList<String>()

    private var nextId = 0
    private fun feed(name: String, category: String, logo: String? = null): ChannelCandidate =
        ChannelCandidate(LiveChannel(1, "${nextId++}", null, name, logo, null), category)

    private val NBC = "US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val NEWS = "US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val ENT = "US| ENTERTAINMENT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val LOOP = "US| 24/7 ACTION/ADVENTURE ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val ABC = "US| ABC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val FOX = "US| FOX ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val TV = "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ"
    private val ATT = "US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ"

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

    @Test fun programmesCrossingTheWindowEdgeAreIncluded() = runBlocking {
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

    @Test fun aBoardOfThousandsOfLinkedChannelsReturnsAllTheirProgrammes() = runBlocking {
        val feeds = (1..1_200).map { feed("US: CHANNEL $it", ENT) }
        lineup.value = feeds
        guide.replaceGuide(
            GuideOrigin.EPGSHARE, feeds.indices.map { GuideChannel("G$it", GuideOrigin.EPGSHARE, null) },
            feeds.indices.asSequence().map { programme("G$it", "Show $it", windowStart + hour) },
        )
        guide.replaceLinks(feeds.mapIndexed { i, c -> link(c, "G$i") })
        val rows = board().section("Entertainment").rows
        assertEquals(1_200, rows.size)
        assertTrue(rows.all { it.programmes.size == 1 })
    }

    @Test fun movingTheWindowReadsTheLineupAndRunsTheRefresherOnce() = runBlocking {
        lineup.value = listOf(feed("US: CNN HD", NEWS))
        for (i in 0 until 3) repo.board(GuideFilter.ALL, windowStart + i * hour).first()
        repo.board(GuideFilter.NEWS, windowStart).first()
        repo.search("cnn").first()
        assertEquals(1, lineupCollections)
        assertEquals(1, refreshes)
    }

    @Test fun appCopiesOfStationsGoUnderMoreEvenInDefaultMarkets() {
        lineup.value = listOf(
            feed("US: NBC 4 (WNBC) NEW YORK (A) ᴿᴬᵂ", NBC),
            feed("PRIME: NBC NEW YORK (WNBC) ᴿᴬᵂ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ"),
            feed("RK: NBC 4 NEW YORK (WNBC) ᴿᴬᵂ", "US| ROKU ᴿᴬᵂ ⁶⁰ᶠᵖˢ"),
        )
        val nbc = board().section("NBC")
        assertEquals(1, nbc.rows.size)
        assertEquals(2, nbc.more.size)
        assertTrue(nbc.more.all { it.key.startsWith("prime ") || it.key.startsWith("roku ") })
    }

    @Test fun stationRowIsNamedByItsShortestNetworkFirstFeed() {
        lineup.value = listOf(
            feed("TV: A3 NEW YORK 04 WNBC NBC ᴿᴬᵂ", "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ"),
            feed("US: NBC 4 (WNBC) NEW YORK (A) ᴿᴬᵂ", NBC),
            feed("US: NBC (WNBC) NEW YORK (D) ᴿᴬᵂ", NBC),
        )
        val row = board().section("NBC").rows.single()
        assertEquals(3, row.feeds.size)
        assertEquals("NBC (WNBC) NEW YORK", row.name)
    }

    @Test fun aFailingLineupDoesNotCrashAndKeepsTheLastBoard() = runBlocking {
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val good = listOf(feed("US: CNN HD", NEWS))
            val failing: LibraryStore = object : LibraryStore by fake {
                override fun observeSportsCandidates() = flow<List<ChannelCandidate>> { emit(good); throw java.io.IOException("boom") }
            }
            val failingRepo = repoFor(failing)
            assertEquals(listOf("CNN"), failingRepo.board(GuideFilter.ALL, windowStart).first().section("News").rows.map { it.name })
            Thread.sleep(200)
            assertEquals(listOf("CNN"), failingRepo.board(GuideFilter.NEWS, windowStart).first().section("News").rows.map { it.name })
            assertTrue(uncaught.toString(), uncaught.isEmpty())
            assertEquals(1, warnings.size)
            assertFalse(warnings.single().contains("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    /** I2: every network copy of New York's one ABC station is that station's row, with its guide; the app copy stays apart. */
    @Test fun networkCopiesWithoutCallSignFoldIntoTheirStation() = runBlocking {
        val wabc = feed("US: ABC (WABC)", ABC)
        val copies = listOf(
            feed("TV: A3 NEW YORK 07 WABC ABC ᴿᴬᵂ", TV), feed("US: ABC 7 NEW YORK", ABC), feed("TV: ABC7 NEW YORK ᴿᴬᵂ", TV),
            feed("TV: ABC7 NEW YORK NEW YORK ᶜᶦᵗʸ ᴿᴬᵂ", "US| TV ᶜᶦᵗʸ ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), feed("US: ABC HD [NEW YORK]", ABC),
        )
        val prime = feed("PRIME: ABC7 NEW YORK ᴿᴬᵂ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        lineup.value = listOf(wabc) + copies + prime
        guide.replaceGuide(GuideOrigin.EPGSHARE, listOf(GuideChannel("WABC-DT.us_locals1", GuideOrigin.EPGSHARE, null)), sequenceOf(programme("WABC-DT.us_locals1", "Eyewitness News", windowStart)))
        guide.replaceLinks(listOf(link(wabc, "WABC-DT.us_locals1")))
        val abc = board().section("ABC")
        val station = abc.rows.single()
        assertEquals("station:ABC:WABC", station.key)
        assertEquals("ABC (WABC)", station.name)
        assertEquals((listOf(wabc) + copies).map { it.channel.id }.toSet(), station.feeds.map { it.channel.id }.toSet())
        assertEquals(listOf("Eyewitness News"), station.programmes.map { it.title })
        assertEquals(listOf(prime.channel.id), abc.more.single().feeds.map { it.channel.id })
    }

    @Test fun networkCopyStaysItsOwnRowWhenItsStationIsNotCertain() = runBlocking {
        fun foxRows(vararg feeds: ChannelCandidate): Int {
            lineup.value = feeds.toList()
            return runBlocking { repoFor(library).board(GuideFilter.ALL, windowStart).first() }.section("FOX").rows.size
        }
        // Two FOX stations in New York: which one "FOX 5 LOCAL" is can't be told.
        assertEquals(3, foxRows(feed("US: FOX 5 (WNYW) NEW YORK HD", FOX), feed("US: FOX 9 (WWOR) NEW YORK HD", FOX), feed("AT&T: FOX 5 LOCAL NEW YORK ᴿᴬᵂ", ATT)))
        // One station, but not on the copy's channel number.
        assertEquals(2, foxRows(feed("US: FOX 5 (WNYW) NEW YORK HD", FOX), feed("AT&T: FOX 11 LOCAL NEW YORK ᴿᴬᵂ", ATT)))
        // A station in another market.
        assertEquals(2, foxRows(feed("US: FOX 11 (KTTV) LOS ANGELES HD", FOX), feed("AT&T: FOX 11 LOCAL NEW YORK ᴿᴬᵂ", ATT)))
        // One station on the copy's number: folded.
        assertEquals(1, foxRows(feed("US: FOX 5 (WNYW) NEW YORK HD", FOX), feed("AT&T: FOX 5 LOCAL NEW YORK ᴿᴬᵂ", ATT)))
    }

    /** M3: "US: ABC HD" in the provider's News category is a news row, not ABC's national feed. */
    @Test fun nationalRowNeedsItsNetworkCategory() {
        lineup.value = listOf(feed("US: ABC HD", NEWS), feed("US: ABC (EAST)", ABC))
        val b = board()
        assertEquals(listOf("ABC (EAST)"), b.section("ABC").rows.map { it.name })
        assertEquals(listOf("ABC"), b.section("News").rows.map { it.name })
    }

    /** M1: a row whose feeds link to both origins shows epgshare01's guide, whichever feed comes first. */
    @Test fun anEpgshareLinkBeatsAProviderLinkInARow() = runBlocking {
        guide.replaceGuide(GuideOrigin.EPGSHARE, listOf(GuideChannel("AE.us2", GuideOrigin.EPGSHARE, null)), sequenceOf(programme("AE.us2", "Epg show", windowStart)))
        guide.replaceGuide(GuideOrigin.PROVIDER, listOf(GuideChannel("P:ae", GuideOrigin.PROVIDER, null)), sequenceOf(programme("P:ae", "Provider show", windowStart)))
        for (providerFirst in listOf(true, false)) {
            val a = feed("US: A&E HD", ENT)
            val b = feed("TV: A&E ᴿᴬᵂ", TV)
            lineup.value = listOf(a, b)
            val (p, e) = if (providerFirst) a to b else b to a
            guide.replaceLinks(listOf(link(p, "P:ae"), link(e, "AE.us2")))
            val row = repoFor(library).board(GuideFilter.ALL, windowStart).first().section("Entertainment").rows.single()
            assertEquals("provider first: $providerFirst", listOf("Epg show"), row.programmes.map { it.title })
        }
    }

    private fun xmltv(file: String) = javaClass.classLoader!!.getResourceAsStream("live/$file")!!.bufferedReader().readLines()
        .filter { it.isNotEmpty() }.map { it.split('\t') }.map { XmltvChannel(it[0], it[1].split(" | "), it.getOrNull(2)?.ifEmpty { null }) }

    /**
     * I3 and I2 over the real lineup and epgshare01 channel lists: no two rows show the same cable guide, and
     * New York's ABC is one row. Prints the counts for the report.
     */
    @Test fun fullLineupRowsDoNotShareGuides() = runBlocking {
        val links = GuideMatcher.match(fixture, xmltv("epgshare_locals_channels.tsv"), xmltv("epgshare_us2_channels.tsv"))
        guide.replaceLinks(links.map { (k, g) -> ChannelGuideLink(k.first, k.second, g) })
        lineup.value = fixture
        val b = repoFor(library, Dispatchers.Default).board(GuideFilter.ALL, windowStart).first()
        val rows = b.sections.flatMap { it.rows + it.more }.distinctBy { it.key }
        val guideOf = rows.associateWith { r -> r.feeds.firstNotNullOfOrNull { links[it.channel.sourceId to it.channel.id] } }
        val shared = guideOf.entries.filter { it.value != null }.groupBy({ it.value!! }) { it.key }.filterValues { it.size > 1 }
        val cableShared = shared.filterKeys { it.endsWith(".us2") }.mapValues { (_, rs) -> rs.map { it.name } }
        fun main(n: String) = b.section(n).rows
        println(
            "GUIDE_ROWS total ${rows.size}, shared guides ${shared.size} (${shared.values.sumOf { it.size }} rows; cable ${cableShared.size}), " +
                Network.entries.joinToString { n -> "$n ${main(n.name).size}+${b.section(n.name).more.size}" } +
                ", main rows without call sign: " + Network.entries.joinToString { n -> "$n ${main(n.name).count { r -> r.feeds.all { ChannelIdentity.of(it.channel.name, it.categoryName).callSign == null } }}" },
        )
        shared.forEach { (g, rs) -> println("GUIDE_SHARED $g: ${rs.joinToString(" || ") { it.key }}") }
        assertEquals(emptyMap<String, List<String>>(), cableShared)
        assertEquals(listOf("station:ABC:WABC"), main("ABC").filter { r -> r.feeds.any { "NEW YORK" in it.channel.name } }.map { it.key })
    }

    /** I4(c): the first board logs how long each stage took, and nothing about the channels. */
    @Test fun firstBoardLogsEachStageTiming() = runBlocking {
        val cnn = feed("US: CNN HD", NEWS)
        lineup.value = listOf(cnn, feed("US: A&E HD", ENT))
        guide.replaceGuide(GuideOrigin.EPGSHARE, listOf(GuideChannel("CNN", GuideOrigin.EPGSHARE, null)), sequenceOf(programme("CNN", "Show", windowStart)))
        guide.replaceLinks(listOf(link(cnn, "CNN")))
        board()
        val ms = Regex("\\d+ ms")
        for (stage in listOf("lineup query", "sort", "identities", "resolve", "first programmes", "first board")) {
            assertTrue("$stage in $timings", timings.any { it.startsWith(stage) && ms.containsMatchIn(it) })
        }
        assertTrue(timings.toString(), timings.none { "CNN" in it || "A&E" in it })
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
        assertTrue("took ${ms}ms", ms < 5_000)
        assertTrue(rows.none { r -> r.name.contains("##") || r.name.contains("NO EVENT", ignoreCase = true) })
        assertTrue(rows.none { it.genre == LiveGenre.EVENTS })
        val nbc = b.section("NBC")
        val ids = nbc.rows.map { r -> r.key to ChannelIdentity.of(r.feeds.first().channel.name, r.feeds.first().categoryName) }
        for ((key, id) in ids) assertTrue("$key: ${id.displayName}", (id.callSign != null && id.market in DEFAULT_MARKETS) || (id.callSign == null && id.network == Network.NBC))
        assertFalse(nbc.rows.isEmpty())
    }

    /** A download writes programmes in many passes; the screen gets a board for the first state and a settled one, not one per write. */
    @Test fun rapidProgrammeWritesProduceFewBoards() = runBlocking {
        val cnn = feed("US: CNN HD", NEWS)
        lineup.value = listOf(cnn)
        guide.replaceLinks(listOf(link(cnn, "CNN")))
        val boards = java.util.concurrent.CopyOnWriteArrayList<GuideBoard>()
        val job = launch(Dispatchers.Default) { repoFor(library, Dispatchers.Default).board(GuideFilter.ALL, windowStart).collect { boards += it } }
        while (boards.isEmpty()) delay(10)
        for (i in 1..20) {
            guide.replaceGuide(GuideOrigin.EPGSHARE, listOf(GuideChannel("CNN", GuideOrigin.EPGSHARE, null)), sequenceOf(programme("CNN", "Show $i", windowStart)))
            delay(5)
        }
        delay(800)
        job.cancel()
        assertTrue("${boards.size} boards", boards.size <= 3)
        assertEquals("Show 20", boards.last().section("News").rows.single().programmes.single().title)
    }

    /** Rough timings for the report (not a pass/fail bound beyond "seconds, not minutes"). */
    @Test fun timeToFirstBoardOnTheFullLineup() = runBlocking {
        lineup.value = fixture
        var started = System.nanoTime()
        repoFor(library, Dispatchers.Default).board(GuideFilter.ALL, windowStart).first()
        val bare = (System.nanoTime() - started) / 1_000_000
        val linked = fixture.take(1_000)
        guide.replaceGuide(
            GuideOrigin.EPGSHARE, linked.indices.map { GuideChannel("G$it", GuideOrigin.EPGSHARE, null) },
            linked.indices.asSequence().flatMap { i -> (0 until 50).asSequence().map { k -> programme("G$i", "Show $i.$k", windowStart - 2 * hour + k * 30 * 60_000L, windowStart - 2 * hour + (k + 1) * 30 * 60_000L) } },
        )
        guide.replaceLinks(linked.mapIndexed { i, c -> link(c, "G$i") })
        started = System.nanoTime()
        repoFor(library, Dispatchers.Default).board(GuideFilter.ALL, windowStart).first()
        val withGuide = (System.nanoTime() - started) / 1_000_000
        println("GUIDE_TIMING first board: ${fixture.size} channels, no guide ${bare} ms; 1,000 linked x 50 programmes ${withGuide} ms")
        assertTrue("$bare / $withGuide ms", bare < 10_000 && withGuide < 10_000)
    }
}
