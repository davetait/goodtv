package com.keyspaceapps.goodtv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keyspaceapps.goodtv.data.guide.ChannelGuideLink
import com.keyspaceapps.goodtv.data.guide.GuideChannel
import com.keyspaceapps.goodtv.data.guide.GuideOrigin
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.SourceRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GuideStoreTest {
    private lateinit var db: GoodTvDatabase
    private lateinit var library: RoomLibraryStore
    private lateinit var guide: RoomGuideStore
    private var sid = 0L

    private val hour = 3_600_000L
    private fun at(h: Int) = h * hour
    private fun prog(id: String, from: Int, to: Int, title: String) =
        GuideProgramme(id, at(from), at(to), title, "About $title", listOf("News", "Talk"), null)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GoodTvDatabase::class.java).build()
        library = RoomLibraryStore(db, Json { ignoreUnknownKeys = true })
        guide = RoomGuideStore(db)
        sid = library.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
    }

    @After fun tearDown() = db.close()

    @Test
    fun replaceGuideSwapsOnlyThatOrigin() = runBlocking {
        guide.replaceGuide(GuideOrigin.EPGSHARE, listOf(GuideChannel("fox.us", GuideOrigin.EPGSHARE, null)), sequenceOf(prog("fox.us", 18, 19, "Old")))
        guide.replaceGuide(GuideOrigin.PROVIDER, listOf(GuideChannel("p1", GuideOrigin.PROVIDER, "http://l")), sequenceOf(prog("p1", 18, 19, "Provider show")))
        guide.replaceGuide(GuideOrigin.EPGSHARE, listOf(GuideChannel("fox.us", GuideOrigin.EPGSHARE, null)), sequenceOf(prog("fox.us", 18, 19, "New")))

        val all = guide.observeProgrammes(setOf("fox.us", "p1"), at(0), at(24)).first()
        assertEquals(listOf("New", "Provider show"), all.map { it.title }.sorted())
        assertEquals(listOf("News", "Talk"), all.first { it.guideId == "p1" }.genres)
        assertEquals(setOf("fox.us", "p1"), guide.observeChannels().first().map { it.guideId }.toSet())
    }

    @Test
    fun replaceGuideHandlesMoreThanOneBatch() = runBlocking {
        val programmes = (0 until 1200).asSequence().map { GuideProgramme("c", it * 1000L, it * 1000L + 500, "P$it", null, emptyList(), null) }
        guide.replaceGuide(GuideOrigin.EPGSHARE, listOf(GuideChannel("c", GuideOrigin.EPGSHARE, null)), programmes)
        assertEquals(1200, guide.observeProgrammes(setOf("c"), 0, Long.MAX_VALUE).first().size)
    }

    @Test
    fun observeProgrammesReturnsOverlaps() = runBlocking {
        guide.replaceGuide(
            GuideOrigin.EPGSHARE, listOf(GuideChannel("c", GuideOrigin.EPGSHARE, null), GuideChannel("d", GuideOrigin.EPGSHARE, null)),
            sequenceOf(prog("c", 21, 22, "Later"), prog("c", 17, 18, "Before").copy(startMs = at(17) + hour / 2, endMs = at(19)), prog("c", 15, 17, "Gone"), prog("c", 22, 23, "Starts at until"), prog("d", 18, 19, "Other channel")),
        )
        val got = guide.observeProgrammes(setOf("c"), at(18), at(21)).first()
        assertEquals(listOf("Before"), got.map { it.title })
        val wider = guide.observeProgrammes(setOf("c", "d"), at(18), at(22)).first()
        assertEquals(listOf("Before", "Later", "Other channel"), wider.map { it.title }.sorted())
        assertEquals(listOf("c", "c", "d"), wider.map { it.guideId })
    }

    @Test
    fun searchFindsLaterShows() = runBlocking {
        guide.replaceGuide(
            GuideOrigin.EPGSHARE, listOf(GuideChannel("c", GuideOrigin.EPGSHARE, null)),
            sequenceOf(prog("c", 16, 17, "Hannity Earlier"), prog("c", 21, 22, "Hannity"), prog("c", 22, 23, "100% Hannity_x"), prog("c", 19, 20, "Other")),
        )
        assertEquals(listOf("Hannity", "100% Hannity_x"), guide.searchProgrammes("hannity", at(18), 10).map { it.title })
        assertEquals(listOf("100% Hannity_x"), guide.searchProgrammes("100%", at(18), 10).map { it.title })
        assertEquals(1, guide.searchProgrammes("hannity", at(18), 1).size)
    }

    @Test
    fun favouritesSurviveACatalogReplace() = runBlocking {
        val key = "$sid:7"
        guide.setFavourite(key, true, 5)
        library.replaceCatalog(sid, Catalog(listOf(Category(sid, ContentKind.LIVE, "c", "News")), listOf(LiveChannel(sid, "7", "c", "Fox", null, 1)), emptyList(), emptyList()))
        assertEquals(setOf(key), guide.observeFavourites().first())
        guide.setFavourite(key, false, 6)
        assertEquals(emptySet<String>(), guide.observeFavourites().first())
    }

    @Test
    fun deletingASourceDropsItsLinks() = runBlocking {
        guide.replaceLinks(listOf(ChannelGuideLink(sid, "7", "fox.us")))
        assertEquals(listOf(ChannelGuideLink(sid, "7", "fox.us")), guide.observeLinks().first())
        library.deleteSource(sid)
        assertEquals(emptyList<ChannelGuideLink>(), guide.observeLinks().first())
    }

    @Test
    fun replaceLinksReplacesAll() = runBlocking {
        guide.replaceLinks(listOf(ChannelGuideLink(sid, "1", "a"), ChannelGuideLink(sid, "2", "b")))
        guide.replaceLinks(listOf(ChannelGuideLink(sid, "3", "c")))
        assertEquals(listOf(ChannelGuideLink(sid, "3", "c")), guide.observeLinks().first())
    }

    @Test
    fun fetchedAtIsPerOrigin() = runBlocking {
        assertNull(guide.fetchedAt(GuideOrigin.EPGSHARE))
        guide.markFetched(GuideOrigin.EPGSHARE, 42)
        guide.markFetched(GuideOrigin.EPGSHARE, 43)
        assertEquals(43L, guide.fetchedAt(GuideOrigin.EPGSHARE))
        assertNull(guide.fetchedAt(GuideOrigin.PROVIDER))
    }
}
