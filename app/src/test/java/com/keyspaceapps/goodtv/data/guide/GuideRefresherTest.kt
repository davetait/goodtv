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
    private val guide = InMemoryGuideStore()
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
        refresher = GuideRefresher(
            guide, library, SourceRegistry(listOf(channelSource)), EpgShareSource(OkHttpClient(), server.url("/epgshare01/")),
            playback, { now }, Dispatchers.Unconfined, tmp.newFolder(), { warnings += it },
        )
        runBlocking { addSource() }
    }

    @After fun tearDown() = server.close()

    private suspend fun addSource() {
        sourceId = library.insertSource(SourceRecord(0, SourceType.XTREAM, "p", SourceConfig.Xtream("http://p", "u", "p"), true, now, null))
        fun ch(id: String, name: String, epg: String? = null) = LiveChannel(sourceId, id, "c1", name, null, null, epgChannelId = epg)
        library.replaceCatalog(
            sourceId,
            Catalog(
                listOf(Category(sourceId, ContentKind.LIVE, "c1", "US| TV")),
                listOf(
                    ch("1", "US: NBC 4 NEW YORK (WNBC)", "wnbc.us"),
                    ch("2", "US: FOX NEWS CHANNEL HD", "foxnews.us"),
                    ch("3", "US: SOME LOCAL ACCESS CHANNEL", "access.us"),
                    ch("4", "US: NO GUIDE AT ALL"),
                ),
                emptyList(), emptyList(),
            ),
        )
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
        assertTrue(tmp.root.walkTopDown().none { it.isFile && it.name.startsWith("guide") })
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
        playback.playing.value = true
        refresher.refreshIfStale()
        assertTrue(channelSource.openGuideCalls.isEmpty())
        assertNull(guide.fetchedAt(GuideOrigin.PROVIDER))
        playback.playing.value = false
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
}
