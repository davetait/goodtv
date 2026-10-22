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

    private val goodProgramme = """<programme channel="x" start="20261020180000 +0000" stop="20261020190000 +0000"><title>Good</title></programme>"""

    @Test(timeout = 20_000)
    fun manyUnclosedStartsFinishQuickly() {
        val unclosed = """<programme channel="x" start="20261020180000 +0000" stop="20261020190000 +0000">"""
        val xml = StringBuilder("<tv>")
        while (xml.length < 5_000_000) xml.append(unclosed).append('\n')
        xml.append(goodProgramme).append(goodProgramme).append(goodProgramme).append("</tv>")
        val t0 = System.nanoTime()
        val r = read(xml.toString())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(3, r.count)
        assertTrue("took ${ms}ms", ms < 2_000)
    }

    @Test(timeout = 20_000)
    fun unclosedCategoryInsideABlockDoesNotStall() {
        val xml = StringBuilder("""<tv><programme channel="x" start="20261020180000 +0000" stop="20261020190000 +0000"><title>T</title>""")
        while (xml.length < 1_000_000) xml.append("<category>")
        xml.append("</programme></tv>")
        val t0 = System.nanoTime()
        val r = read(xml.toString())
        val ms = (System.nanoTime() - t0) / 1_000_000
        // The programme is kept; the unclosed category yields no genres.
        assertEquals("T", r.programmes.single().title)
        assertTrue(r.programmes.single().genres.size <= 32)
        assertTrue("took ${ms}ms", ms < 1_000)
    }

    @Test
    fun genresAndDisplayNamesAreCappedAt32() {
        val cats = (1..40).joinToString("") { "<category>G$it</category>" }
        val names = (1..40).joinToString("") { "<display-name>N$it</display-name>" }
        val r = read(
            """<tv><channel id="c">$names</channel>
            <programme channel="x" start="20261020180000 +0000" stop="20261020190000 +0000"><title>T</title>$cats</programme></tv>""",
        )
        assertEquals(32, r.channels.single().names.size)
        assertEquals(32, r.programmes.single().genres.size)
        assertEquals("G1", r.programmes.single().genres.first())
    }

    /** One-off load check (run by removing @Ignore): ~200 MB, 200k programmes, 5% wanted. */
    @org.junit.Ignore("manual load check")
    @Test
    fun largeGeneratedFileStaysFastAndSmall() {
        val total = 200_000
        val desc = "d".repeat(700)
        val gen = object : InputStream() {
            private var i = -1
            private var cur = ByteArray(0)
            private var at = 0
            var bytes = 0L
            var peak = 0L
            private fun nextBlock(): ByteArray? {
                i++
                return when {
                    i == 0 -> "<tv>".toByteArray()
                    i <= 20 -> """<channel id="c${i - 1}"><display-name>C${i - 1}</display-name></channel>""".toByteArray()
                    i <= 20 + total -> {
                        val n = i - 21
                        """<programme channel="c${n % 20}" start="20261020180000 +0000" stop="20261020190000 +0000"><title>P$n</title><desc>$desc</desc><category>A</category></programme>
""".toByteArray()
                    }
                    i == 21 + total -> "</tv>".toByteArray()
                    else -> null
                }
            }
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= cur.size) { cur = nextBlock() ?: return -1; at = 0 }
                val n = minOf(len, cur.size - at)
                System.arraycopy(cur, at, b, off, n)
                at += n; bytes += n
                if ((bytes and 0xFFFFFF) < n) {
                    System.gc() // live heap, not garbage
                    val rt = Runtime.getRuntime()
                    peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
                }
                return n
            }
        }
        val t0 = System.nanoTime()
        val r = read(gen, keep = { it == "c0" })
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("XMLTV load: ${gen.bytes / 1_000_000} MB, ${r.count} programmes, ${ms} ms, peak used heap ${gen.peak / 1_000_000} MB")
        assertEquals(total / 20, r.count)
    }
}
