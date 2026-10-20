package com.keyspaceapps.goodtv.ui.player

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLabelsTest {
    private fun label(info: TrackInfo) = TrackLabels.label(info, fallback = "Track 1", display = Locale.ENGLISH)

    @Test fun languageNamesComeFirstInTheViewersLanguage() {
        assertEquals("English", label(TrackInfo("en", null)))
        assertEquals("German", label(TrackInfo("deu", null)))
        assertEquals("MKV's older codes too", "French", label(TrackInfo("fre", null)))
        assertEquals("Portuguese", label(TrackInfo("pt-BR", null)))
        assertEquals("Allemand", TrackLabels.label(TrackInfo("de", null), "Piste 1", Locale.FRENCH))
    }

    @Test fun theStreamsLabelIsAddedOnlyWhenItSaysSomethingNew() {
        assertEquals("English", label(TrackInfo("en", "English")))
        assertEquals("English · SDH", label(TrackInfo("en", "SDH")))
        assertEquals("Commentary", label(TrackInfo(null, "Commentary")))
    }

    @Test fun audioLayoutAndForcedAreNamed() {
        assertEquals("French · 5.1", label(TrackInfo("fr", null, channels = 6)))
        assertEquals("English · Stereo", label(TrackInfo("en", null, channels = 2)))
        assertEquals("English · Forced", label(TrackInfo("en", null, forced = true)))
    }

    @Test fun unknownLanguagesFallBackToATrackNumber() {
        assertEquals("Track 1", label(TrackInfo(null, null)))
        assertEquals("Track 1", label(TrackInfo("und", " ")))
    }

    @Test fun repeatedLabelsAreNumbered() {
        assertEquals(listOf("English", "English (2)", "German", "English (3)"), TrackLabels.disambiguate(listOf("English", "English", "German", "English")))
    }
}
