package com.keyspaceapps.goodtv.data.db

import androidx.room.withTransaction
import com.keyspaceapps.goodtv.data.guide.LineupStore
import com.keyspaceapps.goodtv.data.guide.LiveGenre
import com.keyspaceapps.goodtv.data.guide.Network
import com.keyspaceapps.goodtv.data.guide.SavedFeed
import com.keyspaceapps.goodtv.data.guide.SavedLineup
import com.keyspaceapps.goodtv.data.guide.SavedRow
import com.keyspaceapps.goodtv.data.guide.Visibility
import com.keyspaceapps.goodtv.domain.LiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private const val BATCH = 500

class RoomLineupStore(private val db: GoodTvDatabase) : LineupStore {
    private val dao = db.lineup()

    override suspend fun saved(): SavedLineup? = db.withTransaction { read(dao.meta()) }

    override fun observeSaved(): Flow<SavedLineup?> = dao.observeMeta().map { meta -> db.withTransaction { read(meta) } }

    override suspend fun savedStamp(): Pair<Long, Int>? = dao.meta()?.let { it.savedGeneration to it.rulesVersion }

    private suspend fun read(meta: LineupMetaEntity?): SavedLineup? {
        meta ?: return null
        val feeds = dao.feeds().groupBy { it.rowKey }
        val rows = dao.rows().map { r -> r.toDomain(feeds[r.key].orEmpty().map { it.toDomain() }) }
        return SavedLineup(rows, meta.savedGeneration, meta.rulesVersion)
    }

    override suspend fun save(lineup: SavedLineup) = db.withTransaction {
        dao.deleteRows() // feeds cascade
        dao.deleteMeta()
        lineup.rows.chunked(BATCH).forEach { chunk -> dao.insertRows(chunk.map { it.toEntity() }) }
        lineup.rows.asSequence()
            .flatMap { row -> row.feeds.asSequence().mapIndexed { i, feed -> feed.toEntity(row.key, i) } }
            .chunked(BATCH)
            .forEach { dao.insertFeeds(it) }
        dao.upsertMeta(LineupMetaEntity(0, lineup.generation, lineup.rulesVersion))
    }

    override suspend fun generation(): Long = dao.generation()

    override fun observeGeneration(): Flow<Long> = dao.observeGeneration()

    override fun observeVisibility(): Flow<Visibility> =
        combine(dao.observeEnabledSources(), dao.observeHiddenLive()) { sources, hidden ->
            Visibility(sources.toSet(), hidden.mapTo(HashSet()) { it.sourceId to it.categoryId })
        }
}

/** Counts one library write; call inside the same transaction as the write so the count never moves without it. */
internal suspend fun LineupDao.bumpGeneration() {
    seedState()
    increment()
}

private fun SavedRow.toEntity() =
    LineupRowEntity(key, position, name, genre.name, network?.name, callSign, market, loopShow, providerLogo, sortKey, loopKey, appCopy)

private fun LineupRowEntity.toDomain(feeds: List<SavedFeed>) =
    SavedRow(key, position, name, LiveGenre.valueOf(genre), network?.let { Network.valueOf(it) }, callSign, market, loopShow, providerLogo, sortKey, loopKey, appCopy, feeds)

private fun SavedFeed.toEntity(rowKey: String, position: Int) = LineupFeedEntity(
    rowKey, position, channel.sourceId, channel.id, channel.categoryId, channel.name, channel.logoUrl, channel.number,
    channel.title, channel.language, channel.quality, channel.epgChannelId, categoryName,
)

private fun LineupFeedEntity.toDomain() = SavedFeed(
    LiveChannel(sourceId, channelId, categoryId, name, logoUrl, number, title, language, quality, epgChannelId),
    categoryName,
)
