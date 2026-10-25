package com.keyspaceapps.goodtv.ui.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.Locale

/**
 * Counts the bytes the player downloads and the time it spends downloading them, for the info box's bitrate and
 * network speed. The player's loading threads call it per read and per download; each call only adds to a
 * counter under a lock, so it costs nothing measurable, whether or not the box is open.
 */
@OptIn(UnstableApi::class)
class TrafficMeter(private val clock: () -> Long = SystemClock::elapsedRealtime) : TransferListener {
    private val lock = Any()
    private var bytesTotal = 0L
    private var active = 0
    private var activeSince = 0L
    private var activeMs = 0L
    private var activeBytes = 0L

    /** Every byte downloaded so far. */
    val bytes: Long get() = synchronized(lock) { bytesTotal }

    /**
     * Bytes over the time at least one download was running, so a full buffer (no downloads) doesn't drag it down
     * and a stream that arrives in bursts is measured during the bursts. Null before anything has downloaded.
     */
    fun networkBps(): Long? = synchronized(lock) {
        val ms = activeMs + if (active > 0) clock() - activeSince else 0
        if (ms <= 0 || activeBytes <= 0) null else activeBytes * 8 * 1000 / ms
    }

    internal fun started() = synchronized(lock) { if (active++ == 0) activeSince = clock() }
    internal fun ended() = synchronized(lock) {
        if (active > 0 && --active == 0) activeMs += clock() - activeSince
    }
    internal fun received(count: Long) = synchronized(lock) { bytesTotal += count; activeBytes += count }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { if (isNetwork) started() }
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) received(bytesTransferred.toLong())
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { if (isNetwork) ended() }
}

/**
 * Bitrate: bytes downloaded per second of video downloaded since playback began (or the last seek, when the buffer
 * jumps), shown once at least [MIN_VIDEO_MS] has arrived. Unlike a stream's declared bitrate, live channels have it
 * too. With nothing downloaded (a local file) there is none, and the stream's own figure shows instead.
 */
class BitrateMeter {
    private var fromBytes = 0L
    private var fromMs = 0L

    fun reset(bytes: Long, fromMs: Long) {
        fromBytes = bytes; this.fromMs = fromMs
    }

    fun bitrateBps(bytes: Long, bufferedMs: Long): Long? {
        val videoMs = bufferedMs - fromMs
        val got = bytes - fromBytes
        return if (videoMs >= MIN_VIDEO_MS && got > 0) got * 8 * 1000 / videoMs else null
    }

    companion object {
        const val MIN_VIDEO_MS = 2_000L
    }
}

/** The info box's wording. */
object InfoText {
    /** Under this much video ahead, the buffer shows as low. */
    const val LOW_BUFFER_MS = 5_000L

    /** "5.8 Mbps", "42 Mbps", "850 kbps". */
    fun rate(bps: Long): String = when {
        bps >= 10_000_000 -> "${bps / 1_000_000} Mbps"
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        else -> "${bps / 1_000} kbps"
    }

    /** "1920×1080 · 23.976 fps · H.264"; the frame rate is left out when unknown; null with no video size. */
    fun video(width: Int, height: Int, frameRate: Float, mimeType: String?): String? {
        if (width <= 0 || height <= 0) return null
        val fps = frameRate.takeIf { it > 0f }?.let { String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.') + " fps" }
        return listOfNotNull("$width×$height", fps, mimeType?.let(::codec)).joinToString(" · ")
    }

    fun bufferLow(aheadMs: Long): Boolean = aheadMs < LOW_BUFFER_MS

    private fun codec(mimeType: String): String = when (mimeType) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/mpeg2" -> "MPEG-2"
        "video/dolby-vision" -> "Dolby Vision"
        else -> mimeType
    }
}
