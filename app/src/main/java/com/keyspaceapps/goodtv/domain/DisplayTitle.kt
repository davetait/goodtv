package com.keyspaceapps.goodtv.domain

import java.util.Locale

/**
 * A catalog name split for display: "4K-AR - The Matrix (1999)" → title "The Matrix", year 1999,
 * language "AR", quality "4K", shown as "The Matrix (1999) (AR · 4K)". [title] (with the year) is
 * also what lists sort and search by, so tags never decide the order.
 */
data class DisplayName(val title: String, val year: Int?, val language: String?, val quality: String?) {
    /** "The Matrix (1999)" */
    val base: String get() = if (year != null) "$title ($year)" else title

    /** "The Matrix (1999) (AR · 4K)" */
    val label: String get() = displayLabel(base, language, quality)

    /** "The Matrix (AR · 4K)", for pages that show the year elsewhere. */
    val labelWithoutYear: String get() = displayLabel(title, language, quality)
}

/** [base] followed by "(language · quality)" when either is known. */
fun displayLabel(base: String, language: String?, quality: String?): String =
    listOfNotNull(language, quality).joinToString(" · ").let { if (it.isEmpty()) base else "$base ($it)" }

/**
 * Parses IPTV catalog names. Movies and series carry a leading tag ("EN - ", "4K-FR-HDR - ",
 * "AR-SUBS - ", "NF - "); channels a colon tag ("UK: ", "VIP: ", "CA EN: ") plus decorations
 * ("ᴿᴬᵂ", "ᴴᴰ", "³⁸⁴⁰ᴾ"). The tag is always removed; its language part (ISO language/country codes
 * and a few provider codes, with SUB/DUB markers) becomes [DisplayName.language], 4K/8K becomes
 * [DisplayName.quality], and platform or category tags (NF, AMZ, VIP, SOC…) are dropped.
 */
object DisplayTitle {
    fun parse(raw: String, live: Boolean = false): DisplayName {
        val quality4k = raw.contains("⁴ᴷ") || raw.contains("ᵁᴴᴰ") || raw.contains("³⁸⁴⁰")
        var s = stripDecorations(raw)
        if (s.isEmpty()) return DisplayName(raw.trim(), null, null, null)

        var tagTokens = emptyList<String>()
        tagOf(s, live)?.let { (tag, rest) -> if (rest.isNotBlank()) { tagTokens = tokens(tag); s = rest.trim() } }
        // Panels sometimes repeat the tag: "NL - NL - Spotlight", "4K-AR - AR- The Green Mile".
        if (!live && tagTokens.isNotEmpty()) {
            REPEAT_TAG.find(s)?.takeIf { m -> tokens(m.groupValues[1]).all(::isKnownToken) && s.length > m.value.length }?.let { m ->
                tagTokens = (tagTokens + tokens(m.groupValues[1])).distinct(); s = s.substring(m.value.length).trim()
            }
        }

        var year: Int? = null
        if (!live) {
            // Panels write a missing year as "None": "PREDICTION.None", "X (None)". Not "None Shall Escape".
            s = s.removeSuffix(".None").replace(NONE_SUFFIX, "")
            if (' ' !in s && ('.' in s || '_' in s)) s = s.replace('.', ' ').replace('_', ' ')
            PAREN_YEAR.find(s)?.let { m -> year = m.groupValues[1].toInt(); s = s.substring(0, m.range.first) }
                ?: TRAILING_YEAR.find(s)?.takeIf { it.groupValues[1].toInt() <= latestYear() && !EVENT_DATE.containsMatchIn(s) }?.let { m ->
                    year = m.groupValues[1].toInt(); s = s.substring(0, m.range.first)
                }
        }

        var trailing4k = false
        while (true) {
            val m = TRAILING_TAG.find(s.trim()) ?: break
            if (m.value.trim().uppercase().let { it == "4K" || it == "UHD" || it.startsWith("2160") || it.startsWith("3840") }) trailing4k = true
            s = s.trim().substring(0, m.range.first)
        }
        s = s.trim().trimEnd('-', ':', '|', '.', ',').trim()
        if (s.isEmpty()) return DisplayName(stripDecorations(raw).ifEmpty { raw.trim() }, null, null, null)

        val quality = when {
            tagTokens.any { it == "8K" } -> "8K"
            tagTokens.any { it in FOUR_K } || quality4k || trailing4k -> "4K"
            else -> null
        }
        return DisplayName(s, year, languageOf(tagTokens, live), quality)
    }

    /** "FR - Breaking Bad - S01E01" under series "Breaking Bad" → null (nothing beyond the number). */
    fun episodeTitle(raw: String, seriesTitle: String): String? {
        var s = parse(raw).title
        if (s.startsWith(seriesTitle, ignoreCase = true)) s = s.substring(seriesTitle.length)
        // "The Bear (US) - S01E01 - System": the show's own tag is left in front once its name is gone.
        s = LEADING_TAG.replace(s, "")
        s = EPISODE_CODE.replace(s, " ").replace(REPEATED_SEPARATORS, " - ").replace(EDGE_SEPARATORS, "").replace(SPACES, " ")
        return s.ifEmpty { null }
    }

    // ---- tag ----

    // Movies/series: any caps tag before " - " or " | " (the panel convention) or " -" with no space
    // after it ("AR-DUB -We Bare Bears"), "[MULTI] ", "|EN| ", or a two-letter code with a colon
    // ("UK: "); longer colon prefixes are real titles ("FBI: Most Wanted").
    private val VOD_TAG = Regex("""^(?:\[([^\]]{1,15})]\s*|\|([^|]{1,10})\|\s*|([A-Z0-9][A-Z0-9+\-]{0,13})\s+[-|]\s+|([A-Z]{2}):\s+|([A-Z0-9][A-Z0-9+\-]{0,13})\s+-(?=\S))""")
    private val KNOWN_ONLY_TAG = Regex("""^([A-Z0-9][A-Z0-9+\-]{0,13}?)\s*(?:-|:)\s+""")
    private val REPEAT_TAG = Regex("""^([A-Z0-9][A-Z0-9+\-]{0,13}?)\s*-\s+""")
    // Channels: a short caps tag before ":" or " | ".
    private val LIVE_TAG = Regex("""^([A-Z0-9+/][A-Z0-9+/\- ]{0,12}?)\s*(?::|\s\|)\s+""")

    private fun tagOf(s: String, live: Boolean): Pair<String, String>? {
        if (live) {
            // Channel tags are short caps codes before ":" or " | " on ~70% of a real panel's channels
            // (299 distinct ones), so any such code counts.
            LIVE_TAG.find(s)?.let { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { return it to s.substring(m.range.last + 1) } }
            return null
        }
        VOD_TAG.find(s)?.let { m -> return m.groupValues.drop(1).first { it.isNotEmpty() } to s.substring(m.range.last + 1) }
        // Less regular forms, only when every token is a known code: "SE- Quick", "AR-SUBS: Secret of the Wings".
        KNOWN_ONLY_TAG.find(s)?.takeIf { m -> tokens(m.groupValues[1]).all(::isKnownToken) }?.let { m -> return m.groupValues[1] to s.substring(m.range.last + 1) }
        return null
    }

    private fun tokens(tag: String) = tag.uppercase().split('-', ' ', '/').filter { it.isNotEmpty() }.let { parts ->
        // "24/7" is one token, not "24" and "7".
        if (tag.contains("24/7")) parts.filterNot { it == "24" || it == "7" } + "24/7" else parts
    }

    private fun isKnownToken(t: String) =
        t in DROP || t in FOUR_K || t == "8K" || t in SUB || t in DUB || t in EXTRA_LANG || t in LANGUAGE_WORDS || (t.length == 2 && t in ISO) || t == "S" || t == "D"

    /**
     * Language codes from the tag in panel order, plus SUB/DUB: "AR-SUBS" → "AR-SUB", "4K-FR-HDR" → "FR",
     * "TELUGU" → "TE". Unknown three-letter codes are kept for channels ("ARG", "PAK"), which use them for countries.
     */
    private fun languageOf(tokens: List<String>, live: Boolean): String? {
        val out = mutableListOf<String>()
        var marker: String? = null
        tokens.forEachIndexed { i, t ->
            when {
                t in DROP || t in FOUR_K || t == "8K" -> Unit
                t == "SUB" || t == "SUBS" || (t == "S" && i > 0) -> marker = "SUB"
                t == "DUB" || (t == "D" && i > 0) -> marker = "DUB"
                t in EXTRA_LANG -> out += t
                t in LANGUAGE_WORDS -> out += LANGUAGE_WORDS.getValue(t)
                live && t.length == 3 && t.all { it in 'A'..'Z' } -> out += t
                t.length == 2 && t.all { it in 'A'..'Z' } && (t in ISO || tokens.size > 1) -> out += t
            }
        }
        if (out.isEmpty()) return null
        return (out + listOfNotNull(marker)).distinct().joinToString("-")
    }

    // ---- title ----

    private val PAREN_YEAR = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
    private val TRAILING_YEAR = Regex("""[\s.]((?:19|20)\d{2})$""")
    private val TRAILING_TAG = Regex(
        """(?:\s*\([A-Z]{2,3}\)|\s*\((?:[A-Za-z]{2,3}\s)?(?:Sub|Subs|Dub)\)|\s*\((?:HD|FHD|UHD|4K)\)|\s+(?:4K|UHD|FHD|HD|SD|HEVC|H\.?265|MULTI|RAW|\d{3,4}[pP]))$""",
        RegexOption.IGNORE_CASE,
    )
    // A full date ("08.12.2024") means a dated event; its year is not a release year.
    private val EVENT_DATE = Regex("""\b\d{1,2}\.\d{1,2}\.\d{2,4}\b""")
    private val EDGE_SEPARATORS = Regex("""^[\s\-:|.,]+|[\s\-:|.,]+$""")
    private val LEADING_TAG = Regex("""^\s*\([^)]{1,12}\)""")
    private val REPEATED_SEPARATORS = Regex("""(\s*[\-–|]\s*){2,}""")
    // Compiled once: titles are parsed for every channel of a ~12k lineup.
    private val NONE_SUFFIX = Regex("""\s*\(None\)$""")
    private val SPACES = Regex("""\s+""")
    private val DECORATIONS = Regex("""[ᴀ-ᶿ⁰-₟²³¹◉●]""")
    private val WIDE_SPACES = Regex("""[\s\u00A0\u2007\u202F]+""")
    private val EPISODE_CODE = Regex("""\b[Ss]\d{1,2}\s?[Ee]\d{1,3}\b""")

    /** Superscript/small-capital decorations ("ᴿᴬᵂ", "³⁸⁴⁰ᴾ", "◉") removed, spaces collapsed. */
    private fun stripDecorations(raw: String): String = raw
        .replace(DECORATIONS, "")
        // Non-breaking spaces (some panels put them after the tag) count as spaces; plain \s misses them.
        .replace(WIDE_SPACES, " ")
        .trim()

    private fun latestYear() = java.time.Year.now().value + 1

    private val FOUR_K = setOf("4K", "UHD", "2160P", "3840P")
    private val SUB = setOf("SUB", "SUBS")
    private val DUB = setOf("DUB")

    /** Tags that say where a title is filed, not its language: quality, platforms, categories, bundles. */
    private val DROP = setOf(
        "HD", "FHD", "SD", "HDR", "HDR10", "DV", "HEVC", "H265", "RAW", "VIP", "PREM", "PREMIUM", "GOLD", "PLUS", "TOP",
        "SOC", "SPORT", "SPORTS", "ANM", "DOC", "KID", "KIDS", "POD", "NF", "AMZ", "D+", "A+", "DSC+", "UNV", "PRIME",
        "TUBI", "JOYN", "PLAY", "PLAY+", "TV", "NEXT", "SAT", "24/7", "NBA", "NFL", "NHL", "MLB", "PPV", "M+", "EVENT",
        "MOVIES", "FILM", "VO", "RK", "T2", "GO", "AV", "NM", "YP", "STC", "MEO", "DSTV", "AMP", "AFR", "F",
        // Channel bundles and providers seen on a real panel.
        "OSN", "SPO", "SKY", "SKYGO", "NOW", "WOW", "VIX", "GOBX", "DVB", "DVB-T", "ZG", "VD", "RX", "SS", "SU", "TY", "TK",
        "TF", "RC", "FL", "FZ", "CRB", "CON", "GEN", "ONE", "CHAMP", "PLAYER", "MUSIC", "ZINA", "ATP", "UFC", "CBC", "NCAAM",
        "COITED", "M", "L1", "L2", "F1", "V+", "C+", "P+", "PB", "SRI", "CABO", "SPFL", "LSV", "ETHO", "CAM", "BU", "MALI",
    )

    /** Provider language/region codes that aren't ISO two-letter codes. */
    private val EXTRA_LANG = setOf("EXYU", "EX", "QC", "SHAM", "IRQ", "LATAM")

    /** Channel tags that spell the language out. */
    private val LANGUAGE_WORDS = mapOf(
        "ENGLISH" to "EN", "HINDI" to "HI", "TAMIL" to "TA", "PUNJABI" to "PA", "MALAYALAM" to "ML", "TELUGU" to "TE",
        "GUJARATI" to "GU", "KANNADA" to "KN", "MARATHI" to "MR", "BENGALI" to "BN", "BHOJPURI" to "BHO", "ODIA" to "OR",
    )

    private val ISO: Set<String> = (Locale.getISOCountries().asList() + Locale.getISOLanguages().filter { it.length == 2 })
        .map { it.uppercase() }.toSet()
}
