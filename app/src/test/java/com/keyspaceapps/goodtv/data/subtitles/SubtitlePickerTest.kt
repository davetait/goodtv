package com.keyspaceapps.goodtv.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitlePickerTest {
    private fun sub(id: Long, release: String, hi: Boolean = false, trusted: Boolean = false, downloads: Int = 0, ai: Boolean = false, mt: Boolean = false, file: String? = null) =
        OsSubtitle(id, release, file, "en", hi, trusted, ai, mt, downloads)
    private val episode = SubtitleQuery(136315, 1, 2)
    private val movie = SubtitleQuery(603)

    @Test fun dropsAiAndMachineTranslations() {
        assertEquals(3L, SubtitlePicker.best(listOf(sub(1, "a", ai = true, downloads = 900), sub(2, "b", mt = true, downloads = 800), sub(3, "c", downloads = 1)), movie)?.fileId)
    }

    @Test fun dropsEpisodeResultsNamedForAnotherEpisode() {
        val results = listOf(
            sub(1, "The.Bear.SE02E02.WEBRip-DNSP", downloads = 9000),
            sub(2, "BEAR 02 - ENG", downloads = 10),
            sub(3, "The.Bear.S01E02.1080p", downloads = 5),
            sub(4, "x", file = "The.Bear.S03E02.srt", downloads = 8000),
        )
        assertEquals("no code: kept, and most downloaded of the rest", 2L, SubtitlePicker.best(results, episode)?.fileId)
        assertEquals(listOf(2L, 3L), results.filter { SubtitlePicker.matchesEpisode(it, episode) }.map { it.fileId })
    }

    @Test fun prefersNotHearingImpairedThenTrustedThenMostDownloaded() {
        val results = listOf(sub(1, "hi", hi = true, trusted = true, downloads = 9999), sub(2, "plain", downloads = 50), sub(3, "trusted", trusted = true, downloads = 10))
        assertEquals(3L, SubtitlePicker.best(results, movie)?.fileId)
        assertEquals(2L, SubtitlePicker.best(results.filter { it.fileId != 3L }, movie)?.fileId)
    }

    @Test fun nothingUsableIsNull() {
        assertNull(SubtitlePicker.best(emptyList(), movie))
        assertNull(SubtitlePicker.best(listOf(sub(1, "a", ai = true)), movie))
    }
}
