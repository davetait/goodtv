package com.keyspaceapps.goodtv.data.progress

import kotlinx.coroutines.flow.Flow

enum class WatchKind { MOVIE, EPISODE }

/** A loaded value, so "not loaded yet" (null) differs from "loaded, nothing there" (Loaded(null)). */
data class Loaded<T>(val value: T)

/** Which title a position belongs to. [seriesId] is set for episodes. */
data class WatchRef(val sourceId: Long, val kind: WatchKind, val itemId: String, val seriesId: String? = null)

/** Where the user stopped. [durationMs] is 0 when the stream never reported one. */
data class WatchProgress(
    val ref: WatchRef,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
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

/** Storage for [WatchProgressRepository]; Room in the app, in-memory in tests. */
interface WatchProgressStore {
    fun observe(ref: WatchRef): Flow<WatchProgress?>
    fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgress>>
    suspend fun upsert(progress: WatchProgress)
    suspend fun delete(ref: WatchRef)
}

/**
 * Remembers how far into a movie or episode the user got. The player reports positions as it plays;
 * detail pages read them to offer Resume. A report under [WatchRules.MIN_RESUME_MS] clears the
 * saved spot, so "Start over" followed by leaving straight away forgets the old position.
 */
class WatchProgressRepository(private val store: WatchProgressStore, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun record(ref: WatchRef, positionMs: Long, durationMs: Long) {
        val duration = durationMs.coerceAtLeast(0)
        val finished = WatchRules.isFinished(positionMs, duration)
        if (positionMs < WatchRules.MIN_RESUME_MS && !finished) store.delete(ref)
        else store.upsert(WatchProgress(ref, positionMs.coerceAtLeast(0), duration, finished, clock()))
    }

    fun observe(ref: WatchRef): Flow<WatchProgress?> = store.observe(ref)

    /** Progress of every episode of one series, newest first. */
    fun observeSeries(sourceId: Long, seriesId: String): Flow<List<WatchProgress>> = store.observeSeries(sourceId, seriesId)
}
