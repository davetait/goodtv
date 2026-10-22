package com.keyspaceapps.goodtv.data.guide

import android.util.Log
import com.keyspaceapps.goodtv.data.repo.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Keeps the saved lineup current: whenever the library generation moves past the saved one, the rules
 * version or the app install ([appVersion], so every install and update rebuilds once even if a rules change
 * forgot its bump) changes or nothing is saved, it runs [LineupBuilder] on [compute] and saves the rows stamped
 * with the generation read before building. A sync's burst of writes settles for [debounceMs] first, so it
 * builds once; a write during a build is caught by the next check, which builds again. A failed check
 * (build or store) keeps the old lineup and waits for the next generation; a failing generation query is
 * retried after [RETRY_MS]; a cancelled build saves nothing.
 */
class LineupKeeper(
    private val library: LibraryStore,
    private val lineups: LineupStore,
    private val compute: CoroutineDispatcher,
    private val debounceMs: Long = 2_000,
    private val warn: (String) -> Unit = { Log.w("GoodTV", it) },
    private val debug: (String) -> Unit = { Log.d("GoodTvGuide", it) },
    /** The install's PackageInfo.lastUpdateTime (AppGraph reads it; tests pass their own). */
    private val appVersion: Long,
) {
    /** Call once per process. The first generation is checked at once, later ones after [debounceMs] without a newer one. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope): Job = scope.launch(compute) {
        var first = true
        lineups.observeGeneration()
            // A failing generation query (a closed or locked database) is warned about and subscribed again shortly.
            .retryWhen { e, _ ->
                if (e is CancellationException) return@retryWhen false
                warn("Lineup generation failed: ${e.javaClass.simpleName}")
                delay(RETRY_MS)
                true
            }
            .distinctUntilChanged()
            .debounce { if (first) { first = false; 0L } else debounceMs }
            // A write during a build leaves its generation here; the next check sees the saved one is behind and builds again.
            .conflate()
            .collect { check() }
    }

    /** Any failure (a store error, a full disk, even running out of memory on a big lineup) keeps the old lineup until the next generation. */
    private suspend fun check() {
        try {
            keepCurrent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            warn("Lineup build failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun keepCurrent() {
        // The stamp alone: a current lineup is never read just to be compared.
        val stamp = lineups.savedStamp()
        val generation = lineups.generation()
        if (stamp == LineupStamp(generation, LineupBuilder.RULES_VERSION, appVersion)) return
        val rows = LineupBuilder.build(library.observeSportsCandidates().first(), debug)
        currentCoroutineContext().ensureActive()
        lineups.save(SavedLineup(rows, generation, LineupBuilder.RULES_VERSION, appVersion))
    }

    private companion object {
        const val RETRY_MS = 5_000L
    }
}
