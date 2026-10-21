package com.keyspaceapps.goodtv.ui.nav

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.subtitles.SubtitleQuery
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleQueriesTest {
    private val store = FakeLibraryStore()
    private val registry = SourceRegistry(listOf(FakeChannelSource()))
    private val repo = LibraryRepository(store, registry, SyncManager(store, registry, CoroutineScope(StandardTestDispatcher())), AppSettings(InMemoryDataStore()))

    private suspend fun seed(): Long {
        val sid = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
        store.replaceCatalog(
            sid,
            Catalog(
                emptyList(), emptyList(),
                listOf(Movie(sid, "m1", null, "The Matrix", null, "mkv", null, null, tmdbId = 603), Movie(sid, "m2", null, "Home video", null, "mkv", null, null)),
                listOf(Series(sid, "s1", null, "The Bear", null, null, null, tmdbId = 136315)),
            ),
        )
        return sid
    }

    @Test fun movieUsesItsTmdbId() = runTest {
        val sid = seed()
        assertEquals(SubtitleQuery(603), SubtitleQueries.forKey(PlayerKey(sid, PlayerKey.KIND_MOVIE, "m1", "mkv", "The Matrix"), repo))
    }

    @Test fun episodeUsesTheSeriesIdSeasonAndNumber() = runTest {
        val sid = seed()
        val key = PlayerKey(sid, PlayerKey.KIND_EPISODE, "e2", "mkv", "The Bear · S1E2", seriesId = "s1", season = 1, episode = 2)
        assertEquals(SubtitleQuery(136315, 1, 2), SubtitleQueries.forKey(key, repo))
    }

    @Test fun liveOrMissingIdsGiveNothing() = runTest {
        val sid = seed()
        assertNull(SubtitleQueries.forKey(PlayerKey(sid, PlayerKey.KIND_LIVE, "l1", null, "News"), repo))
        assertNull("no TMDB id", SubtitleQueries.forKey(PlayerKey(sid, PlayerKey.KIND_MOVIE, "m2", "mkv", "Home video"), repo))
        assertNull("no season", SubtitleQueries.forKey(PlayerKey(sid, PlayerKey.KIND_EPISODE, "e2", "mkv", "x", seriesId = "s1", season = null, episode = 2), repo))
    }
}
