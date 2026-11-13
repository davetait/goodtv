package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.domain.Genre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The fast conversion gives exactly what the b5b986d code gave ([ConversionEquivalence]), on the JVM. */
class ConversionEquivalenceTest {
    @Test
    fun displayTitleMatchesLegacyOnTheCorpus() = assertNull(ConversionEquivalence.report(ConversionEquivalence.titleDiffs()))

    @Test
    fun genresMatchLegacyOnTheCorpus() = assertNull(ConversionEquivalence.report(ConversionEquivalence.genreDiffs()))

    @Test
    fun tmdbIdsMatchLegacyOnTheCorpus() =
        assertNull(ConversionEquivalence.report(ConversionEquivalence.tmdbDiffs(includeNonAsciiDigits = false)))

    /** The JDK oracle reads only ASCII digits, so these are checked against what Android's regex gives. */
    @Test
    fun tmdbIdsReadNonAsciiDigitsAsAndroidDoes() {
        for ((raw, id) in ConversionEquivalence.NON_ASCII_DIGIT_TMDB) assertEquals(raw, id, tmdbIdOf(raw))
    }

    @Test
    fun genreMemoGivesTheSameSet() {
        val text = "Drama / Sci-Fi & Fantasy"
        val first = Genre.fromPanelText(text)
        val second = Genre.fromPanelText(text)
        assertEquals(first, second)
        assertSame(first, second)
    }
}
