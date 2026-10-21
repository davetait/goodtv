package com.keyspaceapps.goodtv.data.subtitles

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    fun forQuery(query: SubtitleQuery): OnlineSubtitleSource = object : OnlineSubtitleSource {
        private val srt = File(dir, "${query.key}.srt")
        private val offset = File(dir, "${query.key}.offset")

        override suspend fun find(): OsSubtitle? = lock.withLock {
            if (query.key in searches) return@withLock searches[query.key]
            val found = try {
                SubtitlePicker.best(api.search(query), query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenSubtitlesException) {
                return@withLock null // not remembered: try again next time
            }
            found.also { searches[query.key] = it }
        }

        override suspend fun fetch(choice: OsSubtitle): Result<String> = try {
            withContext(Dispatchers.IO) { if (srt.exists()) srt.readText() else null }?.let { return Result.success(it) }
            val text = api.fetchText(api.downloadLink(choice.fileId))
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val tmp = File(dir, "${query.key}.srt.tmp")
                tmp.writeText(text)
                tmp.renameTo(srt)
            }
            Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        override suspend fun offsetMs(): Long = withContext(Dispatchers.IO) { offset.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L }

        override suspend fun setOffsetMs(ms: Long) = withContext(Dispatchers.IO) { dir.mkdirs(); offset.writeText(ms.toString()) }
    }
}
