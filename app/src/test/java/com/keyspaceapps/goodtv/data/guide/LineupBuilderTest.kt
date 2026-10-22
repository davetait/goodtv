package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
