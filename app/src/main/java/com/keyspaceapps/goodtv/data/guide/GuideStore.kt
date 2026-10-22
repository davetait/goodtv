package com.keyspaceapps.goodtv.data.guide

import kotlinx.coroutines.flow.Flow

/** Storage for guide data, channel links and favourite channels; Room in the app, in-memory in tests. */
interface GuideStore {
    /** Swaps [origin]'s channels and programmes for the new ones in one transaction; the other origin is untouched. */
    suspend fun replaceGuide(origin: GuideOrigin, channels: List<GuideChannel>, programmes: Sequence<GuideProgramme>)
    suspend fun replaceLinks(links: List<ChannelGuideLink>)
    fun observeLinks(): Flow<List<ChannelGuideLink>>
    fun observeChannels(): Flow<List<GuideChannel>>
    /** Programmes overlapping [fromMs, untilMs), ordered by guide id then start. */
    fun observeProgrammes(guideIds: Set<String>, fromMs: Long, untilMs: Long): Flow<List<GuideProgramme>>
    /** Programmes whose title contains [query] and that end after [fromMs], soonest first. */
    suspend fun searchProgrammes(query: String, fromMs: Long, limit: Int): List<GuideProgramme>
    suspend fun fetchedAt(origin: GuideOrigin): Long?
    suspend fun markFetched(origin: GuideOrigin, atMs: Long)
    /** Row keys of the favourite channels. */
    fun observeFavourites(): Flow<Set<String>>
    suspend fun setFavourite(rowKey: String, favourite: Boolean, atMs: Long)
}
