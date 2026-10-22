# Guide Cold Open Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opening Live TV shows channel rows right away on a cold start, with programmes filling in behind a loading indicator.

**Architecture:**
- **Saved lineup.** The guide's lineup build (sort, channel identities, grouping, station folds, placeholder logos) moves out of `GuideRepository` into a pure `LineupBuilder`. A background `LineupKeeper` runs it whenever the lineup changes and saves the result in Room (v11), stamped with a lineup generation and a rules version.
- **Reading it.** `GuideRepository` reads the saved lineup first, filtered by the current enabled sources and hidden categories, and only builds in memory when nothing is saved.
- **Channels first.** Boards are emitted channels-first (programmes pending), then with programmes.
- **Early start.** The app warms the "All" board at launch, after the first frame.

**Tech Stack:** Kotlin, Room (exported schemas), coroutines/Flow, Compose for TV.

**Spec:** `docs/superpowers/specs/2026-10-22-guide-cold-open-design.md`

## Global Constraints

- **Unchanged behaviour:** what the guide shows, how rows group, and every matching rule stay as they are. All existing guide tests (`GuideRepositoryTest`, `ChannelIdentityTest`, `GuideMatcherTest`, `GuideScreenTest`, `GuideViewModelTest`) pass unmodified, except where a task says an expectation changes.
- **Database:** Room v10 → v11 with a real `MIGRATION_10_11` that keeps all data. Commit the generated `11.json`.
- **Visibility:** feeds of disabled sources or hidden categories never appear, even from a stale saved lineup. They are filtered on every read; a row left with no feeds is dropped.
- **Saved content:** lineup data only. Guide links and epgshare01 logos are resolved on read, as today; the provider-logo choice (placeholder or not) is saved per row.
- **Off Main:** all building and saving runs on the compute dispatcher. Saving is one transaction.
- **Loading copy:**
  - "Loading guide…" until the window's first programme query returns.
  - "Downloading guide…" while `GuideRefresher` is working and the window has no programmes yet.
  - "No guide info" only after programmes have loaded, for a row with none in the window.
- **Early start:** warms `board(GuideFilter.ALL, <current half-hour>)` after the first frame, and never delays the first screen.
- **Timing logs:** tag `GoodTvGuide`, debug level, counts and milliseconds only. Add the stages "saved lineup read" and "first board (saved)".
- **Connected runs:** use `/tmp/goodtv-tools/runclass.sh <class>`, one class per call; it backs up and restores Dave's data. Never play a stream.

## Review Focus

1. **A sync finishes while the app is closed.** On the next open the saved lineup is stale: the guide shows it at once, then switches to the rebuilt rows without moving focus or losing favourites. Pinned in Task 3: `staleSavedLineupShowsThenRebuilds`; Task 4: `rebuiltRowsKeepFocus`.
2. **Dave hides a category, or disables a source, then opens Live TV before the rebuild.** Those channels never show. Pinned in Task 3: `hiddenCategoryFeedsAreFilteredFromTheSavedLineup`.
3. **The app updates with new identity rules** (`RULES_VERSION` changes). The saved lineup is rebuilt, not trusted. Pinned in Task 2: `newRulesVersionRebuilds`.
4. **First ever open with no saved lineup.** It falls back to today's in-memory build and still shows the loading indicator. Pinned in Task 3: `noSavedLineupBuildsInMemory`.
5. **The keeper is cancelled mid-build** (app killed). The old saved lineup stays intact, and no half-written lineup is ever read. Pinned in Task 2: `cancelledBuildKeepsTheOldLineup`.

---

### Task 1: Storage v11 — saved lineup, lineup generation, visibility

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/guide/SavedLineup.kt` (models and `LineupStore` interface)
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/db/RoomLineupStore.kt`
- Modify: `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/GoodTvDatabase.kt` (`MIGRATION_10_11`, version 11), `data/db/RoomLibraryStore.kt` (bump the generation inside the existing write transactions of `insertSource`, `updateSource`, `deleteSource`, `replaceCatalog`, `replaceLive`, `setCategoriesHidden`), `AppGraph.kt` (`val lineupStore`)
- Create: `app/src/test/java/com/keyspaceapps/goodtv/testing/InMemoryLineupStore.kt`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/data/db/LineupStoreTest.kt`, `MigrationTest.kt` (add `migrate10To11AddsSavedLineup`)

**Interfaces:**
- **Produces:**
  - `data class SavedFeed(val channel: LiveChannel, val categoryName: String)`, ordered within its row. Store every `LiveChannel` field plus `categoryName`.
  - `data class SavedRow(val key: String, val position: Int, val name: String, val genre: LiveGenre, val network: Network?, val callSign: String?, val market: String?, val loopShow: String?, val providerLogo: String?, val sortKey: String, val loopKey: String?, val appCopy: Boolean, val feeds: List<SavedFeed>)`
  - `data class SavedLineup(val rows: List<SavedRow>, val generation: Long, val rulesVersion: Int)`
  - `data class Visibility(val enabledSources: Set<Long>, val hiddenLiveCategories: Set<Pair<Long, String>>)`
  - `interface LineupStore`:
    - `suspend fun saved(): SavedLineup?`
    - `fun observeSaved(): Flow<SavedLineup?>`
    - `suspend fun save(lineup: SavedLineup)` — one transaction, replaces everything.
    - `suspend fun generation(): Long`
    - `fun observeGeneration(): Flow<Long>`
    - `fun observeVisibility(): Flow<Visibility>`
- **Tables:**
  - `lineup_rows`: PK `key`; columns as `SavedRow` without feeds; index on `position`.
  - `lineup_feeds(rowKey, position, …LiveChannel fields…, categoryName)`: PK (`rowKey`, `position`); FK `lineup_rows` ON DELETE CASCADE.
  - `lineup_meta(id = 0 PK, savedGeneration, rulesVersion)`
  - `lineup_state(id = 0 PK, generation)`: starts at 0 and is bumped by `RoomLibraryStore` writes.

- [ ] **Step 1: Write the failing tests:**
  - `saveThenReadRoundTripsRowsFeedsInOrder` (2 rows × 3 feeds, every field).
  - `saveReplacesEverything`.
  - `writesBumpTheGeneration` (each of the six writes bumps it by ≥ 1).
  - `visibilityReflectsDisabledSourcesAndHiddenCategories`.
  - `migrate10To11AddsSavedLineup` (v10 data survives; the new tables exist and are empty; generation is 0).
- [ ] **Step 2: Run** `/tmp/goodtv-tools/runclass.sh com.keyspaceapps.goodtv.data.db.LineupStoreTest`, then the same for `…MigrationTest`. Expected: compile failure.
- [ ] **Step 3: Implement.** Copy the migration SQL from the generated `11.json`.
- [ ] **Step 4: Run both classes.** Expected: PASS. `11.json` is committed.
- [ ] **Step 5: Commit** `feat(guide): storage v11 for the saved lineup`.

### Task 2: LineupBuilder and LineupKeeper

**Files:**
- Create: `data/guide/LineupBuilder.kt` (the lineup build moved out of `GuideRepository.lineupOf`, `foldIntoStations` and `nameOf`; identical results)
- Create: `data/guide/LineupKeeper.kt`
- Modify: `data/guide/GuideRepository.kt` (call `LineupBuilder` instead of its private copy; behaviour unchanged), `AppGraph.kt` (`val lineupKeeper`), `GoodTvApp.kt` (start the keeper)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/guide/LineupBuilderTest.kt`, `LineupKeeperTest.kt`

**Interfaces:**
- **Consumes:** `LineupStore`, `SavedRow`/`SavedLineup` (Task 1), `ChannelIdentity`, `ChannelOrder`, `PlaceholderLogos`.
- **Produces:**
  - `object LineupBuilder { const val RULES_VERSION = 1; fun build(candidates: List<ChannelCandidate>): List<SavedRow> }`
  - `class LineupKeeper(library: LibraryStore, lineups: LineupStore, compute: CoroutineDispatcher, debounceMs: Long = 2_000)` with `fun start(scope: CoroutineScope)`.
  - **The keeper's behaviour:**
    - It watches `observeGeneration()` combined with the saved lineup.
    - It rebuilds when the generation differs from the saved generation, when the rules version differs from `RULES_VERSION`, or when nothing is saved.
    - It waits `debounceMs` after the last generation change, so a sync's burst of writes produces one build.
    - It reads `observeSportsCandidates().first()`, runs `LineupBuilder.build`, and saves `SavedLineup(rows, generationReadBeforeBuilding, RULES_VERSION)`.
    - If the generation moved during the build, it builds again.
    - On a build exception it logs a warning (class name only), keeps the old lineup, and retries on the next generation change.

- [ ] **Step 1: Write the failing tests:**
  - `builderMatchesTheRepositoryBuild`: over the full `visible_channels.tsv` fixture, `LineupBuilder.build` gives the same keys, names, genres, networks, markets and feed lists as the current `GuideRepository` rows. Capture those before moving any code.
  - `keeperSavesOnFirstStart`.
  - `generationChangeRebuildsOnce` (5 rapid bumps → 1 build).
  - `newRulesVersionRebuilds`.
  - `cancelledBuildKeepsTheOldLineup`.
  - `buildFailureKeepsTheOldLineupAndWarns`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*LineupBuilderTest' --tests '*LineupKeeperTest'`. Expected: FAIL (compile).
- [ ] **Step 3: Implement.** Move the code, don't rewrite it.
- [ ] **Step 4: Run them, then the whole unit suite.** Expected: all green, with `GuideRepositoryTest` unchanged.
- [ ] **Step 5: Commit** `feat(guide): build the lineup in the background and save it`.

### Task 3: The repository reads the saved lineup, channels first

**Files:**
- Modify: `data/guide/GuideRepository.kt`, `data/guide/GuideRefresher.kt` (add `val working: StateFlow<Boolean>`, true while a refresh runs)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/guide/GuideRepositoryTest.kt` (add cases)

**Interfaces:**
- **Consumes:** `LineupStore.observeSaved()`, `observeVisibility()` (Task 1); `LineupBuilder` (Task 2).
- **Produces:**
  - `GuideBoard(sections, windowStartMs, programmesLoaded: Boolean, downloading: Boolean)` — defaults `true`/`false`, so existing callers compile.
  - `GuideRepository` gains a constructor parameter `lineups: LineupStore`, placed after `store`.
  - **The rows source:**
    - When a saved lineup exists, rows come from it, filtered by `Visibility` (a feed is dropped if its source isn't enabled or its category is hidden; a row with no feeds left is dropped). This happens even when it's stale. A rebuilt saved lineup replaces it through `observeSaved()`.
    - When nothing is saved, rows come from today's in-memory path (`observeSportsCandidates` → `LineupBuilder.build`).
  - **`board()` emits twice:**
    1. First, immediately, a board whose rows have empty programmes and `programmesLoaded = false`.
    2. Then, after the window's first programme query, the full board with `programmesLoaded = true`.

    `downloading` mirrors `refresher.working`.

- [ ] **Step 1: Write the failing tests:**
  - `savedLineupFeedsTheBoardWithoutReadingTheLineup` (the fake library's candidate flow is never collected).
  - `hiddenCategoryFeedsAreFilteredFromTheSavedLineup`.
  - `disabledSourceRowsAreDropped`.
  - `staleSavedLineupShowsThenRebuilds` (the saved lineup is replaced; the board updates).
  - `noSavedLineupBuildsInMemory`.
  - `boardEmitsChannelsThenProgrammes` (first emission `programmesLoaded = false` with rows; second `true`).
  - `downloadingFollowsTheRefresher`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*GuideRepositoryTest'`. Expected: the new cases FAIL.
- [ ] **Step 3: Implement.** Keep every existing `GuideRepositoryTest` case green.
- [ ] **Step 4: Run it, then the whole unit suite.** Expected: green.
- [ ] **Step 5: Commit** `feat(guide): read the saved lineup and show channels before programmes`.

### Task 4: Loading indicator, early start, measurement

**Files:**
- Modify: `ui/live/GuideScreen.kt`, `ui/live/GuideGrid.kt`, `ui/live/GuideViewModel.kt`, `res/values/strings.xml`, `MainActivity.kt` (early start after the first frame)
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/GuideScreenTest.kt` (add cases)

**Interfaces:**
- **Consumes:** `GuideBoard.programmesLoaded`, `GuideBoard.downloading` (Task 3).
- **Produces:**
  - `strings.xml`: `guide_loading` "Loading guide…" and `guide_downloading` "Downloading guide…".
  - **Grid while `!programmesLoaded`:** every row with programmes pending shows one quiet loading cell spanning the window, with no text and no "No guide info". The indicator sits next to the time header, reading "Downloading guide…" when `downloading`, otherwise "Loading guide…". Focus behaves as today: the first row's cell is focusable, Select plays the row's channel, and focus doesn't jump when programmes arrive.
  - **Early start:** after the first frame (for example `window.decorView.post` or `reportFullyDrawn` timing), the app collects `graph.guide.board(GuideFilter.ALL, slotOf(now))` in the app scope until the first full board arrives, then cancels. The repository's 5-minute keep-alive holds it for the screen.

- [ ] **Step 1: Write the failing tests:**
  - `channelsShowWhileProgrammesLoad` (first board without programmes: channel names visible, "Loading guide…" visible, no "No guide info").
  - `downloadingSaysSo`.
  - `programmesFillInWithoutMovingFocus` (also covers `rebuiltRowsKeepFocus`: a second board with the same rows plus programmes keeps focus on the same row).
- [ ] **Step 2: Run** `/tmp/goodtv-tools/runclass.sh com.keyspaceapps.goodtv.ui.GuideScreenTest`. Expected: the new cases FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `GuideScreenTest`, then `./gradlew :app:testDebugUnitTest :app:lintDebug`, then the full connected suite once (`/tmp/goodtv-tools/runclass.sh`). Expected: all green.
- [ ] **Step 5: Measure**, as on 2026-10-22:
  1. Back up the data.
  2. `./gradlew :app:assembleRelease`, then `adb install -r app/build/outputs/apk/release/app-release.apk`. It's signed with the debug key, so the data is kept.
  3. Run three cold opens (`am force-stop`, then `am start -W`) and read the `GoodTvGuide` logs: "saved lineup read" and "first board (saved)", plus the old stages if a rebuild ran.
  4. `./gradlew :app:installDebug` to put the debug build back, and check the data with the backup script.
  5. Screenshots to `.context/cold-open-*.png`, including the loading state if it can be caught. No stream playback.
- [ ] **Step 6: Commit** `feat(guide): channels first with a loading indicator; warm the guide at launch`.
