package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryGuideStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

class GuideRefresherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val files = HashMap<String, ByteArray>()
    private val failing = HashSet<String>()
    private val hits = ArrayList<String>()

    private val library = FakeLibraryStore()
    private val channelSource = FakeChannelSource()
    private val calls = ArrayList<String>()
    private val mem = InMemoryGuideStore()
    private val guide: GuideStore = object : GuideStore by mem {
        override suspend fun replaceLinks(links: List<ChannelGuideLink>) { calls += "links"; mem.replaceLinks(links) }
        override suspend fun markFetched(origin: GuideOrigin, atMs: Long) { calls += "fetched $origin"; mem.markFetched(origin, atMs) }
    }
    private val playback = PlaybackState()
    private var now = Instant.parse("2026-10-20T08:00:00Z").toEpochMilli()
    private lateinit var refresher: GuideRefresher
    private var sourceId = 0L
    private val warnings = ArrayList<String>()

    private fun gz(text: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
    private fun xmltvTime(ms: Long) = DateTimeFormatter.ofPattern("yyyyMMddHHmmss 'Z'").format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC)).replace(" Z", " +0000")

    private fun programme(id: String, title: String, startOffsetMs: Long) =
        """<programme channel="$id" start="${xmltvTime(now + startOffsetMs)}" stop="${xmltvTime(now + startOffsetMs + 3_600_000)}"><title>$title</title></programme>"""

    private fun channel(id: String, name: String) = """<channel id="$id"><display-name>$name</display-name></channel>"""

    private val locals = """<tv>
        ${channel("WNBC-DT.us_locals1", "WNBC-DT")} ${channel("WABC-DT.us_locals1", "WABC-DT")}
        ${programme("WNBC-DT.us_locals1", "Nightly News", 3_600_000)} ${programme("WABC-DT.us_locals1", "Not Wanted", 3_600_000)}
        </tv>"""

    /** The real cable sample's two channels, with their programmes moved to today. */
    private val cable: String get() = javaClass.getResource("/live/epgshare_us2_sample.xml")!!.readText()
        .replace(Regex("""(start|stop)="2026102\d"""")) { m -> """${m.groupValues[1]}="${xmltvTime(now + 3_600_000).take(8)}""" }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.url.encodedPath.substringAfterLast('/')
                hits += name
                return if (name in failing) MockResponse(code = 500) else MockResponse.Builder().body(Buffer().write(files.getValue(name))).build()
            }
        }
        server.start()
        files[EpgShareFiles.LOCALS] = gz(locals)
        files[EpgShareFiles.CABLE] = gz(cable)
        refresher = refresherWith()
        runBlocking { addSource() }
    }

    private val tempDir by lazy { tmp.newFolder() }

    private fun refresherWith(limits: GuideLimits = GuideLimits(), timeoutMs: Long = GuideRefresher.DOWNLOAD_TIMEOUT_MS, store: GuideStore = guide, io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined, early: EarlyRefreshLog = MemoryEarlyRefreshLog()) = GuideRefresher(
        store, library, SourceRegistry(listOf(channelSource)), EpgShareSource(OkHttpClient(), server.url("/epgshare01/")),
        playback, { now }, io, tempDir, { warnings += it }, limits, timeoutMs, early,
    )

    private fun leftovers() = tempDir.listFiles().orEmpty().toList()

    @After fun tearDown() = server.close()

    private suspend fun addSource(vararg names: Pair<String, String?>): Long {
        val id = library.insertSource(SourceRecord(0, SourceType.XTREAM, "p", SourceConfig.Xtream("http://p", "u", "p"), true, now, null))
        val channels = if (names.isEmpty()) listOf(
            "US: NBC 4 NEW YORK (WNBC)" to "wnbc.us", "US: FOX NEWS CHANNEL HD" to "foxnews.us",
            "US: SOME LOCAL ACCESS CHANNEL" to "access.us", "US: NO GUIDE AT ALL" to null,
        ) else names.toList()
        library.replaceCatalog(
            id,
            Catalog(
                listOf(Category(id, ContentKind.LIVE, "c1", "US| TV")),
                channels.mapIndexed { i, (name, epg) -> LiveChannel(id, "${i + 1}", "c1", name, null, null, epgChannelId = epg) },
                emptyList(), emptyList(),
            ),
        )
        if (sourceId == 0L) sourceId = id
        return id
    }

    private fun providerXml() = """<tv>
        ${channel("access.us", "Access")} ${channel("foxnews.us", "Fox")} ${channel("other.us", "Other")}
        ${programme("access.us", "Town Hall", 3_600_000)} ${programme("foxnews.us", "Dup", 3_600_000)} ${programme("other.us", "Other show", 3_600_000)}
        </tv>"""

    private fun provides(xml: String) { channelSource.guideResults[sourceId] = { Result.success(ByteArrayInputStream(xml.toByteArray())) } }

    private suspend fun programmeIds() = guide.observeProgrammes(guide.observeChannels().first().map { it.guideId }.toSet(), 0, Long.MAX_VALUE).first().map { it.guideId }.toSet()

    @Test fun matchesAndStoresOnlyMatchedChannels() = runBlocking {
        refresher.refreshIfStale()
        assertEquals(setOf("WNBC-DT.us_locals1", "Fox.News.Channel.HD.us2"), guide.observeChannels().first().map { it.guideId }.toSet())
        assertEquals(setOf("WNBC-DT.us_locals1", "Fox.News.Channel.HD.us2"), programmeIds())
        assertEquals(
            setOf(ChannelGuideLink(sourceId, "1", "WNBC-DT.us_locals1"), ChannelGuideLink(sourceId, "2", "Fox.News.Channel.HD.us2")),
            guide.observeLinks().first().toSet(),
        )
        assertEquals(now, guide.fetchedAt(GuideOrigin.EPGSHARE))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun notAgainWithin24Hours() = runBlocking {
        refresher.refreshIfStale()
        val first = hits.size
        now += GUIDE_MAX_AGE_MS - 1
        refresher.refreshIfStale()
        assertEquals(first, hits.size)
        now += 1
        refresher.refreshIfStale()
        assertEquals(first * 2, hits.size)
    }

    @Test fun failedDownloadKeepsLastGoodGuide() = runBlocking {
        refresher.refreshIfStale()
        val firstFetch = now
        val before = programmeIds()
        now += GUIDE_MAX_AGE_MS
        failing += EpgShareFiles.CABLE
        refresher.refreshIfStale()
        assertEquals(before, programmeIds())
        assertEquals(2, guide.observeLinks().first().size)
        assertEquals(firstFetch, guide.fetchedAt(GuideOrigin.EPGSHARE))
    }

    @Test fun providerGuideWaitsWhilePlaying() = runBlocking {
        provides(providerXml())
        playback.enter()
        refresher.refreshIfStale()
        assertTrue(channelSource.openGuideCalls.isEmpty())
        assertNull(guide.fetchedAt(GuideOrigin.PROVIDER))
        playback.exit()
        refresher.refreshIfStale()
        assertEquals(listOf(sourceId), channelSource.openGuideCalls)
        refresher.refreshIfStale()
        assertEquals(1, channelSource.openGuideCalls.size)
    }

    @Test fun providerOnlyForUnmatchedChannels() = runBlocking {
        provides(providerXml())
        refresher.refreshIfStale()
        // Channel 3 has a provider id the epgshare files do not cover; 1 and 2 matched, 4 has no id.
        assertEquals(setOf("P:access.us"), guide.observeChannels().first().filter { it.origin == GuideOrigin.PROVIDER }.map { it.guideId }.toSet())
        assertEquals(setOf("WNBC-DT.us_locals1", "Fox.News.Channel.HD.us2", "P:access.us"), programmeIds())
        assertEquals(
            setOf(
                ChannelGuideLink(sourceId, "1", "WNBC-DT.us_locals1"),
                ChannelGuideLink(sourceId, "2", "Fox.News.Channel.HD.us2"),
                ChannelGuideLink(sourceId, "3", "P:access.us"),
            ),
            guide.observeLinks().first().toSet(),
        )
        assertEquals(now, guide.fetchedAt(GuideOrigin.PROVIDER))
    }

    @Test fun failedProviderKeepsItsGuideAndLinks() = runBlocking {
        provides(providerXml())
        refresher.refreshIfStale()
        now += GUIDE_MAX_AGE_MS
        channelSource.guideResults[sourceId] = { Result.failure(java.io.IOException("down")) }
        refresher.refreshIfStale()
        assertTrue("P:access.us" in programmeIds())
        assertTrue(ChannelGuideLink(sourceId, "3", "P:access.us") in guide.observeLinks().first())
        assertEquals(now - GUIDE_MAX_AGE_MS, guide.fetchedAt(GuideOrigin.PROVIDER))
    }

    @Test fun killedAfterEpgshareStillHasItsLinks() = runBlocking {
        val started = CompletableDeferred<Unit>()
        channelSource.guideResults[sourceId] = { started.complete(Unit); awaitCancellation() }
        val job = launch { refresher.refreshIfStale() }
        started.await()
        job.cancelAndJoin()
        assertEquals(2, guide.observeLinks().first().size)
        assertEquals(now, guide.fetchedAt(GuideOrigin.EPGSHARE))
        assertEquals(listOf("links", "fetched EPGSHARE"), calls)
        assertNull(guide.fetchedAt(GuideOrigin.PROVIDER))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun cancellationPropagatesAndRemovesTempFiles() = runBlocking {
        val started = CompletableDeferred<Unit>()
        channelSource.guideResults[sourceId] = { started.complete(Unit); awaitCancellation() }
        val job: Job = launch { refresher.refreshIfStale() }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(leftovers().isEmpty())
    }

    @Test fun playNextKeepsPlayingTrue() {
        playback.enter(); playback.enter(); playback.exit()
        assertTrue(playback.playing.value)
        playback.exit()
        assertTrue(!playback.playing.value)
        playback.exit()
        assertTrue(!playback.playing.value)
    }

    @Test fun providerAbortsWhenPlaybackStarts() = runBlocking {
        channelSource.guideResults[sourceId] = {
            playback.enter() // a player opens while the provider's guide is being downloaded
            Result.success(ByteArrayInputStream(providerXml().toByteArray()))
        }
        refresher.refreshIfStale()
        assertEquals(setOf("WNBC-DT.us_locals1", "Fox.News.Channel.HD.us2"), guide.observeChannels().first().map { it.guideId }.toSet())
        assertNull(guide.fetchedAt(GuideOrigin.PROVIDER))
        assertTrue(guide.observeLinks().first().none { it.guideId.startsWith("P:") })
        assertTrue(warnings.any { "PROVIDER" in it })
        assertTrue(leftovers().isEmpty())
    }

    @Test fun newSourceWithoutLinksTriggersARefresh() = runBlocking {
        refresher.refreshIfStale()
        val first = hits.size
        refresher.refreshIfStale()
        assertEquals(first, hits.size)
        val second = addSource("US: ABC 7 NEW YORK (WABC)" to null)
        refresher.refreshIfStale()
        assertEquals(first * 2, hits.size)
        assertTrue(ChannelGuideLink(second, "1", "WABC-DT.us_locals1") in guide.observeLinks().first())
        // A source nothing matches is tried once, not on every call.
        addSource("US: NOTHING MATCHES THIS" to null)
        refresher.refreshIfStale(); refresher.refreshIfStale()
        assertEquals(first * 3, hits.size)
    }

    /** A source nothing matches costs one early download a day, not one per cold start: the attempt outlives the process. */
    @Test fun earlyRefreshForAnUnlinkedSourceOncePerDayAcrossRestarts() = runBlocking {
        val log = MemoryEarlyRefreshLog()
        refresher = refresherWith(early = log)
        refresher.refreshIfStale()
        val first = hits.size
        val lonely = addSource("US: NOTHING MATCHES THIS" to null)
        refresher.refreshIfStale()
        assertEquals(first * 2, hits.size)
        assertEquals(now, log.lastEarlyRefresh(lonely))
        // A restart (a new refresher, the same saved log) an hour later downloads nothing.
        now += 3_600_000L
        refresherWith(early = log).refreshIfStale()
        assertEquals(first * 2, hits.size)
    }

    @Test fun oneMatchRunsOverBothSources() = runBlocking {
        files[EpgShareFiles.LOCALS] = gz(
            """<tv>${channel("KRQE-DT.us_locals1", "KRQE-DT")} ${channel("KRQE-DT2.us_locals1", "KRQE-DT2")}</tv>""",
        )
        library.replaceCatalog(sourceId, Catalog(emptyList(), emptyList(), emptyList(), emptyList()))
        val a = addSource("US: CBS 13 (KRQE) ALBUQUERQUE HD" to null, "US: CBS 13 (KRQE) ALBUQUERQUE FHD" to null)
        val b = addSource("US: FOX (KRQE) ALBUQUERQUE HD" to null)
        refresher.refreshIfStale()
        val links = guide.observeLinks().first().toSet()
        // Alone, source b's FOX would be KRQE's main channel; across the whole lineup CBS is, so FOX is the subchannel.
        assertEquals(
            setOf(
                ChannelGuideLink(a, "1", "KRQE-DT.us_locals1"), ChannelGuideLink(a, "2", "KRQE-DT.us_locals1"),
                ChannelGuideLink(b, "1", "KRQE-DT2.us_locals1"),
            ),
            links,
        )
        assertEquals(2, hits.size)
    }

    @Test fun oversizeDownloadFailsThatOriginOnly() = runBlocking {
        provides(providerXml())
        val tiny = GuideLimits(locals = GuideCap(download = 20, unpacked = 1 shl 20))
        refresherWith(tiny).refreshIfStale()
        assertNull(guide.fetchedAt(GuideOrigin.EPGSHARE))
        assertEquals(now, guide.fetchedAt(GuideOrigin.PROVIDER))
        assertTrue(warnings.any { "EPGSHARE" in it })
        assertTrue(leftovers().isEmpty())
    }

    @Test fun oversizeUnpackedFileFailsThatOrigin() = runBlocking {
        val bomb = GuideLimits(cable = GuideCap(download = 1 shl 20, unpacked = 100))
        refresherWith(bomb).refreshIfStale()
        assertNull(guide.fetchedAt(GuideOrigin.EPGSHARE))
        assertTrue(guide.observeChannels().first().isEmpty())
        assertTrue(leftovers().isEmpty())
    }

    @Test fun notEnoughDiskSpaceFailsTheOrigin() = runBlocking {
        refresherWith(GuideLimits(spaceReserve = Long.MAX_VALUE / 2)).refreshIfStale()
        assertNull(guide.fetchedAt(GuideOrigin.EPGSHARE))
        assertTrue(hits.isEmpty())
    }

    @Test fun stalledDownloadTimesOut() = runBlocking {
        channelSource.guideResults[sourceId] = {
            Result.success(object : java.io.InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(10); b[off] = ' '.code.toByte(); return 1 }
            })
        }
        val started = System.nanoTime()
        // A real dispatcher: on the test thread the blocking read would also block the timer.
        refresherWith(timeoutMs = 300, io = Dispatchers.IO).refreshIfStale()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertNull(guide.fetchedAt(GuideOrigin.PROVIDER))
        assertEquals(now, guide.fetchedAt(GuideOrigin.EPGSHARE))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun tempFilesRemovedAfterFailure() = runBlocking {
        failing += EpgShareFiles.CABLE
        refresher.refreshIfStale()
        assertTrue(hits.isNotEmpty())
        assertTrue(leftovers().isEmpty())
    }

    @Test fun producerNeverThrowsWhenConsumerLeavesDuringAFailure() {
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val programme = GuideProgramme("g", 0, 1, "t", null, emptyList(), null)
            // Capacity 1: the first programme fills the queue, so reporting the failure blocks.
            val stream = GuideRefresher.ProgrammeStream(null, capacity = 1) { emit, _ -> emit(programme); throw IOException("bad file") }
            stream.sequence.iterator()
            Thread.sleep(300)
            stream.close()
            assertTrue(uncaught.toString(), uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun singleByteReadsAreCheckedToo() {
        var checks = 0
        val input = GuideRefresher.CappedInputStream(ByteArrayInputStream(byteArrayOf(1, 2)), 10) { checks++ }
        input.read(); input.read()
        assertEquals(2, checks)
    }

    /** Manual load check (remove @Ignore): ~500 MB of XMLTV through the real pipeline; prints time and peak live heap. */
    @org.junit.Ignore("manual load check")
    @Test fun largeGuideStaysFastAndSmall() = runBlocking {
        val big = tmp.newFile("big.xml.gz")
        val total = 700_000
        val desc = "d".repeat(600)
        java.util.zip.GZIPOutputStream(big.outputStream().buffered()).bufferedWriter().use { w ->
            w.write("<tv>" + channel("WNBC-DT.us_locals1", "WNBC-DT"))
            for (i in 0 until total) {
                w.write("""<programme channel="WNBC-DT.us_locals1" start="${xmltvTime(now + 3_600_000)}" stop="${xmltvTime(now + 7_200_000)}"><title>P$i</title><desc>$desc $i</desc><category>A</category></programme>""")
                w.write("\n")
            }
            w.write("</tv>")
        }
        files[EpgShareFiles.LOCALS] = big.readBytes()
        var count = 0L
        var peak = 0L
        val counting = object : GuideStore by mem {
            override suspend fun replaceGuide(origin: GuideOrigin, channels: List<GuideChannel>, programmes: Sequence<GuideProgramme>) {
                for (p in programmes) {
                    if (++count % 100_000 == 0L) { System.gc(); val rt = Runtime.getRuntime(); peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()) }
                }
            }
        }
        val t0 = System.nanoTime()
        refresherWith(store = counting).refreshIfStale()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("GUIDE load: $count programmes, ~${count * (desc.length + 260) / 1_000_000} MB unpacked, ${ms} ms, peak live heap ${peak / 1_000_000} MB")
        assertTrue(count >= total) // plus the cable sample's own programmes
    }
}
