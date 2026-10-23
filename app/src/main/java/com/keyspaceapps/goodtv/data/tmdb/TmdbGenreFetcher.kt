package com.keyspaceapps.goodtv.data.tmdb

import com.keyspaceapps.goodtv.domain.Genre
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where [TmdbGenreFetcher] reads its work and writes results; Room in the app. */
interface TmdbGenreStore {
    /** Distinct TMDB ids of enabled movies whose genres were never fetched, or not since [before]. */
    suspend fun pendingMovieTmdbIds(before: Long): List<Long>
    suspend fun movieTmdbIdCount(): Int
    /** Replaces the genres of each id (empty set = TMDB has none / unknown id) and records [fetchedAt]. */
    suspend fun saveMovieGenres(genres: Map<Long, Set<Genre>>, fetchedAt: Long)
}

/**
 * Fills movie genres from TMDB in the background: one details call per distinct TMDB id (the
 * provider's movie list carries no genres). Polite and resumable: at most one request per
 * [minIntervalMs] across [workers], results saved every [batchSize] ids, so a restart continues
 * where it stopped. Each call also warms the rating cache. Genres are re-fetched after TMDB's
 * 6-month cache limit.
 *
 * A rejected key stops the run ([State.Stopped]); network or server failures end it quietly and
 * the next [start] (next app launch) carries on.
 */
class TmdbGenreFetcher(
    private val api: TmdbApi?,
    private val store: TmdbGenreStore,
    private val metaCache: TmdbCache,
    private val scope: CoroutineScope,
    private val language: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 50,
    private val workers: Int = 4,
    private val batchSize: Int = 100,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    sealed interface State {
        data object Idle : State
        /** [done] of [total] distinct TMDB ids have genres. */
        data class Running(val done: Int, val total: Int) : State
        data class Stopped(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val lock = Any()
    private var job: Job? = null

    /** Starts a run unless one is going; safe to call on every launch and after every sync. */
    fun start(): Job = synchronized(lock) {
        job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }
    }

    private class Halt(val reason: String?) : Exception()

    private suspend fun run() {
        val api = api?.takeIf { it.isConfigured } ?: return
        val pending = store.pendingMovieTmdbIds(before = clock() - TmdbRepository.MAX_CACHE_AGE_MS)
        if (pending.isEmpty()) { _state.value = State.Idle; return }
        val total = store.movieTmdbIdCount().coerceAtLeast(pending.size)
        var done = total - pending.size
        _state.value = State.Running(done, total)

        val lang = language()
        val queue = Channel<Long>(Channel.UNLIMITED).apply { pending.forEach { trySend(it) }; close() }
        val buffer = HashMap<Long, Set<Genre>>()
        val bufferLock = Mutex()
        val flushLock = Mutex() // workers flush concurrently; one at a time keeps [done] honest
        val pace = Mutex()
        var nextAt = 0L

        suspend fun flush() = flushLock.withLock {
            val batch = bufferLock.withLock { HashMap(buffer).also { buffer.clear() } }
            if (batch.isNotEmpty()) {
                store.saveMovieGenres(batch, clock())
                done += batch.size
                _state.value = State.Running(done, total)
            }
        }

        try {
            coroutineScope {
                repeat(workers) {
                    launch {
                        for (id in queue) {
                            val wait = pace.withLock { val now = clock(); (nextAt - now).also { nextAt = maxOf(now, nextAt) + minIntervalMs } }
                            if (wait > 0) sleep(wait)
                            val genres = fetch(api, id, lang) ?: continue
                            val full = bufferLock.withLock { buffer[id] = genres; buffer.size >= batchSize }
                            if (full) flush()
                        }
                    }
                }
            }
            flush()
            _state.value = State.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (h: Halt) {
            runCatching { flush() }
            _state.value = h.reason?.let { State.Stopped(it) } ?: State.Idle
        }
    }

    /** Genres for [id], or null to skip it this run (rate limited twice). Throws [Halt] to end the run. */
    private suspend fun fetch(api: TmdbApi, id: Long, lang: String): Set<Genre>? {
        repeat(2) { attempt ->
            try {
                val details = api.movie(id, lang) ?: return emptySet()
                metaCache.putMeta(movieMeta(details, lang, clock()))
                return details.genres.mapNotNull { Genre.fromTmdbMovieGenre(it.id) }.toSet()
            } catch (e: TmdbException) {
                when (e.reason) {
                    TmdbException.Reason.AUTH -> throw Halt(e.message)
                    TmdbException.Reason.RATE_LIMITED -> if (attempt == 0) sleep(RATE_LIMIT_PAUSE_MS)
                    TmdbException.Reason.BAD_RESPONSE -> return null
                    TmdbException.Reason.NETWORK, TmdbException.Reason.SERVER -> throw Halt(null)
                }
            }
        }
        return null
    }

    companion object {
        const val RATE_LIMIT_PAUSE_MS = 30_000L
    }
}
