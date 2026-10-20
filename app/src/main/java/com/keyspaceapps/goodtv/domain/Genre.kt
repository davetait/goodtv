package com.keyspaceapps.goodtv.domain

import java.text.Normalizer

/**
 * One genre list for both screens. Movies get theirs from TMDB genre ids ([fromTmdbMovieGenre]);
 * series from the panel's free-text genre in whatever language it uses ([fromPanelText]).
 */
enum class Genre {
    ACTION, ADVENTURE, ANIMATION, COMEDY, CRIME, DOCUMENTARY, DRAMA, FAMILY, FANTASY, HISTORY, HORROR,
    KIDS, MUSIC, MYSTERY, NEWS, POLITICS, REALITY, ROMANCE, SCIFI, SOAP, SPORT, TALK, THRILLER, TV_MOVIE,
    WAR, WESTERN;

    companion object {
        /** TMDB `/genre/movie/list` ids (checked against the live API). */
        fun fromTmdbMovieGenre(id: Int): Genre? = when (id) {
            28 -> ACTION; 12 -> ADVENTURE; 16 -> ANIMATION; 35 -> COMEDY; 80 -> CRIME; 99 -> DOCUMENTARY
            18 -> DRAMA; 10751 -> FAMILY; 14 -> FANTASY; 36 -> HISTORY; 27 -> HORROR; 10402 -> MUSIC
            9648 -> MYSTERY; 10749 -> ROMANCE; 878 -> SCIFI; 10770 -> TV_MOVIE; 53 -> THRILLER
            10752 -> WAR; 37 -> WESTERN
            else -> null
        }

        /**
         * Genres in a panel's genre text such as "Drama / Sci-Fi & Fantasy", "Δράμα", "akcja i przygoda"
         * or "ژانر: اجتماعی، درام". Each part is matched against word stems in the languages real panels
         * use; unknown words are ignored.
         */
        fun fromPanelText(text: String?): Set<Genre> {
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
    }
}
