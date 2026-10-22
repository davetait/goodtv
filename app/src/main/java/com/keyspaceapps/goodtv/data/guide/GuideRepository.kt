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

data class GuideBoard(val sections: List<GuideSection>, val windowStartMs: Long)

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
 * Turns the visible lineup, the stored guide links and programmes and the favourites into the guide
 * screen's board. All of a lineup's merging (identities of ~12.6k channels, grouping, placeholder
 * logos) runs on [compute], once per lineup; moving the window or switching filters only re-queries
 * programmes.
 */
class GuideRepository(
    private val library: LibraryStore,
    private val store: GuideStore,
    private val refresher: GuideRefresher,
    private val liveRefresher: LiveRefresher,
    private val clock: () -> Long,
    private val compute: CoroutineDispatcher,
    private val warn: (String) -> Unit = { Log.w("GoodTV", it) },
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

    @Volatile private var lineupCache: Lineup? = null

    private fun lineupOf(candidates: List<ChannelCandidate>): Lineup {
        lineupCache?.takeIf { it.source == candidates }?.let { return it }
        val placeholders = PlaceholderLogos.of(candidates.map { it.channel })
        val groups = LinkedHashMap<String, MutableList<Pair<ChannelCandidate, ChannelIdentity>>>()
        for (c in ChannelOrder.sort(candidates)) {
            val id = ChannelIdentity.of(c.channel.name, c.categoryName)
            if (!id.hidden) groups.getOrPut(id.rowKey) { ArrayList() }.add(c to id)
        }
        val rows = groups.map { (key, feeds) ->
            val ids = feeds.map { it.second }
            val network = ids.firstNotNullOfOrNull { it.network }
            val callSign = ids.firstNotNullOfOrNull { it.callSign }
            val name = nameOf(ids, network, callSign)
            val loopShow = ids.firstNotNullOfOrNull { it.loopShow }
            Base(
                key = key,
                name = name,
                genre = ids.firstOrNull { it.genre != LiveGenre.ENTERTAINMENT }?.genre ?: LiveGenre.ENTERTAINMENT,
                network = network,
                callSign = callSign,
                market = ids.firstNotNullOfOrNull { it.market },
                loopShow = loopShow,
                feeds = feeds.map { it.first },
                providerLogo = feeds.firstNotNullOfOrNull { (c, _) -> c.channel.logoUrl?.takeIf { it.isNotBlank() && it !in placeholders } },
                sortKey = GameMatcher.normalize(name),
                loopKey = loopShow?.let(GameMatcher::normalize),
                appCopy = network != null && APP_PREFIXES.any { key.startsWith(it) },
            )
        }
        return Lineup(candidates, rows).also { lineupCache = it }
    }

    /**
     * A station's copies are named differently ("A3 NEW YORK 04 WNBC NBC", "NBC (WNBC) NEW YORK"): the row takes
     * the shortest name that starts with its network, else the shortest. Other rows take their first feed's name.
     */
    private fun nameOf(ids: List<ChannelIdentity>, network: Network?, callSign: String?): String {
        if (network == null || callSign == null) return ids.first().displayName
        val word = network.name.lowercase()
        val names = ids.map { it.displayName }
        return names.filter { GameMatcher.normalize(PROVIDER_TAG.replaceFirst(it, "")).startsWith(word) }.minByOrNull { it.length }
            ?: names.minBy { it.length }
    }

    private fun resolve(lineup: Lineup, links: List<ChannelGuideLink>, channels: List<GuideChannel>): List<Resolved> {
        val guideOf = HashMap<Pair<Long, String>, String>(links.size * 2)
        for (l in links) guideOf[l.sourceId to l.channelId] = l.guideId
        val epgLogos = channels.filter { it.origin == GuideOrigin.EPGSHARE && !it.logoUrl.isNullOrBlank() }.associate { it.guideId to it.logoUrl }
        return lineup.rows.map { b ->
            // A looping channel has no schedule worth showing, whatever it is linked to.
            val guideId = if (b.loopShow != null) null else b.feeds.firstNotNullOfOrNull { guideOf[it.channel.sourceId to it.channel.id] }
            Resolved(b, guideId, epgLogos[guideId] ?: b.providerLogo)
        }
    }

    /** A failure that gets past [resolved]'s own handling is logged, never thrown at the app. */
    private val scope = CoroutineScope(SupervisorJob() + compute + CoroutineExceptionHandler { _, e -> warn("Guide failed: ${e.javaClass.simpleName}") })

    /**
     * The lineup merged with guide links and favourites; the programme-free part of every board and search,
     * built once and shared. Starting it also asks the guide refresher for a daily refresh, off the first emission.
     */
    private val resolved: Flow<Pair<List<Resolved>, Set<String>>> = combine(
        // Room re-runs a query on every write to its tables; an unchanged result rebuilds nothing.
        library.observeSportsCandidates().distinctUntilChanged(), store.observeLinks().distinctUntilChanged(),
        store.observeChannels().distinctUntilChanged(), store.observeFavourites().distinctUntilChanged(),
    ) { candidates, links, channels, favourites -> resolve(lineupOf(candidates), links, channels) to favourites }
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
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)

    private fun row(r: Resolved, favourites: Set<String>, programmes: Map<String, List<GuideProgramme>>) = GuideRow(
        key = r.base.key, name = r.base.name, logoUrl = r.logoUrl, genre = r.base.genre, feeds = r.base.feeds,
        programmes = r.guideId?.let { programmes[it] }.orEmpty(), loopShow = r.base.loopShow, favourite = r.base.key in favourites,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun board(filter: GuideFilter, windowStartMs: Long): Flow<GuideBoard> = resolved.flatMapLatest { (all, favourites) ->
        val shown = if (filter == GuideFilter.FAVOURITES) all.filter { it.base.key in favourites } else all.filter { inFilter(it.base, filter) }
        val ids = shown.mapNotNullTo(HashSet()) { it.guideId }
        programmesOf(ids, windowStartMs, windowStartMs + WINDOW_MS).map { list ->
            val byGuide = list.groupBy { it.guideId }
            val rows = shown.map { it to row(it, favourites, byGuide) }
            GuideBoard(sections(filter, rows), windowStartMs)
        }
    }.flowOn(compute)

    /**
     * [GuideStore.observeProgrammes] in chunks, so a long list of ids never overruns SQLite's bind-variable limit.
     * A download writes the programme table many times and every chunk re-queries on each write: unchanged chunks
     * are dropped, and after the first list the rest settle for [SETTLE_MS] so the board is rebuilt once per burst.
     */
    private fun programmesOf(ids: Set<String>, fromMs: Long, untilMs: Long): Flow<List<GuideProgramme>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        val chunks = ids.chunked(ID_CHUNK).map { store.observeProgrammes(it.toSet(), fromMs, untilMs).distinctUntilChanged() }
        return combine(chunks) { parts -> parts.flatMap { it }.sortedWith(compareBy({ it.guideId }, { it.startMs })) }
            .conflate()
            .settled(SETTLE_MS)
    }

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
        return resolved.mapLatest { (all, favourites) ->
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
        private const val FAVOURITES_TITLE = "Favourites"
        private const val SEARCH_PROGRAMMES = 50
        private const val ID_CHUNK = 500
        private const val RETRY_MS = 5_000L
        private const val SETTLE_MS = 250L
        private val APP_PREFIXES = listOf("prime ", "roku ", "tubi ")
        private val PROVIDER_TAG = Regex("""^\s*[A-Z0-9&/+]{1,6}:\s*""")
    }
}
