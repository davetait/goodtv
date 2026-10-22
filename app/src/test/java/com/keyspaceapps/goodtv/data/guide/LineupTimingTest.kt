package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.data.sports.ChannelOrder
import com.keyspaceapps.goodtv.domain.LiveChannel
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rough timings of the lineup merge's two big steps over Dave's 12.6k visible channels (for the report; bound "well under seconds"). */
class LineupTimingTest {
    private val lineup by lazy {
        javaClass.classLoader!!.getResourceAsStream("live/visible_channels.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotEmpty() }.map { it.split('\t') }
            .mapIndexed { i, r -> ChannelCandidate(LiveChannel(1, "$i", null, r[0], r[2], null), r[1]) }
    }

    @Test fun sortAndIdentitiesOfTheFullLineup() {
        val channels = lineup
        // Warm the JIT once, then time a second pass.
        repeat(2) { pass ->
            var started = System.nanoTime()
            ChannelOrder.sort(channels)
            val sortMs = (System.nanoTime() - started) / 1_000_000
            started = System.nanoTime()
            channels.forEach { ChannelIdentity.of(it.channel.name, it.categoryName) }
            val identityMs = (System.nanoTime() - started) / 1_000_000
            println("LINEUP_TIMING pass ${pass + 1}: ${channels.size} channels, sort $sortMs ms, identities $identityMs ms")
            assertTrue("sort $sortMs ms, identities $identityMs ms", sortMs < 5_000 && identityMs < 5_000)
        }
    }
}
