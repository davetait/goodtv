package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideMatcherTest {
    private fun tsv(file: String): List<List<String>> =
        javaClass.classLoader!!.getResourceAsStream("live/$file")!!.bufferedReader().readLines()
            .filter { it.isNotEmpty() }.map { it.split('\t') }

    private fun xmltv(file: String) = tsv(file).map { XmltvChannel(it[0], it[1].split(" | "), it.getOrNull(2)?.ifEmpty { null }) }

    private val locals by lazy { xmltv("epgshare_locals_channels.tsv") }
    private val cable by lazy { xmltv("epgshare_us2_channels.tsv") }

    /** Dave's visible lineup: name, category, logo (a hash standing in for the URL). */
    private val lineup by lazy {
        tsv("visible_channels.tsv").mapIndexed { i, r -> ChannelCandidate(LiveChannel(1, "$i", null, r[0], r[2], null), r[1]) }
    }

    private fun candidate(name: String, category: String = "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ", id: String = name) =
        ChannelCandidate(LiveChannel(1, id, null, name, null, null), category)

    private fun guideOf(name: String, category: String = "US| TV ᴿᴬᵂ ⁶⁰ᶠᵖˢ", l: List<XmltvChannel> = locals, c: List<XmltvChannel> = cable): String? =
        GuideMatcher.match(listOf(candidate(name, category)), l, c)[1L to name]

    @Test fun callSignNeedsParenthesesOrNetworkWord() {
        assertNull(guideOf("24/7: DOCTOR WHO", "US| 24/7 ACTION/ADVENTURE ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertNull(guideOf("AT&T: HONG KONG FIGHT CLUB ᴿᴬᵂ", "US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertNull(guideOf("24/7: SCARECROW AND MRS. KING", "US| 24/7 ACTION/ADVENTURE ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertNull(guideOf("US: DOCTOR WHO 4K", "US| CINEMA TV SHOWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertNull(guideOf("AT&T: CLASSIC DOCTOR WHO ᴿᴬᵂ", "US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals("WHO-DT.us_locals1", guideOf("US: NBC 13 (WHO) DES MOINES (A) ᴿᴬᵂ", "US| NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals("WNBC-DT.us_locals1", guideOf("US: NBC 4 NEW YORK (WNBC)"))
        assertEquals("WCBS-DT.us_locals1", guideOf("TV: A3 NEW YORK 02 WCBS CBS ᴿᴬᵂ"))
        assertEquals("KING-DT.us_locals1", guideOf("US: SEATTLE, WA KING NBC 5 (A) ᴿᴬᵂ"))
    }

    @Test fun subchannelsGetTheirOwnGuideOrNone() {
        assertEquals("WWAY-DT2.us_locals1", guideOf("US: CBS 3 (WWAY-DT2) WILMINGTON HD"))
        assertEquals("WWAY-DT.us_locals1", guideOf("US: ABC 3 (WWAY) WILMINGTON HD"))
        // Names drop a low-power station's suffix.
        assertEquals("WBTS-CD.us_locals1", guideOf("US: NBC 10 (WBTS) BOSTON (H) ᴿᴬᵂ"))
        val onlyMain = listOf(XmltvChannel("WWAY-DT.us_locals1", listOf("WWAY-DT"), null))
        assertNull(guideOf("US: CBS 3 (WWAY-DT2) WILMINGTON HD", l = onlyMain))
    }

    @Test fun stationsNeverFallThroughToCable() {
        val cbsNamed = listOf(XmltvChannel("CBS.KYW.us2", listOf("CBS KYW"), null))
        assertNull(guideOf("US: CBS (KYW)", l = emptyList(), c = cbsNamed))
        // A network's national feed has no call sign, so the cable file may list it.
        assertEquals("CW.us2", guideOf("US: CW HD"))
    }

    @Test fun newsAndAppStreamsAreNotTheStation() {
        assertNull(guideOf("PRIME: CBS OMAHA NEWS (KMTV) ᴿᴬᵂ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertNull(guideOf("RK: WABC ABC7 NEW YORK ᴿᴬᵂ", "US| ROKU ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertNull(guideOf("PRIME: WSB ATLANTA (ABC) ᴿᴬᵂ", "US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
    }

    @Test fun cableMatchesByNameAndRegion() {
        assertEquals("Bravo.HD.us2", guideOf("US: BRAVO HD"))
        assertEquals("Bravo.HD.us2", guideOf("US: BRAVO EAST HD"))
        assertEquals("Bravo.(Pacific).us2", guideOf("US: BRAVO WEST HD"))
        assertEquals("A.and.E.HD.East.us2", guideOf("AT&T: A&E ᴿᴬᵂ"))
        assertEquals("AMC+.us2", guideOf("RK: AMC+ ᴿᴬᵂ"))
        assertEquals("Fox.News.Channel.HD.us2", guideOf("US: FOX NEWS CHANNEL HD"))
        assertNull(guideOf("24/7: CNBC", "US| 24/7 REALITY ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
    }

    @Test fun ambiguousCableNameMatchesNothing() {
        val two = listOf(
            XmltvChannel("Vice.us2", listOf("Vice"), null),
            XmltvChannel("VICE.HD.us2", listOf("VICE HD"), null),
            XmltvChannel("Ovation.us2", listOf("Ovation"), null),
        )
        assertNull(guideOf("TV: VICE ᴿᴬᵂ", l = emptyList(), c = two))
        assertEquals("Ovation.us2", guideOf("TV: OVATION ᴿᴬᵂ", l = emptyList(), c = two))
        // The same names in two ids of the real file.
        assertNull(guideOf("US: HBO WEST HD"))
    }

    @Test fun realLineupMatchRates() {
        val start = System.nanoTime()
        val links = GuideMatcher.match(lineup, locals, cable)
        val ms = (System.nanoTime() - start) / 1_000_000
        val byKey = lineup.associateBy { it.channel.sourceId to it.channel.id }
        val matched = links.mapKeys { byKey.getValue(it.key) }
        val localCount = matched.count { it.value.endsWith(".us_locals1") }
        val cableCount = matched.count { it.value.endsWith(".us2") }
        println("guide matching: $localCount locals, $cableCount cable in $ms ms")
        // Measured 1,273: below the plan's 1,300 because news streams and app (Prime/Roku/Tubi) copies of stations never get the station's guide.
        assertTrue("locals $localCount", localCount >= 1_250)
        assertTrue("cable $cableCount", cableCount >= 650)
        assertEquals(0, matched.keys.count { it.channel.name.startsWith("24/7:") })
        // A station guide only ever comes from a call sign the name itself carries.
        val callSignMissing = matched.filter { (c, guide) -> guide.endsWith(".us_locals1") && guide.substringBefore('-').substringBefore('.') !in c.channel.name }
        assertEquals(emptyMap<ChannelCandidate, String>(), callSignMissing)
        assertFalse(matched.keys.any { ChannelIdentity.of(it.channel.name, it.categoryName).let { i -> i.hidden || i.genre == LiveGenre.EVENTS } })
        assertTrue("took $ms ms", ms < 2_000)
    }

    @Test fun oneIdentityPerRow() {
        // Whatever the name style, the channels of one row agree on what the row is. Genre is left out: it
        // also comes from the category, so A&E under Entertainment and under AT&T may differ.
        val visible = lineup.map { ChannelIdentity.of(it.channel.name, it.categoryName) }.filter { !it.hidden }
        val conflicts = visible.groupBy { it.rowKey }.filterValues { row ->
            row.map { listOf(it.network, it.callSign, it.region, it.genre == LiveGenre.EVENTS) }.distinct().size > 1 ||
                row.mapNotNull { it.market }.distinct().size > 1
        }.keys
        // The provider files one Philadelphia station under "NBC (WCAU) LOS ANGELES, CA".
        assertEquals(setOf("station:NBC:WCAU"), conflicts)
    }

    @Test fun placeholderLogosAreTheWidelySharedOnes() {
        val channels = lineup.map { it.channel }
        val counts = channels.groupingBy { it.logoUrl }.eachCount()
        val placeholders = PlaceholderLogos.of(channels)
        assertEquals(1_600, counts["d7ffb154"])
        assertTrue("d7ffb154" in placeholders)
        // The Starz family's logo, on its 16 channels, is a real one.
        assertEquals(16, counts["721a1773"])
        assertFalse("721a1773" in placeholders)
        val unique = counts.entries.first { it.value == 1 }.key
        assertFalse(unique in placeholders)
        val generic = LiveChannel(1, "g", null, "US: SOMETHING", "http://dtil.tmsimg.com/sources/generic/generic_sources_h3.png", null)
        assertEquals(setOf(generic.logoUrl), PlaceholderLogos.of(listOf(generic)))
    }
}
