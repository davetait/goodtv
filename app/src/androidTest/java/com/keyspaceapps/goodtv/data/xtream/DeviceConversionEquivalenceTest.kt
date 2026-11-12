package com.keyspaceapps.goodtv.data.xtream

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ConversionEquivalence] on the device, where both the new code and the frozen oracle run Android's ICU
 * regex (Unicode \s and \d), the engine that ships. Here the oracle also judges non-ASCII digits.
 */
@RunWith(AndroidJUnit4::class)
class DeviceConversionEquivalenceTest {
    @Test
    fun displayTitleMatchesLegacyOnTheCorpus() = assertNull(ConversionEquivalence.report(ConversionEquivalence.titleDiffs()))

    @Test
    fun genresMatchLegacyOnTheCorpus() = assertNull(ConversionEquivalence.report(ConversionEquivalence.genreDiffs()))

    @Test
    fun tmdbIdsMatchLegacyOnTheCorpus() =
        assertNull(ConversionEquivalence.report(ConversionEquivalence.tmdbDiffs(includeNonAsciiDigits = true)))

    @Test
    fun tmdbIdsReadNonAsciiDigits() {
        for ((raw, id) in ConversionEquivalence.NON_ASCII_DIGIT_TMDB) assertEquals(raw, id, tmdbIdOf(raw))
    }
}
