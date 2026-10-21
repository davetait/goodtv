package com.keyspaceapps.goodtv.ui.player

/**
 * Seeking with the D-pad on the seek bar. Each press moves a preview point rather than the
 * video; holding the key speeds up (10 s, then 30 s, then 1 min, then 2 min or 1/50 of the video),
 * and the jump happens [COMMIT_DELAY_MS] after the last press, or at once on Select.
 */
object Scrub {
    const val COMMIT_DELAY_MS = 800L

    /** How far one press moves; [repeatCount] is how long the key has been held (0 = a tap). */
    fun step(repeatCount: Int, durationMs: Long): Long = when {
        repeatCount < 4 -> 10_000L
        repeatCount < 12 -> 30_000L
        repeatCount < 24 -> 60_000L
        else -> maxOf(120_000L, durationMs / 50)
    }

    /** The preview point after one press [forward] or back from [fromMs], kept inside the video. */
    fun next(fromMs: Long, forward: Boolean, repeatCount: Int, durationMs: Long): Long {
        val moved = fromMs + if (forward) step(repeatCount, durationMs) else -step(repeatCount, durationMs)
        return if (durationMs > 0) moved.coerceIn(0, durationMs) else moved.coerceAtLeast(0)
    }
}
