package com.keyspaceapps.goodtv.testing

import com.keyspaceapps.goodtv.data.guide.ChannelGuideLink
import com.keyspaceapps.goodtv.data.guide.GuideChannel
import com.keyspaceapps.goodtv.data.guide.GuideOrigin
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.guide.GuideStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryGuideStore : GuideStore {
    private val channels = MutableStateFlow<List<GuideChannel>>(emptyList())
    private val programmes = MutableStateFlow<List<GuideProgramme>>(emptyList())
    private val links = MutableStateFlow<List<ChannelGuideLink>>(emptyList())
    private val fetched = MutableStateFlow<Map<GuideOrigin, Long>>(emptyMap())
    private val favourites = MutableStateFlow<Set<String>>(emptySet())

    override suspend fun replaceGuide(origin: GuideOrigin, channels: List<GuideChannel>, programmes: Sequence<GuideProgramme>) {
        val gone = this.channels.value.filter { it.origin == origin }.map { it.guideId }.toSet()
        this.programmes.update { old -> old.filterNot { it.guideId in gone } + programmes.toList() }
        this.channels.update { old -> old.filterNot { it.origin == origin } + channels }
    }

    override suspend fun replaceLinks(links: List<ChannelGuideLink>) { this.links.value = links }

    override fun observeLinks(): Flow<List<ChannelGuideLink>> = links

    override suspend fun linkedSourceIds(): Set<Long> = links.value.mapTo(HashSet()) { it.sourceId }

    override fun observeChannels(): Flow<List<GuideChannel>> = channels

    override fun observeProgrammes(guideIds: Set<String>, fromMs: Long, untilMs: Long): Flow<List<GuideProgramme>> =
        programmes.map { all ->
            all.filter { it.guideId in guideIds && it.startMs < untilMs && it.endMs > fromMs }.sortedWith(compareBy({ it.guideId }, { it.startMs }))
        }

    override suspend fun searchProgrammes(query: String, fromMs: Long, limit: Int): List<GuideProgramme> =
        programmes.value.filter { it.endMs > fromMs && it.title.contains(query, ignoreCase = true) }.sortedBy { it.startMs }.take(limit)

    override suspend fun fetchedAt(origin: GuideOrigin): Long? = fetched.value[origin]

    override suspend fun markFetched(origin: GuideOrigin, atMs: Long) = fetched.update { it + (origin to atMs) }

    override fun observeFavourites(): Flow<Set<String>> = favourites

    override suspend fun setFavourite(rowKey: String, favourite: Boolean, atMs: Long) =
        favourites.update { if (favourite) it + rowKey else it - rowKey }
}
