package com.keyspaceapps.goodtv.data.tmdb

import kotlinx.serialization.Serializable

/** How a person is tied to a title. [TmdbPerson.role] is the character for [CAST], else null. */
@Serializable
enum class PersonJob { CAST, DIRECTOR, CREATOR }

@Serializable
data class TmdbPerson(val id: Long, val name: String, val role: String?, val job: PersonJob)

/** One title a person counts for; [date] is its release or first-air date, null when TMDB has none. */
data class PersonCredit(val kind: TmdbKind, val tmdbId: Long, val date: String?)

data class PersonMatch(val id: Long, val name: String)

/** Pure rules for who to show on a title and which of a person's credits count as real work. */
object PeopleRules {
    const val MAX_PEOPLE = 10

    private val SELF_ROLE = Regex("""^(him|her|them)sel(f|ves)\b|^self\b""")
    private val NON_WORK_GENRES = setOf(10767, 10763, 10764) // Talk, News, Reality
    private val COUNTED_CREW_JOBS = setOf("Director", "Producer", "Executive Producer", "Creator")
    private val SEARCH_DEPARTMENTS = setOf("Acting", "Directing", "Writing", "Production", "Creator")
    private const val MIN_ONE_WORD_POPULARITY = 10.0
    private const val MAX_MATCHES = 2

    fun moviePeople(c: TmdbCredits): List<TmdbPerson> {
        val people = LinkedHashMap<Long, TmdbPerson>()
        c.crew.filter { it.job == "Director" }.forEach { people.putIfAbsent(it.id, TmdbPerson(it.id, it.name, null, PersonJob.DIRECTOR)) }
        c.cast.sortedBy { it.order }.forEach { people.putIfAbsent(it.id, TmdbPerson(it.id, it.name, it.character.clean(), PersonJob.CAST)) }
        return people.values.take(MAX_PEOPLE)
    }

    fun tvPeople(t: TmdbTvPeople): List<TmdbPerson> {
        val people = LinkedHashMap<Long, TmdbPerson>()
        t.created_by.forEach { people.putIfAbsent(it.id, TmdbPerson(it.id, it.name, null, PersonJob.CREATOR)) }
        t.aggregate_credits?.cast.orEmpty().sortedBy { it.order }.forEach {
            people.putIfAbsent(it.id, TmdbPerson(it.id, it.name, it.roles.firstOrNull()?.character.clean(), PersonJob.CAST))
        }
        return people.values.take(MAX_PEOPLE)
    }

    /** The movies and shows [c] should count toward a person's search hits: real roles and real crew work, once each. */
    fun countedCredits(c: TmdbPersonCredits): List<PersonCredit> {
        val counted = c.cast.filter { countsAsCast(it) }.mapNotNull { credit(it.media_type, it.id, it.release_date, it.first_air_date) } +
            c.crew.filter { countsAsCrew(it) }.mapNotNull { credit(it.media_type, it.id, it.release_date, it.first_air_date) }
        val byTitle = LinkedHashMap<Pair<TmdbKind, Long>, PersonCredit>()
        for (credit in counted) {
            val key = credit.kind to credit.tmdbId
            val seen = byTitle[key]
            byTitle[key] = if (seen == null || (credit.date != null && (seen.date == null || credit.date > seen.date))) credit else seen
        }
        return byTitle.values.toList()
    }

    /**
     * At most two people whose name is what was typed, so a half-typed or common name finds nobody.
     * One word must be the whole name; two or more must each be a whole word of the name, so
     * "samuel jackson" finds Samuel L. Jackson but "tom cru" finds nobody.
     */
    fun confidentPeople(query: String, results: List<TmdbPersonResult>): List<PersonMatch> {
        val q = TitleCleaner.normalize(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        val oneWord = words.size == 1
        fun nameMatches(name: String): Boolean {
            val n = TitleCleaner.normalize(name)
            return n == q || (!oneWord && n.split(' ').toSet().containsAll(words))
        }
        return results
            .filter { nameMatches(it.name) && it.known_for_department in SEARCH_DEPARTMENTS }
            .filter { !oneWord || (it.popularity ?: 0.0) >= MIN_ONE_WORD_POPULARITY }
            .sortedByDescending { it.popularity ?: 0.0 }
            .take(MAX_MATCHES)
            .map { PersonMatch(it.id, it.name) }
    }

    private fun countsAsCast(c: TmdbPersonCast): Boolean {
        val tv = c.media_type == "tv"
        if (!tv && c.media_type != "movie") return false
        if (SELF_ROLE.containsMatchIn(c.character.orEmpty().lowercase().trim())) return false
        if (tv && (c.episode_count in 1..2 || c.genre_ids.any { it in NON_WORK_GENRES })) return false
        return true
    }

    private fun countsAsCrew(c: TmdbPersonCrew): Boolean {
        val tv = c.media_type == "tv"
        if (!tv && c.media_type != "movie") return false
        if (c.job !in COUNTED_CREW_JOBS && c.department != "Writing") return false
        return !(tv && c.episode_count in 1..2 && c.job != "Creator")
    }

    private fun credit(mediaType: String, id: Long, releaseDate: String?, firstAirDate: String?): PersonCredit? = when (mediaType) {
        "movie" -> PersonCredit(TmdbKind.MOVIE, id, releaseDate.takeUnless { it.isNullOrBlank() })
        "tv" -> PersonCredit(TmdbKind.TV, id, firstAirDate.takeUnless { it.isNullOrBlank() })
        else -> null
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
