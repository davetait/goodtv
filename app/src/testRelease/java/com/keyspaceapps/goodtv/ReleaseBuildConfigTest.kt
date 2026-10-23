package com.keyspaceapps.goodtv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Release builds must never carry the developer's keys; users enter their own in Settings. */
class ReleaseBuildConfigTest {
    @Test
    fun releaseBuildHasNoKeys() {
        assertEquals("", BuildConfig.TMDB_API_KEY)
        assertEquals("", BuildConfig.OPENSUBTITLES_API_KEY)
        assertEquals("", BuildConfig.OPENSUBTITLES_USERNAME)
        assertEquals("", BuildConfig.OPENSUBTITLES_PASSWORD)
        // Control: the Xtream fields were already blanked in release.
        assertEquals("", BuildConfig.XTREAM_SERVER)
    }
}
