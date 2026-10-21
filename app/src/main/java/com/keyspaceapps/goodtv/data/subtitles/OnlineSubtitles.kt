package com.keyspaceapps.goodtv.data.subtitles

import com.keyspaceapps.goodtv.ui.player.Srt
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Online English subtitles for one title, as the player sees them. */
interface OnlineSubtitleSource {
    /** The result to offer, or null when OpenSubtitles has nothing usable (or can't be reached). */
    suspend fun find(): OsSubtitle?
    /** The SRT text of [choice]: from the saved copy, else downloaded once (spending a download) and saved. */
    suspend fun fetch(choice: OsSubtitle): Result<String>
    /** How much later (positive) or earlier the subtitles show, saved per title. */
    suspend fun offsetMs(): Long
    suspend fun setOffsetMs(ms: Long)
}

/**
 * OpenSubtitles English subtitles, kept in [dir]: `<query key>.srt` once downloaded (so a title
 * costs one download ever) and `<query key>.offset` for its timing. Searches are remembered for
 * the process; they cost no downloads but are best-effort, so a failed search is just "nothing".
 */
class OnlineSubtitles(private val api: OpenSubtitlesApi, private val dir: File) {
    private val lock = Mutex()
    private val searches = HashMap<String, OsSubtitle?>()
    private val downloads = KeyedLocks()

    fun forQuery(query: SubtitleQuery): OnlineSubtitleSource = object : OnlineSubtitleSource {
        private val srt = File(dir, "${query.key}.srt")
        private val offset = File(dir, "${query.key}.offset")

        override suspend fun find(): OsSubtitle? = lock.withLock {
            if (query.key in searches) return@withLock searches[query.key]
            val found = try {
                SubtitlePicker.best(api.search(query), query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock null // best-effort and not remembered: try again next time
            }
            found.also { searches[query.key] = it }
        }

        override suspend fun fetch(choice: OsSubtitle): Result<String> = try {
            // One download per title even if asked twice at once; the paid-for download is kept even
            // if the player closes meanwhile (NonCancellable), and only text that has cues is saved.
            withContext(NonCancellable + Dispatchers.IO) {
                downloads.withLock(query.key) {
                    readSaved() ?: run {
                        val text = api.fetchText(api.downloadLink(choice.fileId))
                        if (Srt.parse(text).isEmpty()) {
                            throw OpenSubtitlesException("OpenSubtitles sent a file with no subtitles in it", OpenSubtitlesException.Reason.BAD_RESPONSE)
                        }
                        save(text)
                        text
                    }
                }
            }.let { Result.success(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        private fun readSaved(): String? = runCatching { srt.takeIf { it.exists() }?.readText() }.getOrNull()

        /** Best effort: a failed save (disk full) still shows this time's subtitles. */
        private fun save(text: String) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "${query.key}.srt.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(srt)) { tmp.copyTo(srt, overwrite = true); tmp.delete() }
            }
        }

        override suspend fun offsetMs(): Long = withContext(Dispatchers.IO) {
            runCatching { offset.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() }.getOrNull() ?: 0L
        }

        override suspend fun setOffsetMs(ms: Long) = withContext(Dispatchers.IO) {
            runCatching { dir.mkdirs(); offset.writeText(ms.toString()) }
            Unit
        }
    }
}

/** One mutex per key, so downloads of different titles don't wait on each other. */
private class KeyedLocks {
    private val locks = HashMap<String, Mutex>()
    suspend fun <T> withLock(key: String, block: suspend () -> T): T = synchronized(locks) { locks.getOrPut(key) { Mutex() } }.withLock { block() }
}

