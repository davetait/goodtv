package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.data.sports.GameMatcher
import com.keyspaceapps.goodtv.domain.DisplayTitle

enum class LiveGenre { NETWORK, NEWS, SPORTS, ENTERTAINMENT, COMEDY, MOVIES, KIDS, DOCUMENTARY, EVENTS }

enum class Network { ABC, CBS, NBC, FOX, PBS, CW }

enum class Region { EAST, WEST }

/** The five largest US TV markets: their local stations show under each network, the rest fold into "More NBC"… */
val DEFAULT_MARKETS: List<String> = listOf("New York", "Los Angeles", "Chicago", "Philadelphia", "Dallas")

/**
 * What a provider channel is, read from its name and category: the guide row it belongs to, its genre,
 * and for a local station its network, call sign and market.
 *
 * [rowKey] comes from the name alone (never ids), so favourites survive re-syncs. It merges copies that
 * differ only in tag, quality or backup markers ("US: A&E HD", "TV: A&E ᴿᴬᵂ"), and, off the networks, the
 * ones the guide matcher calls one channel ("THE X", "X CHANNEL", "X EAST" and plain "X"); West feeds and
 * different call signs stay apart. A station's copies (call sign plus network) are one row whatever
 * the name says: network, call sign with any subchannel ("WWAY-DT2") and feed region. [callSign] is set
 * only when the name carries one in parentheses or next to a network word, so "Doctor WHO" is not
 * station WHO. A station's own news stream is not the station; an app's copy of one (Prime, Roku,
 * Tubi) keeps its network and market but is its own row, "FOX 7 AUSTIN (Prime)", with no call sign.
 */
data class ChannelIdentity(
    val rowKey: String,
    val displayName: String,
    val genre: LiveGenre,
    val network: Network?,
    val callSign: String?,
    val market: String?,
    val region: Region?,
    /** The show a "24/7:" channel loops, "30 Rock". */
    val loopShow: String?,
    /** Headings, empty event slots and ended ones. */
    val hidden: Boolean,
    /**
     * A network row's channel number: right after the network word ("ABC7", "FOX 5 LOCAL") or right before
     * the call sign ("A3 NEW YORK 07 WABC ABC"). Lets a copy without a call sign find its station.
     */
    val channelNumber: Int? = null,
) {
    companion object {
        private val HEADING = Regex("""#{2,}""")
        /** "US: ", "AT&T: ", "24/7: ": provider and category tags. */
        private val TAG = Regex("""^\s*([A-Z0-9&/+]{1,6}):\s*""")
        private val LOOP = Regex("""^\s*24/7\s+""")
        private val LETTERS_OR_DIGITS = Regex("""[a-z]+|[0-9]+""")
        private val SPACES = Regex("""\s+""")
        private val PARENS = Regex("""\([^)]*\)""")
        /** "US: ABC HD [NEW YORK]": a network row named by its city. */
        private val BRACKET = Regex("""\[[^\]]*\]""")
        /** "WWAY", "WWAY-DT2", "WCIV.2", and in parentheses "(WEVV2)" or "(KRQEDT2)". */
        private val CALL_SIGN = Regex("""(?<![A-Za-z0-9])([KW][A-Z]{2,3})(?:-(DT|TV|LD|CD|D)(\d?)|\.(\d)|(DT)?(\d))?(?![A-Za-z0-9])""")
        /** Words shaped like call signs that aren't any. */
        private val NOT_CALL_SIGNS = setOf("WEST", "KIDS", "WACO")
        /** Free streaming apps: their "ABC7 New York" is a news stream, not the broadcast. */
        private val APP_TAGS = mapOf("PRIME" to "Prime", "RK" to "Roku", "TUBI" to "Tubi")
        private val NETWORK_WORDS = Network.entries.associateBy { it.name.lowercase() }
        private val MARKET_WORDS = DEFAULT_MARKETS.map { it.lowercase() to it } + listOf("fort worth" to "Dallas", "dfw" to "Dallas")
        private val SMALL_WORDS = setOf("a", "an", "and", "at", "for", "in", "of", "on", "or", "the", "to", "vs")

        /** By the start of the name, after its tag; "espn*" is any word starting "espn". */
        private val KNOWN_CHANNELS: List<Pair<String, LiveGenre>> = listOf(
            "cnn" to LiveGenre.NEWS, "hln" to LiveGenre.NEWS, "msnbc" to LiveGenre.NEWS, "fox news" to LiveGenre.NEWS,
            "fox business" to LiveGenre.NEWS, "cnbc" to LiveGenre.NEWS, "bloomberg" to LiveGenre.NEWS, "newsmax" to LiveGenre.NEWS,
            "newsnation" to LiveGenre.NEWS, "c span" to LiveGenre.NEWS,
            "espn*" to LiveGenre.SPORTS, "fs1" to LiveGenre.SPORTS, "fs2" to LiveGenre.SPORTS, "fox sports" to LiveGenre.SPORTS,
            "nfl network" to LiveGenre.SPORTS, "nfl redzone" to LiveGenre.SPORTS, "nba tv" to LiveGenre.SPORTS, "mlb network" to LiveGenre.SPORTS,
            "nhl network" to LiveGenre.SPORTS, "bein" to LiveGenre.SPORTS, "golf channel" to LiveGenre.SPORTS, "tennis channel" to LiveGenre.SPORTS,
            "cbs sports" to LiveGenre.SPORTS, "nbc sports" to LiveGenre.SPORTS, "big ten network" to LiveGenre.SPORTS,
            "sec network" to LiveGenre.SPORTS, "acc network" to LiveGenre.SPORTS,
            "comedy central" to LiveGenre.COMEDY,
            "hbo" to LiveGenre.MOVIES, "showtime" to LiveGenre.MOVIES, "tcm" to LiveGenre.MOVIES, "turner classic movies" to LiveGenre.MOVIES,
            "starz" to LiveGenre.MOVIES, "cinemax" to LiveGenre.MOVIES, "movie channel" to LiveGenre.MOVIES,
            "disney" to LiveGenre.KIDS, "nickelodeon" to LiveGenre.KIDS, "nick jr" to LiveGenre.KIDS, "nicktoons" to LiveGenre.KIDS,
            "cartoon network" to LiveGenre.KIDS, "boomerang" to LiveGenre.KIDS, "pbs kids" to LiveGenre.KIDS,
            "discovery" to LiveGenre.DOCUMENTARY, "history" to LiveGenre.DOCUMENTARY, "hgtv" to LiveGenre.DOCUMENTARY,
            "food network" to LiveGenre.DOCUMENTARY, "national geographic" to LiveGenre.DOCUMENTARY, "nat geo" to LiveGenre.DOCUMENTARY,
            "animal planet" to LiveGenre.DOCUMENTARY, "science channel" to LiveGenre.DOCUMENTARY, "smithsonian" to LiveGenre.DOCUMENTARY,
        )
        private val CATEGORY_GENRES = mapOf(
            "comedy" to LiveGenre.COMEDY, "movies" to LiveGenre.MOVIES, "sport" to LiveGenre.SPORTS, "sports" to LiveGenre.SPORTS,
            "replay" to LiveGenre.SPORTS, "cinemania" to LiveGenre.MOVIES, "kids" to LiveGenre.KIDS, "cartoon" to LiveGenre.KIDS,
        )

        /** A provider category's words and the genre they give. */
        class CategoryInfo(val words: Set<String>, val genre: LiveGenre?)

        /** Per category name: a lineup has ~100 categories and ~12,600 channels. */
        private val categories = java.util.concurrent.ConcurrentHashMap<String, CategoryInfo>()
        private const val MAX_CATEGORIES = 4096

        /** [categoryName]'s words and genre, worked out once per distinct name. */
        fun categoryOf(categoryName: String): CategoryInfo = categories[categoryName] ?: run {
            val words = GameMatcher.normalize(categoryName).split(' ').toSet()
            if (categories.size >= MAX_CATEGORIES) categories.clear()
            CategoryInfo(words, words.firstNotNullOfOrNull { CATEGORY_GENRES[it] }).also { categories[categoryName] = it }
        }

        fun of(name: String, categoryName: String): ChannelIdentity {
            val plain = GameMatcher.normalize(name)
            val hidden = HEADING.containsMatchIn(name) || GameMatcher.CLOSED.any { plain.startsWith(it) } ||
                "no event streaming" in plain || plain.endsWith(" no event")
            val tag = TAG.find(name)
            var body = if (tag != null) name.substring(tag.range.last + 1) else name
            val tagName = tag?.groupValues?.get(1)
            // "24/7: 30 ROCK", or "US: 24/7 MET GALA 2023".
            val loopTag = if (tagName == "24/7") null else LOOP.find(body)
            val loop = tagName == "24/7" || loopTag != null
            if (loopTag != null) body = body.substring(loopTag.range.last + 1)
            val categoryInfo = categoryOf(categoryName)
            val category = categoryInfo.words
            val event = !loop && "ppv" in category

            val words = ChannelNames.words(body)
            val news = words.indices.any { words[it] == "news" && words.getOrNull(it - 1) != "newport" }
            val notStation = hidden || loop || event || news
            val app = APP_TAGS[tagName]
            val parts = partsOf(words)
            val netAt = parts.indexOfFirst { it in NETWORK_WORDS }
            val found = if (notStation) null else callSignOf(body, hasNetworkWord = netAt >= 0)
            val isStation = !notStation && netAt >= 0 && (
                found != null || parts.getOrNull(netAt + 1)?.all(Char::isDigit) == true ||
                    // A national feed, "ABC (EAST)", only where the provider files it under that network: its "US: ABC HD" in News is a news channel.
                    (parts.all { it == parts[netAt] || it in ChannelNames.REGIONS } && parts[netAt] in category) ||
                    // The network and its city in brackets, "ABC HD [NEW YORK]".
                    BRACKET.find(body)?.let { b -> partsOf(ChannelNames.words(body.removeRange(b.range))).all { it == parts[netAt] } } == true
                )
            val network = if (isStation) NETWORK_WORDS.getValue(parts[netAt]) else null
            val region = when {
                loop -> null
                // "WEST HARTFORD" is a city: a station's feed says "(EAST)" or ends with it.
                found != null -> ChannelNames.REGIONS[words.lastOrNull()]
                    ?: PARENS.findAll(body).firstNotNullOfOrNull { ChannelNames.REGIONS[GameMatcher.normalize(it.value)] }
                else -> words.drop(1).firstNotNullOfOrNull { ChannelNames.REGIONS[it] }
            }
            // An app's copy of a station is its own channel and never gets the broadcast's guide.
            val callSign = if (app == null) found else null
            // "TV: DAVENPORT, IA KWQC NBC 6 A3 HD LOS ANGELES": the station's city is the one before the comma.
            val city = " " + ChannelNames.words(body.substringBefore(',')).joinToString(" ") + " "
            val market = if (network == null) null else MARKET_WORDS.firstOrNull { " ${it.first} " in city }?.second

            val genre = if (event) LiveGenre.EVENTS else {
                (if (loop) null else knownGenre(words)) ?: categoryInfo.genre ?: when {
                    news -> LiveGenre.NEWS
                    network != null -> LiveGenre.NETWORK
                    "news" in category -> LiveGenre.NEWS
                    else -> LiveGenre.ENTERTAINMENT
                }
            }
            // DisplayTitle drops most tags but not "AT&T:".
            val displayName = DisplayTitle.parse(ChannelNames.MARKER.replace(name, "").replace(SPACES, " ").trim(), live = true).title
                .let { t -> tag?.let { t.removePrefix(it.value.trim()) } ?: t }.trimStart(':', ' ')
            val nameKey = words.joinToString(" ").ifEmpty { plain.ifEmpty { name.trim() } }
            // Every copy of a station is one row, however the name is written: "CBS 2 WCBS NEW YORK", "A3 NEW YORK 02 WCBS CBS".
            val key = when {
                loop -> "24/7 $nameKey"
                // "NBA: CHICAGO BULLS" in a PPV category is an event slot, not the AT&T channel of that name.
                event -> "event $nameKey"
                network != null && found != null -> listOfNotNull("station", network.name, found, region?.name).joinToString(":")
                network != null -> nameKey
                // The copies the guide matcher calls one channel are one row: "FOX NEWS" and "FOX NEWS CHANNEL",
                // "HBO" and "HBO EAST" (an unmarked feed is the East one). West stays apart.
                else -> ChannelNames.cableKey(body).let { (base, r) -> if (base.isEmpty()) nameKey else if (r == Region.WEST) "$base west" else base }
            }
            val channelNumber = if (network == null) null else {
                parts.getOrNull(netAt + 1)?.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
                    ?: found?.let { call -> words.indexOf(call.substringBefore('-').lowercase()) }?.takeIf { it > 0 }
                        ?.let { words[it - 1].takeIf { w -> w.all(Char::isDigit) }?.toIntOrNull() }
            }
            val appCopy = app?.takeIf { network != null }
            return ChannelIdentity(
                rowKey = if (appCopy != null) "${appCopy.lowercase()} $key" else key,
                displayName = if (appCopy != null) "$displayName ($appCopy)" else displayName,
                genre = genre,
                network = network,
                callSign = callSign,
                market = market,
                region = region,
                loopShow = if (loop) titleCase(displayName.removePrefix("24/7").trim()).ifEmpty { null } else null,
                hidden = hidden,
                channelNumber = channelNumber,
            )
        }

        /** "abc7" is the network word and a channel number. */
        private fun partsOf(words: List<String>): List<String> = words.flatMap { w -> LETTERS_OR_DIGITS.findAll(w).map { it.value }.toList() }

        /** The call sign in parentheses, else the first one when a network word is in the name, with any subchannel: "WWAY-DT2". */
        private fun callSignOf(body: String, hasNetworkWord: Boolean): String? {
            val parens = PARENS.findAll(body).map { it.range }.toList()
            fun inParens(c: MatchResult) = parens.any { c.range.first > it.first && c.range.last < it.last }
            // A number glued on ("WEVV2") only counts in parentheses; elsewhere it may be a brand ("KTLA5").
            val found = CALL_SIGN.findAll(body).filter { it.groupValues[1] !in NOT_CALL_SIGNS && (it.groupValues[6].isEmpty() || inParens(it)) }.toList()
            val m = found.firstOrNull(::inParens) ?: found.firstOrNull()?.takeIf { hasNetworkWord } ?: return null
            val g = m.groupValues
            val designation = when {
                g[4].isNotEmpty() -> ChannelNames.designation("DT", g[4])
                g[6].isNotEmpty() -> ChannelNames.designation("DT", g[6])
                else -> ChannelNames.designation(g[2], g[3])
            }
            return g[1] + designation.orEmpty()
        }

        private fun knownGenre(words: List<String>): LiveGenre? {
            val s = words.dropWhile { it == "the" }.joinToString(" ") + " "
            return KNOWN_CHANNELS.firstOrNull { (k, _) -> if (k.endsWith('*')) s.startsWith(k.dropLast(1)) else s.startsWith("$k ") }?.second
        }

        /** "SCARECROW AND MRS. KING" → "Scarecrow and Mrs. King". */
        private fun titleCase(s: String): String = s.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, w ->
            val lower = w.lowercase()
            if (i > 0 && lower in SMALL_WORDS) lower else lower.replaceFirstChar { it.titlecase() }
        }.joinToString(" ")
    }
}

/** Name pieces [ChannelIdentity] and [GuideMatcher] share. */
internal object ChannelNames {
    private val QUALITY = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "raw", "hevc", "hdr", "1080p", "720p", "60fps", "50fps", "fps", "backup")
    /** Quality words that, in a guide's channel name, name a channel of their own ("Fox Sports 4K"), not a copy. */
    val VARIANTS = setOf("4k", "8k", "uhd")
    val REGIONS = mapOf("east" to Region.EAST, "west" to Region.WEST, "pacific" to Region.WEST)
    /** "(D)", "(PC)", "(D11)": backup and server markers. */
    val MARKER = Regex("""\((?:[A-Z]{1,2}|D\d{1,2})\)""")

    /** Lower-case words without quality (but [keep]) or backup markers, "&" and "+" spelled out so "A&E" meets "A and E". */
    fun words(s: String, keep: Set<String> = emptySet()): List<String> =
        GameMatcher.normalize(MARKER.replace(s, " ").replace("&", " and ").replace("+", " plus "))
            .split(' ').filter { it.isNotEmpty() && (it !in QUALITY || it in keep) }

    /** A cable channel's name for matching, without its region, and the region: "Bravo (Pacific)" → ("bravo", WEST). */
    fun cableKey(s: String, keep: Set<String> = emptySet()): Pair<String, Region?> {
        var region: Region? = null
        val w = words(s, keep).filterIndexed { i, t -> (if (i > 0) REGIONS[t] else null)?.also { region = it } == null }.toMutableList()
        if (w.firstOrNull() == "the") w.removeAt(0)
        if (w.size > 1 && w.last() == "channel") w.removeAt(w.lastIndex)
        return w.joinToString(" ") to region
    }

    /** A call sign's suffix as one form: "" for the main station (-DT, -TV, -D, none), "-DT2" for a subchannel, "-LD", "-CD". */
    fun designation(suffix: String, digits: String): String? {
        val n = digits.toIntOrNull() ?: 1
        return when (suffix) {
            "", "DT", "TV", "D" -> if (n <= 1) "" else "-DT$n"
            "LD", "CD" -> "-$suffix$digits"
            else -> null
        }
    }
}
