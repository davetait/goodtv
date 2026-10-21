package com.keyspaceapps.goodtv.ui.watchlist

import com.keyspaceapps.goodtv.data.watchlist.WatchListKind
import com.keyspaceapps.goodtv.domain.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchListKindTest {
    @Test fun moviesAndSeriesCanBeSavedChannelsCannot() {
        assertEquals(WatchListKind.MOVIE, ContentKind.MOVIE.watchListKind())
        assertEquals(WatchListKind.SERIES, ContentKind.SERIES.watchListKind())
        assertNull(ContentKind.LIVE.watchListKind())
    }
}
