package com.keyspaceapps.goodtv.domain

import com.keyspaceapps.goodtv.domain.Genre.ACTION
import com.keyspaceapps.goodtv.domain.Genre.ADVENTURE
import com.keyspaceapps.goodtv.domain.Genre.ANIMATION
import com.keyspaceapps.goodtv.domain.Genre.COMEDY
import com.keyspaceapps.goodtv.domain.Genre.CRIME
import com.keyspaceapps.goodtv.domain.Genre.DOCUMENTARY
import com.keyspaceapps.goodtv.domain.Genre.DRAMA
import com.keyspaceapps.goodtv.domain.Genre.FAMILY
import com.keyspaceapps.goodtv.domain.Genre.FANTASY
import com.keyspaceapps.goodtv.domain.Genre.MYSTERY
import com.keyspaceapps.goodtv.domain.Genre.POLITICS
import com.keyspaceapps.goodtv.domain.Genre.SCIFI
import com.keyspaceapps.goodtv.domain.Genre.WAR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inputs are genre strings seen on a real panel. */
class GenreTest {
    private fun check(text: String, vararg expected: Genre) = assertEquals("'$text'", expected.toSet(), Genre.fromPanelText(text))

    @Test fun englishAndTmdbStyleComposites() {
        check("Drama", DRAMA)
        check("Drama / Sci-Fi & Fantasy", DRAMA, SCIFI, FANTASY)
        check("Action & Adventure, Animation", ACTION, ADVENTURE, ANIMATION)
        check("War & Politics", WAR, POLITICS)
    }

    @Test fun otherLanguages() {
        check("Δράμα", DRAMA)
        check("Περιπέτεια - Δράσης", ADVENTURE, ACTION)
        check("Επ. Φαντασία - Φαντασίας", SCIFI, FANTASY)
        check("Akcja i Przygoda", ACTION, ADVENTURE)
        check("Comédie", COMEDY)
        check("Komödie", COMEDY)
        check("Misdaad", CRIME)
        check("Suç", CRIME)
        check("Aile", FAMILY)
        check("دراما", DRAMA)
        check("ژانر: اجتماعی، درام، خانوادگی", DRAMA, FAMILY)
        check("מדע בדיוני ופנטזיה", SCIFI, FANTASY)
        check("Боевик и Приключения", ACTION, ADVENTURE)
        check("Dokumentarfilm", DOCUMENTARY)
        check("Mistério", MYSTERY)
        // Letters NFD does not decompose (ł, ø) and stems that fold (ئ, й) must still match.
        check("Kryminał", CRIME)
        check("عائلي", FAMILY)
        check("Семейный", FAMILY)
        check("Børn", Genre.KIDS)
    }

    @Test fun shortWordsOnlyMatchWholeWords() {
        // "aile" inside "Failed", "suc" inside "Success", "sf" inside "Transformers": none should match.
        check("Failed Success Transformers")
        check("Dram", DRAMA)
        check("SF", SCIFI)
    }

    @Test fun blankAndUnknown() {
        check("")
        check("Podcast")
        assertEquals(emptySet<Genre>(), Genre.fromPanelText(null))
    }

    @Test fun tmdbMovieIds() {
        assertEquals(SCIFI, Genre.fromTmdbMovieGenre(878))
        assertEquals(Genre.TV_MOVIE, Genre.fromTmdbMovieGenre(10770))
        assertNull(Genre.fromTmdbMovieGenre(1))
    }
}
