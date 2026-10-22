package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.sports.ChannelCandidate

/**
 * Ties live channels to the epgshare01 guide channels that list their programmes.
 *
 * A local station matches by its [ChannelIdentity.callSign] (`WNBC-DT.us_locals1` is WNBC; a subchannel
 * needs its own id, `WWAY-DT2`). Anything else matches a cable channel whose name or id, without quality
 * and region words, is the channel's name, within the same region (East and an unmarked feed are one).
 * A name that fits several guide channels matches none: no guide is better than the wrong show. Headings,
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
        val out = HashMap<Pair<Long, String>, String>()
        for (c in channels) {
            val identity = ChannelIdentity.of(c.channel.name, c.categoryName)
            if (identity.hidden || identity.loopShow != null || identity.genre == LiveGenre.EVENTS) continue
            val guide = identity.callSign?.let { stations[it] } ?: cableGuide(identity.displayName, cableIds) ?: continue
            out[c.channel.sourceId to c.channel.id] = guide
        }
        return out
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
