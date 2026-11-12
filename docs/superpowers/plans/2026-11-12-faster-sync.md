# Faster Source Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refreshing a source on the onn 4K Pro goes from about 202 s to under 30 s (25–35 s is acceptable), with an identical library.

**Architecture:**
- **Conversion:** make the DTO → domain step cheap without changing its output. Equivalence tests compare it with a frozen copy of today's code over a real-panel corpus.
- **Streaming:** sources stream their lists to a `CatalogWriter` as each one arrives. All three lists download at once, and each is mapped on `Dispatchers.Default`.
- **Staging:** the Room store stages each list in un-indexed staging tables, in short transactions. It then swaps everything into the real tables in one short final transaction, using a fast clear when the source is the only one in a table.

**Tech Stack:** Kotlin, coroutines, kotlinx.serialization, Room/SQLite (`SupportSQLiteStatement`), JUnit4, instrumented Room tests.

**Spec:** `docs/superpowers/specs/2026-11-12-faster-sync-design.md`

## Global Constraints

- **Identical library:** the same categories, channels, movies, series and series genres, with the same `DisplayName` (title, year, language, quality), `tmdbId` and genres for every panel value.
- **All or nothing:** a failed or cancelled sync leaves the previous library untouched and records the error as today (`markSync(sourceId, null, message)`; `OutOfMemoryError` keeps today's message).
- **No long write locks:** no Room write transaction may stay open across a network download. Each staging write chunk is its own transaction of at most 5,000 rows.
- **Memory:** peak memory must not exceed today's. Each list is dropped once it has been written to staging.
- **Multi-source:** a sync never changes another source's rows.
- **Same follow-on steps:** hidden-category pruning (only for kinds the panel listed), `lineup.bumpGeneration()`, `markSync` and `SyncState` behave as today. Movie details, series info, seasons and episodes are deleted for the source as today.
- **Credentials:** they live in `local.properties`. Never print, log or commit them.
- **Emulator:** run instrumented tests only through `~/.goodtv-tools/runclass.sh <class>`, one class per call. Never run a full real-provider sync on the emulator, because it hangs. Never send key events to the emulator.

## Review Focus

1. **A rare title shape that today's parser handles specially:** event dates, "(None)", repeated tags, and decorations combined with 4K markers. The new parser must give exactly the same `DisplayName`. *(Task 1: `displayTitleMatchesLegacyOnTheCorpus` plus the existing `DisplayTitleTest`)*
2. **A sync that fails after some lists are staged** (for example, series times out after movies were written). The library stays as it was, and the next sync starts with clean staging. *(Task 3 test `failureAfterStagingKeepsTheLibrary`)*
3. **Two sources, syncing one.** The other's rows survive, and the fast clear is not used. *(Task 3 test `twoSourcesOnlyTheSyncedOneChanges`)*
4. **Cancellation mid-sync**, for example when the source is edited. Same as 2: nothing changes, and staging is cleaned up. *(Task 2 test `cancelledSyncAbandonsTheWriter`)*
5. **A panel answering an empty list for one kind** (a bad day). Today that empty list replaces the kind's rows, and hidden choices for that kind are not pruned. Keep exactly that. *(Task 3 test `emptyKindReplacesRowsButKeepsHiddenChoices`)*

---

### Task 1: Cheap conversion, identical output

**Files:**
- Modify:
  - `app/src/main/java/com/keyspaceapps/goodtv/data/xtream/XtreamMappers.kt` (`tmdbIdOf`)
  - `app/src/main/java/com/keyspaceapps/goodtv/domain/Genre.kt` (`fromPanelText`, `fold`)
  - `app/src/main/java/com/keyspaceapps/goodtv/domain/DisplayTitle.kt`
- Create:
  - `app/src/test/java/com/keyspaceapps/goodtv/legacy/LegacyMapping.kt`: a verbatim snapshot of today's `DisplayTitle` object, `Genre.fromPanelText` with its `fold` and `STEMS`/`FOLDED_STEMS`, and `tmdbIdOf`, renamed `LegacyDisplayTitle`, `legacyGenres` and `legacyTmdbIdOf`. Copy them from commit b5b986d. Header comment: "Frozen copy of the b5b986d conversion: the oracle for equivalence tests. Never edit."
  - `app/src/test/java/com/keyspaceapps/goodtv/data/xtream/ConversionEquivalenceTest.kt`
- Fixtures (already captured, uncommitted, under `app/src/test/resources/xtream/corpus/`):
  - `live_names.txt` (15,000)
  - `movie_names.txt` (20,000)
  - `series_names.txt` (10,000)
  - `genres.txt` (4,820 distinct panel genre texts)
  - `tmdb.txt` (3,080 raw tmdb values)
  - Each file holds one value per line, UTF-8.

**Interfaces:**
- Produces the same public signatures as today: `DisplayTitle.parse(raw: String, live: Boolean = false): DisplayName`, `Genre.fromPanelText(text: String?): Set<Genre>` and `internal fun tmdbIdOf(raw: String?): Long?`.

- [ ] **Step 1: Write the equivalence tests.** In `ConversionEquivalenceTest`:
  - `displayTitleMatchesLegacyOnTheCorpus`: every line of `live_names.txt` gives `DisplayTitle.parse(it, live = true) == LegacyDisplayTitle.parse(it, live = true)`. Every line of `movie_names.txt` and `series_names.txt` gives `DisplayTitle.parse(it) == LegacyDisplayTitle.parse(it)`. On failure, report the first 20 differing inputs.
  - `genresMatchLegacyOnTheCorpus`: every line of `genres.txt`, plus `null`, `""` and `"  "`, gives the same result as `legacyGenres`.
  - `tmdbIdsMatchLegacyOnTheCorpus`: every line of `tmdb.txt`, plus `null`, `""`, `" 12 "`, `"0"`, `"43332-"`, `"tt0111161"`, `"-5"`, `"12abc"`, `"99999999999999999999"` and `"٣٤"` (Arabic-Indic digits), gives the same result as `legacyTmdbIdOf`.
  - `genreMemoGivesTheSameSet`: calling `fromPanelText` twice with the same text gives equal sets, and the second call returns the same set instance (proves the memo).
- [ ] **Step 2: Run them.**
  - Run: `./gradlew :app:testDebugUnitTest --tests '*ConversionEquivalenceTest'`
  - Expected: the three corpus tests pass against today's code, because the oracle is a copy. `genreMemoGivesTheSameSet` fails. That is the RED for the memo; the corpus tests are the guard for steps 3–5.
- [ ] **Step 3: `tmdbIdOf`.**
  - Scan the leading ASCII digits of the trimmed value by hand: no `Regex`, no allocation beyond the trim.
  - Return null when there are none, on overflow, or when the value is not greater than 0. `Regex("^\\d+")` matches only ASCII digits on the JVM; check `"٣٤"` against the oracle.
- [ ] **Step 4: Genres.**
  - Precompile `fold`'s two patterns, which today are compiled on every call.
  - Memoise `fromPanelText` per text in a bounded, thread-safe cache: `ConcurrentHashMap` with at most 20,000 entries, cleared when full.
  - An ASCII fast path in `fold` is optional, if it gives identical output.
- [ ] **Step 5: `DisplayTitle.parse`.** Keep the output identical, and only skip work that can't change it:
  - Skip `DECORATIONS` and `WIDE_SPACES` replacement when no such character is present (a cheap scan).
  - Compute `latestYear()` once per process.
  - Run `REPEAT_TAG`, `PAREN_YEAR`, `TRAILING_YEAR` and `TRAILING_TAG` only when a cheap check says they could match. For example, run `PAREN_YEAR` only when `(` or `[` is present, `TRAILING_YEAR` only when the last 4 characters are digits, and `TRAILING_TAG` only when the name ends with `)` or a letter or digit.
  - The equivalence test is the judge. Don't restructure the regexes themselves.
- [ ] **Step 6: Run the tests.**
  - Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
  - Expected: all pass, including the existing `DisplayTitleTest`, `GenreTest` and `XtreamMappersTest`, and lint has 0 errors.
- [ ] **Step 7: Commit.**
  - `git add` the three main files, the two test files and `app/src/test/resources/xtream/corpus/`.
  - Message: `perf(sync): title, genre and TMDB id conversion without per-item regex compiles`.

### Task 2: Streaming catalog: three downloads at once, each list handed on as it arrives

**Files:**
- Modify:
  - `app/src/main/java/com/keyspaceapps/goodtv/data/source/ChannelSource.kt`
  - `app/src/main/java/com/keyspaceapps/goodtv/data/xtream/XtreamChannelSource.kt`
  - `app/src/main/java/com/keyspaceapps/goodtv/data/repo/LibraryStore.kt`
  - `app/src/main/java/com/keyspaceapps/goodtv/data/repo/SyncManager.kt`
  - The test fakes `app/src/test/java/com/keyspaceapps/goodtv/testing/FakeChannelSource.kt` and `FakeLibraryStore`, wherever it lives.
- Tests:
  - `app/src/test/java/com/keyspaceapps/goodtv/data/repo/SyncManagerTest.kt`
  - `app/src/test/java/com/keyspaceapps/goodtv/data/xtream/XtreamChannelSourceTest.kt`

**Interfaces:**
- Produces, in `LibraryStore.kt`:
```kotlin
/** One sync's writes: lists arrive in any order, from any thread; nothing is visible until [commit]. */
interface CatalogWriter {
    suspend fun categories(kind: ContentKind, list: List<Category>)
    suspend fun live(list: List<LiveChannel>)
    suspend fun movies(list: List<Movie>)
    suspend fun series(list: List<Series>)
    /** Replaces the source's catalog with what was written, as replaceCatalog does, in one short transaction. */
    suspend fun commit()
    /** Drops what was written; the library is untouched. Safe to call more than once, and after commit. */
    suspend fun abandon()
}
// in LibraryStore:
suspend fun beginCatalog(sourceId: Long): CatalogWriter
```
- In `ChannelSource`:
```kotlin
/** Streams the catalog into [writer] as each list arrives. Default: fetchCatalog, then every list. */
suspend fun streamCatalog(sourceId: Long, config: SourceConfig, writer: CatalogWriter): Result<Unit> =
    fetchCatalog(sourceId, config).map { c -> /* writes c's categories by kind, live, movies and series */ }
```
- `XtreamChannelSource.streamCatalog` overrides it:
  - All six calls start at once; the cap of two goes.
  - Each list is mapped on `Dispatchers.Default` as soon as it arrives, and handed to the writer.
  - Each kind's categories are written even when empty, so `commit` knows which kinds the panel listed.
- `fetchCatalog` stays: other callers and the fakes use it.
- `SyncManager.doRefresh`:
  - Calls `store.beginCatalog`, then `source.streamCatalog(id, config, writer)`.
  - On success: `writer.commit()`, then `markSync(id, now, null)`.
  - On failure or exception: `writer.abandon()`, then `markSync` with the error as today.
  - On `CancellationException`: `withContext(NonCancellable) { writer.abandon() }`, then rethrow.
- `FakeLibraryStore.beginCatalog` returns a writer that buffers the lists and calls its own `replaceCatalog` on commit.

- [ ] **Step 1: Write the failing tests.**
  - `SyncManagerTest`:
    - `successfulSyncCommitsTheStreamedCatalog`: the fake source streams the lists; the store's catalog equals them after refresh.
    - `failedSyncAbandonsAndKeepsTheLibrary`: the source writes movies, then fails; the old catalog stays, `abandon` was called and the error is recorded.
    - `cancelledSyncAbandonsTheWriter`: cancel the job while the source is suspended mid-stream; `abandon` was called and the old catalog stays.
  - `XtreamChannelSourceTest` (MockWebServer):
    - `streamCatalogDownloadsAllListsAtOnce`: the dispatcher records concurrent in-flight requests and sees 3 list calls in flight together.
    - `streamCatalogHandsEachListToTheWriterAsItArrives`: delay `get_series` until `get_live_streams` has been written, and assert the writer saw live before series finished.
- [ ] **Step 2: Run and see them fail.**
  - Run: `./gradlew :app:testDebugUnitTest --tests '*SyncManagerTest' --tests '*XtreamChannelSourceTest'`
  - Expected: compile failure on `beginCatalog`, `CatalogWriter` and `streamCatalog`.
- [ ] **Step 3: Implement the interfaces above.**
  - The Room store needs a `beginCatalog` to compile. Until Task 3, it may return a buffering writer that calls the existing `replaceCatalog` on commit.
- [ ] **Step 4: Run.**
  - Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
  - Expected: all pass.
- [ ] **Step 5: Commit.**
  - Message: `perf(sync): stream the catalog, three downloads at once, each list handed on as it arrives`.

### Task 3: Room staging and a short swap

**Files:**
- Modify:
  - `app/src/main/java/com/keyspaceapps/goodtv/data/db/GoodTvDatabase.kt` (version 14, `MIGRATION_13_14`, staging entities or raw tables)
  - `app/src/main/java/com/keyspaceapps/goodtv/data/db/RoomLibraryStore.kt`
  - `app/src/main/java/com/keyspaceapps/goodtv/data/db/Daos.kt` (if needed)
  - `app/schemas/…/14.json` (generated)
- Tests:
  - `app/src/androidTest/java/com/keyspaceapps/goodtv/data/db/CatalogStagingTest.kt` (new, instrumented, in-memory Room)
  - the existing migration test (add 13 → 14)

**Interfaces:**
- Consumes `CatalogWriter` and `LibraryStore.beginCatalog` from Task 2.
- Produces a `RoomLibraryStore.beginCatalog(sourceId)` that returns a writer which:
  - **On creation:** clears this source's staging rows.
  - **Staging writes:**
    - Each write inserts into `staging_categories`, `staging_live`, `staging_movies`, `staging_series` or `staging_series_genres`. These have the same columns as the real tables and no indexes or keys.
    - Inserts use multi-row `INSERT … VALUES (…),(…)` statements, keeping each statement within 999 bound parameters. Each chunk of at most 5,000 rows is its own transaction.
    - Writes are serialised with a `Mutex`.
    - Series genres are derived from `Series.genres` exactly as `replaceCatalog` does.
    - It records which kinds' categories were written.
  - **`commit()`, in one `db.withTransaction`:**
    - Removes the source's old rows from `categories`, `live_channels`, `movies`, `series` and `series_genres`. When no other source has rows in a table, it uses an unconditional `DELETE FROM <table>`; otherwise it uses today's per-source deletes.
    - Deletes movie details, series info, seasons and episodes as today.
    - Copies staging into each real table with `INSERT INTO t(cols) SELECT cols FROM staging_t WHERE sourceId = ?`. If the staged rows could collide on a primary key, `INSERT OR REPLACE` keeps today's last-row-wins behaviour; match what today's inserts do.
    - Prunes hidden rows for the kinds written, and calls `lineup.bumpGeneration()`.
    - Clears the source's staging rows.
  - **`abandon()`:** clears the source's staging rows.
- Migration 13 → 14 only creates the five staging tables. Existing data is untouched.

- [ ] **Step 1: Write the failing instrumented tests** in `CatalogStagingTest`. Use an in-memory database, and compare against what `replaceCatalog` produces for the same `Catalog`.
  - `commitGivesTheSameRowsAsReplaceCatalog`: compare the categories, live channels, movies, series and series genres rows, all columns.
  - `failureAfterStagingKeepsTheLibrary`: write movies to the writer, call `abandon`; the old rows are unchanged and staging is empty.
  - `twoSourcesOnlyTheSyncedOneChanges`
  - `emptyKindReplacesRowsButKeepsHiddenChoices`: hidden categories of a kind written as empty survive.
  - `nothingVisibleBeforeCommit`: after writes but before commit, a query sees the old rows.
  - `aNewSyncStartsWithCleanStaging`: begin, write, never commit or abandon (as if the process died), then begin again and commit. Only the second sync's rows appear.
- [ ] **Step 2: Add the 13 → 14 case to the migration test.**
- [ ] **Step 3: Run and see them fail.**
  - Run: `~/.goodtv-tools/runclass.sh com.keyspaceapps.goodtv.data.db.CatalogStagingTest`
  - Expected: FAIL.
- [ ] **Step 4: Implement it, then run** `CatalogStagingTest` and the migration test class through runclass, then `./gradlew :app:testDebugUnitTest :app:lintDebug`.
  - Expected: all pass.
- [ ] **Step 5: Run the full instrumented suite once** (`~/.goodtv-tools/runclass.sh` with no argument).
  - Expected: all pass. The known flaky tests are OnlineSubtitlesTest, `CategoriesScreenTest.uncheckingHidesFromLiveAndCountsUpdate` and `LivePreviewUiTest.pausingInFullScreenThenBackPlaysInThePanel`; rerun those alone if they fail.
- [ ] **Step 6: Commit.**
  - Message: `perf(sync): stage each list as it arrives and swap the catalog in one short transaction`.

### After the tasks (controller)

1. Build the release with the `SyncTimingJvmProbe`-style `SyncTiming` logs, adapted to the new code, and install it on the onn box.
2. Press Refresh and record the times: start to "synced", and per step.
3. Install a clean build afterwards.
4. If the result is over 35 s, report where the time goes and propose the diff-sync follow-up.
