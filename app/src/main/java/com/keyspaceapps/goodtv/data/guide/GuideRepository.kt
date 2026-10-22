package com.keyspaceapps.goodtv.data.guide

import android.util.Log
import com.keyspaceapps.goodtv.data.repo.LibraryStore
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.data.sports.ChannelOrder
import com.keyspaceapps.goodtv.data.sports.GameMatcher
import com.keyspaceapps.goodtv.data.sports.LiveRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import java.util.concurrent.atomic.AtomicBoolean

/** Which rows the guide shows: everything (but event slots), favourites, one network's stations, or a genre. */
enum class GuideFilter { ALL, FAVOURITES, NETWORK, NEWS, SPORTS, ENTERTAINMENT, COMEDY, MOVIES, KIDS, DOCUMENTARY, EVENTS }

/** One merged channel: every visible feed sharing a row key, ordered by [ChannelOrder.sort]. */
data class GuideRow(
    val key: String,
    val name: String,
    val logoUrl: String?,
    val genre: LiveGenre,
    val feeds: List<ChannelCandidate>,
    val programmes: List<GuideProgramme>,
    val loopShow: String?,
    val favourite: Boolean,
)

/** [more] holds a network's stations in the markets that aren't default; empty for other sections. */
data class GuideSection(val title: String, val network: Network?, val rows: List<GuideRow>, val more: List<GuideRow>)

/**
 * [programmesLoaded] is false while the window's programmes are still being read (the rows have none yet);
 * [downloading] is true while the guide refresher is downloading a guide.
 */
data class GuideBoard(
    val sections: List<GuideSection>,
    val windowStartMs: Long,
    val programmesLoaded: Boolean = true,
    val downloading: Boolean = false,
)

/** [programme] is null when the row matched by its name or the show it loops. */
data class GuideSearchHit(val row: GuideRow, val programme: GuideProgramme?)

/** What the guide screen needs from the guide; the screen depends on this, not on the class. */
interface GuideSource {
    fun board(filter: GuideFilter, windowStartMs: Long): Flow<GuideBoard>
    fun search(query: String): Flow<List<GuideSearchHit>>
    suspend fun setFavourite(rowKey: String, favourite: Boolean)
    suspend fun refreshEventSlots()
}

/**
 * Turns the lineup, the stored guide links and programmes and the favourites into the guide screen's board.
 * The rows come from the lineup the [LineupKeeper] saved in [lineups], filtered by the current [Visibility];
 * only when nothing is saved and the keeper isn't building (or there is no [lineups]) is the visible lineup merged here ([LineupBuilder]:
 * identities of ~12.6k channels, grouping, placeholder logos), on [compute], once per lineup. Moving the window
 * or switching filters only re-queries programmes. A board whose programmes take longer than
 * [CHANNELS_FIRST_DELAY_MS] comes channels first: the rows without programmes, then the same rows once they are read.
 */
class GuideRepository(
    private val library: LibraryStore,
    private val store: GuideStore,
    private val refresher: GuideRefresher,
    private val liveRefresher: LiveRefresher,
    private val clock: () -> Long,
    private val compute: CoroutineDispatcher,
    private val warn: (String) -> Unit = { Log.w("GoodTV", it) },
    private val debug: (String) -> Unit = { Log.d("GoodTvGuide", it) },
    private val lineups: LineupStore? = null,
    /** [LineupKeeper.building]: while it's true and nothing is saved, the rows wait for the keeper's save. */
    private val lineupBuilding: StateFlow<Boolean>? = null,
) : GuideSource {
    /** A row before guide data: the part that depends on the lineup alone. */
    private class Base(
        val key: String,
        val name: String,
        val genre: LiveGenre,
        val network: Network?,
        val callSign: String?,
        val market: String?,
        val loopShow: String?,
        val feeds: List<ChannelCandidate>,
        val providerLogo: String?,
        /** [GameMatcher.normalize] of [name] and [loopShow], computed once: sorting and search use them. */
        val sortKey: String,
        val loopKey: String?,
        /** A Prime, Roku or Tubi copy of a station: its own row, never among a network's main rows. */
        val appCopy: Boolean,
    )

    /** A [Base] with its guide link and logo. */
    private class Resolved(val base: Base, val guideId: String?, val logoUrl: String?)

    private class Lineup(val source: List<ChannelCandidate>, val rows: List<Base>)

    /** The lineup's rows, and whether they came from the saved lineup (for the timing log). */
    private class Rows(val rows: List<Base>, val fromSaved: Boolean)

    /** What every board and search shares: the resolved rows and the favourites. */
    private class Shared(val rows: List<Resolved>, val favourites: Set<String>, val fromSaved: Boolean)

    @Volatile private var lineupCache: Lineup? = null

    private fun lineupOf(candidates: List<ChannelCandidate>): Lineup {
        lineupCache?.takeIf { it.source == candidates }?.let { return it }
        val rows = LineupBuilder.build(candidates, debug).map { baseOf(it, it.feeds) }
        return Lineup(candidates, rows).also { lineupCache = it }
    }

    private fun baseOf(r: SavedRow, feeds: List<SavedFeed>) = Base(
        key = r.key, name = r.name, genre = r.genre, network = r.network, callSign = r.callSign, market = r.market,
        loopShow = r.loopShow, feeds = feeds.map { ChannelCandidate(it.channel, it.categoryName) }, providerLogo = r.providerLogo,
        sortKey = r.sortKey, loopKey = r.loopKey, appCopy = r.appCopy,
    )

    /**
     * The saved rows the guide may show now: a feed of a disabled source or a hidden category is dropped, and a row left
     * without feeds goes. The rest of a row stays as saved; the keeper's rebuild tidies its name and grouping.
     */
    private fun visibleRows(saved: SavedLineup, visibility: Visibility): List<Base> = saved.rows.mapNotNull { r ->
        val feeds = r.feeds.filter { f ->
            f.channel.sourceId in visibility.enabledSources && (f.channel.sourceId to f.channel.categoryId) !in visibility.hiddenLiveCategories
        }
        if (feeds.isEmpty()) null else baseOf(r, feeds)
    }

    /** Today's path: the visible lineup read and merged here. */
    private fun builtRows(): Flow<Rows> =
        // Room re-runs a query on every write to its tables; an unchanged result rebuilds nothing.
        library.observeSportsCandidates().timedFirst("lineup query") { ", ${it.size} channels" }.distinctUntilChanged()
            .map { Rows(lineupOf(it).rows, fromSaved = false) }

    /**
     * The saved lineup filtered by the current visibility; [unsavedRows] only while nothing is saved, never both.
     * Room re-emits an equal [Visibility] on every sync, and an equal lineup converts nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun rows(): Flow<Rows> = if (lineups == null) builtRows() else combine(
        lineups.observeSaved().timedFirst("saved lineup read") { ", ${it?.rows?.size ?: 0} rows" },
        lineups.observeVisibility().distinctUntilChanged(),
    ) { saved, visibility -> saved?.let { it to visibility } }
        .distinctUntilChanged()
        .flatMapLatest { shown ->
            if (shown == null) return@flatMapLatest unsavedRows(lineups)
            // The fallback build is never used again once a lineup is saved.
            lineupCache = null
            flowOf(Rows(visibleRows(shown.first, shown.second), fromSaved = true))
        }

    /**
     * Nothing saved yet: while the keeper builds (the first launch after install), nothing until its save arrives,
     * so the lineup is built once; [builtRows] only when the keeper is idle or its build failed. A save that has
     * landed but not yet reached [LineupStore.observeSaved] is waited for too.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun unsavedRows(lineups: LineupStore): Flow<Rows> = lineupBuilding?.flatMapLatest { building ->
        if (building || lineups.savedStamp() != null) emptyFlow() else builtRows()
    } ?: builtRows()

    private fun resolve(rows: List<Base>, links: List<ChannelGuideLink>, channels: List<GuideChannel>): List<Resolved> {
        val started = System.nanoTime()
        try {
            return resolveRows(rows, links, channels)
        } finally {
            debug("resolve ${msSince(started)} ms, ${links.size} links")
        }
    }

    private fun resolveRows(rows: List<Base>, links: List<ChannelGuideLink>, channels: List<GuideChannel>): List<Resolved> {
        val guideOf = HashMap<Pair<Long, String>, String>(links.size * 2)
        for (l in links) guideOf[l.sourceId to l.channelId] = l.guideId
        val epgLogos = channels.filter { it.origin == GuideOrigin.EPGSHARE && !it.logoUrl.isNullOrBlank() }.associate { it.guideId to it.logoUrl }
        return rows.map { b ->
            // A looping channel has no schedule worth showing, whatever it is linked to. epgshare01's guide beats the provider's.
            val linked = if (b.loopShow != null) emptyList() else b.feeds.mapNotNull { guideOf[it.channel.sourceId to it.channel.id] }
            val guideId = linked.firstOrNull { !it.startsWith(PROVIDER_PREFIX) } ?: linked.firstOrNull()
            Resolved(b, guideId, epgLogos[guideId] ?: b.providerLogo)
        }
    }

    /** A failure that gets past [resolved]'s own handling is logged, never thrown at the app. */
    private val scope = CoroutineScope(SupervisorJob() + compute + CoroutineExceptionHandler { _, e -> warn("Guide failed: ${e.javaClass.simpleName}") })

    /**
     * The lineup merged with guide links and favourites; the programme-free part of every board and search,
     * built once and shared. Starting it also asks the guide refresher for a daily refresh, off the first emission.
     */
    private val resolved: Flow<Shared> = combine(
        rows(), store.observeLinks().distinctUntilChanged(),
        store.observeChannels().distinctUntilChanged(), store.observeFavourites().distinctUntilChanged(),
    ) { rows, links, channels, favourites -> Shared(resolve(rows.rows, links, channels), favourites, rows.fromSaved) }
        .flowOn(compute)
        // A failing lineup read keeps the last rows (the shared replay) and is tried again shortly.
        .retryWhen { e, _ ->
            if (e is CancellationException) return@retryWhen false
            warn("Guide lineup failed: ${e.javaClass.simpleName}")
            delay(RETRY_MS)
            true
        }
        .onStart {
            scope.launch {
                try {
                    refresher.refreshIfStale()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The guide stays as it was; the next start tries again.
                }
            }
        }
        // Kept [KEEP_MS] after the last screen leaves, so coming back to the Live tab within 5 minutes is instant.
        .shareIn(scope, SharingStarted.WhileSubscribed(KEEP_MS, replayExpirationMillis = 0), replay = 1)

    private fun row(r: Resolved, favourites: Set<String>, programmes: Map<String, List<GuideProgramme>>) = GuideRow(
        key = r.base.key, name = r.base.name, logoUrl = r.logoUrl, genre = r.base.genre, feeds = r.base.feeds,
        programmes = r.guideId?.let { programmes[it] }.orEmpty(), loopShow = r.base.loopShow, favourite = r.base.key in favourites,
    )

    /** A board and whether its rows came from the saved lineup. */
    private class Built(val board: GuideBoard, val fromSaved: Boolean)

    /** [CHANNELS_FIRST_DELAY_MS] have passed since the board was asked for. */
    private object ChannelsDue

    /**
     * Until this collection's first programmes arrive, each board is built first without them ([GuideBoard.programmesLoaded]
     * false); that board is shown only if the full one hasn't come within [CHANNELS_FIRST_DELAY_MS] of collection (a cold
     * open), never after it, so paging and switching chips never blink. After that a change (a favourite, new links) keeps
     * the last board until the new one is whole. [GuideBoard.downloading] follows [GuideRefresher.working].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun board(filter: GuideFilter, windowStartMs: Long): Flow<GuideBoard> = flow {
        val started = System.nanoTime()
        val loaded = AtomicBoolean(false)
        val boards = resolved.flatMapLatest { s ->
            val shown = if (filter == GuideFilter.FAVOURITES) s.rows.filter { it.base.key in s.favourites } else s.rows.filter { inFilter(it.base, filter) }
            val ids = shown.mapNotNullTo(HashSet()) { it.guideId }
            val whole = programmesOf(ids, windowStartMs, windowStartMs + WINDOW_MS).map { list ->
                val byGuide = list.groupBy { it.guideId }
                val rows = shown.map { it to row(it, s.favourites, byGuide) }
                loaded.set(true)
                Built(GuideBoard(sections(filter, rows), windowStartMs), s.fromSaved)
            }
            if (loaded.get()) whole else whole.onStart {
                val channels = GuideBoard(sections(filter, shown.map { it to row(it, s.favourites, emptyMap()) }), windowStartMs, programmesLoaded = false)
                emit(Built(channels, s.fromSaved))
            }
        }
        val due = flow { delay(CHANNELS_FIRST_DELAY_MS); emit(ChannelsDue) }
        // One collector decides what shows, so a channels-only board can never follow the full one.
        var last: GuideBoard? = null
        var held: Built? = null
        var channelsDue = false
        var downloading = refresher.working.value
        var logged = false
        fun show(built: Built) {
            last = built.board
            if (!built.board.programmesLoaded) {
                debug("${if (built.fromSaved) "channels board (saved)" else "channels board"} ${msSince(started)} ms")
            } else if (!logged) {
                logged = true
                val rows = built.board.sections.sumOf { it.rows.size + it.more.size }
                debug("${if (built.fromSaved) "first board (saved)" else "first board"} ${msSince(started)} ms, $rows rows")
            }
        }
        merge(boards, refresher.working, due).collect { event ->
            when (event) {
                is Built -> when {
                    event.board.programmesLoaded -> { held = null; show(event) }
                    last?.programmesLoaded == true -> return@collect
                    !channelsDue -> { held = event; return@collect }
                    else -> show(event)
                }
                is ChannelsDue -> {
                    channelsDue = true
                    show(held ?: return@collect)
                    held = null
                }
                is Boolean -> {
                    if (event == downloading) return@collect
                    downloading = event
                }
            }
            last?.let { emit(it.copy(downloading = downloading)) }
        }
    }.flowOn(compute)

    /**
     * [GuideStore.observeProgrammes] in chunks, so a long list of ids never overruns SQLite's bind-variable limit.
     * A download writes the programme table many times and every chunk re-queries on each write: unchanged chunks
     * are dropped, and after the first list the rest settle for [SETTLE_MS] so the board is rebuilt once per burst.
     */
    private fun programmesOf(ids: Set<String>, fromMs: Long, untilMs: Long): Flow<List<GuideProgramme>> {
        if (ids.isEmpty()) return flowOf(emptyList<GuideProgramme>()).timedFirst("first programmes") { ", 0 ids" }
        val chunks = ids.chunked(ID_CHUNK).map { store.observeProgrammes(it.toSet(), fromMs, untilMs).distinctUntilChanged() }
        return combine(chunks) { parts -> parts.flatMap { it }.sortedWith(compareBy({ it.guideId }, { it.startMs })) }
            .timedFirst("first programmes") { ", ${ids.size} ids, ${it.size} programmes" }
            .conflate()
            .settled(SETTLE_MS)
    }

    /** Logs (at debug level, tag GoodTvGuide) how long the first value took from collection; counts only, never names. */
    private fun <T> Flow<T>.timedFirst(stage: String, detail: (T) -> String = { "" }): Flow<T> = flow {
        val started = System.nanoTime()
        var first = true
        collect {
            if (first) {
                first = false
                debug("$stage ${msSince(started)} ms${detail(it)}")
            }
            emit(it)
        }
    }

    private fun msSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    /** The first value at once, later ones only after [ms] without a newer one. */
    @OptIn(FlowPreview::class)
    private fun <T> Flow<T>.settled(ms: Long): Flow<T> = flow {
        var first = true
        emitAll(debounce { if (first) { first = false; 0L } else ms })
    }

    private fun inFilter(b: Base, filter: GuideFilter): Boolean = when (filter) {
        GuideFilter.ALL -> b.genre != LiveGenre.EVENTS
        GuideFilter.FAVOURITES -> false
        GuideFilter.NETWORK -> b.network != null
        GuideFilter.EVENTS -> b.genre == LiveGenre.EVENTS
        else -> b.genre.name == filter.name
    }

    private fun sections(filter: GuideFilter, rows: List<Pair<Resolved, GuideRow>>): List<GuideSection> {
        fun byName(list: List<Pair<Resolved, GuideRow>>) = list.sortedBy { it.first.base.sortKey }
        fun section(title: String, network: Network?, main: List<Pair<Resolved, GuideRow>>, more: List<Pair<Resolved, GuideRow>> = emptyList()) =
            GuideSection(title, network, main.map { it.second }, more.map { it.second })

        val favourites = byName(rows.filter { it.second.favourite }).takeIf { it.isNotEmpty() }?.let { section(FAVOURITES_TITLE, null, it) }
        val networks = Network.entries.mapNotNull { n ->
            val mine = rows.filter { it.first.base.network == n }
            // A national feed is the network's own name ("ABC (EAST)"); every other row is a local, placed by its market.
            val allowed = ChannelNames.REGIONS.keys + n.name.lowercase()
            val (national, locals) = mine.partition { r -> !r.first.base.appCopy && r.first.base.callSign == null && ChannelNames.words(r.second.name).all { it in allowed } }
            val (local, other) = locals.partition { it.first.base.market in DEFAULT_MARKETS && !it.first.base.appCopy }
            val main = byName(national) + local.sortedWith(compareBy({ DEFAULT_MARKETS.indexOf(it.first.base.market) }, { it.first.base.sortKey }))
            val more = other.sortedWith(compareBy({ it.first.base.market == null }, { it.first.base.market }, { it.first.base.sortKey }))
            if (main.isEmpty() && more.isEmpty()) null else section(n.name, n, main, more)
        }
        fun genre(g: LiveGenre, from: List<Pair<Resolved, GuideRow>>) =
            byName(from.filter { it.first.base.genre == g }).takeIf { it.isNotEmpty() }?.let { section(titleOf(g), null, it) }
        return when (filter) {
            GuideFilter.ALL -> listOfNotNull(favourites) + networks +
                LiveGenre.entries.filter { it != LiveGenre.NETWORK && it != LiveGenre.EVENTS }.mapNotNull { g -> genre(g, rows.filter { it.first.base.network == null }) }
            GuideFilter.FAVOURITES -> listOfNotNull(favourites)
            GuideFilter.NETWORK -> networks
            else -> listOfNotNull(genre(LiveGenre.valueOf(filter.name), rows))
        }
    }

    private fun titleOf(g: LiveGenre) = g.name.lowercase().replaceFirstChar { it.titlecase() }

    override fun search(query: String): Flow<List<GuideSearchHit>> {
        val needle = GameMatcher.normalize(query)
        if (needle.isEmpty()) return flowOf(emptyList())
        @OptIn(ExperimentalCoroutinesApi::class)
        return resolved.mapLatest { shared ->
            val all = shared.rows
            val favourites = shared.favourites
            val now = clock()
            val byGuide = all.filter { it.guideId != null }.groupBy { it.guideId!! }
            val programmeHits = store.searchProgrammes(query.trim(), now, SEARCH_PROGRAMMES)
                .sortedWith(compareBy({ !(it.startMs <= now && now < it.endMs) }, { it.startMs }))
                .flatMap { p -> byGuide[p.guideId].orEmpty().map { GuideSearchHit(row(it, favourites, emptyMap()), p) } }
            val named = all.filter { r ->
                needle in r.base.sortKey || r.base.loopKey?.contains(needle) == true
            }.sortedBy { it.base.sortKey }.map { GuideSearchHit(row(it, favourites, emptyMap()), null) }
            val seen = programmeHits.mapTo(HashSet()) { it.row.key }
            programmeHits + named.filter { seen.add(it.row.key) }.take(SEARCH_PROGRAMMES)
        }.flowOn(compute)
    }

    override suspend fun setFavourite(rowKey: String, favourite: Boolean) = store.setFavourite(rowKey, favourite, clock())

    override suspend fun refreshEventSlots() = liveRefresher.refreshIfStale()

    companion object {
        const val WINDOW_MS = 3 * 3_600_000L
        /** How long a board waits for its programmes before showing its channels alone. */
        const val CHANNELS_FIRST_DELAY_MS = 200L
        private const val FAVOURITES_TITLE = "Favourites"
        private const val SEARCH_PROGRAMMES = 50
        private const val ID_CHUNK = 500
        private const val RETRY_MS = 5_000L
        private const val SETTLE_MS = 250L
        private const val KEEP_MS = 5 * 60_000L
    }
}
