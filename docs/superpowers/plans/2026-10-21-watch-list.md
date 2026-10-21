# Watch List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Dave save the exact copy of a movie or series by holding Select on its poster, or with a detail-page button. Saved titles appear in a "Watch list" row on Home after Continue Watching. Movies leave on their own once finished.

**Architecture:**
- **Storage:** a `watch_list` Room table keyed by (sourceId, kind, itemId) with a cascade foreign key to `sources` (DB v8 → v9). It's read through a `WatchListRepository` like `WatchProgressRepository`. The Home row is a catalog join that hides disabled sources, hidden categories and titles missing from the catalog.
- **Posters:** one `LocalWatchList` composition local, provided at the nav root, gives every poster the saved set and opens one shared Add/Remove dialog. Browse screens don't thread new parameters through.
- **Auto-removal:** `WatchProgressRepository.record` calls back when a movie is finished.

**Tech Stack:** Kotlin, Compose for TV (tv-material3), Room 2.x with exported schemas, kotlinx-coroutines, JUnit4 (JVM and instrumented).

**Spec:** `docs/superpowers/specs/2026-10-21-watch-list-design.md`

## Global Constraints

- Only the exact copy is saved. The key is (sourceId, kind MOVIE|SERIES, itemId), with no TMDB-id matching anywhere.
- **Row:**
  - Placed right after Continue Watching.
  - Newest saved first, no cap, hidden when empty.
  - Selecting a card opens `MovieDetailKey` / `SeriesDetailKey`.
  - Hold Select (and the remote's Menu key) offers removal.
- **Hidden, not deleted,** while the source is disabled, the title is missing from the catalog, or its category is hidden. Deleting a source deletes its rows (FK cascade).
- **Auto-removal:**
  - A movie is removed when `WatchRules.isFinished` is true for that exact copy.
  - Series are never removed automatically.
  - Episodes never touch the list.
- **Copy (strings):**
  - "Watch list" (row title)
  - "Add to watch list"
  - "Remove from watch list"
  - "＋ Watch list" (detail button)
  - "✓ On watch list" (detail button when saved)
  - The row hint reuses `continue_hint` ("Hold Select to remove").
- **Bookmark corner:** saved posters show it everywhere posters appear.
- **Live channels** get no watch-list menu.
- **Database:** v9 with a real `MIGRATION_8_9`. Export `app/schemas/.../9.json` and commit it.
- **Compose TV focus:**
  - Request initial focus from `onPlaced`, not `LaunchedEffect`.
  - Removing the focused row card hands focus to its neighbour, as in `ContinueWatchingRow`.
- **Connected test runs:** back up the emulator app data first and restore it immediately after: `python3 -I /tmp/goodtv-tools/backup.py /tmp/goodtv-backups`, then `./gradlew :app:installDebug` and `restore.py <newest dir>`.

## Review Focus

1. **Finishing a different copy of a saved film** (EN saved, 4K watched) must leave the saved copy on the list. Pinned in Task 2: `finishingAnotherCopyKeepsTheSavedOne`.
2. **A re-sync that briefly drops the title from the catalog, or a hidden category,** must hide the card, not delete the row. It reappears when the title returns. Pinned in Task 1: `missingFromCatalogIsHiddenNotDeleted`.
3. **Stopping a saved movie partway** (or in its first 30 s) must keep it. Pinned in Task 2: `partwayKeepsIt`.
4. **Hold Select on a poster** must never open the detail page or start playback; only the dialog opens. Pinned in Task 3: `menuOnPosterOpensTheDialogNotTheDetail`.
5. **Removing the last card from the Home row** hides the row and focus goes to the next row's first card, not the root. Pinned in Task 5: `removingTheLastCardMovesFocusToTheNextRow`.

---

### Task 1: Watch list storage

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/watchlist/WatchList.kt` (domain types, store interface, repository)
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/db/RoomWatchListStore.kt`
- Modify: `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/GoodTvDatabase.kt` (entity, `WatchListDao`, `MIGRATION_8_9`, version 9), `AppGraph.kt` (`val watchList`)
- Create: `app/src/test/java/com/keyspaceapps/goodtv/testing/InMemoryWatchListStore.kt`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/data/db/WatchListStoreTest.kt`, `MigrationTest.kt` (add a case)

**Interfaces:**
- Produces:
  - `enum class WatchListKind { MOVIE, SERIES }`
  - `data class WatchListRef(val sourceId: Long, val kind: WatchListKind, val itemId: String) { val key: String get() = "${kind.name}:$sourceId:$itemId" }`
  - `data class WatchListItem(val ref: WatchListRef, val title: String, val imageUrl: String?, val tmdbId: Long?, val lookupName: String, val savedAt: Long)`. The title is the catalog's display label (`displayLabel(title ?: name, language, quality)`, as `ContinueRow.toItem` does).
  - `interface WatchListStore { fun observeSaved(): Flow<Set<WatchListRef>>; fun observeRow(): Flow<List<WatchListItem>>; suspend fun add(ref: WatchListRef, savedAt: Long); suspend fun remove(ref: WatchListRef) }`
  - `class WatchListRepository(store: WatchListStore, clock: () -> Long = System::currentTimeMillis)`
    - `fun saved(): Flow<Set<WatchListRef>>`
    - `fun row(): Flow<List<WatchListItem>>`
    - `suspend fun add(ref)`
    - `suspend fun remove(ref)`
  - `AppGraph.watchList: WatchListRepository`
- Entity: `WatchListEntity(sourceId: Long, kind: String, itemId: String, savedAt: Long)`
  - Table `watch_list`.
  - PK (sourceId, kind, itemId).
  - FK `sources.id` ON DELETE CASCADE.
  - Indices on `sourceId` and `savedAt`.
  - The spec's "title and poster stored too" isn't needed: the row must join the catalog anyway to hide missing titles. The join supplies both, so nothing is stored that can go stale.
- Row query: the union of a movie join (`movies m ON m.sourceId = w.sourceId AND m.id = w.itemId`, kind 'MOVIE', poster `m.posterUrl`) and a series join (`series x`, kind 'SERIES', poster `x.coverUrl`).
  - Both require `sources.enabled = 1`.
  - Both use `NOT EXISTS (… hidden_categories …)` with the same shape as `Daos.kt:83/91`.
  - `ORDER BY savedAt DESC`.

- [ ] **Step 1: Write the failing instrumented store tests** in `WatchListStoreTest` (seed source, categories, a movie and a series through the existing DAOs, as `WatchProgressStoreTest` does):
  - `addedTitlesShowNewestFirst`: add movie at t=1 and series at t=2. `observeRow().first().map { it.ref.key }` is `["SERIES:1:s1", "MOVIE:1:m1"]`. Titles come from the catalog.
  - `missingFromCatalogIsHiddenNotDeleted`: saved movie m1, then the catalog is replaced without m1. The row is empty but `observeSaved()` still contains it. Replace again with m1 and it's back in the row.
  - `disabledSourceAndHiddenCategoryHide`: disable the source and the row is empty. Re-enable it, hide the movie's category, and the movie is gone while the series stays.
  - `deletingTheSourceDeletesItsRows`: delete the source and `observeSaved()` is empty.
  - `addTwiceKeepsOneRowWithTheNewTime`: add at t=1, then again at t=5. The row has one entry with savedAt 5.
- [ ] **Step 2: Write the failing migration test** `migrate8To9AddsWatchList` in `MigrationTest`: create v8 with one source and one watch_progress row, run `runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)`, check both rows survive and `watch_list` exists and is empty.
- [ ] **Step 3: Run them to verify they fail.** First back up the emulator data (Global Constraints).
  Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.data.db.WatchListStoreTest,com.keyspaceapps.goodtv.data.db.MigrationTest`
  Expected: compilation fails (no `WatchListRef`, no `MIGRATION_8_9`).
- [ ] **Step 4: Implement** the entity, DAO, `MIGRATION_8_9` (SQL matching Room's generated schema; copy it from `9.json` after the first build), version 9, the Room store, the repository, `InMemoryWatchListStore` (the row returns every saved ref with a title equal to its key) and `AppGraph.watchList`.
- [ ] **Step 5: Run the same command, then restore the emulator data.** Expected: all pass. `app/schemas/com.keyspaceapps.goodtv.data.db.GoodTvDatabase/9.json` exists.
- [ ] **Step 6: Commit** `feat(watchlist): watch list table, migration 8→9 and repository`.

### Task 2: Finished movies leave the list

**Files:**
- Modify: `data/progress/WatchProgress.kt` (`WatchProgressRepository`), `AppGraph.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/progress/WatchProgressRepositoryTest.kt` (add cases; create the file if it doesn't exist)

**Interfaces:**
- Consumes: `WatchListRepository.remove`, `WatchListRef`, `WatchListKind` (Task 1).
- Produces: `WatchProgressRepository(store, clock, onMovieFinished: suspend (WatchRef) -> Unit = {})`. It's called from `record` after the upsert, only when `ref.kind == WatchKind.MOVIE && finished`.

- [ ] **Step 1: Write the failing tests.** They use `InMemoryWatchProgressStore` and a callback that records refs.
  - `finishedMovieCallsBack`: record movie m1 at 118 min of 120 and expect `[WatchRef(1, MOVIE, "m1")]`.
  - `partwayKeepsIt`: positions 10 s and 60 min of 120 give no callback.
  - `finishedEpisodeDoesNotCallBack`: an EPISODE at its end gives no callback.
  - `finishingAnotherCopyKeepsTheSavedOne`, end to end with `InMemoryWatchListStore`. Wire `WatchProgressRepository(progressStore, onMovieFinished = { watchList.remove(WatchListRef(it.sourceId, WatchListKind.MOVIE, it.itemId)) })`. Save m1, finish m2, and m1 is still saved. Finish m1 and it's gone.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*WatchProgressRepositoryTest'`. Expected: compile failure on the new parameter.
- [ ] **Step 3: Implement** the parameter and the call. In `AppGraph`, wire `watchProgress` with `onMovieFinished = { watchList.remove(WatchListRef(it.sourceId, WatchListKind.MOVIE, it.itemId)) }`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(watchlist): finished movies leave the watch list`.

### Task 3: Hold Select on posters, bookmark corner, shared dialog

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/watchlist/WatchListUi.kt` (`LocalWatchList`, `WatchListHost`, the dialog)
- Modify: `ui/browse/PosterCard.kt` (`saved` corner; Menu key fires `onLongClick`), `ui/browse/CategoryRows.kt`, `ui/browse/PosterGrid.kt`, `ui/home/PopularRows.kt`, `ui/home/HomeTabScreen.kt` (search grid), `ui/home/ContinueWatching.kt` (drop its own Menu `onKeyEvent`, now in `PosterCard`), `ui/nav/GoodTvNavigation.kt` (wrap the nav content in `WatchListHost`), `res/values/strings.xml`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/WatchListUiTest.kt`

**Interfaces:**
- Consumes: `AppGraph.watchList` (Task 1).
- Produces:
  - `data class WatchTarget(val ref: WatchListRef, val title: String)`
  - `interface WatchListUi { val saved: Set<WatchListRef>; fun openMenu(target: WatchTarget) }`
  - `val LocalWatchList = staticCompositionLocalOf<WatchListUi> { WatchListUi.None }`. `None` has an empty set and a no-op `openMenu`, so screens and tests without a host behave as before.
  - `@Composable fun WatchListHost(repo: WatchListRepository, content: @Composable () -> Unit)`. It collects `repo.saved()` and provides `LocalWatchList`. It shows a dialog with the title, one button ("Add to watch list" or "Remove from watch list" for that ref) and Cancel, with the first button focused via `onPlaced`. The dialog is styled like `ContinueWatching.RemoveDialog`.
  - `PosterCard(..., saved: Boolean = false)` draws the bookmark corner (top-right, shape and colour are the builder's call).
- Posters to wire, kind from the screen:
  - `CategoryRows` (from `row.category.category.kind`) and `PosterGrid` (its `kind`).
  - Popular rows (from `PopularCard.destination`: `MovieDetailKey` → MOVIE, `SeriesDetailKey` → SERIES).
  - Home search results (MOVIE/SERIES only; LIVE gets no `onLongClick`).
  - "See all" tiles get nothing.
  - Continue Watching keeps its own menu and gets no bookmark.

- [ ] **Step 1: Write the failing UI tests** in `WatchListUiTest`. Seed one source with a movie category ("Action": m1 "Heat", m2 "Ronin") and a series category ("Drama": s1 "Severance"). Launch `MainActivity` on the Movies tab (seed before launching, as `MoviesScreenTest` does). Press `Key.Menu` for "hold Select", as `ContinueWatchingUiTest` does.
  - `menuOnPosterOpensTheDialogNotTheDetail`: focus "Heat", press Menu. "Add to watch list" is displayed and no detail page is open (the "Play" text doesn't exist).
  - `addingShowsTheBookmarkAndSaves`: Menu, then Center. The dialog closes, `appGraph.watchList.saved().first()` contains MOVIE:m1, and the poster's bookmark node (tag `"watchlist-saved"`) is displayed.
  - `savedPosterOffersRemove`: with m1 saved before launch, Menu on "Heat" shows "Remove from watch list". Center removes it.
  - `liveChannelsHaveNoMenu`: in Home search results for a channel, Menu opens nothing.
- [ ] **Step 2: Run** (after a backup) `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.ui.WatchListUiTest`. Expected: FAIL (compile, then no dialog). Restore.
- [ ] **Step 3: Implement** as in Interfaces. Strings: `watch_list_add` "Add to watch list", `watch_list_remove` "Remove from watch list".
- [ ] **Step 4: Run** the same class, plus `ContinueWatchingUiTest`, `MoviesScreenTest`, `SeriesScreenTest`, `PopularRowsUiTest` and `HomeTabScreenTest` (Menu moved into `PosterCard`). Expected: all pass. Restore.
- [ ] **Step 5: Commit** `feat(watchlist): hold Select on posters to add or remove, bookmark corner`.

### Task 4: Detail page button

**Files:**
- Modify: `ui/movies/MovieDetailScreen.kt`, `ui/series/SeriesDetailScreen.kt`, `res/values/strings.xml`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/WatchListUiTest.kt` (add cases)

**Interfaces:**
- Consumes: `LocalWatchList` for the saved state; `AppGraph.watchList.add/remove` through the screen's ViewModel (`goodTvViewModel { … it.watchList }`, which adds `toggleWatchList()`).
- **Placement:**
  - Movie: the last button in the Play/Resume row.
  - Series: the last button in the Resume row. When the page has no Resume row, it sits in its own row at the same spot (after `TmdbCredit`).
  - It never takes initial focus; Play/Resume keeps it.
- Strings: `watch_list_button` "＋ Watch list", `watch_list_on` "✓ On watch list".

- [ ] **Step 1: Write the failing tests:**
  - `movieDetailButtonToggles`: open Heat's detail, focus "＋ Watch list", press Center. It reads "✓ On watch list" and the repository has m1. Press again, it reads "＋ Watch list" and m1 is gone.
  - `seriesDetailButtonToggles`: the same on Severance's page.
  - `playStillTakesInitialFocus`: on opening the movie detail, "Play" is focused.
- [ ] **Step 2: Run** the class (backup first). Expected: FAIL (no button). Restore.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the class plus `ResumeUiTest`. Expected: pass. Restore.
- [ ] **Step 5: Commit** `feat(watchlist): watch list button on movie and series pages`.

### Task 5: The Home row

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/home/WatchListRow.kt` (`WatchListViewModel`, `WatchListRow`)
- Modify: `ui/home/HomeTabScreen.kt` (`HomeRows` gets the items; first-row focus includes the new row), `res/values/strings.xml` (`home_watch_list` "Watch list")
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/WatchListUiTest.kt` (add cases)

**Interfaces:**
- Consumes: `WatchListRepository.row()/remove()` (Task 1); `LocalWatchList` isn't used here because the row has its own remove dialog.
- **Produces:**
  - `class WatchListViewModel(repo: WatchListRepository) : ViewModel` with `items: StateFlow<List<WatchListItem>?>` and `remove(ref)`.
  - `@Composable fun WatchListRow(items, initialFocus: MutableState<Boolean>, onOpen: (GoodTvKey) -> Unit, onRemove: (WatchListRef) -> Unit, onEmptied: () -> Unit, focus: FocusMemory?, claimInitialFocus: Boolean)`. It mirrors `ContinueWatchingRow`:
    - same header with the `continue_hint` hint;
    - same Menu/hold dialog, with the copy "Remove from watch list";
    - same neighbour refocus and `onEmptied`;
    - focus memory keys `"watchlist:${ref.key}"`.
- **HomeRows order:** Continue Watching, Watch list, Popular Movies, Popular Shows. The first row present claims initial focus. `onEmptied` hands focus to the next row present, as Continue Watching's does.

- [ ] **Step 1: Write the failing tests:**
  - `rowShowsSavedNewestFirstAfterContinueWatching`: save m1 then s1 before launch, with one Continue Watching movie. The "Watch list" header sits below "Continue Watching" (compare top bounds) and the first card is "Severance".
  - `emptyListHidesTheRow`: no "Watch list" text on Home.
  - `selectingACardOpensItsDetail`: Center on "Heat" shows its detail page ("Play" exists).
  - `removingACardFocusesItsNeighbour`: Menu, then Center on the first card, and the second card is focused.
  - `removingTheLastCardMovesFocusToTheNextRow`: with one saved title and a Popular Movies row seeded (as `PopularRowsUiTest` does), remove it. The row is gone and the first Popular card is focused.
- [ ] **Step 2: Run** the class (backup first). Expected: the new cases FAIL. Restore.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:lintDebug`, then (backup first) the full `:app:connectedDebugAndroidTest`. Expected: all pass. Restore right after.
- [ ] **Step 5: Device check:** with the restored data, save a movie from Movies (hold Select) and see the bookmark. Check the Home row, remove from the row, and use the detail button. Screenshots go to `.context/watchlist-*.png`.
- [ ] **Step 6: Commit** `feat(watchlist): Watch list row on Home`.
