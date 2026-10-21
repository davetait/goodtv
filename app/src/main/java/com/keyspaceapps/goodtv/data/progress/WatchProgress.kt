package com.keyspaceapps.goodtv.data.progress

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class WatchKind { MOVIE, EPISODE }

/** A loaded value, so "not loaded yet" (null) differs from "loaded, nothing there" (Loaded(null)). */
data class Loaded<T>(val value: T)

/** Which title a position belongs to. [seriesId] is set for episodes. */
data class WatchRef(val sourceId: Long, val kind: WatchKind, val itemId: String, val seriesId: String? = null)

/**
 * What's needed to start playback again without the detail page (episode lists are dropped on every
 * re-sync): the player title, container extension and, for episodes, season and number.
 */
data class PlaybackInfo(val title: String, val ext: String?, val season: Int? = null, val episode: Int? = null)

/** Where the user stopped. [durationMs] is 0 when the stream never reported one. */
data class WatchProgress(
    val ref: WatchRef,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
    val info: PlaybackInfo? = null,
) {
    /** Worth offering "Resume from …": started properly and not at the credits. */
    val resumable: Boolean get() = !finished && positionMs >= WatchRules.MIN_RESUME_MS
}

object WatchRules {
    /** Below this the user didn't really start (or started over and left); nothing worth resuming. */
    const val MIN_RESUME_MS = 30_000L
    /** Within this much of the end counts as watched (credits)... */
    const val END_SLACK_MS = 120_000L
    /** ...or within this fraction of the end, for long films with long credits. */
    const val END_FRACTION = 0.05

    fun isFinished(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val remaining = durationMs - positionMs
        return remaining <= maxOf(END_SLACK_MS, (durationMs * END_FRACTION).toLong())
    }
}

/**
 * One Continue Watching card: a part-watched movie, or a series with its most recent episode
 * [progress] (finished = "up next"). [title]/[imageUrl]/[tmdbId] come from the synced catalog row.
 */
data class ContinueItem(
    val progress: WatchProgress,
    val title: String,
    val imageUrl: String?,
    val tmdbId: Long?,
    val lookupName: String,
) {
    val kind: WatchKind get() = progress.ref.kind
    /** Movie id, or series id for episodes. */
    val itemId: String get() = progress.ref.seriesId ?: progress.ref.itemId
    val key: String get() = "${kind.name}:${progress.ref.sourceId}:$itemId"
}

/** Storage for [WatchProgressRepository]; Room in the app, in-memory in tests. */
interface WatchProgressStore {
    fun observe(ref: WatchRef): Flow<WatchProgress?>
    fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgress>>
    suspend fun upsert(progress: WatchProgress)
    suspend fun delete(ref: WatchRef)
    suspend fun deleteSeries(sourceId: Long, seriesId: String)
    /** Unfinished movies of enabled sources still in the catalog, newest first. */
    fun observeContinueMovies(limit: Int): Flow<List<ContinueItem>>
    /** Per series of enabled sources still in the catalog, its most recently watched episode, newest first. */
    fun observeContinueSeries(limit: Int): Flow<List<ContinueItem>>
}

/**
 * Remembers how far into a movie or episode the user got. The player reports positions as it plays;
 * detail pages read them to offer Resume. A report under [WatchRules.MIN_RESUME_MS] clears the
 * saved spot, so "Start over" followed by leaving straight away forgets the old position.
 * [onMovieFinished] hears of each movie watched to the credits (the watch list drops it).
 */
class WatchProgressRepository(
    private val store: WatchProgressStore,
    private val onMovieFinished: suspend (WatchRef) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun record(ref: WatchRef, positionMs: Long, durationMs: Long, info: PlaybackInfo? = null) {
        val duration = durationMs.coerceAtLeast(0)
        val finished = WatchRules.isFinished(positionMs, duration)
        if (positionMs < WatchRules.MIN_RESUME_MS && !finished) store.delete(ref)
        else store.upsert(WatchProgress(ref, positionMs.coerceAtLeast(0), duration, finished, clock(), info))
        if (finished && ref.kind == WatchKind.MOVIE) onMovieFinished(ref)
    }

    /** Home's Continue Watching: movies and series merged, newest first, at most [limit]. */
    fun observeContinueWatching(limit: Int = CONTINUE_LIMIT): Flow<List<ContinueItem>> =
        combine(store.observeContinueMovies(limit), store.observeContinueSeries(limit)) { movies, series ->
            (movies + series).sortedByDescending { it.progress.updatedAt }.take(limit)
        }

    /** Takes [item] off Continue Watching by forgetting its progress: the movie, or every episode of the series. */
    suspend fun remove(item: ContinueItem) {
        val ref = item.progress.ref
        when (ref.kind) {
            WatchKind.MOVIE -> store.delete(ref)
            WatchKind.EPISODE -> store.deleteSeries(ref.sourceId, ref.seriesId ?: return)
        }
    }

    companion object {
        const val CONTINUE_LIMIT = 30
    }

    fun observe(ref: WatchRef): Flow<WatchProgress?> = store.observe(ref)

    /** Progress of every episode of one series, newest first. */
    fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgress>> = store.observeSeries(sourceId, seriesId)
}
