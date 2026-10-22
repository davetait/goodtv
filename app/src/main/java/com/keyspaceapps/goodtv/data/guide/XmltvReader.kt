package com.keyspaceapps.goodtv.data.guide

import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.ZoneOffset

/** What [XmltvReader.read] got through: how many programmes it passed on, and whether the file ended with `</tv>`. */
data class XmltvReadResult(val programmes: Int, val complete: Boolean)

/** A `<channel>` of an XMLTV file: its id, every display name, and its first icon. */
data class XmltvChannel(val id: String, val names: List<String>, val iconUrl: String?)

/**
 * Reads an XMLTV guide (tens to hundreds of MB) as a stream, never holding more than a chunk and one
 * block in memory.
 *
 * Not a real XML parser: it scans for `<channel>` and `<programme>` blocks and picks out the few
 * fields the guide needs, so a file that goes bad partway through still yields everything before the
 * break. A block that doesn't parse (cut off, bad time, no title) is skipped and reading carries on.
 *
 * Work is bounded on hostile input: every scan moves forward only, a start tag is looked at for at
 * most [MAX_TAG_CHARS], a block for at most [MAX_BLOCK_CHARS], and a start with no end before the
 * next start is dropped, so unclosed tags cost linear time, not quadratic.
 */
object XmltvReader {
    private const val CHUNK_CHARS = 1 shl 20
    /** A block longer than this is garbage (real ones are a few KB); its start tag is skipped. */
    private const val MAX_BLOCK_CHARS = 4 shl 20
    /** Start tags are short (an id or two times); a '>' further away than this means it is not one. */
    private const val MAX_TAG_CHARS = 4096
    /** Most genres and display names kept per block. */
    private const val MAX_LISTED = 32

    private const val OPEN_CHANNEL = "<channel"
    private const val OPEN_PROGRAMME = "<programme"
    private const val CLOSE_CHANNEL = "</channel>"
    private const val CLOSE_PROGRAMME = "</programme>"
    private const val CLOSE_TV = "</tv>"
    private const val IS_CHANNEL = 1
    private const val IS_PROGRAMME = 2

    private val TIME = Regex("""^\s*(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*(?:([+-])(\d{2}):?(\d{2}))?\s*$""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")
    private val ID = attributeRegex("id")
    private val CHANNEL_ATTR = attributeRegex("channel")
    private val START = attributeRegex("start")
    private val STOP = attributeRegex("stop")
    private val SRC = attributeRegex("src")

    private fun attributeRegex(name: String) = Regex("""\s$name\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    /**
     * Streams [input] (UTF-8) and returns how many programmes were passed to [onProgramme], and whether
     * the input ended with `</tv>` (trailing whitespace aside): a file cut off between two blocks parses
     * cleanly, so only its missing end shows it is not whole. Never throws on bad markup; an I/O failure
     * of [input] itself still propagates.
     *
     * [onChannel] gets every `<channel>`, wanted or not, because the channel matcher needs the whole
     * list. [keepChannel] only filters programmes, and so does the window: a programme is passed on
     * only when it overlaps [fromMs, untilMs), and its text is only taken from the buffer when it is.
     * [keepChannel] runs inside the same guard as parsing, so one that throws skips that programme.
     */
    fun read(
        input: InputStream,
        keepChannel: (String) -> Boolean,
        fromMs: Long,
        untilMs: Long,
        onChannel: (XmltvChannel) -> Unit,
        onProgramme: (GuideProgramme) -> Unit,
    ): XmltvReadResult {
        val reader = InputStreamReader(input, Charsets.UTF_8)
        val chunk = CharArray(CHUNK_CHARS)
        var buf = ""
        var count = 0
        // The last few non-blank characters of the input so far: a whole file ends with "</tv>".
        var tail = ""

        /** Appends the next chunk to [buf] (from [keepFrom] on); false at the end of the input. */
        fun more(keepFrom: Int): Boolean {
            val n = reader.read(chunk)
            if (n < 0) return false
            val text = String(chunk, 0, n)
            val trimmed = text.trimEnd()
            if (trimmed.isNotEmpty()) tail = (tail + trimmed.takeLast(CLOSE_TV.length)).takeLast(CLOSE_TV.length)
            buf = buf.substring(keepFrom) + text
            return true
        }

        var pos = 0
        // Where the end-tag search for the open start (at index 0 after a refill) may continue, so a
        // block that arrives in many small reads is scanned once, not once per read.
        var resume = -1
        while (true) {
            val s = nextStart(buf, pos)
            if (s < 0) {
                // Keep a tail long enough to hold a split "<programme" so the next chunk can complete it.
                if (!more(maxOf(pos, buf.length - OPEN_PROGRAMME.length - 2))) break
                pos = 0
                resume = -1
                continue
            }
            val kind = startAt(buf, s)

            // The start tag: find its '>' within a short bound; a '<' first means it was cut off.
            val tagLimit = minOf(buf.length, s + MAX_TAG_CHARS)
            var gt = -1
            var cutOffAt = -1
            var i = s + 1
            while (i < tagLimit) {
                val c = buf[i]
                if (c == '>') { gt = i; break }
                if (c == '<') { cutOffAt = i; break }
                i++
            }
            if (cutOffAt >= 0) { pos = cutOffAt; resume = -1; continue }
            if (gt < 0) {
                if (tagLimit == buf.length && buf.length - s < MAX_TAG_CHARS) {
                    if (!more(s)) break
                    pos = 0
                } else pos = s + 1
                resume = -1
                continue
            }

            // The end of the block: the close tag, or none if another start or the cap comes first.
            var bodyEnd = gt + 1
            var end = gt + 1
            if (buf[gt - 1] != '/') {
                val close = if (kind == IS_CHANNEL) CLOSE_CHANNEL else CLOSE_PROGRAMME
                val scanLimit = minOf(buf.length, s + MAX_BLOCK_CHARS)
                var j = maxOf(resume, gt + 1)
                var closeAt = -1
                var nextStartAt = -1
                while (true) {
                    j = buf.indexOf('<', j)
                    if (j < 0 || j >= scanLimit) break
                    if (buf.startsWith(close, j)) { closeAt = j; break }
                    if (startAt(buf, j) != 0) { nextStartAt = j; break }
                    j++
                }
                if (closeAt < 0) {
                    resume = -1
                    if (nextStartAt >= 0) { pos = nextStartAt; continue }
                    if (buf.length - s >= MAX_BLOCK_CHARS) { pos = s + 1; continue }
                    val resumeAt = maxOf(gt + 1, buf.length - CLOSE_PROGRAMME.length) - s
                    if (!more(s)) break
                    pos = 0
                    resume = resumeAt
                    continue
                }
                bodyEnd = closeAt
                end = closeAt + close.length
            }
            resume = -1
            pos = end
            val tag = buf.substring(s, gt)
            if (kind == IS_CHANNEL) {
                val channel = try { parseChannel(tag, buf, gt + 1, bodyEnd) } catch (_: Exception) { null }
                if (channel != null) onChannel(channel)
            } else {
                val programme = try {
                    parseProgramme(tag, buf, gt + 1, bodyEnd, keepChannel, fromMs, untilMs)
                } catch (_: Exception) { null }
                if (programme != null) { onProgramme(programme); count++ }
            }
        }
        return XmltvReadResult(count, complete = tail == CLOSE_TV)
    }

    /** Index of the next `<channel` or `<programme` tag start at or after [from], or -1. */
    private fun nextStart(s: String, from: Int): Int {
        var i = from
        while (true) {
            i = s.indexOf('<', i)
            if (i < 0) return -1
            if (startAt(s, i) != 0) return i
            i++
        }
    }

    /** [IS_CHANNEL] or [IS_PROGRAMME] if a start tag begins at [i] (or may, cut off by the end of [s]), else 0. */
    private fun startAt(s: String, i: Int): Int {
        val kind: Int
        val after: Int
        when {
            s.startsWith(OPEN_CHANNEL, i) -> { kind = IS_CHANNEL; after = i + OPEN_CHANNEL.length }
            s.startsWith(OPEN_PROGRAMME, i) -> { kind = IS_PROGRAMME; after = i + OPEN_PROGRAMME.length }
            else -> return 0
        }
        if (after >= s.length) return kind
        val c = s[after]
        return if (c == '>' || c == '/' || c.isWhitespace()) kind else 0
    }

    private fun parseChannel(tag: String, buf: String, bodyStart: Int, bodyEnd: Int): XmltvChannel? {
        val id = attribute(tag, ID)?.takeIf { it.isNotBlank() } ?: return null
        val body = buf.substring(bodyStart, bodyEnd)
        val names = elements(body, "display-name", MAX_LISTED)
        return XmltvChannel(id, names, icon(body))
    }

    private fun parseProgramme(
        tag: String, buf: String, bodyStart: Int, bodyEnd: Int,
        keepChannel: (String) -> Boolean, fromMs: Long, untilMs: Long,
    ): GuideProgramme? {
        val guideId = attribute(tag, CHANNEL_ATTR) ?: return null
        if (!keepChannel(guideId)) return null
        val startMs = parseTime(attribute(tag, START)) ?: return null
        val endMs = parseTime(attribute(tag, STOP)) ?: return null
        if (endMs <= startMs || endMs <= fromMs || startMs >= untilMs) return null
        val body = buf.substring(bodyStart, bodyEnd)
        val title = elements(body, "title", 1).firstOrNull() ?: return null
        val description = elements(body, "desc", 1).firstOrNull()
        return GuideProgramme(guideId, startMs, endMs, title, description, elements(body, "category", MAX_LISTED), icon(body))
    }

    /**
     * The non-blank, unescaped text of up to [max] `<name>…</name>` elements of [body]. An opening
     * with no close ends the search (the rest is cut off), instead of retrying at the next opening.
     */
    private fun elements(body: String, name: String, max: Int): List<String> {
        val open = "<$name"
        val close = "</$name>"
        val out = ArrayList<String>(1)
        var from = 0
        while (out.size < max) {
            val i = body.indexOf(open, from)
            if (i < 0) break
            val after = i + open.length
            if (after >= body.length) break
            val c = body[after]
            if (c != '>' && c != '/' && !c.isWhitespace()) { from = after; continue }
            val gt = body.indexOf('>', after)
            if (gt < 0) break
            if (body[gt - 1] == '/') { from = gt + 1; continue }
            val e = body.indexOf(close, gt + 1)
            if (e < 0) break
            val t = text(body.substring(gt + 1, e))
            if (t.isNotEmpty()) out += t
            from = e + close.length
        }
        return out
    }

    /** `yyyyMMddHHmmss ±hhmm` as epoch millis; a missing offset means UTC. Null if it isn't a time. */
    private fun parseTime(raw: String?): Long? {
        val m = TIME.matchEntire(raw ?: return null) ?: return null
        val g = m.groupValues
        val local = LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        var offsetSeconds = 0
        if (g[7].isNotEmpty()) {
            offsetSeconds = g[8].toInt() * 3600 + g[9].toInt() * 60
            if (g[7] == "-") offsetSeconds = -offsetSeconds
        }
        return local.toEpochSecond(ZoneOffset.ofTotalSeconds(offsetSeconds)) * 1000
    }

    private fun attribute(tag: String, regex: Regex): String? {
        val m = regex.find(tag) ?: return null
        return unescape(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }

    /** The `src` of the first `<icon>` of [body]. */
    private fun icon(body: String): String? {
        var from = 0
        while (true) {
            val i = body.indexOf("<icon", from)
            if (i < 0) return null
            val after = i + "<icon".length
            if (after >= body.length) return null
            val c = body[after]
            if (c != '>' && c != '/' && !c.isWhitespace()) { from = after; continue }
            val gt = body.indexOf('>', after)
            if (gt < 0) return null
            return attribute(body.substring(i, gt), SRC)?.takeIf { it.isNotBlank() }
        }
    }

    /** Element text with CDATA unwrapped, entities unescaped and the ends trimmed. */
    private fun text(raw: String): String {
        val body = if (raw.contains("<![CDATA[")) raw.replace("<![CDATA[", "").replace("]]>", "") else raw
        return unescape(body).trim()
    }

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (e[1] == 'x') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
                }
            }
        }
    }
}
