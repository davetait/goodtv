package com.keyspaceapps.goodtv.data.guide

import android.util.Log
import com.keyspaceapps.goodtv.data.repo.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the saved lineup current: whenever the library generation moves past the saved one, the rules
 * version changes or nothing is saved, it runs [LineupBuilder] on [compute] and saves the rows stamped with
 * the generation read before building. A sync's burst of writes settles for [debounceMs] first, so it
 * builds once; a write during a build is caught by the next check, which builds again. A failed build
 * keeps the old lineup and waits for the next generation; a cancelled one saves nothing.
 */
class LineupKeeper(
    private val library: LibraryStore,
    private val lineups: LineupStore,
    private val compute: CoroutineDispatcher,
    private val debounceMs: Long = 2_000,
    private val warn: (String) -> Unit = { Log.w("GoodTV", it) },
    private val debug: (String) -> Unit = { Log.d("GoodTvGuide", it) },
) {
    /** Call once per process. The first generation is checked at once, later ones after [debounceMs] without a newer one. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope): Job = scope.launch(compute) {
        var first = true
        lineups.observeGeneration()
            .distinctUntilChanged()
            .debounce { if (first) { first = false; 0L } else debounceMs }
            // A write during a build leaves its generation here; the next check sees the saved one is behind and builds again.
            .conflate()
            .collect { keepCurrent() }
    }

    private suspend fun keepCurrent() {
        val saved = lineups.saved()
        val generation = lineups.generation()
        if (saved != null && saved.generation == generation && saved.rulesVersion == LineupBuilder.RULES_VERSION) return
        val rows = try {
            LineupBuilder.build(library.observeSportsCandidates().first(), debug)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("Lineup build failed: ${e.javaClass.simpleName}")
            return
        }
        currentCoroutineContext().ensureActive()
        lineups.save(SavedLineup(rows, generation, LineupBuilder.RULES_VERSION))
    }
}
