# Live TV Guide Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Live tab's category list with an on-screen TV guide. Channels are rows, half-hour columns run across time, and the guide is grouped by channel genre with favourites first. Guide data comes from epgshare01, with the provider's guide as the fallback.

**Architecture:**
- **Data:** a new `data/guide` package:
  - **XMLTV reader:** a tolerant, streaming scanner that runs on the JVM, so it can be unit-tested.
  - **Channel identity:** row key, genre, network, market, region, 24/7 show, heading and empty-slot detection.
  - **Matcher:** maps each channel to a guide id.
  - **Refresher:** runs at most once a day, never while playing, and keeps the last good guide on failure.
  - **Repository:** assembles a `GuideBoard` (sections → rows → programmes) for the screen.
- **Storage:** Room v10, which adds the guide tables, the channel→guide map, favourites, and `epgChannelId` on live channels.
- **Screen:** `ui/live/GuideScreen` pages through time in fixed 3-hour windows. There's no horizontal scroll container, so focus stays simple on a TV.

**Tech Stack:** Kotlin, Compose for TV (tv-material3), Room (exported schemas), OkHttp (+ MockWebServer in tests), kotlinx-coroutines, `java.util.zip.GZIPInputStream`.

**Spec:** `docs/superpowers/specs/2026-10-21-live-guide-design.md`

## Global Constraints

- **epgshare01 files:**
  - Base URL `https://epgshare01.online/epgshare01/`.
  - Files `epg_ripper_US_LOCALS1.xml.gz` and `epg_ripper_US2.xml.gz`.
  - User-Agent `GoodTV/0.2`.
- **Provider fallback:**
  - `xmltv.php` via the existing Xtream credentials.
  - Never runs while something is playing.
  - Only for channels with no epgshare01 match.
- **Reading files:**
  - Guide files are read as streams and never held whole.
  - Keep only programmes overlapping [now − 2 h, now + 48 h], for matched channels only.
  - A failed or malformed download keeps the last good guide. Nothing crashes.
- **Refresh:** at most once every 24 h per origin (`GUIDE_MAX_AGE_MS = 24 * 3_600_000`). It runs at app start after `refreshStale()` and when a sync finishes, and always on a background dispatcher.
- **Genres, in this order:**
  - Network, News, Sports, Entertainment, Comedy, Movies, Kids, Documentary, Events.
  - Chips: All, Favourites, then those genres. Label "Docs" is not used; the chip says "Documentary".
- **Networks, in this order:** ABC, CBS, NBC, FOX, PBS, CW.
- **Markets shown by default:** New York, Los Angeles, Chicago, Philadelphia, Dallas–Fort Worth (`DEFAULT_MARKETS`). Every other local station goes in the collapsed "More NBC" / "More CBS"… row at the end of its network.
- **Events** appear only under the Events chip, never under All.
- **Not shown:**
  - heading rows (names starting `#`);
  - empty slots (`No event streaming`);
  - ended slots (names starting `ENDED`/`End`, as `GameMatcher` treats them);
  - hidden categories;
  - disabled sources.
- **Merging rows:**
  - Copies differing only in quality or backup markers (HD, FHD, UHD, SD, 4K, 8K, RAW, `(D)`, `(PC)`, `(A)`, `(H)`, "backup") merge into one row with a feed picker.
  - East/West feeds and different call signs never merge.
- **Logos:**
  - Source order: epgshare01 logo when matched; else the provider logo unless it's a placeholder; else the name.
  - Placeholders are any logo URL used by ≥ 20 channels, or a URL containing `generic`.
  - Logos sit on a `#ECEEF2` tile.
- **Copy:**
  - "No guide info".
  - "{show} · all day" for 24/7 rows.
  - "More {network}" for the collapsed row.
  - "Add to favourites" / "Remove from favourites".
  - "Search shows and channels".
- **Favourites:**
  - Hold Select (or the Menu key) on a channel's name cell.
  - Stored by row key in Room; they survive re-syncs.
  - Shown first, together.
- **Grid:**
  - 30-minute columns, 6 visible (3 hours).
  - Window start = now floored to :00/:30. It moves by 30 min when focus crosses an edge, never earlier than the current half-hour and never later than now + 48 h − 3 h.
  - A red "now" line.
- **Select:**
  - On an on-now programme: plays, via the feed picker when there are several feeds.
  - On a later programme: details only.
  - Back from the player returns to the same cell.
- **Connected test runs:** back up the emulator data first with `python3 -I /tmp/goodtv-tools/backup.py /tmp/goodtv-backups`, and restore right after (`/tmp/goodtv-tools/runclass.sh <class>` does all of this).
  - Run one class per command; the comma filter runs only the first.
  - The emulator is launched with `nohup … & disown`.
- **Tests never reach epgshare01 or the provider:** use MockWebServer and the fixtures in `app/src/test/resources/live/`.

## Review Focus

1. **A call sign that's also a word** ("DOCTOR WHO", "HONG KONG FIGHT CLUB", "SCARECROW AND MRS. KING") must not get a local station's guide, while "NBC 13 (WHO) DES MOINES" must. Pinned in Task 3: `callSignNeedsParenthesesOrNetworkWord`.
2. **The guide file is cut off, or malformed partway** (seen on the provider's file): every programme before the break is kept, and the previous guide isn't wiped. Pinned in Task 2: `malformedMidFileKeepsWhatCameBefore`; and in Task 4: `failedDownloadKeepsLastGoodGuide`.
3. **A playback session spanning the daily refresh:** no provider guide download starts while playing. Pinned in Task 4: `providerGuideWaitsWhilePlaying`.
4. **Programmes crossing the window edges** (started before the window, ending after it) show clipped to the visible columns, not dropped. Pinned in Task 6: `programmeCrossingWindowEdgeIsClipped`.
5. **Two channels whose normalised names collide** (e.g. two "FOX SPORTS 1" from different categories with different call signs or regions) must not merge into one row or share a guide by accident. Pinned in Task 3: `regionsAndCallSignsStayApart`.

---

### Task 1: Storage v10 (guide tables, channel→guide map, favourites, `epgChannelId`)

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/guide/GuideModels.kt`, `data/guide/GuideStore.kt` (interface), `data/db/RoomGuideStore.kt`
- Modify: `domain/Models.kt` (`LiveChannel.epgChannelId: String? = null`, appended last), `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/GoodTvDatabase.kt` (`MIGRATION_9_10`, version 10), `data/xtream/XtreamMappers.kt` (carry `epg_channel_id`), `data/db/RoomLibraryStore.kt` (map the new column), `AppGraph.kt` (`val guideStore`)
- Create: `app/src/test/java/com/keyspaceapps/goodtv/testing/InMemoryGuideStore.kt`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/data/db/GuideStoreTest.kt`, `MigrationTest.kt` (add `migrate9To10AddsGuide`), `app/src/test/java/com/keyspaceapps/goodtv/data/xtream/XtreamMappersTest.kt` (add a case)

**Interfaces:**
- **Produces:**
  - `enum class GuideOrigin { EPGSHARE, PROVIDER }`
  - `data class GuideChannel(val guideId: String, val origin: GuideOrigin, val logoUrl: String?)`
  - `data class GuideProgramme(val guideId: String, val startMs: Long, val endMs: Long, val title: String, val description: String?, val genres: List<String>, val imageUrl: String?)`
  - `data class ChannelGuideLink(val sourceId: Long, val channelId: String, val guideId: String)`
  - `interface GuideStore`:
    - `suspend fun replaceGuide(origin: GuideOrigin, channels: List<GuideChannel>, programmes: Sequence<GuideProgramme>)`: one transaction, inserted in batches of 500; deletes that origin's old rows first.
    - `suspend fun replaceLinks(links: List<ChannelGuideLink>)`
    - `fun observeLinks(): Flow<List<ChannelGuideLink>>`
    - `fun observeChannels(): Flow<List<GuideChannel>>`
    - `fun observeProgrammes(guideIds: Set<String>, fromMs: Long, untilMs: Long): Flow<List<GuideProgramme>>`: overlapping [from, until), ordered by guideId then startMs.
    - `suspend fun searchProgrammes(query: String, fromMs: Long, limit: Int): List<GuideProgramme>`: title LIKE, ending after `fromMs`, ordered by startMs. Use the repo's `ESCAPE '\'` pattern.
    - `suspend fun fetchedAt(origin: GuideOrigin): Long?`
    - `suspend fun markFetched(origin: GuideOrigin, atMs: Long)`
    - `fun observeFavourites(): Flow<Set<String>>`
    - `suspend fun setFavourite(rowKey: String, favourite: Boolean, atMs: Long)`
  - `AppGraph.guideStore: GuideStore`
- **Tables:**
  - `guide_channels(guideId PK, origin, logoUrl)`
  - `guide_programmes(guideId, startMs, endMs, title, description, genres TEXT, imageUrl)`:
    - PK (guideId, startMs);
    - index endMs;
    - FK guide_channels ON DELETE CASCADE;
    - genres joined with `|`.
  - `channel_guide(sourceId, channelId, guideId)`: PK (sourceId, channelId); FK sources ON DELETE CASCADE.
  - `favourite_channels(rowKey PK, addedAt)`
  - `guide_meta(origin PK, fetchedAt)`
  - `live_channels` + `epgChannelId TEXT` (nullable, `ALTER TABLE ADD COLUMN`).

- [ ] **Step 1: Write the failing tests:**
  - `GuideStoreTest.replaceGuideSwapsOnlyThatOrigin`: EPGSHARE then PROVIDER, then replace EPGSHARE again; PROVIDER programmes remain.
  - `observeProgrammesReturnsOverlaps`: a 17:30–19:00 programme is returned for window 18:00–21:00.
  - `searchFindsLaterShows`: "hannity" finds the 21:00 programme when fromMs = 18:00.
  - `favouritesSurviveACatalogReplace`
  - `deletingASourceDropsItsLinks`
  - `migrate9To10AddsGuide`: v9 with a source, a live channel and a watch_list row; after migrating, all survive, `live_channels.epgChannelId` is NULL, and the new tables exist.
  - `XtreamMappersTest.liveKeepsEpgChannelId`
- [ ] **Step 2: Run (backup first):** `/tmp/goodtv-tools/runclass.sh com.keyspaceapps.goodtv.data.db.GuideStoreTest`, then the same for `MigrationTest`; `./gradlew :app:testDebugUnitTest --tests '*XtreamMappersTest'`. Expected: compile failures (no `GuideStore`, no `MIGRATION_9_10`).
- [ ] **Step 3: Implement.** Copy the migration SQL from the generated `app/schemas/.../10.json` after the first build.
- [ ] **Step 4: Run the same commands.** Expected: all pass, and `10.json` is committed.
- [ ] **Step 5: Commit** `feat(guide): storage v10 for guide data, channel links and favourites`.

### Task 2: Tolerant streaming XMLTV reader

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/guide/XmltvReader.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/guide/XmltvReaderTest.kt`, using fixture `app/src/test/resources/live/epgshare_us2_sample.xml` (committed with this plan)

**Interfaces:**
- **Consumes:** `GuideProgramme` (Task 1).
- **Produces:**
  - `data class XmltvChannel(val id: String, val names: List<String>, val iconUrl: String?)`
  - `object XmltvReader { fun read(input: InputStream, keepChannel: (String) -> Boolean, fromMs: Long, untilMs: Long, onChannel: (XmltvChannel) -> Unit, onProgramme: (GuideProgramme) -> Unit): Int }`. It returns the programme count and never throws on bad markup.
- **Approach:** scan text in 1 MiB chunks for `<channel …>…</channel>` and `<programme …>…</programme>` blocks, carrying any partial block over to the next chunk.
  - Read attributes in any order, and times as `yyyyMMddHHmmss ±hhmm`.
  - Read `<title>`, the first `<desc>`, every `<category>`, and the `<icon src>`.
  - Unescape XML entities.
  - Skip a block that doesn't parse, and keep going.

- [ ] **Step 1: Write the failing tests:**
  - `readsChannelsAndProgrammesFromSample`: 2 channels (`Fox.News.Channel.HD.us2`, `AMC.HD.us2`); every programme has a title; at least one has genres and an image.
  - `keepsOnlyWantedChannelsInsideTheWindow`
  - `attributeOrderAndOffsetsDoNotMatter`: `start="20261020180000 -0400"` → 22:00Z; channel-first and start-first orders give the same result.
  - `entitiesAreUnescaped`: `&amp;`, `&#39;`, `&quot;`.
  - `malformedMidFileKeepsWhatCameBefore`: 3 good programmes, then `<programme channel="x" start=` cut off, then garbage; result 3, no exception.
  - `blockSplitAcrossChunksIsRead`: an input stream that returns 7 bytes per read.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*XmltvReaderTest'`. Expected: compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(guide): tolerant streaming XMLTV reader`.

### Task 3: Channel identity, genres and guide matching

**Files:**
- Create: `data/guide/ChannelIdentity.kt` (`ChannelIdentity`, `LiveGenre`, `Network`, `Region`, `DEFAULT_MARKETS`), `data/guide/GuideMatcher.kt`, `data/guide/PlaceholderLogos.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/guide/ChannelIdentityTest.kt`, `GuideMatcherTest.kt`, using fixtures `visible_channels.tsv` (name, category, logo hash), `epgshare_locals_channels.tsv` and `epgshare_us2_channels.tsv` (id, display names, icon), all committed with this plan

**Interfaces:**
- **Consumes:** `LiveChannel`, `ChannelCandidate` (data/sports), `GameMatcher.normalize` (internal; reuse it, don't copy it).
- **Produces:**
  - `enum class LiveGenre { NETWORK, NEWS, SPORTS, ENTERTAINMENT, COMEDY, MOVIES, KIDS, DOCUMENTARY, EVENTS }`
  - `enum class Network { ABC, CBS, NBC, FOX, PBS, CW }`
  - `enum class Region { EAST, WEST }`
  - `val DEFAULT_MARKETS: List<String> = listOf("New York", "Los Angeles", "Chicago", "Philadelphia", "Dallas")`, with aliases "Fort Worth" and "DFW" → Dallas.
  - `data class ChannelIdentity(val rowKey: String, val displayName: String, val genre: LiveGenre, val network: Network?, val callSign: String?, val market: String?, val region: Region?, val loopShow: String?, val hidden: Boolean)`
  - `companion object { fun of(name: String, categoryName: String): ChannelIdentity }`
  - `object GuideMatcher { fun match(channels: List<ChannelCandidate>, locals: List<XmltvChannel>, cable: List<XmltvChannel>): Map<Pair<Long, String>, String> }`, keyed by (sourceId, channelId) → epgshare guideId.
  - `object PlaceholderLogos { fun of(channels: List<LiveChannel>): Set<String> }`
- **Rules the tests pin:**
  - `hidden` is true for headings, empty slots and ended slots.
  - `rowKey` merges quality and backup copies only.
  - **Network** comes from a network word in the name (not the category).
  - **Market** comes from a city in the name (`null` when none of `DEFAULT_MARKETS` matches).
  - **`loopShow`** is the show for `24/7:` names.
  - **Genres:** a known-channel table (CNN, MSNBC, Fox News, CNBC, Bloomberg → NEWS; ESPN*, FS1, NFL Network, beIN → SPORTS; Comedy Central → COMEDY; HBO, Showtime, TCM, Starz → MOVIES; Disney, Nickelodeon, Cartoon Network → KIDS; Discovery, History, HGTV, Food, National Geographic → DOCUMENTARY), then category keywords (`24/7 COMEDY` → COMEDY, `24/7 MOVIES` → MOVIES, `SPORT` → SPORTS, `KIDS` → KIDS, `PPV` → EVENTS), then local network stations → NETWORK. Default ENTERTAINMENT.
  - **Matching locals:** a `[KW][A-Z]{2,3}` call sign found in `epgshare_locals` ids (`WNBC-DT.us_locals1` → WNBC). It counts only when it's in parentheses in the name, or the name also contains a network word as a whole token.
  - **Matching cable:** normalised name minus quality and region words equals a normalised epgshare US2 display name or id. When it fits several ids, no match.

- [ ] **Step 1: Write the failing tests:**
  - `headingsAndEmptySlotsAreHidden`: "####### NBC ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ #######", "- NO EVENT STREAMING - | 8K EXCLUSIVE | US: MLS PPV 1", "ENDED | DET - PISTONS VS BOS - CELTICS | …".
  - `qualityCopiesShareARowKey`: "US: A&E HD" and "TV: A&E ᴿᴬᵂ" share a key; "US: NBC BRAVO (EAST) (D) ᴿᴬᵂ" and "(WEST)" don't.
  - `regionsAndCallSignsStayApart`: "NBC 5 (WRAL) RALEIGH/DURHAM (A)" ≠ "NBC 5 (WMAQ) CHICAGO".
  - `networkMarketAndCallSign`: "US: NBC 4 NEW YORK (WNBC)" → NBC, market "New York", WNBC; "TV: A3 NEW YORK 02 WCBS CBS ᴿᴬᵂ" → CBS, "New York", WCBS; "NBC 5 (WRAL) RALEIGH/DURHAM" → market null.
  - `genresFromChannelsAndCategories`: "US: FOX NEWS HD" → NEWS; "24/7: 30 ROCK" in "US| 24/7 COMEDY ᴿᴬᵂ ⁶⁰ᶠᵖˢ" → COMEDY with loopShow "30 Rock"; "US: ESPN HD" → SPORTS; a PPV slot → EVENTS.
  - `callSignNeedsParenthesesOrNetworkWord`: "24/7: DOCTOR WHO", "AT&T: HONG KONG FIGHT CLUB ᴿᴬᵂ" and "24/7: SCARECROW AND MRS. KING" → no guide; "US: DOCTOR WHO 4K" and "AT&T: CLASSIC DOCTOR WHO ᴿᴬᵂ" → no guide; "NBC 13 (WHO) DES MOINES (A) ᴿᴬᵂ" → `WHO-DT.us_locals1`.
  - `ambiguousCableNameMatchesNothing`
  - `realLineupMatchRates`: over `visible_channels.tsv` (regular, non-PPV rows): ≥ 1,300 locals matched, ≥ 650 cable, and zero `24/7:` rows matched to locals.
  - `placeholderLogosAreTheWidelySharedOnes`: from the fixture's logo hashes, the hash used 1,600 times is a placeholder and a unique one is not.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ChannelIdentityTest' --tests '*GuideMatcherTest'`. Expected: compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the same command.** Expected: PASS, with matching over the full fixture under 2 s.
- [ ] **Step 5: Commit** `feat(guide): channel identity, genres and guide matching`.

### Task 4: Guide sources and the daily refresher

**Files:**
- Create: `data/guide/EpgShareSource.kt`, `data/guide/GuideRefresher.kt`, `data/guide/PlaybackState.kt`
- Modify: `data/xtream/XtreamApi.kt` (`suspend fun xmltv(cfg): InputStream` via `xmltv.php`), `data/source/ChannelSource.kt` + `XtreamChannelSource.kt` (`suspend fun openGuide(sourceId: Long, config: SourceConfig): Result<InputStream>`), `testing/FakeChannelSource.kt`, `AppGraph.kt` (`guideRefresher`, `playback`), `MainActivity.kt` (start it after `refreshStale().join()`), `GoodTvApp.kt` (refresh when a sync finishes, like `startGenreFillAfterSyncs`), `ui/player/PlayerScreen.kt` (`DisposableEffect`: playing true while shown)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/guide/GuideRefresherTest.kt`, `EpgShareSourceTest.kt`

**Interfaces:**
- **Consumes:** Tasks 1–3, plus `LibraryStore.observeSportsCandidates()` (all visible live channels with category names).
- **Produces:**
  - `class EpgShareSource(http: OkHttpClient, baseUrl: HttpUrl = "https://epgshare01.online/epgshare01/".toHttpUrl())` with `suspend fun open(file: String): InputStream` (gunzipped, streaming).
  - `object EpgShareFiles { const val LOCALS = "epg_ripper_US_LOCALS1.xml.gz"; const val CABLE = "epg_ripper_US2.xml.gz" }`
  - `class PlaybackState { val playing: MutableStateFlow<Boolean> }`
  - `class GuideRefresher(store: GuideStore, library: LibraryStore, registry: SourceRegistry, epgShare: EpgShareSource, playback: PlaybackState, clock: () -> Long, io: CoroutineDispatcher)` with `suspend fun refreshIfStale()` (Mutex, single flight).
- **Each run:**
  1. Read the channel lists of both epgshare01 files, run `GuideMatcher`, write the links, and store programmes for matched ids only.
  2. If the provider is not stale and not playing, read its guide for channels that have `epgChannelId` and no epgshare01 link. These are stored with guideId `"P:<epgChannelId>"`, with links pointing there.
  3. `markFetched` each origin only on success. Any exception leaves that origin's stored guide untouched.

- [ ] **Step 1: Write the failing tests** (MockWebServer serving gzipped fixtures):
  - `matchesAndStoresOnlyMatchedChannels`
  - `notAgainWithin24Hours`
  - `failedDownloadKeepsLastGoodGuide`: a second run returns HTTP 500 and the first run's programmes remain.
  - `providerGuideWaitsWhilePlaying`: `playing = true` gives no `openGuide` call; after `false`, the next run calls it once.
  - `providerOnlyForUnmatchedChannels`
  - `openGunzipsAStream`
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*GuideRefresherTest' --tests '*EpgShareSourceTest'`. Expected: compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the same command, then the whole unit suite.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(guide): epgshare01 and provider guide refresh, daily, never while playing`.

### Task 5: Favourite channels and the guide board

**Files:**
- Create: `data/guide/GuideRepository.kt` (`GuideBoard`, `GuideSection`, `GuideRow`, `GuideFilter`, `GuideSearchHit`)
- Modify: `AppGraph.kt` (`val guide: GuideRepository`)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/guide/GuideRepositoryTest.kt`

**Interfaces:**
- **Consumes:** Tasks 1–4.
- **Produces:**
  - `enum class GuideFilter { ALL, FAVOURITES, NETWORK, NEWS, SPORTS, ENTERTAINMENT, COMEDY, MOVIES, KIDS, DOCUMENTARY, EVENTS }`
  - `data class GuideRow(val key: String, val name: String, val logoUrl: String?, val genre: LiveGenre, val feeds: List<ChannelCandidate>, val programmes: List<GuideProgramme>, val loopShow: String?, val favourite: Boolean)`. Feeds are ordered by `ChannelOrder.sort`.
  - `data class GuideSection(val title: String, val network: Network?, val rows: List<GuideRow>, val more: List<GuideRow>)`. `more` holds the non-default-market locals; it's empty for other sections.
  - `data class GuideBoard(val sections: List<GuideSection>, val windowStartMs: Long)`
  - `data class GuideSearchHit(val row: GuideRow, val programme: GuideProgramme?)`
  - `class GuideRepository(library: LibraryStore, store: GuideStore, refresher: GuideRefresher, clock: () -> Long, compute: CoroutineDispatcher)`:
    - `fun board(filter: GuideFilter, windowStartMs: Long): Flow<GuideBoard>`
    - `fun search(query: String): Flow<List<GuideSearchHit>>`
    - `suspend fun setFavourite(rowKey: String, favourite: Boolean)`
    - `suspend fun refreshEventSlots()`, which reuses `LiveRefresher` from the Sports code.
- **Board rules:**
  - **Section order:** Favourites (when any), then one section per network (in Network order), then the genres in order.
  - **ALL** includes everything except EVENTS. **EVENTS** includes only event slots.
  - **Programmes** cover [windowStart, windowStart + 3 h).
  - **Search** matches programme titles from now on, channel names and `loopShow`. On-now hits come first, then by start time.

- [ ] **Step 1: Write the failing tests** (in-memory stores):
  - `favouritesFirstThenNetworksThenGenres`
  - `defaultMarketsShownOthersUnderMore`: WNBC New York in rows; WRAL Raleigh in `more`.
  - `copiesMergeIntoOneRowWithFeeds`: two A&E copies → one row, 2 feeds.
  - `eventsOnlyUnderEventsFilter`
  - `hiddenRowsNeverAppear`
  - `loopShowRowsHaveNoProgrammes`
  - `logoPrefersGuideThenRealProviderThenNone`
  - `searchFindsLaterShowAndLoopAndChannel`: "Hannity" (21:00), "30 Rock" (24/7), "CNN" (name).
  - `programmeCrossingWindowEdgeIsClipped`: a 17:30–19:00 programme is included for window 18:00.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*GuideRepositoryTest'`. Expected: compile failure.
- [ ] **Step 3: Implement.** Grouping and identity run on `compute`, never on Main.
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(guide): guide board with favourites, networks, markets and search`.

### Task 6: The guide screen in the Live tab

**Files:**
- Create: `ui/live/GuideScreen.kt` (`GuideScreen`, grid, details panel), `ui/live/GuideViewModel.kt`, `ui/live/FeedPicker.kt` (moved out of `SportsScreen.ChannelPanel`; Sports uses it)
- Modify: `ui/home/HomeScreen.kt` (`HomeTab.LIVE -> GuideScreen(onPlay = onNavigate)`), `ui/sports/SportsScreen.kt` (use `FeedPicker`), `res/values/strings.xml`
- Delete: `ui/live/LiveScreen.kt`, `ui/live/LiveViewModel.kt`, `androidTest/.../ui/LiveScreenTest.kt` (replaced by `GuideScreenTest`)
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/GuideScreenTest.kt`, plus updates to `HomeTabsTest.kt` for the Live tab's new content

**Interfaces:**
- **Consumes:** `GuideRepository` (Task 5), `WatchListDialog(title, action, onAction, onDismiss)` (reuse it for the favourite menu), `PlayerKey`.
- **Produces:** `@Composable fun GuideScreen(onPlay: (PlayerKey) -> Unit, board: Flow<GuideBoard>? = null, clock: () -> Long = System::currentTimeMillis)`. The `board` parameter replaces the real data in tests, as `SportsScreen` does.
- **Layout:** as in the approved mockup.
  - Chips row and search field above the grid.
  - Channel cell 220 dp wide: logo on a `#ECEEF2` tile, or the name when there's no logo, with the name beside it.
  - 6 half-hour columns and a red now line.
  - Details panel below: picture, title · channel, time · genre · "N min left", description.
  - A "More NBC" row sits at the end of each network section; Select expands it in place.

- [ ] **Step 1: Write the failing tests** (fixed board, fixed clock 18:10):
  - `chipsAndSectionsShow`
  - `focusStartsOnTheProgrammeOnNowInTheFirstRow`
  - `rightPastTheLastColumnPagesForwardHalfAnHour`: header shows 6:30 after paging.
  - `leftNeverGoesBeforeNow`
  - `selectOnNowPlaysTheSingleFeed`
  - `selectWithSeveralFeedsOpensThePicker`
  - `selectOnALaterProgrammeOnlyShowsDetails`
  - `menuOnChannelOffersFavourite`
  - `moreRowExpands`
  - `noGuideRowSaysSo`
  - `loopRowShowsAllDay`
  - `backFromThePlayerReturnsToTheSameCell`
  - `programmeCrossingWindowEdgeIsClipped`: the 17:30–19:00 cell starts at the first column.
- [ ] **Step 2: Run (backup first)** `/tmp/goodtv-tools/runclass.sh com.keyspaceapps.goodtv.ui.GuideScreenTest`. Expected: FAIL (no `GuideScreen`).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the class, then `SportsScreenTest`, then `HomeTabsTest`.** Expected: all pass.
- [ ] **Step 5: Commit** `feat(guide): Live tab is an on-screen TV guide`.

### Task 7: Search and the Events view

**Files:**
- Modify: `ui/live/GuideScreen.kt`, `ui/live/GuideViewModel.kt`, `res/values/strings.xml`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/GuideScreenTest.kt` (add cases)

**Interfaces:**
- **Consumes:** `GuideRepository.search`, `GuideRepository.refreshEventSlots`, `GuideFilter.EVENTS`.
- **Search:**
  - Typing replaces the grid with a results list: logo, title, channel, and "On now · 25 min left" or "Tonight 9:00 PM" (time in the device's format via `DateFormat.getTimeFormat`).
  - **Select:** an on-now hit plays, a later hit shows its details, and a channel hit plays.
  - Clearing the search restores the grid and its focus.
- **Events:**
  - Opening the Events chip triggers `refreshEventSlots()` (at most every 30 min, as Sports does).
  - Slots sit at their parsed start time (`ChannelTimes.parse`), or span the row when there's none.

- [ ] **Step 1: Write the failing tests:**
  - `searchShowsOnNowFirstThenLater`
  - `searchHitOnNowPlays`
  - `searchHitLaterShowsDetails`
  - `clearingSearchRestoresTheGrid`
  - `eventsChipShowsSlotsAtTheirTime`
- [ ] **Step 2: Run** `/tmp/goodtv-tools/runclass.sh com.keyspaceapps.goodtv.ui.GuideScreenTest`. Expected: the new cases FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run:**
  - `./gradlew :app:testDebugUnitTest :app:lintDebug`
  - `/tmp/goodtv-tools/runclass.sh` (the full connected suite)

  Expected: all pass, and data is restored after the run.
- [ ] **Step 5: Device check** with Dave's restored data (which triggers the first real guide download):
  - **Live tab:** the guide fills, the NBC section shows the New York, Los Angeles, Chicago, Philadelphia and Dallas stations with real logos, and "More NBC" expands.
  - **Search** finds "Hannity", and a favourite moves to the top.
  - **Memory:** watch memory during the download (`adb shell dumpsys meminfo com.keyspaceapps.goodtv`) and record the peak.
  - **Screenshots** go to `.context/guide-*.png`.
- [ ] **Step 6: Commit** `feat(guide): search shows and channels; Events view`.
