# Sports Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Sports tab listing today's NHL, NBA, MLB, NFL, WNBA and PWHL games that are on now or later today, grouped by league with team logos. Selecting a game plays its channel, or lets the user pick when there are several.

**Architecture:**
- **Schedules:** two schedule sources, ESPN's scoreboard and PWHL HockeyTech, produce `SportsGame`s.
- **Matching:** a pure `GameMatcher` pairs each game with the provider's live channels whose names mention both teams.
- **Freshness:** `LiveRefresher` re-downloads only the live channel list when it's over 30 minutes old.
- **Combining:** `SportsRepository` merges these into a `SportsBoard`, re-read every 60 s while the screen is open.
- **Display:** `SportsScreen` shows the board and opens the player or a channel picker.

**Tech Stack:** Kotlin, Compose for TV, OkHttp and kotlinx.serialization (like `TmdbApi`), Room, Coil, JUnit and `mockwebserver3`, Compose UI tests on the `GoogleTV_API34` emulator.

**Spec:** `docs/superpowers/specs/2026-10-21-sports-screen-design.md`, approved 2026-10-21. Read it; this plan argues from it.

## Global Constraints

- **Leagues and order:** NHL, NBA, MLB, NFL, WNBA, PWHL. Nothing else appears.
- **What's shown:** finished games are never shown. Live games come first in each league, then later-today games by start time.
- **Sources:**
  - ESPN: `https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard?dates=YYYYMMDD`, with paths `hockey/nhl`, `basketball/nba`, `baseball/mlb`, `football/nfl`, `basketball/wnba`.
  - PWHL: `https://lscluster.hockeytech.com/feed/?feed=modulekit&view=scorebar&client_code=pwhl&key=446521baf8c38984&fmt=json&lang=en&numberofdaysahead=1&numberofdaysback=1`. Its body may be wrapped in `( … )`.
- **User-Agent:** `GoodTV/0.2` on schedule requests.
- **One stream at a time:** nothing on this screen opens a stream except the chosen channel.
- **Credentials:** never print or log provider credentials.
- **Parsing:** the live list (about 57,000 channels) is streamed, never read into one string.
- **Copy, verbatim:** "Sports", "LIVE", "On %d channels", "Channel not known", "No games on today", "Couldn't load %s schedule".
- **Tests:** they never reach the real sources. Before any `connectedDebugAndroidTest` run, back up the emulator app's data (DB, `-wal`, `-shm`, `files/datastore`, `files/subtitles`), because the run uninstalls the app. Restore it afterwards with the script approach, using explicit file names.

## Review Focus

1. **Shared names across leagues on the same night** (Kings, Rangers, Jets, "New York"): a channel fitting two games matches neither unless a league word decides. Pinned in Task 3: `sharedNicknameNeedsALeagueWord`.
2. **A channel for yesterday's same matchup** (MLB series, NHL back-to-backs) still named "LIVE … Mon 19 Oct": it must not attach to today's game. Pinned in Task 3: `staleDateOutsideWindowDoesNotMatch`.
3. **One league's schedule fails while others work:** that league shows "Couldn't load NHL schedule", and the others still appear. Pinned in Task 5: `oneFailingSourceIsFlaggedOthersStillShow`.
4. **A live game that started before midnight local time** (West Coast late games): still shown as live on the new day. Pinned in Task 5: `liveGameFromYesterdayStillShows`.
5. **The PWHL expansion teams whose nickname is "PWHL"** ("PWHL Las Vegas"): "PWHL" must never count as a team name, or every PWHL channel matches every game. Pinned in Task 3: `leagueWordIsNeverATeamName`.

---

## File Structure

Paths are under `app/src/main/java/com/keyspaceapps/goodtv/`. Tests mirror them, and fixtures go in `app/src/test/resources/sports/`.

| File | Responsibility |
|---|---|
| `data/sports/SportsModels.kt` | `League`, `Team`, `GameState`, `SportsGame`, `SportsException`, the `ScheduleSource` interface |
| `data/sports/EspnScoreboard.kt` | `ScheduleSource` for the five ESPN leagues |
| `data/sports/PwhlScorebar.kt` | `ScheduleSource` for the PWHL (HockeyTech) |
| `data/sports/GameMatcher.kt` | pure: channel ↔ game matching, plus `ChannelTimes` (dates in names) |
| `data/sports/ChannelOrder.kt` | pure: picker order |
| `data/sports/LiveRefresher.kt` | refreshes only the live list when it's stale |
| `data/sports/SportsRepository.kt` | combines everything into `Flow<SportsBoard>` |
| `data/source/ChannelSource.kt`, `data/xtream/XtreamChannelSource.kt` (modify) | `fetchLive` |
| `data/repo/LibraryStore.kt`, `data/db/RoomLibraryStore.kt`, `data/db/Daos.kt` (modify) | `replaceLive`, `observeSportsCandidates` |
| `ui/sports/SportsScreen.kt`, `ui/sports/SportsViewModel.kt` | the tab, cards and picker |
| `ui/nav/NavKeys.kt`, `ui/home/HomeScreen.kt`, `AppGraph.kt`, `res/values/strings.xml` (modify) | wiring |

---

### Task 1: Models and the ESPN scoreboard source

**Files:**
- Create: `data/sports/SportsModels.kt` and `data/sports/EspnScoreboard.kt`.
- Test: `app/src/test/.../data/sports/EspnScoreboardTest.kt`.
- Fixture: `app/src/test/resources/sports/espn_nhl.json`, a real response captured 2026-10-20 at `/tmp/sportsfx/espn_nhl.json`. Copy it in and keep all events.

**Interfaces:**
- Produces:
  - `enum class League(val label: String, val espnPath: String?)`: `NHL("NHL","hockey/nhl")`, `NBA("NBA","basketball/nba")`, `MLB("MLB","baseball/mlb")`, `NFL("NFL","football/nfl")`, `WNBA("WNBA","basketball/wnba")`, `PWHL("PWHL", null)`. Declaration order is display order.
  - `data class Team(val displayName: String, val nickname: String?, val location: String?, val abbreviation: String, val logoUrl: String?)`
  - `enum class GameState { SCHEDULED, LIVE, FINAL }`
  - `data class SportsGame(val league: League, val id: String, val startsAt: Instant, val state: GameState, val away: Team, val home: Team, val awayScore: String?, val homeScore: String?, val detail: String?, val broadcasters: List<String>)`, where `key = "${league.name}:$id"`.
  - `class SportsException(message: String) : Exception(message)`
  - `interface ScheduleSource { suspend fun games(league: League, day: LocalDate): List<SportsGame> }`, which throws `SportsException` on network or format failure.
  - `class EspnScoreboard(client: OkHttpClient, baseUrl: HttpUrl = "https://site.api.espn.com/apis/site/v2/sports/".toHttpUrl(), io: CoroutineDispatcher = Dispatchers.IO) : ScheduleSource`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun requestsTheLeaguePathAndDate()       // games(NHL, 2026-10-20) → GET /…/hockey/nhl/scoreboard?dates=20261020, User-Agent "GoodTV/0.2"
@Test fun mapsTeamsScoresStateAndBroadcasters() // fixture: first event → away/home by competitors[].homeAway; MTL home: Team("Montreal Canadiens","Canadiens","Montreal","MTL","https://a.espncdn.com/i/teamlogos/nhl/500/scoreboard/mtl.png"); broadcasters == ["ESPN+","NBC Sports BA"]
@Test fun statesMapFromEspn()                  // status.type.state "pre"→SCHEDULED, "in"→LIVE (detail = shortDetail, e.g. "13:16 - 2nd"), "post"→FINAL
@Test fun failuresAreSportsExceptions()         // HTTP 500 and malformed JSON → SportsException
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*EspnScoreboardTest*'`
Expected: compilation fails.

- [ ] **Step 3: Implement the models and `EspnScoreboard`**

- **Field mapping:**
  - `events[].id`, and `date` as an ISO instant (`2026-10-20T23:00Z`, no seconds; parse leniently).
  - `competitions[0].competitors[]`: `homeAway`, `score`, and `team.displayName`/`name` (nickname)/`location`/`abbreviation`/`logo`.
  - `competitions[0].broadcasts[].names`, flattened and de-duplicated.
- **A league with no ESPN path:** throw `IllegalArgumentException`.

- [ ] **Step 4: Run them to confirm they pass**

Run: the same command as Step 2.
Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(sports): schedule models and ESPN scoreboard source"
```

---

### Task 2: The PWHL HockeyTech source

**Files:**
- Create: `data/sports/PwhlScorebar.kt`.
- Test: `PwhlScorebarTest.kt`.
- Fixture: `sports/pwhl.json`. Copy it from `/tmp/sportsfx/pwhl.json`, trimmed to the first 6 games.

**Interfaces:**
- Consumes: Task 1 models.
- Produces: `class PwhlScorebar(client: OkHttpClient, baseUrl: HttpUrl = "https://lscluster.hockeytech.com/feed/".toHttpUrl(), io: CoroutineDispatcher = Dispatchers.IO) : ScheduleSource`. It returns only games whose `GameDateISO8601` falls on `day` in the device zone, and throws for any league but PWHL.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun sendsTheLeagueKeyAndFormat()      // query has feed=modulekit, view=scorebar, client_code=pwhl, key=446521baf8c38984, fmt=json
@Test fun stripsJsonpParentheses()          // body "(" + fixture + ")" parses the same as the bare fixture
@Test fun mapsTeams()                       // first game: home Team(displayName="PWHL Las Vegas", nickname=null, location="Las Vegas", abbreviation="VEG", logoUrl="https://assets.leaguestat.com/pwhl/logos/50x50/12.png"); away "Minnesota Frost" nickname "Frost"; startsAt 2026-11-23T02:50:00Z
@Test fun statusMapping()                   // GameStatus "1"→SCHEDULED, "2" or "3"→LIVE (detail = GameStatusString), "4"→FINAL; scores from HomeGoals/VisitorGoals only when LIVE
@Test fun onlyTheRequestedDay()             // day 2026-11-22 in zone America/Toronto → only the 21:50 game
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*PwhlScorebarTest*'`
Expected: compilation fails.

- [ ] **Step 3: Implement `PwhlScorebar`**

- `nickname` is null when `HomeNickname`/`VisitorNickname` equals "PWHL" (case-insensitive).
- `broadcasters` is empty; this feed lists none.
- The zone is injectable for the test. The constructor gains `zone: () -> ZoneId = ZoneId::systemDefault`.

- [ ] **Step 4: Run them to confirm they pass**

Run: the same command as Step 2.
Expected: 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(sports): PWHL schedule from HockeyTech"
```

---

### Task 3: Matching channels to games

**Files:**
- Create: `data/sports/GameMatcher.kt`.
- Test: `GameMatcherTest.kt`.

**Interfaces:**
- Consumes: Task 1 models, and `LiveChannel` (existing).
- Produces:
  - `data class ChannelCandidate(val channel: LiveChannel, val categoryName: String)`
  - `object ChannelTimes { fun parse(name: String, now: Instant): Instant? }`, which reads the formats below. Without a time zone it assumes UTC; the 30-hour window absorbs the error.
  - `object GameMatcher { const val WINDOW_HOURS = 30L; fun match(games: List<SportsGame>, channels: List<ChannelCandidate>, now: Instant): Map<String, List<ChannelCandidate>> }`, keyed by `SportsGame.key`. Every game key is present, with an empty list when unmatched.

- [ ] **Step 1: Write the failing tests**

Every channel name below is a real one from the provider.

```kotlin
// games: NHL San Jose Sharks @ Montreal Canadiens (the fixture has MTL home), NBA Knicks @ 76ers, NHL Devils/Avalanche, NBA Pistons/Celtics (FINAL), NHL Jets (Winnipeg) vs Blues (St. Louis), NFL Jets (New York) vs Patriots
@Test fun matchesTheProvidersNamingStyles() {
    // each of these must land on the right game:
    // "LIVE | NHL MONTREAL - SAN JOSE | Tue 20 Oct 19:05 EDT (US) | 8K EXCLUSIVE | US: VIAPLAY PPV 3"
    // "NHL: San Jose vs Montreal @ Oct 20 7:00 PM :Sportsnet+  15"
    // "US (Peacock 005) | 76ers vs. Knicks (2026-10-20 18:30:05)"
    // "LIVE | NYK - KNICKS VS PHI - 76ERS | Tue 20 Oct 18:00 EDT (US) | 8K EXCLUSIVE | US: NBA PASS PPV 2"
    // "LIVE | NHL: NEW JERSEY DEVILS - COLORADO AVALANCHE | Wed 21 Oct 00:55 EEST (FI)"
    // "Next | 76ers vs. Knicks | all | 20-10-2026 | 01:00 (GMT) | 8K EXCLUSIVE | CA: SOCCER PPV 30"   (22 h off, inside the window)
}
@Test fun endedAndEmptySlotsNeverMatch()      // "ENDED | DET - PISTONS VS BOS - CELTICS | …", "End | Celtics vs. Pistons | …", "- NO EVENT STREAMING - | 8K EXCLUSIVE | US: MLS PPV 1" → nothing
@Test fun needsBothTeams()                    // "US: NHL NETWORK", "MONTREAL CANADIENS PREGAME" → nothing
@Test fun sharedNicknameNeedsALeagueWord()    // "Next | Jets vs. Blues | …" → NHL game only (Blues is NHL-only); "Jets vs. Patriots" → NFL; a channel "Jets" + an ambiguous opponent fitting both → neither
@Test fun leagueWordInCategoryDecides()       // name "New York vs Boston @ Oct 20 7:00 PM" with categoryName "US| NBA PPV" → only the NBA game when an NHL NY–BOS game also exists
@Test fun staleDateOutsideWindowDoesNotMatch()// "LIVE | NHL MONTREAL - SAN JOSE | Sun 18 Oct 19:05 EDT" with game 2026-10-20T23:00Z → nothing
@Test fun abbreviationsOnlyAsWholeWords()     // "SJ" must not match inside "SJÖBERG" or "SJÄLLAND"; "MTL" matches in "MTL - SJ"
@Test fun leagueWordIsNeverATeamName()        // PWHL teams with nickname null: channel "PWHL PPV 1" matches no PWHL game
@Test fun accentsAndCaseDoNotMatter()         // "Montréal vs. Nashville" matches Montreal Canadiens vs Nashville Predators
@Test fun channelTimesFormats() {
    // "Tue 20 Oct 19:05 EDT" → 2026-10-20T23:05Z ; "@ Oct 20 7:00 PM" → 2026-10-20T19:00Z (no zone → UTC)
    // "(2026-10-20 18:30:05)" → 2026-10-20T18:30:05Z ; "20-10-2026 | 01:00 (GMT)" → 2026-10-20T01:00Z
    // "start:2026-10-18 22:08:00" → 2026-10-18T22:08Z ; "Sat 8th Nov 3:00 PM ET" → 2026-11-08T20:00Z ; no time → null
    // a date without a year takes the year that puts it nearest `now`
}
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*GameMatcherTest*'`
Expected: compilation fails.

- [ ] **Step 3: Implement**

- **Normalising names:** lower-case, strip accents (`Normalizer` NFD, then drop marks), turn non-letters and non-digits into spaces, and compare on word boundaries. A multi-word name must match as consecutive words.
- **What identifies a team:** `displayName`, `location`, `nickname` and `abbreviation`, as non-null forms.
  - A form equal to a league word (`nhl nba mlb nfl wnba pwhl`) is never used.
  - An abbreviation counts only when it is 2–4 letters long and appears as a whole word.
- **Never a match:**
  - names whose normalised start is `ended`, `end`, `final` or `no event streaming`;
  - names that contain `replay`.
- **The steps:**
  1. Find the candidate games whose two teams both match.
  2. If the name's `ChannelTimes` is outside ±`WINDOW_HOURS` of `startsAt`, drop that candidate.
  3. If more than one candidate remains, keep only those whose league word appears in the name or the category name.
  4. If more than one still remains, the channel matches nothing.
- **Time zones:** EDT −4, EST −5, ET −5 in Nov–Mar and −4 otherwise, CEST +2, CET +1, EEST +3, EET +2, GMT/UTC 0, NDT −2.5, NST −3.5, PDT −7, PST −8. Any other zone is treated as UTC.

- [ ] **Step 4: Run them to confirm they pass**

Run: the same command as Step 2.
Expected: 10 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(sports): match live channels to games by teams, date and league"
```

---

### Task 4: Picker order

**Files:**
- Create: `data/sports/ChannelOrder.kt`.
- Test: `ChannelOrderTest.kt`.

**Interfaces:**
- Consumes: Task 3 `ChannelCandidate`.
- Produces: `object ChannelOrder { fun sort(list: List<ChannelCandidate>): List<ChannelCandidate> }`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun northAmericaFirstThenEnglishFrenchOthersThenLiveThenName()
// categories "US| NBA PASS PPV", "CA| SPORTSNET+ PPV", "CA| SOCCER PPV" (all North American) before "SE| VIAPLAY PPV"
// within NA: English before "NHL: French Language Broadcast San Jose vs Montreal …" before "… (Spanish) …"
// within a group: names starting "LIVE" before "Next", then alphabetical by name
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ChannelOrderTest*'`
Expected: compilation fails.

- [ ] **Step 3: Implement**

- **North American:** the category name starts with `US|` or `CA|`.
- **Language rank:**
  - French: the name contains `french`, `français`, `francais`, or the language field is `FR`.
  - Spanish and other: `spanish`, `español`, or `(es)`, or any other language field.
  - English: the rest.
- **Live first:** a name whose normalised start is `live`.

- [ ] **Step 4: Run it to confirm it passes**

Run: the same command as Step 2.
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(sports): picker order"
```

---

### Task 5: Live-only refresh and the sports repository

**Files:**
- Create: `data/sports/LiveRefresher.kt` and `data/sports/SportsRepository.kt`.
- Modify:
  - `data/source/ChannelSource.kt` and `data/xtream/XtreamChannelSource.kt`;
  - `data/repo/LibraryStore.kt`, `data/db/RoomLibraryStore.kt` and `data/db/Daos.kt`;
  - `test/.../testing/FakeLibraryStore.kt` and `FakeChannelSource.kt`.
- Test: `LiveRefresherTest.kt` and `SportsRepositoryTest.kt` (JVM); `androidTest/.../data/db/SportsCandidatesTest.kt` (Room).

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  - **Fetching:** `ChannelSource.fetchLive(sourceId: Long, config: SourceConfig): Result<LiveCatalog>`, with `data class LiveCatalog(val categories: List<Category>, val live: List<LiveChannel>)`. The categories are LIVE only. For Xtream it is the live categories plus live streams, mapped exactly as `fetchCatalog` maps them.
  - **Storing:** `LibraryStore.replaceLive(sourceId: Long, live: LiveCatalog)`. In one transaction it replaces only this source's LIVE categories and live channels. Hidden-category rows and `lastSyncAt` stay as they are.
  - **Reading:** `LibraryStore.observeSportsCandidates(): Flow<List<ChannelCandidate>>` returns every live channel of enabled sources in visible categories, with its category name.
  - **Refreshing:** `class LiveRefresher(store: LibraryStore, registry: SourceRegistry, syncing: () -> Boolean, clock: () -> Long = System::currentTimeMillis)`, with `const val MAX_AGE_MS = 30 * 60 * 1000L` and `suspend fun refreshIfStale(): Unit`. A source is fresh when max(`lastSyncAt`, its last refresh in this process) is under `MAX_AGE_MS` old. It is skipped while `syncing()`. Failures are swallowed per source, since the screen keeps what it has.
  - **Combining:**
    - `data class GameWithChannels(val game: SportsGame, val channels: List<ChannelCandidate>)`
    - `data class LeagueGames(val league: League, val games: List<GameWithChannels>, val failed: Boolean)`
    - `data class SportsBoard(val leagues: List<LeagueGames>)`
    - `class SportsRepository(sources: Map<League, ScheduleSource>, store: LibraryStore, refresher: LiveRefresher, clock: () -> Instant = Instant::now, zone: () -> ZoneId = ZoneId::systemDefault, pollMs: Long = 60_000)` with `fun observe(): Flow<SportsBoard>`.
    - `observe()` starts `refresher.refreshIfStale()` once, concurrently: the first board is emitted from what the store already has, and the refreshed channels arrive through `observeSportsCandidates()`. It reads every league's schedule for today and yesterday every `pollMs` while collected, and matches against `observeSportsCandidates()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// LiveRefresherTest (FakeLibraryStore + FakeChannelSource with a fetchLive counter)
@Test fun refreshesOnlyStaleEnabledSources()        // lastSyncAt 10 min ago → no fetch; 40 min ago → one fetchLive; disabled source → none
@Test fun twiceInARowFetchesOnce()                  // refreshIfStale(); refreshIfStale() → one fetchLive
@Test fun skippedWhileAFullSyncRuns()               // syncing = { true } → none
@Test fun replaceLiveKeepsMoviesSeriesAndHidden()   // after replaceLive: movies/series unchanged, hidden LIVE category still hidden
// SportsRepositoryTest (fake ScheduleSources, StandardTestDispatcher, advanceTimeBy)
@Test fun leaguesInOrderLiveFirstNoFinals()         // NBA + NHL games given in mixed order → board leagues [NHL, NBA]; LIVE before SCHEDULED by startsAt; FINAL absent; league with no games absent
@Test fun liveGameFromYesterdayStillShows()         // zone America/Los_Angeles, now 2026-10-21T07:30Z (00:30 local): yesterday's LIVE game present, yesterday's SCHEDULED not
@Test fun oneFailingSourceIsFlaggedOthersStillShow()// NHL throws SportsException with no cache → LeagueGames(NHL, [], failed = true); NBA present
@Test fun lastGoodScheduleSurvivesAnOutage()        // NHL ok then throws → still shows the earlier games, failed = false
@Test fun pollsEveryMinuteWhileCollected()          // advanceTimeBy(60_000) → schedule read again; scores update
@Test fun channelsAttach()                          // candidates flow with "US (Peacock 005) | 76ers vs. Knicks (2026-10-20 18:30:05)" → that game's channels has it
// SportsCandidatesTest (androidTest, in-memory Room)
@Test fun visibleEnabledLiveChannelsWithCategoryNames() // hidden category and disabled source left out; categoryName filled
@Test fun replaceLiveTouchesOnlyLive()              // movies, series, hidden rows survive; live channels replaced
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*LiveRefresherTest*' --tests '*SportsRepositoryTest*'`
Expected: compilation fails.

- [ ] **Step 3: Implement**

- **DAO:** `observeSportsCandidates` joins `live_channels`, `categories` (kind 'LIVE') and `sources` (enabled = 1), excluding hidden categories. Category names go through the existing category-label cleanup if there is one; otherwise they're used raw.
- **Matching:** runs on `Dispatchers.Default`.
- **Polling:** use `flow { while (true) { emit(read()); delay(pollMs) } }`, combined with the candidates flow.

- [ ] **Step 4: Run them to confirm they pass**

Run: the same command as Step 2, then (after the backup) `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.data.db.SportsCandidatesTest`.
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(sports): live-only refresh and the sports board"
```

---

### Task 6: The Sports tab, cards and channel picker

**Files:**
- Create: `ui/sports/SportsViewModel.kt` and `ui/sports/SportsScreen.kt`.
- Modify:
  - `ui/nav/NavKeys.kt`: `HomeTab { HOME, LIVE, SPORTS, MOVIES, SERIES, SOURCES }`;
  - `ui/home/HomeScreen.kt`: the `when` branch and `labelRes`;
  - `AppGraph.kt`: `sports: SportsRepository`, built with `EspnScoreboard` for the five ESPN leagues, `PwhlScorebar` for PWHL, and `LiveRefresher(store, sourceRegistry, { syncManager.state.value is SyncState.Syncing })`;
  - `res/values/strings.xml`: `tab_sports` "Sports", `sports_live` "LIVE", `sports_on_channels` "On %d channels", `sports_channel_unknown` "Channel not known", `sports_none_today` "No games on today", `sports_failed` "Couldn't load %s schedule".
- Test: `androidTest/.../ui/SportsScreenTest.kt`. It uses a fake `SportsRepository` source: make `SportsScreen` take `board: StateFlow<SportsBoard?>` through the view model's constructor, as other screens inject repositories.

**Interfaces:**
- Consumes: Task 5 `SportsBoard`, `GameWithChannels` and `ChannelOrder.sort`.
- Produces:
  - `SportsScreen(onPlay: (PlayerKey) -> Unit)`. It plays a live channel with `PlayerKey(channel.sourceId, PlayerKey.KIND_LIVE, channel.id, null, channel.label)`.
  - `SportsViewModel(board: Flow<SportsBoard>)` with a `FocusMemory`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun leagueRowsWithLiveBadgeScoreAndStartTime() // NHL row: live MTL–SJ card shows "LIVE", "2 – 1", "13:16 - 2nd"; scheduled NBA card shows local start time
@Test fun oneChannelPlaysAtOnce()                    // select → onPlay(PlayerKey(sid, KIND_LIVE, id, null, label))
@Test fun severalChannelsOpenThePickerInOrder()      // "On 3 channels"; select → panel titled "Knicks @ 76ers"; rows in ChannelOrder order; select row 2 → onPlay with that channel; Back closes the panel, focus back on the card
@Test fun unmatchedGameShowsBroadcasterAndDoesNothing() // "ESPN+ · NBC Sports BA"; with no broadcasters "Channel not known"; select → no onPlay
@Test fun failedLeagueSaysSo()                       // LeagueGames(NHL, [], failed = true) → "Couldn't load NHL schedule"
@Test fun nothingTodaySaysSo()                       // empty board → "No games on today"
@Test fun backFromThePlayerRefocusesTheCard()        // as FocusRestoreTest does
```

- [ ] **Step 2: Run them to confirm they fail**

Back up the emulator data first, then run `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.ui.SportsScreenTest`.
Expected: compilation fails.

- [ ] **Step 3: Implement**

- **Rows:** reuse the `CategoryRows` look (titles in `titleMedium`, 48 dp side padding, `LazyRow`).
- **Cards:**
  - 280×160 dp, built on tv-material `Card`.
  - The away logo and abbreviation on the left, "@" in the centre, the home team on the right. The logos are 48 dp Coil `AsyncImage`s, with an abbreviation-in-a-circle fallback.
  - Below: the score and detail, or the start time; then the bottom line from the spec.
- **Picker:** reuse the player's `TrackPanel` look. Make it `internal` or copy its styling into a small `SportsChannelPanel`.
- **Focus:** initial focus via `onPlaced` on the first card of the first row (see the memory note on focus in tests).

- [ ] **Step 4: Run them to confirm they pass**

Run: the same command as Step 2.
Expected: 7 tests pass. Then run the whole instrumented suite. `HomeTabsTest`'s "UP lands on the beam-overlapping tab" assertion may move with the extra tab: update it to what the device does, without changing app behaviour.

- [ ] **Step 5: Check on the device and commit**

1. Restore the emulator data and launch the app.
2. Open Sports: today's leagues appear with logos.
3. Select a game with several channels: the picker appears, and choosing one plays it.
4. Press Back: you're back on the card.
5. Save screenshots to `.context/`.

```bash
git commit -m "feat(sports): Sports tab with league rows, game cards and a channel picker"
```

---

## Out of scope (decided)

- Finished games and highlights.
- Leagues other than the six.
- Notifications.
- Picking a channel automatically when there are several.
- Remembering the last picked feed.
- Naming the next day with games when today is empty (the spec's "if the sources give one cheaply"). The ESPN and HockeyTech calls are per day, so this would cost extra requests on every empty day. The screen just says "No games on today".
