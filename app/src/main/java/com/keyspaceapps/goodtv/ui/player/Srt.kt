package com.keyspaceapps.goodtv.ui.player

/** One subtitle line or block, shown from [startMs] until (not including) [endMs]. */
data class TimedCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * A forgiving SRT reader for downloaded subtitles (Media3's parser needs Android's Html, so it can't
 * run in unit tests): BOM, CRLF, "." before milliseconds, HTML-ish tags and SSA override blocks
 * such as {\an8} are handled; a block that can't be read is skipped rather than failing the file.
 */
object Srt {
    private val TIMING = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(text: String): List<TimedCue> =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("""\n\s*\n"""))
            .mapNotNull { block ->
                val lines = block.trim().lines()
                val timingIndex = lines.indexOfFirst { "-->" in it }
                if (timingIndex < 0) return@mapNotNull null
                val m = TIMING.find(lines[timingIndex]) ?: return@mapNotNull null
                val g = m.groupValues
                val body = lines.drop(timingIndex + 1).joinToString("\n") { TAGS.replace(it, "").trim() }.trim()
                if (body.isEmpty()) null else TimedCue(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), body)
            }
            .sortedBy { it.startMs }

    private fun ms(h: String, m: String, s: String, frac: String) =
        ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + frac.padEnd(3, '0').toLong()
}

/** The cues showing at [positionMs] when the subtitles are moved [offsetMs] later (negative: earlier). */
fun List<TimedCue>.at(positionMs: Long, offsetMs: Long): List<TimedCue> =
    filter { it.startMs + offsetMs <= positionMs && positionMs < it.endMs + offsetMs }
