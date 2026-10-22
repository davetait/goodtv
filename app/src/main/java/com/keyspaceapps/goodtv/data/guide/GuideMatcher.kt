package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.sports.ChannelCandidate

/**
 * Ties live channels to the epgshare01 guide channels that list their programmes.
 *
 * A local station matches by its [ChannelIdentity.callSign] (`WNBC-DT.us_locals1` is WNBC; a subchannel
 * needs its own id, `WWAY-DT2`). Names often leave a subchannel's designation out ("FOX 22 (WSBT)" is
 * WSBT's second channel, CBS its main), so a call sign's main network is the one most of its undesignated
 * channels carry; a channel of another network takes the call sign's one subchannel id, or nothing. Anything else matches a cable channel whose name or id, without quality
 * and region words, is the channel's name, within the same region (East and an unmarked feed are one).
 * A station (call sign and network) with no locals id gets no guide. A name that fits several guide channels matches none: no guide is better than the wrong show. Headings,
 * event slots and 24/7 loops never match. Pure and fast enough (~12,600 channels well under a second) to
 * run over the whole lineup on a background dispatcher.
 */
object GuideMatcher {
    private val STATION_ID = Regex("""^([KW][A-Z]{2,3})(?:-([A-Z]+)(\d*))?(_?)\.""")

    /** (sourceId, channelId) → guide id. */
    fun match(channels: List<ChannelCandidate>, locals: List<XmltvChannel>, cable: List<XmltvChannel>): Map<Pair<Long, String>, String> {
        val stations = stations(locals)
        val cableIds = HashMap<Pair<String, Region?>, MutableSet<String>>()
        for (c in cable) for (name in c.names + c.id.substringBeforeLast('.')) {
            val key = ChannelNames.cableKey(name)
            if (key.first.isNotEmpty()) cableIds.getOrPut(key) { HashSet() } += c.id
        }
        val identities = channels.map { it to ChannelIdentity.of(it.channel.name, it.categoryName) }
        val mainNetworks = mainNetworks(identities.map { it.second })
        val subchannels = stations.filterKeys { "-DT" in it }.entries.groupBy({ it.key.substringBefore('-') }) { it.value }
        val out = HashMap<Pair<Long, String>, String>()
        for ((c, identity) in identities) {
            if (identity.hidden || identity.loopShow != null || identity.genre == LiveGenre.EVENTS) continue
            val call = identity.callSign
            // A station has its own guide or none; its name never stands in for a cable channel's.
            val guide = when {
                call == null -> cableGuide(identity.displayName, cableIds)
                identity.network == null -> stations[call]
                '-' in call || identity.network == mainNetworks[call] -> stations[call]
                // Another network's channel on this call sign: its one subchannel, never the main channel.
                call in mainNetworks -> subchannels[call]?.singleOrNull()
                else -> null
            }
            out[c.channel.sourceId to c.channel.id] = guide ?: continue
        }
        return out
    }

    /**
     * Call sign → the network its main channel carries: the most common on its channels named without a
     * designation. On a tie, a network seen on its designated subchannels ("FOX (WEVV2)") is not the main
     * one; if that doesn't settle it, the main network is unknown and no undesignated channel takes the main id.
     */
    internal fun mainNetworks(identities: List<ChannelIdentity>): Map<String, Network> {
        val stations = identities.mapNotNull { i -> if (i.hidden) null else i.callSign?.let { c -> i.network?.let { c to it } } }
        val subchannelNetworks = stations.filter { "-DT" in it.first }.groupBy({ it.first.substringBefore('-') }) { it.second }
        return stations.filter { '-' !in it.first }.groupBy({ it.first }) { it.second }.mapNotNull { (call, networks) ->
            val counts = networks.groupingBy { it }.eachCount()
            var top = counts.filterValues { it == counts.values.max() }.keys
            if (top.size > 1) top = top - subchannelNetworks[call].orEmpty().toSet()
            top.singleOrNull()?.let { call to it }
        }.toMap()
    }

    private fun cableGuide(name: String, ids: Map<Pair<String, Region?>, Set<String>>): String? {
        val (base, region) = ChannelNames.cableKey(name)
        if (base.isEmpty()) return null
        var found = ids[base to region].orEmpty()
        if (found.isEmpty() && region != Region.WEST) found = ids[base to if (region == null) Region.EAST else null].orEmpty()
        return found.singleOrNull()
    }

    /** Call sign with designation ("WCBS", "WWAY-DT2") → its one guide id; "WCBS-DT" beats "WCBS-DT_" and "WCBS-TV". */
    private fun stations(locals: List<XmltvChannel>): Map<String, String> {
        val ranked = HashMap<String, MutableList<Pair<Int, String>>>()
        for (c in locals) {
            val g = STATION_ID.find(c.id)?.groupValues ?: continue
            val designation = ChannelNames.designation(g[2], g[3]) ?: continue
            val rank = if (g[2] == "DT" && g[3].isEmpty() && g[4].isEmpty()) 0 else 1
            ranked.getOrPut(g[1] + designation) { mutableListOf() } += rank to c.id
        }
        val out = ranked.mapNotNull { (call, ids) ->
            val best = ids.minOf { it.first }
            ids.filter { it.first == best }.map { it.second }.distinct().singleOrNull()?.let { call to it }
        }.toMap(HashMap())
        // Names drop a low-power station's suffix: "(WBTS)" is WBTS-CD when that's the only WBTS.
        for (call in ranked.keys.map { it.substringBefore('-') }.distinct()) {
            if (call in out) continue
            out[call] = listOf("$call-LD", "$call-CD").mapNotNull { out[it] }.singleOrNull() ?: continue
        }
        return out
    }
}
