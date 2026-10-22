package com.keyspaceapps.goodtv.data.guide

import android.util.Log
import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/** A guide older than this is downloaded again. */
const val GUIDE_MAX_AGE_MS = 24 * 3_600_000L

/** Guide id of a provider channel; the prefix keeps it from ever colliding with an epgshare01 id. */
private const val PROVIDER_PREFIX = "P:"

/**
 * Keeps the TV guide current: once a day it reads epgshare01's two files, matches their channels to
 * the whole visible lineup and stores the programmes of matched channels. The provider's own guide
 * fills in the channels epgshare01 has nothing for, and only while nothing is playing. Each origin
 * either replaces its stored guide completely or leaves it untouched: any failure (HTTP error, cut
 * download, bad file) keeps the last good guide and is tried again on the next call.
 *
 * Files are downloaded to [tempDir] first, so the database write lock is never held while waiting on
 * the network, and are read from there in passes of at most [IDS_PER_PASS] channels, which bounds
 * memory. One refresh at a time; a call while one is running waits and finds the guide fresh.
 */
class GuideRefresher(
    private val store: GuideStore,
    private val library: LibraryStore,
    private val registry: SourceRegistry,
    private val epgShare: EpgShareSource,
    private val playback: PlaybackState,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
    private val tempDir: File,
    private val warn: (String) -> Unit = { Log.w("GoodTV", it) },
) {
    private val lock = Mutex()

    suspend fun refreshIfStale(): Unit = lock.withLock { withContext(io) { refresh() } }

    private suspend fun refresh() {
        val now = clock()
        val epgDue = isStale(GuideOrigin.EPGSHARE, now)
        val providerDue = isStale(GuideOrigin.PROVIDER, now) && !playback.playing.value
        if (!epgDue && !providerDue) return

        // Whatever an earlier run left behind (a killed process) is garbage.
        tempDir.mkdirs()
        tempDir.listFiles()?.forEach { it.delete() }
        val lineup = library.observeSportsCandidates().first()
        val stored = store.observeLinks().first()
        var epgLinks = stored.filterNot { it.guideId.startsWith(PROVIDER_PREFIX) }.associate { (it.sourceId to it.channelId) to it.guideId }
        var providerLinks = stored.filter { it.guideId.startsWith(PROVIDER_PREFIX) }
        var changed = false

        if (epgDue) guarded(GuideOrigin.EPGSHARE) { refreshEpgShare(lineup, now) }?.let { epgLinks = it; changed = true }
        if (providerDue) guarded(GuideOrigin.PROVIDER) { refreshProvider(lineup, epgLinks, now) }?.let { providerLinks = it; changed = true }
        if (changed) {
            val epg = epgLinks.map { (key, guideId) -> ChannelGuideLink(key.first, key.second, guideId) }
            store.replaceLinks(epg + providerLinks.filter { (it.sourceId to it.channelId) !in epgLinks })
        }
    }

    private suspend fun isStale(origin: GuideOrigin, now: Long) = store.fetchedAt(origin)?.let { now - it >= GUIDE_MAX_AGE_MS } ?: true

    /** [block]'s result, or null after logging a failure; cancellation always propagates. */
    private suspend fun <T> guarded(origin: GuideOrigin, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        warn("Guide refresh failed for $origin: ${e.javaClass.simpleName}")
        null
    }

    /** Stores the matched channels' programmes and returns the channel links (key to guide id). */
    private suspend fun refreshEpgShare(lineup: List<ChannelCandidate>, now: Long): Map<Pair<Long, String>, String> {
        val files = ArrayList<File>()
        try {
            val localsFile = download { epgShare.open(EpgShareFiles.LOCALS) }.also { files += it }
            val cableFile = download { epgShare.open(EpgShareFiles.CABLE) }.also { files += it }
            val locals = channelsOf(localsFile)
            val cable = channelsOf(cableFile)
            val links = GuideMatcher.match(lineup, locals, cable)
            val wanted = links.values.toSet()
            val declared = HashSet<String>()
            val passes = ArrayList<Pass>()
            val channels = ArrayList<GuideChannel>()
            for ((file, list) in listOf(localsFile to locals, cableFile to cable)) {
                val ids = list.filter { it.id in wanted && declared.add(it.id) }
                ids.mapTo(channels) { GuideChannel(it.id, GuideOrigin.EPGSHARE, it.iconUrl) }
                ids.map { it.id }.chunked(IDS_PER_PASS).mapTo(passes) { Pass(file, it.toSet()) }
            }
            store.replaceGuide(GuideOrigin.EPGSHARE, channels, programmes(passes, now) { it })
            store.markFetched(GuideOrigin.EPGSHARE, now)
            return links
        } finally {
            files.forEach { it.delete() }
        }
    }

    /** Stores provider guides for the lineup channels epgshare01 left out; returns their links. */
    private suspend fun refreshProvider(
        lineup: List<ChannelCandidate>,
        epgLinks: Map<Pair<Long, String>, String>,
        now: Long,
    ): List<ChannelGuideLink> {
        val open = lineup.map { it.channel }
            .filter { (it.sourceId to it.id) !in epgLinks && !it.epgChannelId.isNullOrBlank() }
            .groupBy { it.sourceId }
        val records = library.getSources().associateBy { it.id }
        val files = ArrayList<File>()
        try {
            val declared = HashSet<String>()
            val channels = ArrayList<GuideChannel>()
            val passes = ArrayList<Pass>()
            val links = ArrayList<ChannelGuideLink>()
            for ((sourceId, wantedChannels) in open) {
                val record = records[sourceId] ?: continue
                val file = download { registry.forType(record.type).openGuide(sourceId, record.config).getOrThrow() }.also { files += it }
                val wanted = wantedChannels.map { it.epgChannelId!! }.toSet()
                val listed = channelsOf(file).filter { it.id in wanted }
                val listedIds = listed.mapTo(HashSet()) { it.id }
                wantedChannels.filter { it.epgChannelId in listedIds }.mapTo(links) { ChannelGuideLink(sourceId, it.id, PROVIDER_PREFIX + it.epgChannelId) }
                // The same provider id in two sources is one guide channel, taken from the first.
                val fresh = listed.filter { declared.add(it.id) }
                fresh.mapTo(channels) { GuideChannel(PROVIDER_PREFIX + it.id, GuideOrigin.PROVIDER, it.iconUrl) }
                fresh.map { it.id }.chunked(IDS_PER_PASS).mapTo(passes) { Pass(file, it.toSet()) }
            }
            store.replaceGuide(GuideOrigin.PROVIDER, channels, programmes(passes, now) { it.copy(guideId = PROVIDER_PREFIX + it.guideId) })
            store.markFetched(GuideOrigin.PROVIDER, now)
            return links
        } finally {
            files.forEach { it.delete() }
        }
    }

    /** One read of [file] keeping the programmes of [ids]. */
    private class Pass(val file: File, val ids: Set<String>)

    /**
     * The programmes of every pass, read lazily, one pass at a time: [replaceGuide][GuideStore.replaceGuide]
     * consumes this inside its write transaction, from local files only.
     */
    private suspend fun programmes(passes: List<Pass>, now: Long, transform: (GuideProgramme) -> GuideProgramme): Sequence<GuideProgramme> {
        val job = currentCoroutineContext()[Job]
        return sequence {
            for (pass in passes) {
                job?.ensureActive()
                val list = ArrayList<GuideProgramme>()
                read(pass.file, { it in pass.ids }, now, onChannel = {}, onProgramme = { job?.ensureActive(); list += transform(it) })
                yieldAll(list)
            }
        }
    }

    private suspend fun channelsOf(file: File): List<XmltvChannel> {
        val job = currentCoroutineContext()[Job]
        val list = ArrayList<XmltvChannel>()
        read(file, { false }, clock(), onChannel = { job?.ensureActive(); list += it }, onProgramme = {})
        return list
    }

    private fun read(file: File, keep: (String) -> Boolean, now: Long, onChannel: (XmltvChannel) -> Unit, onProgramme: (GuideProgramme) -> Unit) {
        BufferedInputStream(file.inputStream(), READ_BUFFER_BYTES).use { input ->
            XmltvReader.read(input, keep, now - WINDOW_BEFORE_MS, now + WINDOW_AHEAD_MS, onChannel, onProgramme)
        }
    }

    /** Copies the stream [open] returns into a new file in [tempDir]; the file is gone again if that fails. */
    private suspend fun download(open: suspend () -> InputStream): File {
        val file = File.createTempFile("guide", ".xml", tempDir)
        try {
            open().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    val job = currentCoroutineContext()[Job]
                    while (true) {
                        job?.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
            return file
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    companion object {
        /** The window kept: programmes that overlap [now - 2 h, now + 48 h]. */
        const val WINDOW_BEFORE_MS = 2 * 3_600_000L
        const val WINDOW_AHEAD_MS = 48 * 3_600_000L
        /** Channels read per pass over a file: a few thousand channels' programmes in memory at once. */
        private const val IDS_PER_PASS = 2000
        private const val READ_BUFFER_BYTES = 1 shl 20
        private const val COPY_BUFFER_BYTES = 1 shl 16
    }
}
