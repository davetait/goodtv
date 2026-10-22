package com.keyspaceapps.goodtv.data.guide

import android.util.Log
import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** A guide older than this is downloaded again. */
const val GUIDE_MAX_AGE_MS = 24 * 3_600_000L

/** Guide id of a provider channel; the prefix keeps it from ever colliding with an epgshare01 id. */
internal const val PROVIDER_PREFIX = "P:"

/** Most bytes one download may write to disk, and most it may unpack to when read. */
data class GuideCap(val download: Long, val unpacked: Long)

/**
 * When each source last had an early epgshare01 refresh for having no guide links. Kept across restarts
 * (see [com.keyspaceapps.goodtv.data.settings.AppSettings]), so a source nothing matches costs one extra
 * download a day rather than one per cold start.
 */
interface EarlyRefreshLog {
    suspend fun lastEarlyRefresh(sourceId: Long): Long?
    suspend fun recordEarlyRefresh(sourceId: Long, atMs: Long)
}

/** An [EarlyRefreshLog] that lasts as long as the process (tests). */
class MemoryEarlyRefreshLog : EarlyRefreshLog {
    private val at = HashMap<Long, Long>()
    override suspend fun lastEarlyRefresh(sourceId: Long): Long? = synchronized(at) { at[sourceId] }
    override suspend fun recordEarlyRefresh(sourceId: Long, atMs: Long) { synchronized(at) { at[sourceId] = atMs } }
}

/** When each origin's refresh last failed (kept across restarts): a failed origin waits an hour before it is tried again. */
interface GuideFailureLog {
    suspend fun lastFailure(origin: GuideOrigin): Long?
    suspend fun recordFailure(origin: GuideOrigin, atMs: Long)
}

/** A [GuideFailureLog] that lasts as long as the process (tests). */
class MemoryGuideFailureLog : GuideFailureLog {
    private val at = HashMap<GuideOrigin, Long>()
    override suspend fun lastFailure(origin: GuideOrigin): Long? = synchronized(at) { at[origin] }
    override suspend fun recordFailure(origin: GuideOrigin, atMs: Long) { synchronized(at) { at[origin] = atMs } }
}

/** Size limits that keep a hostile or broken server from filling the disk or the CPU. */
data class GuideLimits(
    val locals: GuideCap = GuideCap(150 * MIB, 1024 * MIB),
    val cable: GuideCap = GuideCap(50 * MIB, 300 * MIB),
    val provider: GuideCap = GuideCap(600 * MIB, 600 * MIB),
    /** Free space that must remain beyond a download's cap. */
    val spaceReserve: Long = 200 * MIB,
) {
    private companion object { const val MIB = 1024L * 1024L }
}

/**
 * Keeps the TV guide current: once a day it reads epgshare01's two files, matches their channels to
 * the whole visible lineup and stores the programmes of matched channels. The provider's own guide
 * fills in the channels epgshare01 has nothing for, and only while nothing is playing (a player
 * starting mid-download aborts it). Each origin either replaces its stored guide completely or leaves
 * it untouched: any failure (HTTP error, cut or oversize download, timeout, a file that isn't a whole
 * guide or whose match collapsed) keeps the last good guide and is tried again on a call at least an
 * hour later. An origin's links are written before it is marked fetched, so a fresh guide never lacks them.
 *
 * Files are downloaded as received to [tempDir] first, so the database write lock is never held
 * while waiting on the network, then unpacked on read. The programmes of a file are read in one
 * pass on a reader thread and handed to the store through a small queue: memory is bounded by the
 * queue, not by the size of the guide.
 *
 * One refresh at a time; a call while one is running waits and finds the guide fresh.
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
    private val limits: GuideLimits = GuideLimits(),
    private val timeoutMs: Long = DOWNLOAD_TIMEOUT_MS,
    private val early: EarlyRefreshLog = MemoryEarlyRefreshLog(),
    private val failures: GuideFailureLog = MemoryGuideFailureLog(),
) {
    private val lock = Mutex()

    private val _working = MutableStateFlow(false)

    /** True while a refresh is downloading and storing a guide; a call that finds nothing due never sets it. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    suspend fun refreshIfStale(): Unit = lock.withLock { withContext(io) { refresh() } }

    private suspend fun refresh() {
        val now = clock()
        // What is due comes from small reads (dates, sources, which sources have links) first: a start with
        // nothing due never reads the ~12,600-channel lineup or the links.
        val epgOpen = !failedRecently(GuideOrigin.EPGSHARE, now)
        val epgStale = epgOpen && isStale(GuideOrigin.EPGSHARE, now)
        val providerDue = isStale(GuideOrigin.PROVIDER, now) && !failedRecently(GuideOrigin.PROVIDER, now) && !playback.playing.value
        // A source without links gets one early refresh a day; one nothing matches would otherwise download on every start.
        // An attempt dated in the future (the clock was set back) doesn't count.
        val unlinkedSources = if (!epgOpen) emptyList() else {
            val linked = store.linkedSourceIds()
            library.getSources().filter { it.enabled && it.id !in linked }.map { it.id }.filter { id ->
                early.lastEarlyRefresh(id)?.let { now - it in 0 until GUIDE_MAX_AGE_MS } != true
            }
        }
        if (!epgStale && unlinkedSources.isEmpty() && !providerDue) return
        val lineup = library.observeSportsCandidates().first()
        if (lineup.isEmpty()) return
        // Only a source with visible channels counts.
        val inLineup = lineup.mapTo(HashSet()) { it.channel.sourceId }
        val unlinked = unlinkedSources.filter { it in inLineup }
        val epgDue = epgStale || unlinked.isNotEmpty()
        if (!epgDue && !providerDue) return
        _working.value = true
        try {
            refreshDue(lineup, unlinked, epgDue, providerDue, now)
        } finally {
            _working.value = false
        }
    }

    /** The refresh itself, once [refresh] has found what is due. */
    private suspend fun refreshDue(lineup: List<ChannelCandidate>, unlinked: List<Long>, epgDue: Boolean, providerDue: Boolean, now: Long) {
        if (epgDue) unlinked.forEach { early.recordEarlyRefresh(it, now) }
        val stored = store.observeLinks().first()

        // Whatever an earlier run left behind (a killed process) is garbage.
        tempDir.mkdirs()
        tempDir.listFiles()?.forEach { it.delete() }
        var epgLinks = stored.filterNot { it.guideId.startsWith(PROVIDER_PREFIX) }.associate { (it.sourceId to it.channelId) to it.guideId }
        val providerLinks = stored.filter { it.guideId.startsWith(PROVIDER_PREFIX) }

        // A collapse is judged against links of channels still in the lineup: hiding categories or
        // disabling a source shrinks the lineup, not the guide.
        val lineupKeys = lineup.mapTo(HashSet()) { it.channel.sourceId to it.channel.id }
        val storedInLineup = epgLinks.keys.count { it in lineupKeys }
        if (epgDue) guarded(GuideOrigin.EPGSHARE, now) { refreshEpgShare(lineup, now, providerLinks, storedInLineup) }?.let { epgLinks = it }
        // The epgshare01 stage can take minutes: a player may have started since [providerDue] was decided.
        if (providerDue && !playback.playing.value) guarded(GuideOrigin.PROVIDER, now) { refreshProvider(lineup, epgLinks, now) }
    }

    private suspend fun isStale(origin: GuideOrigin, now: Long) = store.fetchedAt(origin)?.let { now - it >= GUIDE_MAX_AGE_MS } ?: true

    /** Whether [origin] failed less than [FAILURE_BACKOFF_MS] ago; a failure dated in the future (the clock was set back) doesn't count. */
    private suspend fun failedRecently(origin: GuideOrigin, now: Long) =
        failures.lastFailure(origin)?.let { now - it in 0 until FAILURE_BACKOFF_MS } == true

    /**
     * [block]'s result, or null after logging a failure (its kind and, for a rejected file, the reason; never
     * a URL or the file's data) and recording it for the backoff. Cancellation always propagates; playback
     * starting is logged but is not a failure of the origin.
     */
    private suspend fun <T> guarded(origin: GuideOrigin, now: Long, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is GuideRejected || it is PlaybackStarted }
        warn("Guide refresh failed for $origin: ${(cause as? GuideRejected)?.let { "rejected, ${it.message}" } ?: (cause ?: e).javaClass.simpleName}")
        if (cause !is PlaybackStarted) failures.recordFailure(origin, now)
        null
    }

    /** A downloaded file that is not a guide worth replacing the stored one with; the message is the reason. */
    private class GuideRejected(reason: String) : IOException(reason)

    private class PlaybackStarted : IOException("Playback started")

    /**
     * Stores the matched channels' programmes and links; returns the links (key to guide id). A match that
     * fell below half of the [storedLinks] (when there were at least [COLLAPSE_MIN_LINKS]) means the files
     * changed shape (new ids, a broken export), not that the channels left: the stored guide is kept.
     */
    private suspend fun refreshEpgShare(
        lineup: List<ChannelCandidate>,
        now: Long,
        providerLinks: List<ChannelGuideLink>,
        storedLinks: Int,
    ): Map<Pair<Long, String>, String> {
        val files = ArrayList<File>()
        try {
            val (localsFile, locals) = fetch(limits.locals, files, { false }) { epgShare.openCompressed(EpgShareFiles.LOCALS) }
            val (cableFile, cable) = fetch(limits.cable, files, { false }) { epgShare.openCompressed(EpgShareFiles.CABLE) }
            val links = GuideMatcher.match(lineup, locals, cable)
            if (storedLinks >= COLLAPSE_MIN_LINKS && links.size * 2 < storedLinks) {
                throw GuideRejected("matched channels fell from $storedLinks to ${links.size}")
            }
            val wanted = links.values.toSet()
            val declared = HashSet<String>()
            val channels = ArrayList<GuideChannel>()
            val specs = ArrayList<Spec>()
            for ((file, list) in listOf(localsFile to locals, cableFile to cable)) {
                val ids = list.filter { it.id in wanted && declared.add(it.id) }
                ids.mapTo(channels) { GuideChannel(it.id, GuideOrigin.EPGSHARE, it.iconUrl) }
                specs += Spec(file, ids.mapTo(HashSet()) { it.id }) { it }
            }
            replaceGuide(GuideOrigin.EPGSHARE, channels, specs, now) { false }
            store.replaceLinks(links.map { (key, guideId) -> ChannelGuideLink(key.first, key.second, guideId) } + providerLinks.filter { (it.sourceId to it.channelId) !in links })
            store.markFetched(GuideOrigin.EPGSHARE, now)
            return links
        } finally {
            files.forEach { it.delete() }
        }
    }

    /** Stores provider guides for the lineup channels epgshare01 left out, with their links. */
    private suspend fun refreshProvider(lineup: List<ChannelCandidate>, epgLinks: Map<Pair<Long, String>, String>, now: Long) {
        val open = lineup.map { it.channel }
            .filter { (it.sourceId to it.id) !in epgLinks && !it.epgChannelId.isNullOrBlank() }
            .groupBy { it.sourceId }
        val records = library.getSources().associateBy { it.id }
        val abortIfPlaying = { playback.playing.value }
        val files = ArrayList<File>()
        try {
            val declared = HashSet<String>()
            val channels = ArrayList<GuideChannel>()
            val specs = ArrayList<Spec>()
            val links = ArrayList<ChannelGuideLink>()
            for ((sourceId, wantedChannels) in open) {
                val record = records[sourceId] ?: continue
                val (file, listed) = fetch(limits.provider, files, abortIfPlaying) {
                    registry.forType(record.type).openGuide(sourceId, record.config).getOrThrow()
                }
                val wanted = wantedChannels.mapTo(HashSet()) { it.epgChannelId!! }
                val ours = listed.filter { it.id in wanted }
                val oursIds = ours.mapTo(HashSet()) { it.id }
                wantedChannels.filter { it.epgChannelId in oursIds }.mapTo(links) { ChannelGuideLink(sourceId, it.id, PROVIDER_PREFIX + it.epgChannelId) }
                // The same provider id in two sources is one guide channel, taken from the first.
                val fresh = ours.filter { declared.add(it.id) }
                fresh.mapTo(channels) { GuideChannel(PROVIDER_PREFIX + it.id, GuideOrigin.PROVIDER, it.iconUrl) }
                specs += Spec(file, fresh.mapTo(HashSet()) { it.id }) { it.copy(guideId = PROVIDER_PREFIX + it.guideId) }
            }
            replaceGuide(GuideOrigin.PROVIDER, channels, specs, now, abortIfPlaying)
            store.replaceLinks(epgLinks.map { (key, guideId) -> ChannelGuideLink(key.first, key.second, guideId) } + links)
            store.markFetched(GuideOrigin.PROVIDER, now)
        } finally {
            files.forEach { it.delete() }
        }
    }

    /** A downloaded file, the programmes of [ids] to keep from it, and how to turn them into stored ones. */
    private class Spec(val file: Download, val ids: Set<String>, val transform: (GuideProgramme) -> GuideProgramme)

    private class Download(val file: File, val unpackedCap: Long)

    private suspend fun replaceGuide(origin: GuideOrigin, channels: List<GuideChannel>, specs: List<Spec>, now: Long, abortIf: () -> Boolean) {
        timed {
            val stream = ProgrammeStream(currentCoroutineContext()[Job], produce = { emit, check ->
                for (spec in specs) {
                    read(spec.file, { it in spec.ids }, now, { check(); if (abortIf()) throw PlaybackStarted() }, {}) { emit(spec.transform(it)) }
                }
            })
            try {
                store.replaceGuide(origin, channels, stream.sequence)
            } finally {
                stream.close()
            }
        }
    }

    /**
     * Downloads one file and reads its channel list, within the time limit; [abortIf] stops it early. A file
     * that isn't whole (no closing `</tv>`: an error page, an empty or cut-off file) or lists no channels fails.
     */
    private suspend fun fetch(cap: GuideCap, files: MutableList<File>, abortIf: () -> Boolean, open: suspend () -> InputStream): Pair<Download, List<XmltvChannel>> = timed {
        val check = coroutineCheck(abortIf)
        val download = download(cap, files, check, open)
        val channels = ArrayList<XmltvChannel>()
        read(download, { false }, clock(), check, { channels += it }) {}
        if (channels.isEmpty()) throw GuideRejected("no channels")
        download to channels
    }

    private fun read(
        download: Download, keep: (String) -> Boolean, now: Long, check: () -> Unit,
        onChannel: (XmltvChannel) -> Unit, onProgramme: (GuideProgramme) -> Unit,
    ) {
        val result = unpacked(download, check).use { input ->
            XmltvReader.read(input, keep, now - WINDOW_BEFORE_MS, now + WINDOW_AHEAD_MS, onChannel, onProgramme)
        }
        if (!result.complete) throw GuideRejected("not a whole guide (no closing </tv>)")
    }

    /** The file's text: gunzipped when it is gzip (whatever its name), capped, calling [check] on each read. */
    private fun unpacked(download: Download, check: () -> Unit): InputStream {
        val raw = BufferedInputStream(download.file.inputStream(), READ_BUFFER_BYTES)
        try {
            raw.mark(2)
            val gzip = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            return CappedInputStream(if (gzip) GZIPInputStream(raw, 1 shl 16) else raw, download.unpackedCap, check)
        } catch (t: Throwable) {
            raw.close()
            throw t
        }
    }

    /** Copies the stream [open] returns, as received, into a new file in [tempDir] (listed in [files] at once). */
    private suspend fun download(cap: GuideCap, files: MutableList<File>, check: () -> Unit, open: suspend () -> InputStream): Download {
        if (tempDir.usableSpace < cap.download + limits.spaceReserve) throw IOException("Not enough space for the guide")
        val file = File.createTempFile("guide", ".dl", tempDir)
        files += file
        // Not even the request goes out once [check] fails (playback started).
        check()
        open().use { input ->
            file.outputStream().use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var written = 0L
                while (true) {
                    check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    written += n
                    if (written > cap.download) throw IOException("Guide download too large")
                    out.write(buffer, 0, n)
                }
            }
        }
        return Download(file, cap.unpacked)
    }

    /** Checks run between reads: stop when the calling coroutine (and so its time limit) is cancelled, or when [abortIf] says so. */
    private suspend fun coroutineCheck(abortIf: () -> Boolean): () -> Unit {
        val job = currentCoroutineContext()[Job]
        return {
            job?.ensureActive()
            if (abortIf()) throw PlaybackStarted()
        }
    }

    /** [block] within the time limit; running out is a failure of the refresh, not a cancellation. */
    private suspend fun <T> timed(block: suspend () -> T): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        throw IOException("Guide download timed out")
    }

    /** Throws [IOException] after more than [cap] bytes, and runs [check] before each read. */
    internal class CappedInputStream(input: InputStream, private val cap: Long, private val check: () -> Unit) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            check()
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check()
            val n = super.read(b, off, len)
            if (n > 0) count(n)
            return n
        }

        private fun count(n: Int) {
            total += n
            if (total > cap) throw IOException("Guide file too large")
        }
    }

    /**
     * Programmes read on a reader thread and handed over through a bounded queue, so [sequence] can be
     * consumed inside the store's write transaction while memory stays at one queue. A failure of the
     * reader surfaces from the sequence, so the transaction rolls back. [close] stops the reader.
     */
    internal class ProgrammeStream(
        private val job: Job?,
        private val capacity: Int = QUEUE_CAPACITY,
        private val produce: (emit: (GuideProgramme) -> Unit, check: () -> Unit) -> Unit,
    ) : AutoCloseable {
        private object End
        private class Failed(val error: Throwable)

        private val queue = ArrayBlockingQueue<Any>(capacity)
        @Volatile private var closed = false
        private var thread: Thread? = null

        val sequence: Sequence<GuideProgramme> = Sequence { start(); consume() }

        private fun start() {
            thread = Thread({ run() }, "guide-reader").apply { isDaemon = true; start() }
        }

        /** Never throws: an exception escaping a thread would take the whole app down. */
        private fun run() {
            try {
                produce(::put) { if (closed) throw IOException("Guide read stopped") }
                put(End)
            } catch (t: Throwable) {
                try {
                    put(Failed(t))
                } catch (_: Throwable) {
                    // Closed or interrupted while reporting: nobody is listening any more.
                }
            }
        }

        private fun put(item: Any) {
            while (!closed) if (queue.offer(item, 100, TimeUnit.MILLISECONDS)) return
            throw IOException("Guide read stopped")
        }

        private fun consume(): Iterator<GuideProgramme> = iterator {
            while (true) {
                job?.ensureActive()
                val item = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (item === End) return@iterator
                if (item is Failed) throw IOException("Guide read failed", item.error)
                yield(item as GuideProgramme)
            }
        }

        override fun close() {
            closed = true
            thread?.interrupt()
            thread?.join(2000)
        }
    }

    companion object {
        /** The window kept: programmes that overlap [now - 2 h, now + 48 h]. */
        const val WINDOW_BEFORE_MS = 2 * 3_600_000L
        const val WINDOW_AHEAD_MS = 48 * 3_600_000L
        /** Longest one download plus parse may take. */
        const val DOWNLOAD_TIMEOUT_MS = 15 * 60_000L
        /** A failed origin isn't tried again for this long, across restarts. */
        const val FAILURE_BACKOFF_MS = 3_600_000L
        /** Below this many stored epgshare01 links a smaller match is believed; from it on, one under half is a broken file. */
        private const val COLLAPSE_MIN_LINKS = 100
        private const val QUEUE_CAPACITY = 2000
        private const val READ_BUFFER_BYTES = 1 shl 20
        private const val COPY_BUFFER_BYTES = 1 shl 16
    }
}
