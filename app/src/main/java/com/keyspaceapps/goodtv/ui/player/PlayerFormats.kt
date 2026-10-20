package com.keyspaceapps.goodtv.ui.player

object PlayerFormats {
    /** "01:05 / 45:00", hours shown only when needed; position alone when the duration is unknown. */
    fun position(positionMs: Long, durationMs: Long): String {
        val pos = clock(positionMs.coerceAtLeast(0))
        return if (durationMs > 0) "$pos / ${clock(durationMs)}" else pos
    }

    /** "12:40", or "1:05:09" past an hour. */
    fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
