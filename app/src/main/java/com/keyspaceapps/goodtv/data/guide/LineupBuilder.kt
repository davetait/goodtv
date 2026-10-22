package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.data.sports.ChannelOrder
import com.keyspaceapps.goodtv.data.sports.GameMatcher

/**
 * The guide's lineup: the visible channels sorted, given identities, grouped into rows, folded into their
 * stations and given their provider logos. Pure and slow on ~12.6k channels (seconds on the TV), so the
 * [LineupKeeper] runs it in the background and saves the result.
 */
object LineupBuilder {
    /** Bump when anything that changes the rows changes (identity rules, grouping, folds, logos): saved lineups rebuild. */
    const val RULES_VERSION = 1

    /** The rows in lineup order, [SavedRow.position] their index. [debug] gets each stage's timing (counts and ms only). */
    fun build(candidates: List<ChannelCandidate>, debug: (String) -> Unit = {}): List<SavedRow> {
        var started = System.nanoTime()
        val placeholders = PlaceholderLogos.of(candidates.map { it.channel })
        val groups = LinkedHashMap<String, MutableList<Pair<ChannelCandidate, ChannelIdentity>>>()
        val sorted = ChannelOrder.sort(candidates)
        debug("sort ${msSince(started)} ms, ${candidates.size} channels")
        started = System.nanoTime()
        for (c in sorted) {
            val id = ChannelIdentity.of(c.channel.name, c.categoryName)
            if (!id.hidden) groups.getOrPut(id.rowKey) { ArrayList() }.add(c to id)
        }
        foldIntoStations(groups, sorted)
        debug("identities ${msSince(started)} ms, ${groups.size} rows")
        started = System.nanoTime()
        var position = 0
        val rows = groups.map { (key, feeds) ->
            val ids = feeds.map { it.second }
            val network = ids.firstNotNullOfOrNull { it.network }
            val callSign = ids.firstNotNullOfOrNull { it.callSign }
            val name = nameOf(ids, network, callSign)
            val loopShow = ids.firstNotNullOfOrNull { it.loopShow }
            SavedRow(
                key = key,
                position = position++,
                name = name,
                genre = ids.firstOrNull { it.genre != LiveGenre.ENTERTAINMENT }?.genre ?: LiveGenre.ENTERTAINMENT,
                network = network,
                callSign = callSign,
                market = ids.firstNotNullOfOrNull { it.market },
                loopShow = loopShow,
                providerLogo = feeds.firstNotNullOfOrNull { (c, _) -> c.channel.logoUrl?.takeIf { it.isNotBlank() && it !in placeholders } },
                sortKey = GameMatcher.normalize(name),
                loopKey = loopShow?.let(GameMatcher::normalize),
                appCopy = network != null && APP_PREFIXES.any { key.startsWith(it) },
                feeds = feeds.map { (c, _) -> SavedFeed(c.channel, c.categoryName) },
            )
        }
        debug("rows ${msSince(started)} ms")
        return rows
    }

    /**
     * A network copy without a call sign ("ABC 7 NEW YORK", "AT&T: FOX 5 LOCAL NEW YORK", "ABC HD [NEW YORK]")
     * is its market's station when that is certain: exactly one main station of the network in that market,
     * and (when the copy has a channel number) one of the station's feeds names the same number. Its feeds
     * then join the station's row and share its guide; otherwise it stays a row of its own. App copies never fold.
     */
    private fun foldIntoStations(groups: LinkedHashMap<String, MutableList<Pair<ChannelCandidate, ChannelIdentity>>>, sorted: List<ChannelCandidate>) {
        fun isApp(key: String) = APP_PREFIXES.any { key.startsWith(it) }
        val stations = HashMap<Pair<Network, String>, MutableList<String>>()
        for ((key, feeds) in groups) {
            if (isApp(key)) continue
            val call = feeds.firstNotNullOfOrNull { it.second.callSign }?.takeIf { '-' !in it } ?: continue
            val network = feeds.firstNotNullOfOrNull { it.second.network } ?: continue
            val market = feeds.firstNotNullOfOrNull { it.second.market } ?: continue
            stations.getOrPut(network to market) { ArrayList() } += key
        }
        if (stations.isEmpty()) return
        var folded = false
        for (key in groups.keys.toList()) {
            val feeds = groups.getValue(key)
            if (isApp(key) || feeds.any { it.second.callSign != null }) continue
            val network = feeds.firstNotNullOfOrNull { it.second.network } ?: continue
            val market = feeds.firstNotNullOfOrNull { it.second.market } ?: continue
            val station = stations[network to market]?.singleOrNull() ?: continue
            val into = groups.getValue(station)
            val numbers = feeds.mapNotNullTo(HashSet()) { it.second.channelNumber }
            if (numbers.isNotEmpty() && into.none { it.second.channelNumber in numbers }) continue
            into += feeds
            groups.remove(key)
            folded = true
        }
        if (!folded) return
        // Feeds stay in [ChannelOrder.sort]'s order.
        val position = java.util.IdentityHashMap<ChannelCandidate, Int>(sorted.size * 2)
        sorted.forEachIndexed { i, c -> position[c] = i }
        for (feeds in groups.values) if (feeds.size > 1) feeds.sortBy { position[it.first] }
    }

    /**
     * A station's copies are named differently ("A3 NEW YORK 04 WNBC NBC", "NBC (WNBC) NEW YORK"): the row takes
     * the shortest name that starts with its network, else the shortest; copies folded in without a call sign
     * don't name it. Other rows take their first feed's name.
     */
    private fun nameOf(ids: List<ChannelIdentity>, network: Network?, callSign: String?): String {
        if (network == null || callSign == null) return ids.first().displayName
        val word = network.name.lowercase()
        val names = ids.filter { it.callSign != null }.map { it.displayName }
        return names.filter { GameMatcher.normalize(PROVIDER_TAG.replaceFirst(it, "")).startsWith(word) }.minByOrNull { it.length }
            ?: names.minBy { it.length }
    }

    private fun msSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private val APP_PREFIXES = listOf("prime ", "roku ", "tubi ")
    private val PROVIDER_TAG = Regex("""^\s*[A-Z0-9&/+]{1,6}:\s*""")
}
