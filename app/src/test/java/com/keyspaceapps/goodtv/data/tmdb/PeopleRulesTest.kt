package com.keyspaceapps.goodtv.data.tmdb

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleRulesTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()

    @Test fun movieDirectorsComeFirstThenCastByOrderAtMostTen() {
        val people = PeopleRules.moviePeople(json.decodeFromString<TmdbCredits>(fixture("movie_credits_361743.json")))
        assertEquals(10, people.size)
        assertEquals(listOf(PersonJob.DIRECTOR, PersonJob.DIRECTOR), people.take(2).map { it.job })
        assertEquals(listOf(10L, 11L), people.take(2).map { it.id })
        assertEquals(people.size, people.map { it.id }.toSet().size)
        assertTrue(people.drop(2).all { it.job == PersonJob.CAST })
        assertEquals(TmdbPerson(500, "Tom Cruise", "Maverick", PersonJob.CAST), people[2])
        assertEquals(listOf(100L, 101L), people.drop(3).take(2).map { it.id })
        // A blank character is no role.
        assertEquals(null, people.first { it.id == 100L }.role)
    }

    @Test fun tvCreatorsComeFirstThenMainCast() {
        val people = PeopleRules.tvPeople(json.decodeFromString<TmdbTvPeople>(fixture("tv_people_1396.json")))
        assertEquals(
            listOf(
                TmdbPerson(66633, "Vince Gilligan", null, PersonJob.CREATOR),
                TmdbPerson(17419, "Bryan Cranston", "Walter White", PersonJob.CAST),
                TmdbPerson(84497, "Aaron Paul", "Jesse Pinkman", PersonJob.CAST),
                TmdbPerson(99999, "No Role", null, PersonJob.CAST),
            ),
            people,
        )
    }

    @Test fun countedCreditsKeepRealWorkOnly() {
        val credits = PeopleRules.countedCredits(json.decodeFromString<TmdbPersonCredits>(fixture("person_credits_500.json")))
        assertEquals(
            listOf(
                PersonCredit(TmdbKind.MOVIE, 361743, "2022-05-24"),
                PersonCredit(TmdbKind.TV, 2004, "2006-04-04"),
                PersonCredit(TmdbKind.MOVIE, 2006, "2015-07-31"),
                PersonCredit(TmdbKind.TV, 2007, "2019-01-01"),
                PersonCredit(TmdbKind.MOVIE, 2009, "2008-08-08"),
            ),
            credits,
        )
    }

    @Test fun aTvCreatorCreditSurvivesAFewEpisodes() {
        val credits = PeopleRules.countedCredits(
            TmdbPersonCredits(crew = listOf(TmdbPersonCrew(1, "tv", "Creator", "Production", first_air_date = "2020-01-01", episode_count = 1))),
        )
        assertEquals(listOf(PersonCredit(TmdbKind.TV, 1, "2020-01-01")), credits)
    }

    @Test fun duplicatesKeepTheLatestDate() {
        val credits = PeopleRules.countedCredits(
            TmdbPersonCredits(
                cast = listOf(TmdbPersonCast(1, "movie", "A", release_date = "2001-01-01")),
                crew = listOf(TmdbPersonCrew(1, "movie", "Director", "Directing", release_date = "2002-01-01"), TmdbPersonCrew(1, "movie", "Producer", "Production", release_date = "")),
            ),
        )
        assertEquals(listOf(PersonCredit(TmdbKind.MOVIE, 1, "2002-01-01")), credits)
    }

    @Test fun confidentPeopleRules() {
        fun r(id: Long, name: String, dept: String?, pop: Double?) = TmdbPersonResult(id, name, dept, pop)
        assertEquals(emptyList<PersonMatch>(), PeopleRules.confidentPeople("Chris", listOf(r(1, "Chris", "Acting", 5.0))))
        assertEquals(listOf(PersonMatch(1, "Chris")), PeopleRules.confidentPeople("Chris", listOf(r(1, "Chris", "Acting", 10.0))))
        val tom = json.decodeFromString<TmdbSearchPage<TmdbPersonResult>>(fixture("search_person_tom_cruise.json")).results
        assertEquals(emptyList<PersonMatch>(), PeopleRules.confidentPeople("tom cru", tom))
        assertEquals(listOf(PersonMatch(500, "Tom Cruise")), PeopleRules.confidentPeople("TOM CRUISE", tom.take(1)))
        assertEquals(listOf(PersonMatch(500, "Tom Cruise")), PeopleRules.confidentPeople("tom cruise", tom.take(1)))
        // The "Crew" namesake is not a known department.
        assertEquals(listOf(PersonMatch(500, "Tom Cruise")), PeopleRules.confidentPeople("tom cruise", tom))
        assertEquals(emptyList<PersonMatch>(), PeopleRules.confidentPeople("tom cruise", tom.drop(1)))
        val three = listOf(r(1, "Ann Lee", "Acting", 3.0), r(2, "Ann Lee", "Directing", 9.0), r(3, "Ann Lee", "Writing", 6.0))
        assertEquals(listOf(PersonMatch(2, "Ann Lee"), PersonMatch(3, "Ann Lee")), PeopleRules.confidentPeople("ann lee", three))
        assertEquals(emptyList<PersonMatch>(), PeopleRules.confidentPeople("  ", three))
        // Two or more words: every typed word is a whole word of the name, so a middle initial can be left out.
        val jackson = listOf(r(2231, "Samuel L. Jackson", "Acting", 40.0), r(9, "Samuel Jacksonville", "Acting", 50.0))
        assertEquals(listOf(PersonMatch(2231, "Samuel L. Jackson")), PeopleRules.confidentPeople("samuel jackson", jackson))
        assertEquals(listOf(PersonMatch(2231, "Samuel L. Jackson")), PeopleRules.confidentPeople("Jackson, Samuel", jackson))
        assertEquals(emptyList<PersonMatch>(), PeopleRules.confidentPeople("samuel jack", jackson))
        assertEquals(emptyList<PersonMatch>(), PeopleRules.confidentPeople("samuel l jackson jr", jackson))
        // One word still needs the whole name and the popularity.
        assertEquals(emptyList<PersonMatch>(), PeopleRules.confidentPeople("jackson", jackson))
        // Word matches keep the department allow-list, popularity order and the limit of two.
        val many = listOf(
            r(1, "Ann Marie Lee", "Acting", 3.0), r(2, "Ann B. Lee", "Directing", 9.0), r(3, "Lee Ann", "Writing", 6.0), r(4, "Ann Lee", "Crew", 99.0),
        )
        assertEquals(listOf(PersonMatch(2, "Ann B. Lee"), PersonMatch(3, "Lee Ann")), PeopleRules.confidentPeople("ann lee", many))
    }
}
