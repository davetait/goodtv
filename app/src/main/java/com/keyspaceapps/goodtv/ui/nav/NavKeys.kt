package com.keyspaceapps.goodtv.ui.nav

import androidx.navigation3.runtime.NavKey
import com.keyspaceapps.goodtv.data.progress.PlaybackInfo
import com.keyspaceapps.goodtv.data.progress.WatchKind
import com.keyspaceapps.goodtv.data.progress.WatchRef
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.PlayableRef
import kotlinx.serialization.Serializable

@Serializable
sealed interface GoodTvKey : NavKey

@Serializable
data object HomeKey : GoodTvKey

@Serializable
/** [startPositionMs] resumes on-demand playback there; [seriesId] ties an episode's progress to its series. */
data class PlayerKey(
    val sourceId: Long,
    val refKind: String,
    val refId: String,
    val ext: String?,
    val title: String,
    val startPositionMs: Long = 0,
    val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) : GoodTvKey {
    fun toRef(): PlayableRef = when (refKind) {
        KIND_MOVIE -> PlayableRef.Movie(refId, ext ?: "mp4")
        KIND_EPISODE -> PlayableRef.Episode(refId, ext ?: "mp4")
        else -> PlayableRef.Live(refId)
    }

    /** Saved with progress so Continue Watching can start playback again directly. */
    fun playbackInfo(): PlaybackInfo = PlaybackInfo(title, ext, season, episode)

    /** What progress is recorded against; null for live channels. */
    fun watchRef(): WatchRef? = when (refKind) {
        KIND_MOVIE -> WatchRef(sourceId, WatchKind.MOVIE, refId)
        KIND_EPISODE -> WatchRef(sourceId, WatchKind.EPISODE, refId, seriesId)
        else -> null
    }

    companion object {
        const val KIND_LIVE = "live"
        const val KIND_MOVIE = "movie"
        const val KIND_EPISODE = "episode"
    }
}

@Serializable
data class MovieDetailKey(val sourceId: Long, val movieId: String) : GoodTvKey

@Serializable
data class SeriesDetailKey(val sourceId: Long, val seriesId: String) : GoodTvKey

/** The library titles one TMDB person is in; [name] titles the page before anything loads. */
@Serializable
data class PersonSearchKey(val personId: Long, val name: String) : GoodTvKey

@Serializable
data class CategoryGridKey(val sourceId: Long, val kind: ContentKind, val categoryId: String, val title: String) : GoodTvKey

@Serializable
data class EditSourceKey(val sourceId: Long?) : GoodTvKey

@Serializable
data class CategoriesKey(val sourceId: Long) : GoodTvKey

/** The sources list, opened from Settings. */
@Serializable
data object SourcesKey : GoodTvKey

/** TMDB and OpenSubtitles keys, opened from Settings. */
@Serializable
data object IntegrationsKey : GoodTvKey

enum class HomeTab { HOME, LIVE, SPORTS, MOVIES, SERIES, SETTINGS }
