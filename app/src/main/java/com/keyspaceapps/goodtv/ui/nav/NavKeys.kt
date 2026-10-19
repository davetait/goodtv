package com.keyspaceapps.goodtv.ui.nav

import androidx.navigation3.runtime.NavKey
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.PlayableRef
import kotlinx.serialization.Serializable

@Serializable
sealed interface GoodTvKey : NavKey

@Serializable
data object HomeKey : GoodTvKey

@Serializable
data class PlayerKey(val sourceId: Long, val refKind: String, val refId: String, val ext: String?, val title: String) : GoodTvKey {
    fun toRef(): PlayableRef = when (refKind) {
        KIND_MOVIE -> PlayableRef.Movie(refId, ext ?: "mp4")
        KIND_EPISODE -> PlayableRef.Episode(refId, ext ?: "mp4")
        else -> PlayableRef.Live(refId)
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

@Serializable
data class CategoryGridKey(val sourceId: Long, val kind: ContentKind, val categoryId: String, val title: String) : GoodTvKey

@Serializable
data class EditSourceKey(val sourceId: Long?) : GoodTvKey

enum class HomeTab { LIVE, MOVIES, SERIES, SOURCES }
