package com.keyspaceapps.goodtv.data.tmdb

import java.text.Normalizer

/** A provider title reduced to what TMDB search understands. [searchable] false = don't bother asking. */
data class CleanTitle(val title: String, val year: Int?, val searchable: Boolean)

/**
 * Turns IPTV catalog names into a TMDB search query. Shapes seen on real panels:
 * "4K-AR - The Matrix (1999)", "AR-ANM-S-S - …", "Shenanigans.2018", "Kaveri (2020) (IN)",
 * "Haris (1972) حریص" (translation after the year), "SOC - Fulham - Arsenal 08.12.2024" (a sports
 * event TMDB has no entry for).
 */
object TitleCleaner {
    // A leading tag: "[MULTI] ", "|EN| ", "4K-AR - " (caps, digits, '+', '-' then " - " or " | "), or a
    // two-letter code with a colon ("UK: "). Longer colon prefixes are real titles ("FBI: Most Wanted").
    private val prefix = Regex("""^(?:\[[^\]]{1,15}]\s*|\|[^|]{1,10}\|\s*|[A-Z0-9][A-Z0-9+\-]{0,13}\s+[-|]\s+|[A-Z]{2}:\s+)""")
    private val parenYear = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
    private val trailingYear = Regex("""[\s.]((?:19|20)\d{2})$""")
    private val trailingTag = Regex("""(?:\s*\([A-Z]{2,3}\)|\s+(?:4K|UHD|FHD|HD|SD|HEVC|H\.?265|MULTI|RAW|\d{3,4}p))$""", RegexOption.IGNORE_CASE)
    private val eventDate = Regex("""\b\d{1,2}\.\d{1,2}\.\d{2,4}\b""")

    fun clean(raw: String): CleanTitle {
        var s = raw.trim().replace(Regex("""\s+"""), " ")
        prefix.find(s)?.let { m -> s.substring(m.range.last + 1).trim().takeIf { it.isNotEmpty() }?.let { s = it } }
        val event = eventDate.containsMatchIn(s)
        if (' ' !in s && ('.' in s || '_' in s)) s = s.replace('.', ' ').replace('_', ' ')

        var year: Int? = null
        parenYear.find(s)?.let { m -> year = m.groupValues[1].toInt(); s = s.substring(0, m.range.first) }
            ?: trailingYear.find(s)?.let { m -> year = m.groupValues[1].toInt(); s = s.substring(0, m.range.first) }

        while (true) {
            val next = trailingTag.replace(s.trim(), "")
            if (next == s.trim()) break
            s = next
        }
        s = s.trim().trimEnd('-', ':', '|', '.', ',').trim()
        if (s.isEmpty()) s = raw.trim()
        return CleanTitle(s, year, searchable = !event && s.any { it.isLetterOrDigit() })
    }

    /** Comparison form: lower case, accents and punctuation dropped, single spaces. */
    fun normalize(title: String): String =
        Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
}
