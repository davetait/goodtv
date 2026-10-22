package com.keyspaceapps.goodtv.ui.home

import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.repo.SyncManager
import com.keyspaceapps.goodtv.data.settings.AppSettings
import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceRegistry
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.tmdb.PeopleRepository
import com.keyspaceapps.goodtv.data.tmdb.TmdbApi
import com.keyspaceapps.goodtv.data.tmdb.TmdbCredential
import com.keyspaceapps.goodtv.domain.Catalog
import com.keyspaceapps.goodtv.domain.Category
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.Movie
import com.keyspaceapps.goodtv.domain.Series
import com.keyspaceapps.goodtv.domain.SourceRecord
import com.keyspaceapps.goodtv.testing.FakeChannelSource
import com.keyspaceapps.goodtv.testing.FakeLibraryStore
import com.keyspaceapps.goodtv.testing.InMemoryDataStore
import com.keyspaceapps.goodtv.testing.InMemoryPeopleCache
import com.keyspaceapps.goodtv.ui.nav.PersonSearchKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeLibraryStore()
    private lateinit var repo: LibraryRepository
    private var sourceId = 0L

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val registry = SourceRegistry(listOf(FakeChannelSource()))
        repo = LibraryRepository(store, registry, SyncManager(store, registry, CoroutineScope(dispatcher)), AppSettings(InMemoryDataStore()))
        sourceId = store.insertSource(SourceRecord(0, SourceType.XTREAM, "A", SourceConfig.Xtream("http://s", "u", "p"), true, null, null))
        store.replaceCatalog(
            sourceId,
            Catalog(
                categories = listOf(Category(sourceId, ContentKind.LIVE, "c1", "News"), Category(sourceId, ContentKind.MOVIE, "m1", "Action")),
                live = listOf(LiveChannel(sourceId, "l1", "c1", "Matrix News", null, 1)),
                movies = listOf(
                    Movie(sourceId, "m1", "m1", "The Matrix", "http://img/m1", "mkv", null, null),
                    Movie(sourceId, "m2", "m1", "Matrix Reloaded", null, "mkv", null, null),
                    Movie(sourceId, "m3", "m1", "Heat", null, "mkv", null, null),
                ),
                series = listOf(Series(sourceId, "s1", null, "Matrix Stories", "http://img/s1", null, null)),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
    }

    /** Keeps [HomeSearchViewModel.results] subscribed, as the screen does. */
    private fun kotlinx.coroutines.test.TestScope.collect(vm: HomeSearchViewModel) = backgroundScope.launch { vm.results.collect {} }

    @Test
    fun blankQueryHasNoResults() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("   ")
        advanceUntilIdle()
        assertNull(vm.results.value)
    }

    @Test
    fun allScopeGroupsLiveThenMoviesThenSeriesWithPrefixMatchesFirst() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery(" matrix ")
        advanceUntilIdle()
        val results = vm.results.value!!
        assertEquals(listOf("Matrix News", "Matrix Reloaded", "The Matrix", "Matrix Stories"), results.map { it.title })
        assertEquals(listOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.MOVIE, ContentKind.SERIES), results.map { it.kind })
        assertEquals("http://img/m1", results[2].imageUrl)
    }

    @Test
    fun scopeLimitsToOneKind() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("matrix")
        vm.setScope(SearchScope.SERIES)
        advanceUntilIdle()
        assertEquals(listOf("Matrix Stories"), vm.results.value!!.map { it.title })

        vm.setScope(SearchScope.LIVE)
        advanceUntilIdle()
        assertEquals(listOf("Matrix News"), vm.results.value!!.map { it.title })
    }

    @Test
    fun waitsForTypingToPauseBeforeSearching() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("heat")
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 50)
        runCurrent()
        assertNull(vm.results.value)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("Heat"), vm.results.value!!.map { it.title })
    }

    @Test
    fun noMatchGivesEmptyListNotNull() = runTest(dispatcher) {
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("zzz")
        advanceUntilIdle()
        assertEquals(emptyList<SearchResult>(), vm.results.value)
    }

    @Test
    fun hiddenCategoriesAreLeftOut() = runTest(dispatcher) {
        store.setCategoriesHidden(sourceId, ContentKind.MOVIE, listOf("m1"), hidden = true)
        val vm = HomeSearchViewModel(repo)
        collect(vm)
        vm.setQuery("matrix")
        vm.setScope(SearchScope.MOVIES)
        advanceUntilIdle()
        assertEquals(emptyList<SearchResult>(), vm.results.value)
    }

    // --- Person sections, against a fake TMDB server ---

    private val server = MockWebServer()
    private val routes = mutableMapOf<String, MockResponse>()
    private val requests = mutableListOf<String>()

    private fun fixture(name: String) = javaClass.getResource("/tmdb/$name")!!.readText()
    private fun ok(body: String) = MockResponse(code = 200, body = body)
    private fun movie(id: String, tmdbId: Long, name: String = "Movie $tmdbId") = Movie(sourceId, id, "m1", name, null, "mkv", null, null, tmdbId = tmdbId)
    private fun show(id: String, tmdbId: Long, name: String = "Show $tmdbId") = Series(sourceId, id, null, name, null, null, null, tmdbId = tmdbId)

    /** Library titles the people cache can match; the fixture's credits for 500 count 361743 (2022), 2006 (2015), 2009 (2008) and series 2007 (2019). */
    private fun peopleRepo(movies: List<Movie>, series: List<Series> = emptyList(), io: CoroutineDispatcher = dispatcher): PeopleRepository {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = request.url.encodedPath + (request.url.queryParameter("query")?.let { "?$it" } ?: "")
                synchronized(requests) { requests += key }
                return routes[key] ?: MockResponse(code = 404, body = "{}")
            }
        }
        server.start()
        val api = TmdbApi(OkHttpClient(), TmdbCredential.ApiKey("k"), baseUrl = server.url("/3/"), io = io, sleep = {})
        return PeopleRepository(api, InMemoryPeopleCache(movies, series), language = { "en-US" })
    }

    private fun tomCruiseRoutes() {
        routes["/3/search/person?Tom Cruise"] = ok(fixture("search_person_tom_cruise.json"))
        routes["/3/search/person?tom cru"] = ok(fixture("search_person_tom_cruise.json"))
        routes["/3/person/500/combined_credits"] = ok(fixture("person_credits_500.json"))
    }

    private fun creditsJson(vararg movieIds: Long) =
        """{"id":1,"cast":[""" + movieIds.joinToString(",") { """{"id":$it,"media_type":"movie","character":"Lead","release_date":"2020-01-01","genre_ids":[18]}""" } + """],"crew":[]}"""

    private fun kotlinx.coroutines.test.TestScope.collectAll(vm: HomeSearchViewModel) {
        backgroundScope.launch { vm.results.collect {} }
        backgroundScope.launch { vm.people.collect {} }
    }

    private fun titlesOf(section: PersonSection) = (section as PersonSection.Found).titles.map { it.title }

    @Test
    fun typedPersonNameAddsASection() = runTest(dispatcher) {
        tomCruiseRoutes()
        val people = peopleRepo(listOf(movie("p1", 361743, "Top Gun: Maverick"), movie("p2", 2009, "Tropic"), movie("p3", 2006, "Rogue")))
        val vm = HomeSearchViewModel(repo, people)
        collectAll(vm)
        vm.setQuery("Tom Cruise")
        advanceUntilIdle()
        val sections = vm.people.value
        assertEquals(1, sections.size)
        val found = sections[0] as PersonSection.Found
        assertEquals(500L, found.personId)
        assertEquals("Tom Cruise", found.name)
        // Newest credit first.
        assertEquals(listOf("Top Gun: Maverick", "Rogue", "Tropic"), found.titles.map { it.title })
        assertEquals(ContentKind.MOVIE, found.titles[0].kind)
        assertEquals("p1", found.titles[0].id)
    }

    @Test
    fun partialNameAddsNoSection() = runTest(dispatcher) {
        tomCruiseRoutes()
        val vm = HomeSearchViewModel(repo, peopleRepo(listOf(movie("p1", 361743))))
        collectAll(vm)
        vm.setQuery("tom cru")
        advanceUntilIdle()
        assertEquals(emptyList<PersonSection>(), vm.people.value)
        assertNotNull(vm.results.value)
    }

    @Test
    fun personSectionNeverDelaysTitleResults() = runTest(dispatcher) {
        tomCruiseRoutes()
        routes["/3/search/person?heat"] = MockResponse.Builder().code(200)
            .body("""{"page":1,"results":[{"id":500,"name":"Heat","known_for_department":"Acting","popularity":45.2}]}""")
            .headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).build()
        // Real IO threads, so the slow TMDB call can't hold up the test dispatcher.
        val vm = HomeSearchViewModel(repo, peopleRepo(listOf(movie("p1", 361743)), io = Dispatchers.IO))
        collectAll(vm)
        vm.setQuery("heat")
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf("Heat"), vm.results.value!!.map { it.title })
        assertEquals(emptyList<PersonSection>(), vm.people.value)
        // Let the slow call finish before the test ends, so nothing outlives it.
        assertEquals(500L, (vm.people.first { it.isNotEmpty() }.single() as PersonSection.Found).personId)
    }

    @Test
    fun pinnedPersonUsesItsIdNotTheName() = runTest(dispatcher) {
        tomCruiseRoutes()
        routes["/3/person/777/combined_credits"] = ok(creditsJson(3001))
        val people = peopleRepo(listOf(movie("p1", 361743, "Top Gun: Maverick"), movie("p2", 3001, "The Other One")))
        val vm = HomeSearchViewModel(repo, people, PersonSearchKey(777, "Tom Cruise"))
        collectAll(vm)
        assertEquals("Tom Cruise", vm.query.value)
        advanceUntilIdle()
        val sections = vm.people.value
        assertEquals(1, sections.size)
        assertEquals(777L, (sections[0] as PersonSection.Found).personId)
        assertEquals(listOf("The Other One"), titlesOf(sections[0]))
        assertFalse(requests.any { it.startsWith("/3/search/person") })
    }

    @Test
    fun editingThePinnedQueryResolvesByName() = runTest(dispatcher) {
        tomCruiseRoutes()
        routes["/3/person/777/combined_credits"] = ok(creditsJson(3001))
        routes["/3/search/person?Nicole Kidman"] = ok("""{"page":1,"results":[{"id":900,"name":"Nicole Kidman","known_for_department":"Acting","popularity":30.0}]}""")
        routes["/3/person/900/combined_credits"] = ok(creditsJson(3002))
        val people = peopleRepo(listOf(movie("p2", 3001, "The Other One"), movie("p3", 3002, "Moulin")))
        val vm = HomeSearchViewModel(repo, people, PersonSearchKey(777, "Tom Cruise"))
        collectAll(vm)
        advanceUntilIdle()
        assertEquals(777L, (vm.people.value.single() as PersonSection.Found).personId)

        vm.setQuery("Nicole Kidman")
        advanceUntilIdle()
        val kidman = vm.people.value.single() as PersonSection.Found
        assertEquals(900L, kidman.personId)
        assertEquals(listOf("Moulin"), kidman.titles.map { it.title })

        // Back to the clicked name (any case): the clicked person again, not a name search.
        vm.setQuery("tom cruise")
        advanceUntilIdle()
        assertEquals(777L, (vm.people.value.single() as PersonSection.Found).personId)
        assertFalse(requests.any { it.startsWith("/3/search/person?Tom") || it.startsWith("/3/search/person?tom") })
    }

    @Test
    fun clickedPersonOfflineShowsNoteNotNoTitles() = runTest(dispatcher) {
        routes["/3/person/500/combined_credits"] = MockResponse(code = 503, body = "{}")
        val vm = HomeSearchViewModel(repo, peopleRepo(listOf(movie("p1", 361743))), PersonSearchKey(500, "Tom Cruise"))
        collectAll(vm)
        advanceUntilIdle()
        assertEquals(listOf<PersonSection>(PersonSection.Offline("Tom Cruise")), vm.people.value)
        // Title results still come.
        assertNotNull(vm.results.value)
    }

    @Test
    fun clickedPersonWithNothingInLibraryShowsNoTitles() = runTest(dispatcher) {
        tomCruiseRoutes()
        val vm = HomeSearchViewModel(repo, peopleRepo(listOf(movie("p9", 99999))), PersonSearchKey(500, "Tom Cruise"))
        collectAll(vm)
        advanceUntilIdle()
        assertEquals(listOf<PersonSection>(PersonSection.NoTitles("Tom Cruise")), vm.people.value)
    }

    @Test
    fun scopeFiltersThePersonSection() = runTest(dispatcher) {
        tomCruiseRoutes()
        val people = peopleRepo(listOf(movie("p1", 361743, "Top Gun: Maverick")), listOf(show("t1", 2007, "Produced Show")))
        val vm = HomeSearchViewModel(repo, people)
        collectAll(vm)
        vm.setQuery("Tom Cruise")
        advanceUntilIdle()
        assertEquals(listOf("Top Gun: Maverick", "Produced Show"), titlesOf(vm.people.value.single()))

        vm.setScope(SearchScope.MOVIES)
        advanceUntilIdle()
        assertEquals(listOf("Top Gun: Maverick"), titlesOf(vm.people.value.single()))

        vm.setScope(SearchScope.SERIES)
        advanceUntilIdle()
        assertEquals(listOf("Produced Show"), titlesOf(vm.people.value.single()))

        vm.setScope(SearchScope.LIVE)
        advanceUntilIdle()
        assertEquals(emptyList<PersonSection>(), vm.people.value)
    }

    @Test
    fun editingTheQueryClearsTheOldPersonAtOnce() = runTest(dispatcher) {
        tomCruiseRoutes()
        routes["/3/search/person?Nicole Kidman"] = MockResponse.Builder().code(200)
            .body("""{"page":1,"results":[{"id":900,"name":"Nicole Kidman","known_for_department":"Acting","popularity":30.0}]}""")
            .headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).build()
        routes["/3/person/900/combined_credits"] = ok(creditsJson(3002))
        val vm = HomeSearchViewModel(repo, peopleRepo(listOf(movie("p1", 361743), movie("p3", 3002, "Moulin")), io = Dispatchers.IO))
        collectAll(vm)
        vm.setQuery("Tom Cruise")
        assertEquals(500L, (vm.people.first { it.isNotEmpty() }.single() as PersonSection.Found).personId)

        vm.setQuery("Nicole Kidman")
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        // TMDB hasn't answered for the new name: no section, and certainly not Tom Cruise's.
        assertEquals(emptyList<PersonSection>(), vm.people.value)
        assertEquals(900L, (vm.people.first { it.isNotEmpty() }.single() as PersonSection.Found).personId)
    }

    @Test
    fun comingBackToTheSameQueryKeepsTheSection() = runTest(dispatcher) {
        tomCruiseRoutes()
        val vm = HomeSearchViewModel(repo, peopleRepo(listOf(movie("p1", 361743))), PersonSearchKey(500, "Tom Cruise"))
        val first = backgroundScope.launch { vm.people.collect {} }
        advanceUntilIdle()
        assertEquals(1, vm.people.value.size)
        // Away on a title long enough for the flow to stop, then back.
        first.cancel()
        advanceTimeBy(10_000)
        val seen = mutableListOf<List<PersonSection>>()
        backgroundScope.launch { vm.people.collect { seen += it } }
        advanceUntilIdle()
        assertTrue(seen.toString(), seen.all { it.isNotEmpty() })
    }
}
