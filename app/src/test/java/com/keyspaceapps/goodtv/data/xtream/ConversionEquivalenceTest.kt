package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.domain.DisplayTitle
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.legacy.LegacyDisplayTitle
import com.keyspaceapps.goodtv.legacy.legacyGenres
import com.keyspaceapps.goodtv.legacy.legacyTmdbIdOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast conversion gives exactly what the b5b986d code gave, on values captured from a real panel.
 * The oracle is a frozen copy ([com.keyspaceapps.goodtv.legacy]).
 */
class ConversionEquivalenceTest {
    @Test
    fun displayTitleMatchesLegacyOnTheCorpus() {
        val diffs = corpus("live_names.txt").mapNotNull { diff(it, DisplayTitle.parse(it, live = true), LegacyDisplayTitle.parse(it, live = true)) } +
            (corpus("movie_names.txt") + corpus("series_names.txt")).mapNotNull { diff(it, DisplayTitle.parse(it), LegacyDisplayTitle.parse(it)) } +
            EDGE_NAMES.flatMap { name ->
                listOf(true, false).mapNotNull { live -> diff("$name (live=$live)", DisplayTitle.parse(name, live), LegacyDisplayTitle.parse(name, live)) }
            }
        assertNoDiffs(diffs)
    }

    @Test
    fun genresMatchLegacyOnTheCorpus() {
        val inputs = corpus("genres.txt") + listOf(null, "", "  ")
        assertNoDiffs(inputs.mapNotNull { diff(it, Genre.fromPanelText(it), legacyGenres(it)) })
    }

    @Test
    fun tmdbIdsMatchLegacyOnTheCorpus() {
        val inputs = corpus("tmdb.txt") +
            listOf(null, "", " 12 ", "0", "43332-", "tt0111161", "-5", "12abc", "99999999999999999999", "٣٤")
        assertNoDiffs(inputs.mapNotNull { diff(it, tmdbIdOf(it), legacyTmdbIdOf(it)) })
    }

    @Test
    fun genreMemoGivesTheSameSet() {
        val text = "Drama / Sci-Fi & Fantasy"
        val first = Genre.fromPanelText(text)
        val second = Genre.fromPanelText(text)
        assertEquals(first, second)
        assertSame(first, second)
    }

    /**
     * One value per line, UTF-8. Split on '\n' only: a value may carry a carriage return
     * (genres.txt has "Drama\r"), which `readLines()` would strip.
     */
    private fun corpus(name: String): List<String> {
        val lines = javaClass.getResource("/xtream/corpus/$name")!!.readText(Charsets.UTF_8).split('\n')
        val values = if (lines.last().isEmpty()) lines.dropLast(1) else lines
        assertTrue("$name is empty", values.isNotEmpty())
        return values
    }

    private companion object {
        /** Names at the edges of the checks that decide when a regex can be skipped. */
        val EDGE_NAMES = listOf(
            "", " ", "  ", "EN - Movie  1999", "EN -\tMovie 1999", "EN - Movie\u00A01999", "EN - Movie 1999\n.None",
            "EN - Movie 1999\r\n.None", "EN - Movie 1999\u2028.None", "EN - Movie 1999\u0085.None", "EN - Show HD\u0085",
            "Show 4K\u0085", "Show (US)\u0085", "Movie 2024", "Movie 2099", "Movie.2001", "Movie_1999", "Movie 1999 HD 720p",
            "Movie [1999]", "Movie (None)", "X (None)", "PREDICTION.None", "NL - NL - Spotlight", "4K-AR - AR- The Green Mile",
            "UK: ᴴᴰ Channel ᴿᴬᵂ", "◉ Channel ³⁸⁴⁰ᴾ", "UK:\u2007BBC One", "Film\u202F(2001)", "Movie \u0661\u0669\u0669\u0669",
            "Movie 19\u0669\u0669", "Show \u212A", "Show (Sub)", "EN - 1999", "Movie 1999 (FR)", "SE- Quick",
        )
    }

    private fun diff(input: String?, actual: Any?, expected: Any?): String? =
        if (actual == expected) null else "${input?.let { "\"$it\"" }}: got $actual, legacy $expected"

    private fun assertNoDiffs(diffs: List<String>) =
        assertTrue("${diffs.size} differ from legacy; first 20:\n" + diffs.take(20).joinToString("\n"), diffs.isEmpty())
}
