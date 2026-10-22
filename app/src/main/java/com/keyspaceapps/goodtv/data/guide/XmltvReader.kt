package com.keyspaceapps.goodtv.data.guide

import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.ZoneOffset

/** A `<channel>` of an XMLTV file: its id, every display name, and its first icon. */
data class XmltvChannel(val id: String, val names: List<String>, val iconUrl: String?)

/**
 * Reads an XMLTV guide (tens to hundreds of MB) as a stream, never holding more than a chunk and one
 * block in memory.
 *
 * Not a real XML parser: it scans for `<channel>` and `<programme>` blocks and picks out the few
 * fields the guide needs, so a file that goes bad partway through still yields everything before the
 * break. A block that doesn't parse (cut off, bad time, no title) is skipped and reading carries on.
 */
object XmltvReader {
    private const val CHUNK_CHARS = 1 shl 20
    /** A block longer than this is garbage (real ones are a few KB); its start tag is skipped. */
    private const val MAX_BLOCK_CHARS = 4 shl 20
    private const val CHANNEL = "channel"
    private const val PROGRAMME = "programme"

    private val TIME = Regex("""^\s*(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*(?:([+-])(\d{2}):?(\d{2}))?\s*$""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    private val DESC = Regex("""<desc\b[^>]*>(.*?)</desc>""", RegexOption.DOT_MATCHES_ALL)
    private val CATEGORY = Regex("""<category\b[^>]*>(.*?)</category>""", RegexOption.DOT_MATCHES_ALL)
    private val DISPLAY_NAME = Regex("""<display-name\b[^>]*>(.*?)</display-name>""", RegexOption.DOT_MATCHES_ALL)
    private val ICON = Regex("""<icon\b[^>]*>""")
    private val attributeRegexes = HashMap<String, Regex>()

    /**
     * Streams [input] (UTF-8) and returns how many programmes were passed to [onProgramme]. Never
     * throws on bad markup; an I/O failure of [input] itself still propagates.
     *
     * [onChannel] gets every `<channel>`, wanted or not, because the channel matcher needs the whole
     * list. [keepChannel] only filters programmes, and so does the window: a programme is passed on
     * only when it overlaps [fromMs, untilMs), and the text of one that isn't is never read.
     */
    fun read(
        input: InputStream,
        keepChannel: (String) -> Boolean,
        fromMs: Long,
        untilMs: Long,
        onChannel: (XmltvChannel) -> Unit,
        onProgramme: (GuideProgramme) -> Unit,
    ): Int {
        val reader = InputStreamReader(input, Charsets.UTF_8)
        val chunk = CharArray(CHUNK_CHARS)
        var buf = ""
        var count = 0
        var eof = false

        /** Appends the next chunk to [buf] (from [keepFrom] on); false at the end of the input. */
        fun more(keepFrom: Int): Boolean {
            val n = reader.read(chunk)
            if (n < 0) { eof = true; return false }
            buf = buf.substring(keepFrom) + String(chunk, 0, n)
            return true
        }

        var pos = 0
        while (true) {
            val s = nextStart(buf, pos)
            if (s < 0) {
                // Keep a tail long enough to hold a split "<programme" so the next chunk can complete it.
                if (!more(maxOf(pos, buf.length - PROGRAMME.length - 2))) break
                pos = 0
                continue
            }
            val kind = if (buf.startsWith("<$CHANNEL", s)) CHANNEL else PROGRAMME
            val end = blockEnd(buf, s, kind)
            if (end == NEED_MORE) {
                if (buf.length - s > MAX_BLOCK_CHARS) { pos = s + 1; continue }
                if (!more(s)) break
                pos = 0
                continue
            }
            // A start tag with another block start inside it was cut off; drop it and read from the next one.
            val nested = nextStart(buf, s + 1)
            if (nested in 0 until end) { pos = nested; continue }
            val block = buf.substring(s, end)
            pos = end
            if (kind == CHANNEL) {
                val channel = try { parseChannel(block) } catch (_: Exception) { null }
                if (channel != null) onChannel(channel)
            } else {
                val programme = try { parseProgramme(block, keepChannel, fromMs, untilMs) } catch (_: Exception) { null }
                if (programme != null) { onProgramme(programme); count++ }
            }
        }
        return count
    }

    private const val NEED_MORE = -1

    /** Index of the next `<channel` or `<programme` tag start at or after [from], or -1 (also when it may be cut off at the end of [s]). */
    private fun nextStart(s: String, from: Int): Int {
        var i = from
        while (true) {
            i = s.indexOf('<', i)
            if (i < 0) return -1
            for (name in listOf(CHANNEL, PROGRAMME)) {
                if (!s.startsWith("<$name", i)) continue
                val after = i + 1 + name.length
                if (after >= s.length) return i
                val c = s[after]
                if (c == '>' || c == '/' || c.isWhitespace()) return i
            }
            i++
        }
    }

    /** Index just past the block starting at [s], or [NEED_MORE] if its end isn't in [buf] yet. */
    private fun blockEnd(buf: String, s: Int, kind: String): Int {
        val gt = buf.indexOf('>', s)
        if (gt < 0) return NEED_MORE
        if (buf[gt - 1] == '/') return gt + 1
        val close = "</$kind>"
        val at = buf.indexOf(close, gt)
        return if (at < 0) NEED_MORE else at + close.length
    }

    private fun parseChannel(block: String): XmltvChannel? {
        val tag = block.substring(0, block.indexOf('>'))
        val id = attribute(tag, "id")?.takeIf { it.isNotBlank() } ?: return null
        val names = DISPLAY_NAME.findAll(block).map { text(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
        return XmltvChannel(id, names, icon(block))
    }

    private fun parseProgramme(block: String, keepChannel: (String) -> Boolean, fromMs: Long, untilMs: Long): GuideProgramme? {
        val tag = block.substring(0, block.indexOf('>'))
        val guideId = attribute(tag, "channel") ?: return null
        if (!keepChannel(guideId)) return null
        val startMs = parseTime(attribute(tag, "start")) ?: return null
        val endMs = parseTime(attribute(tag, "stop")) ?: return null
        if (endMs <= startMs || endMs <= fromMs || startMs >= untilMs) return null
        val title = TITLE.find(block)?.let { text(it.groupValues[1]) }?.takeIf { it.isNotEmpty() } ?: return null
        val description = DESC.find(block)?.let { text(it.groupValues[1]) }?.takeIf { it.isNotEmpty() }
        val genres = CATEGORY.findAll(block).map { text(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
        return GuideProgramme(guideId, startMs, endMs, title, description, genres, icon(block))
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

    private fun attribute(tag: String, name: String): String? {
        val regex = synchronized(attributeRegexes) {
            attributeRegexes.getOrPut(name) { Regex("""\s$name\s*=\s*(?:"([^"]*)"|'([^']*)')""") }
        }
        val m = regex.find(tag) ?: return null
        return unescape(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }

    private fun icon(block: String): String? =
        ICON.find(block)?.let { attribute(it.value, "src") }?.takeIf { it.isNotBlank() }

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
