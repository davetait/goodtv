package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.domain.DisplayTitle
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.legacy.LegacyDisplayTitle
import com.keyspaceapps.goodtv.legacy.legacyGenres
import com.keyspaceapps.goodtv.legacy.legacyTmdbIdOf

/**
 * Compares the fast conversion with the frozen b5b986d copy ([com.keyspaceapps.goodtv.legacy]) on values
 * captured from a real panel. Shared by the JVM test and the instrumented one, which runs Android's ICU regex.
 * Each function returns the differing inputs, empty when all match.
 */
object ConversionEquivalence {
    fun titleDiffs(): List<String> =
        corpus("live_names.txt").mapNotNull { diff(it, DisplayTitle.parse(it, live = true), LegacyDisplayTitle.parse(it, live = true)) } +
            (corpus("movie_names.txt") + corpus("series_names.txt")).mapNotNull { diff(it, DisplayTitle.parse(it), LegacyDisplayTitle.parse(it)) } +
            EDGE_NAMES.flatMap { name ->
                listOf(true, false).mapNotNull { live -> diff("$name (live=$live)", DisplayTitle.parse(name, live), LegacyDisplayTitle.parse(name, live)) }
            }

    fun genreDiffs(): List<String> =
        (corpus("genres.txt") + listOf(null, "", "  ")).mapNotNull { diff(it, Genre.fromPanelText(it), legacyGenres(it)) }

    /**
     * Only Android's oracle reads non-ASCII digits as Android does (ICU's \d is any Unicode digit; the JDK's
     * is ASCII), so the JVM test leaves [NON_ASCII_DIGIT_TMDB] out and checks those values explicitly.
     */
    fun tmdbDiffs(includeNonAsciiDigits: Boolean): List<String> {
        val inputs = corpus("tmdb.txt") + TMDB_EXTRAS + (if (includeNonAsciiDigits) NON_ASCII_DIGIT_TMDB.keys else emptySet())
        return inputs.mapNotNull { diff(it, tmdbIdOf(it), legacyTmdbIdOf(it)) }
    }

    /** What Android gives for TMDB values with non-ASCII digits. */
    val NON_ASCII_DIGIT_TMDB: Map<String, Long?> = mapOf("٣٤" to 34L, "12٣" to 123L, "١٢abc" to 12L, "٠" to null, "12\uD835\uDFCF" to null)

    private val TMDB_EXTRAS = listOf(null, "", " 12 ", "0", "43332-", "tt0111161", "-5", "12abc", "99999999999999999999")

    /** Names at the edges of the checks that decide when a regex can be skipped. */
    private val EDGE_NAMES = listOf(
        "", " ", "  ", "EN - Movie  1999", "EN -\tMovie 1999", "EN - Movie\u00A01999", "EN - Movie 1999\n.None",
        "EN - Movie 1999\r\n.None", "EN - Movie 1999\u2028.None", "EN - Movie 1999\u0085.None", "EN - Show HD\u0085",
        "Show 4K\u0085", "Show (US)\u0085", "Movie 2024", "Movie 2099", "Movie.2001", "Movie_1999", "Movie 1999 HD 720p",
        "Movie [1999]", "Movie (None)", "X (None)", "PREDICTION.None", "NL - NL - Spotlight", "4K-AR - AR- The Green Mile",
        "UK: ᴴᴰ Channel ᴿᴬᵂ", "◉ Channel ³⁸⁴⁰ᴾ", "UK:\u2007BBC One", "Film\u202F(2001)", "Movie \u0661\u0669\u0669\u0669",
        "Movie 19\u0669\u0669", "Show \u212A", "Show (Sub)", "EN - 1999", "Movie 1999 (FR)", "SE- Quick",
    )

    /**
     * One value per line, UTF-8. Split on '\n' only: a value may carry a carriage return
     * (genres.txt has "Drama\r"), which `readLines()` would strip.
     */
    fun corpus(name: String): List<String> {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("xtream/corpus/$name")) { "no corpus $name" }
        val lines = stream.use { it.readBytes().toString(Charsets.UTF_8) }.split('\n')
        val values = if (lines.last().isEmpty()) lines.dropLast(1) else lines
        check(values.isNotEmpty()) { "$name is empty" }
        return values
    }

    private fun diff(input: String?, actual: Any?, expected: Any?): String? =
        if (actual == expected) null else "${input?.let { "\"$it\"" }}: got $actual, legacy $expected"

    /** "N differ from legacy; first 20: …", or null when [diffs] is empty. */
    fun report(diffs: List<String>): String? =
        if (diffs.isEmpty()) null else "${diffs.size} differ from legacy; first 20:\n" + diffs.take(20).joinToString("\n")
}
