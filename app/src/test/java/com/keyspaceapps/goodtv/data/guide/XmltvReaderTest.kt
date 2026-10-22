package com.keyspaceapps.goodtv.data.guide

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmltvReaderTest {
    private fun sample(): InputStream = javaClass.classLoader!!.getResourceAsStream("live/epgshare_us2_sample.xml")!!

    private class Result(val channels: List<XmltvChannel>, val programmes: List<GuideProgramme>, val count: Int)

    private fun read(
        input: InputStream,
        keep: (String) -> Boolean = { true },
        from: Long = Long.MIN_VALUE,
        until: Long = Long.MAX_VALUE,
    ): Result {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<GuideProgramme>()
        val n = XmltvReader.read(input, keep, from, until, { channels += it }, { programmes += it })
        return Result(channels, programmes, n)
    }

    private fun read(xml: String, keep: (String) -> Boolean = { true }, from: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE) =
        read(ByteArrayInputStream(xml.toByteArray()), keep, from, until)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun readsChannelsAndProgrammesFromSample() {
        val r = read(sample())
        assertEquals(listOf("Fox.News.Channel.HD.us2", "AMC.HD.us2"), r.channels.map { it.id })
        assertEquals(listOf("Fox News Channel HD"), r.channels[0].names)
        assertEquals("http://dtil.tmsimg.com/assets/s60179_ll_h15_ab.png?w=360&h=270", r.channels[0].iconUrl)
        assertEquals(16, r.count)
        assertEquals(16, r.programmes.size)
        assertTrue(r.programmes.all { it.title.isNotBlank() })
        assertTrue(r.programmes.any { it.genres.isNotEmpty() })
        assertTrue(r.programmes.any { it.imageUrl != null })
        val first = r.programmes.first()
        assertEquals("Fox.News.Channel.HD.us2", first.guideId)
        assertEquals("Jesse Watters Primetime", first.title)
        assertEquals(ms("2026-10-20T05:00:00Z"), first.startMs)
        assertEquals(ms("2026-10-20T06:00:00Z"), first.endMs)
        assertEquals(listOf("Series", "Talk"), first.genres.take(2))
    }

    @Test
    fun keepsOnlyWantedChannelsInsideTheWindow() {
        val xml = """<tv>
            <channel id="a"><display-name>A</display-name></channel>
            <channel id="b"><display-name>B</display-name></channel>
            <programme channel="a" start="20261020000000 +0000" stop="20261020010000 +0000"><title>ends before</title></programme>
            <programme channel="a" start="20261020010000 +0000" stop="20261020030000 +0000"><title>straddles start</title></programme>
            <programme channel="a" start="20261020030000 +0000" stop="20261020040000 +0000"><title>inside</title></programme>
            <programme channel="a" start="20261020040000 +0000" stop="20261020060000 +0000"><title>straddles end</title></programme>
            <programme channel="a" start="20261020050000 +0000" stop="20261020060000 +0000"><title>starts at until</title></programme>
            <programme channel="b" start="20261020030000 +0000" stop="20261020040000 +0000"><title>other channel</title></programme>
            </tv>"""
        val r = read(xml, keep = { it == "a" }, from = ms("2026-10-20T02:00:00Z"), until = ms("2026-10-20T05:00:00Z"))
        assertEquals(listOf("straddles start", "inside", "straddles end"), r.programmes.map { it.title })
        assertEquals(3, r.count)
        assertEquals(listOf("a", "b"), r.channels.map { it.id })
    }

    @Test
    fun attributeOrderAndOffsetsDoNotMatter() {
        val a = read("""<tv><programme channel="x" start="20261020180000 -0400" stop="20261020190000 -0400"><title>T</title></programme></tv>""")
        val b = read("""<tv><programme start="20261020180000 -0400" stop="20261020190000 -0400" channel="x"><title>T</title></programme></tv>""")
        assertEquals(ms("2026-10-20T22:00:00Z"), a.programmes.single().startMs)
        assertEquals(ms("2026-10-20T23:00:00Z"), a.programmes.single().endMs)
        assertEquals(a.programmes, b.programmes)
    }

    @Test
    fun entitiesAreUnescaped() {
        val r = read(
            """<tv><programme channel="x" start="20261020180000 +0000" stop="20261020190000 +0000">
            <title>Tom &amp; Jerry&#39;s &quot;Show&quot;</title><desc>It&apos;s &lt;b&gt; &#x41;</desc>
            <category>Q&amp;A</category></programme></tv>""",
        )
        val p = r.programmes.single()
        assertEquals("Tom & Jerry's \"Show\"", p.title)
        assertEquals("It's <b> A", p.description)
        assertEquals(listOf("Q&A"), p.genres)
    }

    @Test
    fun malformedMidFileKeepsWhatCameBefore() {
        fun good(i: Int) = """<programme channel="x" start="2026102018000$i +0000" stop="2026102019000$i +0000"><title>P$i</title></programme>"""
        val xml = "<tv>" + (0..2).joinToString("") { good(it) } + """<programme channel="x" start=""" + "\u0000<<garbage"
        val r = read(xml)
        assertEquals(3, r.count)
        assertEquals(listOf("P0", "P1", "P2"), r.programmes.map { it.title })
    }

    @Test
    fun brokenBlockInTheMiddleIsSkippedAndReadingContinues() {
        val xml = """<tv>
            <programme channel="x" start="20261020180000 +0000" stop="20261020190000 +0000"><title>before</title></programme>
            <programme channel="x" start="20261020190000 +0000" stop=
            <programme channel="x" start="20261020200000 +0000" stop="20261020210000 +0000"><title>after</title></programme>
            <programme channel="x" start="not a time" stop="20261020220000 +0000"><title>bad time</title></programme>
            </tv>"""
        assertEquals(listOf("before", "after"), read(xml).programmes.map { it.title })
    }

    @Test
    fun blockSplitAcrossChunksIsRead() {
        val bytes = sample().readBytes()
        val slow = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 7))
        }
        val whole = read(ByteArrayInputStream(bytes))
        val r = read(slow)
        assertEquals(whole.programmes, r.programmes)
        assertEquals(whole.channels, r.channels)
        assertEquals(16, r.count)
    }
}
