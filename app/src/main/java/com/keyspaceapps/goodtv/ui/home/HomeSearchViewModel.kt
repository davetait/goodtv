package com.keyspaceapps.goodtv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyspaceapps.goodtv.data.repo.LibraryRepository
import com.keyspaceapps.goodtv.data.tmdb.PeopleRepository
import com.keyspaceapps.goodtv.data.tmdb.PersonTitle
import com.keyspaceapps.goodtv.domain.ContentKind
import com.keyspaceapps.goodtv.ui.browse.FocusMemory
import com.keyspaceapps.goodtv.ui.nav.PersonSearchKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import com.keyspaceapps.goodtv.domain.label

/** What the search covers. ALL is the default. */
enum class SearchScope(val kinds: List<ContentKind>) {
    ALL(listOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.SERIES)),
    LIVE(listOf(ContentKind.LIVE)),
    MOVIES(listOf(ContentKind.MOVIE)),
    SERIES(listOf(ContentKind.SERIES)),
}

/** One search hit; [key] is unique across kinds and sources. [title] is the clean label, [lookupName] the raw name. */
data class SearchResult(
    val kind: ContentKind, val sourceId: Long, val id: String, val title: String, val imageUrl: String?,
    val tmdbId: Long? = null, val lookupName: String = title,
) {
    val key: String get() = "${kind.name}:$sourceId:$id"
}

/** Results per kind; a search over everything shows up to three times this. */
const val SEARCH_LIMIT_PER_KIND = 60

/** Wait this long after the last keystroke before querying. */
const val SEARCH_DEBOUNCE_MS = 300L

/** A person's part of a search, shown above the title results. */
sealed interface PersonSection {
    val name: String

    /** Library titles the person counts for (one copy each, newest first), in the current scope. */
    data class Found(val personId: Long, override val name: String, val titles: List<SearchResult>) : PersonSection

    /** The clicked person has nothing in the library (in this scope). */
    data class NoTitles(override val name: String) : PersonSection

    /** The clicked person's credits couldn't be fetched and none are cached. */
    data class Offline(override val name: String) : PersonSection

    /** The clicked person's credits are still on their way; never takes focus. */
    data class Loading(override val name: String) : PersonSection
}

/**
 * Search state for the Home tab. Lives in the Home nav entry, so the query and results survive
 * opening a result and coming back. [results] is null while the query is blank (show the rows),
 * else the hits grouped live, then movies, then series.
 *
 * [people] holds the person sections that go above the results. With [pinned] (a person clicked on
 * an info page) the query starts as their name, and while it still is that name (ignoring case) the
 * section is that person's by TMDB id, so namesakes don't mix. Any other query is resolved as a
 * typed name. It is its own flow, so [results] never waits for TMDB. Without [people] (or TMDB off)
 * there are no sections.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeSearchViewModel(
    private val repo: LibraryRepository,
    people: PeopleRepository? = null,
    private val pinned: PersonSearchKey? = null,
) : ViewModel() {
    private val peopleRepo = people

    val focus = FocusMemory()

    private val _query = MutableStateFlow(pinned?.name ?: "")
    val query: StateFlow<String> = _query

    private val _scope = MutableStateFlow(SearchScope.ALL)
    val scope: StateFlow<SearchScope> = _scope

    private val settledQuery: Flow<String> = _query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }

    val results: StateFlow<List<SearchResult>?> =
        combine(settledQuery, _scope) { q, s -> q.trim() to s }
            .flatMapLatest { (q, s) -> if (q.isEmpty()) flowOf(null) else search(q, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The query [people] currently answers; a new one clears the old sections at once. */
    @Volatile private var peopleQuery = ""

    /**
     * Empty while the query is blank, nobody matches, or TMDB is off. A changed query empties it
     * straight away (or, for the clicked person, shows them as [PersonSection.Loading]), so an
     * earlier person's titles never sit under a new query while TMDB answers, and the clicked
     * person's screen never claims nothing matches before their credits arrive. Coming back to the
     * screen (same query) keeps what was shown.
     */
    val people: StateFlow<List<PersonSection>> =
        settledQuery.map { it.trim() }.distinctUntilChanged()
            .flatMapLatest { q ->
                sectionsFor(q).onStart {
                    if (q != peopleQuery) {
                        peopleQuery = q
                        val clicked = pinnedFor(q)?.takeIf { peopleRepo != null }
                        emit(if (clicked != null) listOf(PersonSection.Loading(clicked.name)) else emptyList())
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { _query.value = value }
    fun setScope(value: SearchScope) { _scope.value = value }

    /** The clicked person when [query] still names them. */
    private fun pinnedFor(query: String): PersonSearchKey? = pinned?.takeIf { query.equals(it.name, ignoreCase = true) }

    private fun sectionsFor(query: String): Flow<List<PersonSection>> {
        val people = peopleRepo
        if (people == null || query.isEmpty()) return flowOf(emptyList())
        val clicked = pinnedFor(query)
        if (clicked != null) {
            // null = offline with nothing cached.
            val titles: Flow<List<PersonTitle>?> = flow {
                if (people.ensureCredits(clicked.personId, clicked.name).isFailure) emit(null) else emitAll(people.observeTitles(clicked.personId))
            }
            return combine(titles, _scope) { list, scope ->
                val inScope = list?.inScope(scope)
                listOf(
                    when {
                        inScope == null -> PersonSection.Offline(clicked.name)
                        inScope.isEmpty() -> PersonSection.NoTitles(clicked.name)
                        else -> PersonSection.Found(clicked.personId, clicked.name, inScope)
                    },
                )
            }
        }
        val matched = flow {
            val matches = people.findPeople(query).getOrNull().orEmpty().filter { people.ensureCredits(it.id, it.name).isSuccess }
            if (matches.isEmpty()) {
                emit(emptyList())
            } else {
                emitAll(combine(matches.map { m -> people.observeTitles(m.id).map { m to it } }) { it.toList() })
            }
        }
        return combine(matched, _scope) { list, scope ->
            list.mapNotNull { (m, titles) -> titles.inScope(scope).takeIf { it.isNotEmpty() }?.let { PersonSection.Found(m.id, m.name, it) } }
        }
    }

    private fun List<PersonTitle>.inScope(scope: SearchScope): List<SearchResult> = mapNotNull { t ->
        when (t) {
            is PersonTitle.OfMovie -> t.movie.takeIf { ContentKind.MOVIE in scope.kinds }?.let {
                SearchResult(ContentKind.MOVIE, it.sourceId, it.id, it.label, it.posterUrl, it.tmdbId, it.name)
            }
            is PersonTitle.OfSeries -> t.series.takeIf { ContentKind.SERIES in scope.kinds }?.let {
                SearchResult(ContentKind.SERIES, it.sourceId, it.id, it.label, it.coverUrl, it.tmdbId, it.name)
            }
        }
    }

    private fun search(query: String, scope: SearchScope): Flow<List<SearchResult>> {
        val perKind = scope.kinds.map { kind -> resultsOf(kind, query) }
        return combine(perKind) { lists -> lists.toList().flatten() }
    }

    private fun resultsOf(kind: ContentKind, query: String): Flow<List<SearchResult>> = when (kind) {
        ContentKind.LIVE -> repo.searchLive(query, SEARCH_LIMIT_PER_KIND).map { list ->
            list.map { SearchResult(kind, it.sourceId, it.id, it.label, it.logoUrl, lookupName = it.name) }
        }
        ContentKind.MOVIE -> repo.searchMovies(query, SEARCH_LIMIT_PER_KIND).map { list ->
            list.map { SearchResult(kind, it.sourceId, it.id, it.label, it.posterUrl, it.tmdbId, it.name) }
        }
        ContentKind.SERIES -> repo.searchSeries(query, SEARCH_LIMIT_PER_KIND).map { list ->
            list.map { SearchResult(kind, it.sourceId, it.id, it.label, it.coverUrl, it.tmdbId, it.name) }
        }
    }
}
