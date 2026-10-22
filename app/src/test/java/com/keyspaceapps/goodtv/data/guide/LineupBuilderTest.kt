package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LineupBuilderTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("live/$name")!!.bufferedReader().readLines().filter { it.isNotEmpty() }

    private val fixture by lazy {
        resource("visible_channels.tsv").map { it.split('\t') }.mapIndexed { i, r -> ChannelCandidate(LiveChannel(1, "$i", null, r[0], r[2], null), r[1]) }
    }

    /** One golden line: the row's fields (null as empty), then its feeds as sourceId:channelId:categoryName, in order. */
    private fun line(r: SavedRow) = (
        listOf(r.key, r.name, r.genre, r.network, r.callSign, r.market, r.loopShow, r.providerLogo, r.appCopy).map { (it ?: "").toString() } +
            r.feeds.map { "${it.channel.sourceId}:${it.channel.id}:${it.categoryName}" }
        ).joinToString("\t")

    /**
     * lineup_golden.tsv is the old in-repository build over the full fixture, captured before the build moved:
     * the builder gives the same rows, fields and feeds in the same order.
     */
    @Test fun builderMatchesTheRepositoryBuild() {
        val rows = LineupBuilder.build(fixture)
        val golden = resource("lineup_golden.tsv")
        assertEquals(golden.size, rows.size)
        rows.forEachIndexed { i, r -> assertEquals("row $i", golden[i], line(r)) }
        assertEquals(rows.indices.toList(), rows.map { it.position })
    }

    /**
     * Regenerates lineup_golden.tsv from [LineupBuilder] over the fixture, after a deliberate change to the rules: remove
     * the @Ignore, run `./gradlew :app:testDebugUnitTest --tests '*LineupBuilderTest.regenerateGolden'`, put it back.
     * Changed the golden? bump RULES_VERSION and update GOLDEN_SHA256 (see [rulesVersionMatchesTheGolden]).
     */
    @Ignore("Run by hand to regenerate the golden after changing the rules")
    @Test fun regenerateGolden() {
        // Unit tests run in the module directory.
        File("src/test/resources/live/lineup_golden.tsv").writeText(LineupBuilder.build(fixture).joinToString("") { line(it) + "\n" })
    }

    /**
     * The golden and [LineupBuilder.RULES_VERSION] move together, so a rules change always rebuilds saved lineups.
     * Changed the golden? bump RULES_VERSION and update GOLDEN_SHA256 (and GOLDEN_RULES_VERSION to match).
     */
    @Test fun rulesVersionMatchesTheGolden() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("live/lineup_golden.tsv")!!.use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("lineup_golden.tsv changed: bump LineupBuilder.RULES_VERSION, then set GOLDEN_RULES_VERSION and GOLDEN_SHA256", GOLDEN_SHA256, sha)
        assertEquals("RULES_VERSION changed: the golden it pairs with is GOLDEN_RULES_VERSION's", GOLDEN_RULES_VERSION, LineupBuilder.RULES_VERSION)
    }

    private companion object {
        /** The rules version lineup_golden.tsv was generated with, and the file's SHA-256: updated together, with RULES_VERSION. */
        const val GOLDEN_RULES_VERSION = 1
        const val GOLDEN_SHA256 = "ff617d361e1874828459afb5170697227acba521c7cb6e8543b8f74995d4b64d"
    }
}
