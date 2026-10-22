package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.guide.LineupStamp
import com.keyspaceapps.goodtv.data.guide.LineupStore
import com.keyspaceapps.goodtv.data.guide.SavedLineup
import com.keyspaceapps.goodtv.data.guide.Visibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryLineupStore(visibility: Visibility = Visibility(emptySet(), emptySet())) : LineupStore {
    private val saved = MutableStateFlow<SavedLineup?>(null)
    private val generation = MutableStateFlow(0L)
    private val visibility = MutableStateFlow(visibility)

    override suspend fun saved(): SavedLineup? = saved.value
    override fun observeSaved(): Flow<SavedLineup?> = saved
    override suspend fun savedStamp(): LineupStamp? = saved.value?.let { LineupStamp(it.generation, it.rulesVersion, it.appVersion) }
    override suspend fun save(lineup: SavedLineup) { saved.value = lineup }
    override suspend fun generation(): Long = generation.value
    override fun observeGeneration(): Flow<Long> = generation
    override fun observeVisibility(): Flow<Visibility> = visibility

    /** Simulates a library write. */
    fun bumpGeneration() = generation.update { it + 1 }

    fun setVisibility(value: Visibility) { visibility.value = value }
}
