package com.keyspaceapps.goodtv.data.db

import androidx.room.withTransaction
import com.keyspaceapps.goodtv.data.guide.ChannelGuideLink
import com.keyspaceapps.goodtv.data.guide.GuideChannel
import com.keyspaceapps.goodtv.data.guide.GuideOrigin
import com.keyspaceapps.goodtv.data.guide.GuideProgramme
import com.keyspaceapps.goodtv.data.guide.GuideStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val BATCH = 500
private const val GENRE_SEPARATOR = "|"

class RoomGuideStore(private val db: GoodTvDatabase) : GuideStore {
    private val dao = db.guide()

    override suspend fun replaceGuide(origin: GuideOrigin, channels: List<GuideChannel>, programmes: Sequence<GuideProgramme>) = db.withTransaction {
        dao.deleteChannels(origin.name) // programmes cascade
        channels.chunked(BATCH).forEach { chunk -> dao.insertChannels(chunk.map { GuideChannelEntity(it.guideId, it.origin.name, it.logoUrl) }) }
        programmes.chunked(BATCH).forEach { chunk -> dao.insertProgrammes(chunk.map { it.toEntity() }) }
    }

    override suspend fun replaceLinks(links: List<ChannelGuideLink>) = db.withTransaction {
        dao.deleteLinks()
        links.chunked(BATCH).forEach { chunk -> dao.insertLinks(chunk.map { ChannelGuideEntity(it.sourceId, it.channelId, it.guideId) }) }
    }

    override fun observeLinks(): Flow<List<ChannelGuideLink>> =
        dao.observeLinks().map { rows -> rows.map { ChannelGuideLink(it.sourceId, it.channelId, it.guideId) } }

    override suspend fun linkedSourceIds(): Set<Long> = dao.linkedSourceIds().toSet()

    override fun observeChannels(): Flow<List<GuideChannel>> =
        dao.observeChannels().map { rows -> rows.map { GuideChannel(it.guideId, GuideOrigin.valueOf(it.origin), it.logoUrl) } }

    override fun observeProgrammes(guideIds: Set<String>, fromMs: Long, untilMs: Long): Flow<List<GuideProgramme>> =
        dao.observeProgrammes(guideIds, fromMs, untilMs).map { rows -> rows.map { it.toDomain() } }

    override suspend fun searchProgrammes(query: String, fromMs: Long, limit: Int): List<GuideProgramme> =
        dao.search(escapeLike(query), fromMs, limit).map { it.toDomain() }

    override suspend fun fetchedAt(origin: GuideOrigin): Long? = dao.fetchedAt(origin.name)

    override suspend fun markFetched(origin: GuideOrigin, atMs: Long) = dao.upsertMeta(GuideMetaEntity(origin.name, atMs))

    override fun observeFavourites(): Flow<Set<String>> = dao.observeFavourites().map { it.toSet() }

    override suspend fun setFavourite(rowKey: String, favourite: Boolean, atMs: Long) =
        if (favourite) dao.addFavourite(FavouriteChannelEntity(rowKey, atMs)) else dao.removeFavourite(rowKey)
}

private fun GuideProgramme.toEntity() =
    GuideProgrammeEntity(guideId, startMs, endMs, title, description, genres.joinToString(GENRE_SEPARATOR), imageUrl)

private fun GuideProgrammeEntity.toDomain() =
    GuideProgramme(guideId, startMs, endMs, title, description, genres.split(GENRE_SEPARATOR).filter { it.isNotEmpty() }, imageUrl)
