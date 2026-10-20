package com.keyspaceapps.goodtv.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inputs are real catalog names from a live panel. */
class TitleCleanerTest {
    private fun check(raw: String, title: String, year: Int?) {
        val c = TitleCleaner.clean(raw)
        assertEquals("title of '$raw'", title, c.title)
        assertEquals("year of '$raw'", year, c.year)
        assertTrue("'$raw' should be searchable", c.searchable)
    }

    @Test fun languageAndQualityPrefixes() {
        check("4K-AR - The Matrix (1999)", "The Matrix", 1999)
        check("4K-DE - Matrix  (1999)", "Matrix", 1999)
        check("EN - The Gorge  (2025)", "The Gorge", 2025)
        check("AR-SUBS - Broadchurch (2013) (GB)", "Broadchurch", 2013)
        check("FIFA+ - Under Pressure (2023)", "Under Pressure", 2023)
        check("4K-PL - Pogromcy duchów: Imperium lodu (2024)", "Pogromcy duchów: Imperium lodu", 2024)
        check("UK: Peaky Blinders", "Peaky Blinders", null)
        check("[MULTI] Dune (2021)", "Dune", 2021)
    }

    @Test fun translationAfterYearIsDropped() {
        check("IR - Haris (1972) حریص", "Haris", 1972)
        check("IR - The Lost Chapter: Yuki's Revenge (2025) فصل گمشده: انتقام یوکی", "The Lost Chapter: Yuki's Revenge", 2025)
    }

    @Test fun dottedNamesAndTrailingTags() {
        check("AF-FR - Shenanigans.2018", "Shenanigans", 2018)
        check("PK - Gustakh (2020) (PK)", "Gustakh", 2020)
        check("NL - Black Knight HD", "Black Knight", null)
    }

    @Test fun colonTitlesKeepTheirFirstWord() {
        check("FBI: Most Wanted (2020)", "FBI: Most Wanted", 2020)
        check("EN - CSI: Miami", "CSI: Miami", null)
    }

    @Test fun nonLatinTitlesStaySearchable() {
        check("AR - فيلم زيارة سرية", "زيارة سرية", null)
        check("AR-EG - مسلسل ابن صابرة", "ابن صابرة", null)
    }

    @Test fun datedSportsEventsAreNotSearchable() {
        assertFalse(TitleCleaner.clean("SOC - Fulham - Arsenal 08.12.2024").searchable)
        assertFalse(TitleCleaner.clean("SOC - Pafos FC v Bayern Munich 30.09.25").searchable)
    }

    @Test fun normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("pokemon the movie", TitleCleaner.normalize("Pokémon: The Movie!"))
        assertEquals(TitleCleaner.normalize("Ghost in the Shell"), TitleCleaner.normalize("GHOST IN THE SHELL"))
        assertEquals(TitleCleaner.normalize("My Grandfathers Wife"), TitleCleaner.normalize("My Grandfather’s Wife"))
    }
}
