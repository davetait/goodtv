// Frozen copy of the b5b986d conversion: the oracle for equivalence tests. Never edit.
package com.keyspaceapps.goodtv.legacy

import com.keyspaceapps.goodtv.domain.DisplayName
import com.keyspaceapps.goodtv.domain.Genre
import com.keyspaceapps.goodtv.domain.Genre.*
import java.text.Normalizer
import java.util.Locale

object LegacyDisplayTitle {
    fun parse(raw: String, live: Boolean = false): DisplayName {
        val quality4k = raw.contains("⁴ᴷ") || raw.contains("ᵁᴴᴰ") || raw.contains("³⁸⁴⁰")
        var s = stripDecorations(raw)
        if (s.isEmpty()) return DisplayName(raw.trim(), null, null, null)

        var tagTokens = emptyList<String>()
        tagOf(s, live)?.let { (tag, rest) -> if (rest.isNotBlank()) { tagTokens = tokens(tag); s = rest.trim() } }
        // Panels sometimes repeat the tag: "NL - NL - Spotlight", "4K-AR - AR- The Green Mile".
        if (!live && tagTokens.isNotEmpty()) {
            REPEAT_TAG.find(s)?.takeIf { m -> tokens(m.groupValues[1]).all(::isKnownToken) && s.length > m.value.length }?.let { m ->
                tagTokens = (tagTokens + tokens(m.groupValues[1])).distinct(); s = s.substring(m.value.length).trim()
            }
        }

        var year: Int? = null
        if (!live) {
            // Panels write a missing year as "None": "PREDICTION.None", "X (None)". Not "None Shall Escape".
            s = s.removeSuffix(".None").replace(NONE_SUFFIX, "")
            if (' ' !in s && ('.' in s || '_' in s)) s = s.replace('.', ' ').replace('_', ' ')
            PAREN_YEAR.find(s)?.let { m -> year = m.groupValues[1].toInt(); s = s.substring(0, m.range.first) }
                ?: TRAILING_YEAR.find(s)?.takeIf { it.groupValues[1].toInt() <= latestYear() && !EVENT_DATE.containsMatchIn(s) }?.let { m ->
                    year = m.groupValues[1].toInt(); s = s.substring(0, m.range.first)
                }
        }

        var trailing4k = false
        while (true) {
            val m = TRAILING_TAG.find(s.trim()) ?: break
            if (m.value.trim().uppercase().let { it == "4K" || it == "UHD" || it.startsWith("2160") || it.startsWith("3840") }) trailing4k = true
            s = s.trim().substring(0, m.range.first)
        }
        s = s.trim().trimEnd('-', ':', '|', '.', ',').trim()
        if (s.isEmpty()) return DisplayName(stripDecorations(raw).ifEmpty { raw.trim() }, null, null, null)

        val quality = when {
            tagTokens.any { it == "8K" } -> "8K"
            tagTokens.any { it in FOUR_K } || quality4k || trailing4k -> "4K"
            else -> null
        }
        return DisplayName(s, year, languageOf(tagTokens, live), quality)
    }

    /** "FR - Breaking Bad - S01E01" under series "Breaking Bad" → null (nothing beyond the number). */
    fun episodeTitle(raw: String, seriesTitle: String): String? {
        var s = parse(raw).title
        if (s.startsWith(seriesTitle, ignoreCase = true)) s = s.substring(seriesTitle.length)
        // "The Bear (US) - S01E01 - System": the show's own tag is left in front once its name is gone.
        s = LEADING_TAG.replace(s, "")
        s = EPISODE_CODE.replace(s, " ").replace(REPEATED_SEPARATORS, " - ").replace(EDGE_SEPARATORS, "").replace(SPACES, " ")
        return s.ifEmpty { null }
    }

    // ---- tag ----

    // Movies/series: any caps tag before " - " or " | " (the panel convention) or " -" with no space
    // after it ("AR-DUB -We Bare Bears"), "[MULTI] ", "|EN| ", or a two-letter code with a colon
    // ("UK: "); longer colon prefixes are real titles ("FBI: Most Wanted").
    private val VOD_TAG = Regex("""^(?:\[([^\]]{1,15})]\s*|\|([^|]{1,10})\|\s*|([A-Z0-9][A-Z0-9+\-]{0,13})\s+[-|]\s+|([A-Z]{2}):\s+|([A-Z0-9][A-Z0-9+\-]{0,13})\s+-(?=\S))""")
    private val KNOWN_ONLY_TAG = Regex("""^([A-Z0-9][A-Z0-9+\-]{0,13}?)\s*(?:-|:)\s+""")
    private val REPEAT_TAG = Regex("""^([A-Z0-9][A-Z0-9+\-]{0,13}?)\s*-\s+""")
    // Channels: a short caps tag before ":" or " | ".
    private val LIVE_TAG = Regex("""^([A-Z0-9+/][A-Z0-9+/\- ]{0,12}?)\s*(?::|\s\|)\s+""")

    private fun tagOf(s: String, live: Boolean): Pair<String, String>? {
        if (live) {
            // Channel tags are short caps codes before ":" or " | " on ~70% of a real panel's channels
            // (299 distinct ones), so any such code counts.
            LIVE_TAG.find(s)?.let { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { return it to s.substring(m.range.last + 1) } }
            return null
        }
        VOD_TAG.find(s)?.let { m -> return m.groupValues.drop(1).first { it.isNotEmpty() } to s.substring(m.range.last + 1) }
        // Less regular forms, only when every token is a known code: "SE- Quick", "AR-SUBS: Secret of the Wings".
        KNOWN_ONLY_TAG.find(s)?.takeIf { m -> tokens(m.groupValues[1]).all(::isKnownToken) }?.let { m -> return m.groupValues[1] to s.substring(m.range.last + 1) }
        return null
    }

    private fun tokens(tag: String) = tag.uppercase().split('-', ' ', '/').filter { it.isNotEmpty() }.let { parts ->
        // "24/7" is one token, not "24" and "7".
        if (tag.contains("24/7")) parts.filterNot { it == "24" || it == "7" } + "24/7" else parts
    }

    private fun isKnownToken(t: String) =
        t in DROP || t in FOUR_K || t == "8K" || t in SUB || t in DUB || t in EXTRA_LANG || t in LANGUAGE_WORDS || (t.length == 2 && t in ISO) || t == "S" || t == "D"

    /**
     * Language codes from the tag in panel order, plus SUB/DUB: "AR-SUBS" → "AR-SUB", "4K-FR-HDR" → "FR",
     * "TELUGU" → "TE". Unknown three-letter codes are kept for channels ("ARG", "PAK"), which use them for countries.
     */
    private fun languageOf(tokens: List<String>, live: Boolean): String? {
        val out = mutableListOf<String>()
        var marker: String? = null
        tokens.forEachIndexed { i, t ->
            when {
                t in DROP || t in FOUR_K || t == "8K" -> Unit
                t == "SUB" || t == "SUBS" || (t == "S" && i > 0) -> marker = "SUB"
                t == "DUB" || (t == "D" && i > 0) -> marker = "DUB"
                t in EXTRA_LANG -> out += t
                t in LANGUAGE_WORDS -> out += LANGUAGE_WORDS.getValue(t)
                live && t.length == 3 && t.all { it in 'A'..'Z' } -> out += t
                t.length == 2 && t.all { it in 'A'..'Z' } && (t in ISO || tokens.size > 1) -> out += t
            }
        }
        if (out.isEmpty()) return null
        return (out + listOfNotNull(marker)).distinct().joinToString("-")
    }

    // ---- title ----

    private val PAREN_YEAR = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
    private val TRAILING_YEAR = Regex("""[\s.]((?:19|20)\d{2})$""")
    private val TRAILING_TAG = Regex(
        """(?:\s*\([A-Z]{2,3}\)|\s*\((?:[A-Za-z]{2,3}\s)?(?:Sub|Subs|Dub)\)|\s*\((?:HD|FHD|UHD|4K)\)|\s+(?:4K|UHD|FHD|HD|SD|HEVC|H\.?265|MULTI|RAW|\d{3,4}[pP]))$""",
        RegexOption.IGNORE_CASE,
    )
    // A full date ("08.12.2024") means a dated event; its year is not a release year.
    private val EVENT_DATE = Regex("""\b\d{1,2}\.\d{1,2}\.\d{2,4}\b""")
    private val EDGE_SEPARATORS = Regex("""^[\s\-:|.,]+|[\s\-:|.,]+$""")
    private val LEADING_TAG = Regex("""^\s*\([^)]{1,12}\)""")
    private val REPEATED_SEPARATORS = Regex("""(\s*[\-–|]\s*){2,}""")
    // Compiled once: titles are parsed for every channel of a ~12k lineup.
    private val NONE_SUFFIX = Regex("""\s*\(None\)$""")
    private val SPACES = Regex("""\s+""")
    private val DECORATIONS = Regex("""[ᴀ-ᶿ⁰-₟²³¹◉●]""")
    private val WIDE_SPACES = Regex("""[\s\u00A0\u2007\u202F]+""")
    private val EPISODE_CODE = Regex("""\b[Ss]\d{1,2}\s?[Ee]\d{1,3}\b""")

    /** Superscript/small-capital decorations ("ᴿᴬᵂ", "³⁸⁴⁰ᴾ", "◉") removed, spaces collapsed. */
    private fun stripDecorations(raw: String): String = raw
        .replace(DECORATIONS, "")
        // Non-breaking spaces (some panels put them after the tag) count as spaces; plain \s misses them.
        .replace(WIDE_SPACES, " ")
        .trim()

    private fun latestYear() = java.time.Year.now().value + 1

    private val FOUR_K = setOf("4K", "UHD", "2160P", "3840P")
    private val SUB = setOf("SUB", "SUBS")
    private val DUB = setOf("DUB")

    /** Tags that say where a title is filed, not its language: quality, platforms, categories, bundles. */
    private val DROP = setOf(
        "HD", "FHD", "SD", "HDR", "HDR10", "DV", "HEVC", "H265", "RAW", "VIP", "PREM", "PREMIUM", "GOLD", "PLUS", "TOP",
        "SOC", "SPORT", "SPORTS", "ANM", "DOC", "KID", "KIDS", "POD", "NF", "AMZ", "D+", "A+", "DSC+", "UNV", "PRIME",
        "TUBI", "JOYN", "PLAY", "PLAY+", "TV", "NEXT", "SAT", "24/7", "NBA", "NFL", "NHL", "MLB", "PPV", "M+", "EVENT",
        "MOVIES", "FILM", "VO", "RK", "T2", "GO", "AV", "NM", "YP", "STC", "MEO", "DSTV", "AMP", "AFR", "F",
        // Channel bundles and providers seen on a real panel.
        "OSN", "SPO", "SKY", "SKYGO", "NOW", "WOW", "VIX", "GOBX", "DVB", "DVB-T", "ZG", "VD", "RX", "SS", "SU", "TY", "TK",
        "TF", "RC", "FL", "FZ", "CRB", "CON", "GEN", "ONE", "CHAMP", "PLAYER", "MUSIC", "ZINA", "ATP", "UFC", "CBC", "NCAAM",
        "COITED", "M", "L1", "L2", "F1", "V+", "C+", "P+", "PB", "SRI", "CABO", "SPFL", "LSV", "ETHO", "CAM", "BU", "MALI",
    )

    /** Provider language/region codes that aren't ISO two-letter codes. */
    private val EXTRA_LANG = setOf("EXYU", "EX", "QC", "SHAM", "IRQ", "LATAM")

    /** Channel tags that spell the language out. */
    private val LANGUAGE_WORDS = mapOf(
        "ENGLISH" to "EN", "HINDI" to "HI", "TAMIL" to "TA", "PUNJABI" to "PA", "MALAYALAM" to "ML", "TELUGU" to "TE",
        "GUJARATI" to "GU", "KANNADA" to "KN", "MARATHI" to "MR", "BENGALI" to "BN", "BHOJPURI" to "BHO", "ODIA" to "OR",
    )

    private val ISO: Set<String> = (Locale.getISOCountries().asList() + Locale.getISOLanguages().filter { it.length == 2 })
        .map { it.uppercase() }.toSet()
}

// ---- Genre.fromPanelText, fold, STEMS and FOLDED_STEMS ----

fun legacyGenres(text: String?): Set<Genre> {
    if (text.isNullOrBlank()) return emptySet()
    val parts = text.split(',', '/', '&', '|', '،', ';').map(::fold).filter { it.isNotEmpty() }
    return buildSet {
        for (part in parts) {
            val words = part.split(' ')
            for ((genre, stems) in FOLDED_STEMS) {
                if (stems.any { stem -> if (stem.startsWith('=')) stem.substring(1) in words else part.contains(stem) }) add(genre)
            }
        }
    }
}

/** Lower case, accents and punctuation removed, so "Comédie" and "comedie" compare equal. */
private fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

// Written naturally and folded once below, so "عائلي" or "семейн" compare like the folded input.
// "=word" must match a whole word: short words like Turkish "suç" (crime) or "aile" (family)
// would otherwise match inside unrelated words.
private val STEMS: List<Pair<Genre, List<String>>> = listOf(
    ACTION to listOf("action", "accion", "azione", "aksiyon", "akcj", "actie", "acao", "actiune", "δρασ", "боевик", "екшън", "экшн", "אקשן", "حركة", "اکشن", "toiminta"),
    ADVENTURE to listOf("adventure", "aventur", "avventur", "macera", "przygod", "avontuur", "abenteuer", "περιπετει", "приключен", "הרפתקא", "مغامرة", "ماجراجو", "eventyr", "seikkailu"),
    ANIMATION to listOf("animat", "animac", "animaz", "animer", "animas", "κινουμενα σχεδια", "анимац", "мультфильм", "אנימציה", "رسوم متحركة", "انیمیشن", "cartoon"),
    COMEDY to listOf("comed", "commed", "komed", "komod", "κωμωδ", "комеди", "קומדיה", "كوميدي", "کمدی", "طنز", "vigjatek", "sitcom", "stand up"),
    CRIME to listOf("crime", "crimen", "crimin", "=crima", "krimi", "krymina", "kriminal", "misdaad", "=suc", "αστυνομ", "криминал", "פשע", "جريمة", "جنایی", "bunugyi", "rikos", "policial", "policier", "investigation"),
    DOCUMENTARY to listOf("document", "dokument", "ντοκυμαντ", "ντοκιμαντ", "документ", "דוקו", "תעודה", "وثائقي", "مستند", "belgesel", "docu"),
    DRAMA to listOf("drama", "drame", "dramma", "dramat", "=dram", "draama", "δραμα", "драма", "דרמה", "دراما", "درام"),
    FAMILY to listOf("family", "familia", "familie", "familial", "famiglia", "familij", "familj", "οικογενει", "семейн", "משפחה", "عائلي", "خانوادگی", "=aile"),
    FANTASY to listOf("fantasy", "fantasia", "fantastique", "fantazi", "fantastic", "φαντασ", "фэнтези", "фентъзи", "פנטזיה", "فانتازيا", "فانتزی"),
    HISTORY to listOf("histor", "storic", "tarih", "ιστορ", "истор", "היסטוריה", "تاریخی", "تاريخ"),
    HORROR to listOf("horror", "horreur", "terror", "korku", "τρομου", "ужас", "אימה", "رعب", "ترسناک"),
    KIDS to listOf("kids", "=kid", "children", "enfant", "infantil", "=born", "=børn", "παιδικ", "детск", "ילדים", "أطفال", "اطفال", "cocuk", "kinder"),
    MUSIC to listOf("music", "musik", "μουσικ", "музык", "موسیقی", "موزیکال"),
    MYSTERY to listOf("myster", "mister", "tajemnic", "gizem", "μυστηρ", "мистери", "детектив", "מסתורין", "غموض", "معمایی", "rejtely", "mystik"),
    NEWS to listOf("news", "actualit", "noticias", "=nachrichten", "أخبار", "اخبار"),
    POLITICS to listOf("politic", "politik", "πολιτικ", "политик", "פוליטיקה", "سياسة", "سیاسی"),
    REALITY to listOf("reality", "ριαλιτι", "ריאליטי", "gercek", "virkelighed", "tosi tv", "واقع", "رئالیتی", "realite"),
    ROMANCE to listOf("romance", "romant", "ρομαντ", "романт", "רומנטי", "عاشقانه", "رومانسي"),
    SCIFI to listOf("sci fi", "science fiction", "scifi", "=sf", "ciencia ficcion", "fantascienza", "bilim kurgu", "επ φαντασ", "=нф", "научна фантастика", "מדע בדיוני", "خيال علمي", "علمی تخیلی"),
    SOAP to listOf("soap", "telenovela", "pembe dizi", "סבון", "صابونية"),
    SPORT to listOf("sport", "ورزشی"),
    TALK to listOf("talk", "تاک شو", "حوار", "דיבורים"),
    THRILLER to listOf("thriller", "suspense", "מתח", "θριλερ", "триллер", "gerilim"),
    WAR to listOf("=war", "guerra", "guerre", "krieg", "=krig", "savas", "razboi", "πολεμ", "военн", "מלחמה", "حرب", "جنگی"),
    WESTERN to listOf("western", "γουεστερν", "вестерн", "faroeste", "vastern", "vahsi", "غربي"),
)

private val FOLDED_STEMS: List<Pair<Genre, List<String>>> = STEMS.map { (genre, stems) ->
    genre to stems.map { if (it.startsWith('=')) "=" + fold(it.substring(1)) else fold(it) }
}

// ---- tmdbIdOf ----

fun legacyTmdbIdOf(raw: String?): Long? =
    raw?.trim()?.let { Regex("""^\d+""").find(it)?.value }?.toLongOrNull()?.takeIf { it > 0 }
