# Per-source category filter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user switch off unwanted categories per source (whole prefix blocks at a time), persist the choice, and keep hidden categories and their content out of Live, Movies and Series.

**Architecture:** A `hidden_categories` Room table (only switched-off rows) behind new `LibraryStore` methods; the existing browsing queries exclude hidden rows in SQL. A nav-scoped `CategoriesViewModel` groups a source's categories by prefix and writes every toggle straight to the store. Database version 2 with a tested migration.

**Tech Stack:** existing stack (Room 2.8.5 + KSP, Compose for TV tv-material 1.1.0 `Checkbox`/`TriStateCheckbox`/chips, Navigation 3), plus `androidx.room:room-testing:2.8.5` for the migration test.

**Spec:** `docs/superpowers/specs/2026-10-19-category-filter-design.md`

## Global Constraints

- Only switched-off categories are stored; "everything on" is the empty table; new categories from later syncs are on automatically.
- `hidden_categories(sourceId, kind, categoryId)` PK all three, FK → `sources(id)` ON DELETE CASCADE, no FK to `categories`.
- Database version 2 with explicit `MIGRATION_1_2`; `fallbackToDestructiveMigration` stays only for unknown versions; `app/schemas/…/2.json` committed.
- `replaceCatalog` prunes hidden rows of a kind only when the incoming category list for that kind is non-empty.
- Prefix rule: text before the first `|` or ` - ` (space-dash-space), else the first word; trimmed, upper-cased; `:` is not a separator; prefixes with one member go to a trailing `Other` group.
- Group checkbox on a mixed block: one press hides the whole block; checked only when every member is shown; otherwise tri-state "mixed".
- Toggles save immediately; no Save button; Back leaves.
- Copy: tab labels `Live · 112 of 340`; buttons `Select all`, `Clear all`, `Categories`; status suffix `· N categories hidden`; empty states `Everything is hidden. Sources → Categories to show some.` and `Refresh this source first`.
- Commits on `davetait/google-tv-streamer-app`, one per task.

## Review Focus

1. A sync where one endpoint answers `[]` (HTTP 200) must not wipe that kind's hidden set. Test in Task 1.
2. Names with no separator and a unique first word (`2026 رمضانك`) land in Other; `DE - DOCU-MOVIES` keeps `DE`. Test in Task 2.
3. 900 live categories in the list must render and give the first row focus without jank or a blank screen. Test in Task 3.
4. Removing a source removes its hidden rows (no orphans that would hide a re-added source's categories). Test in Task 1.
5. Hiding every category of a type must say so, not "No sources yet". Test in Task 4.

---

## File map

```
app/build.gradle.kts                                  room-testing dep; androidTest assets.srcDirs += schemas
app/schemas/.../2.json                                exported by KSP after the entity is added
data/db/Entities.kt                                   HiddenCategoryEntity; SummaryRow.hiddenCount; CategoryRow unchanged
data/db/Daos.kt                                       HiddenCategoryDao; filtered queries; observeAllCategories(sourceId, kind)
data/db/GoodTvDatabase.kt                             version 2, MIGRATION_1_2, abstract fun hidden()
data/db/RoomLibraryStore.kt                           new methods; prune in replaceCatalog
data/repo/LibraryStore.kt                             new methods; SourceSummary.hiddenCount in domain/Models.kt
data/repo/LibraryRepository.kt                        pass-throughs
ui/categories/CategoryGroups.kt                       pure grouping: prefixOf(name), group(categories, hidden) → List<CategoryGroup>
ui/categories/CategoriesViewModel.kt, CategoriesScreen.kt
ui/nav/NavKeys.kt (+CategoriesKey), GoodTvNavigation.kt (entry)
ui/sources/SourcesScreen.kt                           Categories button; status suffix
ui/live/LiveScreen.kt, ui/movies/MoviesScreen.kt, ui/series/SeriesScreen.kt   "everything hidden" state
res/values/strings.xml
tools/fake-xtream/server.py                           prefixed category names
app/src/test/.../testing/FakeLibraryStore.kt          new methods
app/src/test/.../ui/categories/CategoryGroupsTest.kt, CategoriesViewModelTest.kt
app/src/androidTest/.../data/db/RoomLibraryStoreTest.kt (+tests), MigrationTest.kt
app/src/androidTest/.../ui/CategoriesScreenTest.kt
```

---

### Task 1: Hidden-category storage, filtered queries, migration

**Files:**
- Modify: `app/build.gradle.kts`, `data/db/Entities.kt`, `Daos.kt`, `GoodTvDatabase.kt`, `RoomLibraryStore.kt`, `data/repo/LibraryStore.kt`, `domain/Models.kt` (SourceSummary), `data/repo/LibraryRepository.kt`, `app/src/test/.../testing/FakeLibraryStore.kt`
- Create: `app/src/androidTest/java/com/keyspaceapps/goodtv/data/db/MigrationTest.kt`
- Test: `RoomLibraryStoreTest.kt` additions, `MigrationTest.kt`

**Interfaces (Produces):**

```kotlin
// domain
data class SourceSummary(..., val lastError: String?, val hiddenCount: Int)   // appended field, default 0 in FakeLibraryStore
// LibraryStore
fun observeAllCategories(sourceId: Long, kind: ContentKind): Flow<List<Category>>          // unfiltered, name order
fun observeHiddenCategories(sourceId: Long): Flow<Set<Pair<ContentKind, String>>>
suspend fun setCategoriesHidden(sourceId: Long, kind: ContentKind, ids: Collection<String>, hidden: Boolean)
// LibraryRepository: same three as pass-throughs
// db
@Entity(tableName = "hidden_categories", primaryKeys = ["sourceId","kind","categoryId"], foreignKeys = [FK sources CASCADE], indices = [Index("sourceId")])
data class HiddenCategoryEntity(val sourceId: Long, val kind: String, val categoryId: String)
val MIGRATION_1_2: Migration   // CREATE TABLE IF NOT EXISTS `hidden_categories` (`sourceId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `categoryId` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `kind`, `categoryId`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE) + CREATE INDEX IF NOT EXISTS `index_hidden_categories_sourceId` ON `hidden_categories` (`sourceId`)
```

Filtered queries: `observeCategories(kind)`, `observeEnabledMovies()`, `observeEnabledSeries()` add `AND NOT EXISTS (SELECT 1 FROM hidden_categories h WHERE h.sourceId = x.sourceId AND h.kind = x.kind/'MOVIE'/'SERIES' AND h.categoryId = x.categoryId)`. `observeSummaries` adds `(SELECT COUNT(*) FROM hidden_categories h WHERE h.sourceId = s.id) AS hiddenCount`.

- [ ] **Step 1: Build setup** — add `androidx-room-testing` to the catalog (`room` version) and `androidTestImplementation(libs.androidx.room.testing)`; in `android { sourceSets { getByName("androidTest").assets.srcDirs("$projectDir/schemas") } }`.
- [ ] **Step 2: Write failing tests in `RoomLibraryStoreTest`**: `hiddenCategoriesAreExcludedFromBrowsingQueriesButNotFromAll` (hide `m1` for source A → `observeCategories(MOVIE)` lacks it, `observeMoviesByCategory` lacks its key, `observeAllCategories(A, MOVIE)` still has it, summary `hiddenCount == 1`); `hiddenSetSurvivesReplaceCatalogAndEmptyKindDoesNotPrune` (hide `c1` LIVE; replace with a catalog whose live categories are empty but movie categories present → `c1` still hidden; replace with a catalog that has live categories but no `c1` → pruned); `deleteSourceRemovesHiddenRows` (Review Focus 4); `setCategoriesHiddenFalseRemovesRows`.
- [ ] **Step 3: Write `MigrationTest`** using `MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GoodTvDatabase::class.java)`: `createDatabase("migration-test", 1)`, insert one `sources` row and one `categories` row with raw SQL, close, `runMigrationsAndValidate("migration-test", 2, true, MIGRATION_1_2)`, then assert the source row is still there and `hidden_categories` exists (insert a row succeeds).
- [ ] **Step 4: Run** `./gradlew :app:compileDebugAndroidTestKotlin` → FAIL (unresolved).
- [ ] **Step 5: Implement** entity, DAO (`insertAll`, `deleteWhere(sourceId, kind, ids)`, `observeForSource`, `pruneMissing(sourceId, kind)` via `DELETE FROM hidden_categories WHERE sourceId=:s AND kind=:k AND categoryId NOT IN (SELECT id FROM categories WHERE sourceId=:s AND kind=:k)`), DB v2 + migration, store methods, prune logic in `replaceCatalog` (per kind, only if that kind's incoming categories are non-empty), summary field, repository pass-throughs, FakeLibraryStore (in-memory set).
- [ ] **Step 6: Run** `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.data.db.RoomLibraryStoreTest,com.keyspaceapps.goodtv.data.db.MigrationTest` → PASS; `app/schemas/.../2.json` exists.
- [ ] **Step 7: Commit** `feat(db): hidden categories table, filtered queries, migration 1→2`

---

### Task 2: Prefix grouping

**Files:**
- Create: `ui/categories/CategoryGroups.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/ui/categories/CategoryGroupsTest.kt`

**Interfaces (Produces):**

```kotlin
data class CategoryGroup(val prefix: String, val categories: List<Category>, val hiddenIds: Set<String>) {
    val shownCount: Int get() = categories.size - hiddenIds.size
    val state: ToggleableState   // On if hiddenIds.isEmpty(), Off if all hidden, Indeterminate otherwise
}
object CategoryGroups {
    const val OTHER = "Other"
    fun prefixOf(name: String): String?          // "AR| BEIN" → "AR"; "FR - NETFLIX" → "FR"; "DE - DOCU-MOVIES" → "DE"; "GERMANY NETFLIX" → "GERMANY"; "" → null
    fun group(categories: List<Category>, hidden: Set<String>): List<CategoryGroup>   // alphabetical by prefix, Other last, members in input order
}
```

- [ ] **Step 1: Write `CategoryGroupsTest`**: `pipeAndDashSeparators`, `firstWordFallback`, `colonIsNotASeparator` (`"Sky Sports: Premier League"` → `"SKY"`? No: first-word fallback gives `"SKY"`, so assert prefixOf == "SKY" and that two such names group together), `docuMoviesHyphenNotSplit`, `singleMemberPrefixesGoToOther` (Review Focus 2: `"2026 رمضانك"` alone → Other), `otherIsLastAndGroupsAlphabetical`, `groupStateReflectsHiddenIds` (On / Off / Indeterminate).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*CategoryGroupsTest*'` → FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** → PASS. **Step 5: Commit** `feat(categories): prefix grouping`

---

### Task 3: Categories screen

**Files:**
- Create: `ui/categories/CategoriesViewModel.kt`, `CategoriesScreen.kt`
- Modify: `ui/nav/NavKeys.kt` (`@Serializable data class CategoriesKey(val sourceId: Long) : GoodTvKey`), `GoodTvNavigation.kt` (entry), `ui/sources/SourcesScreen.kt` (Categories button; status suffix), `res/values/strings.xml`
- Test: `app/src/test/.../ui/categories/CategoriesViewModelTest.kt`, `app/src/androidTest/.../ui/CategoriesScreenTest.kt`

**Interfaces (Produces):**

```kotlin
class CategoriesViewModel(repo: LibraryRepository, val sourceId: Long) : ViewModel() {
    data class TabState(val kind: ContentKind, val shown: Int, val total: Int)
    val nickname: StateFlow<String>
    val synced: StateFlow<Boolean>                       // source.lastSyncAt != null
    val kind: StateFlow<ContentKind>; fun selectKind(k: ContentKind)
    val tabs: StateFlow<List<TabState>>                  // all three kinds, counts live
    val groups: StateFlow<List<CategoryGroup>>           // for the selected kind
    fun toggle(categoryId: String)                       // flips hidden for that id
    fun toggleGroup(group: CategoryGroup)                // state == On → hide all members; else (Off or Indeterminate) → show all members
    fun selectAll(); fun clearAll()                      // current kind
}
@Composable fun CategoriesScreen(key: CategoriesKey)
```

Screen: header `Text(nickname)`; `TabRow` of three `Tab`s (click to switch) labelled `stringResource(R.string.categories_tab, kindLabel, shown, total)` → "Live · 112 of 340"; a `LazyRow` of `Button(Select all)`, `Button(Clear all)`, then one `AssistChip` per group prefix; a `LazyColumn` with, per group, a header `ListItem` (headline prefix, supporting "shown of total", trailing `TriStateCheckbox(state, onClick = null)`, `onClick = toggleGroup`) and one `ListItem` per category (trailing `Checkbox(checked = !hidden, onCheckedChange = null)`, `onClick = toggle`). Chip click: `listState.animateScrollToItem(headerIndex)` then request focus on that header via a `FocusRequester` keyed by prefix. First list row gets initial focus via the `onPlaced` pattern. `synced == false` → `Text(R.string.categories_refresh_first)` instead of the list.

- [ ] **Step 1: Write `CategoriesViewModelTest`** (real `LibraryRepository` over `FakeLibraryStore` as in other VM tests): `allShownByDefaultAndCountsMatch`, `toggleHidesAndShowsAgain`, `toggleGroupOnMixedBlockHidesWholeBlock` (hide one of three → Indeterminate → toggleGroup → all three hidden → toggleGroup → none hidden), `clearAllThenSelectAll`, `switchingKindChangesGroups`.
- [ ] **Step 2: Write `CategoriesScreenTest`** (seed via store, launch `MainActivity` with `lastTab = "SOURCES"`): `categoriesButtonOpensListWithEverythingChecked` (tvClick "Categories"; wait "Live · 3 of 3"; first category row focused — Review Focus 3 variant: seed 900 live categories `"XX| Cat n"` across 30 prefixes and assert the first row is focused and `"AA"` header exists), `uncheckingHidesFromLive` (toggle `"AR| Sports"` → back → Live tab → sidebar lacks it, Sources status shows "1 categories hidden"), `groupCheckboxClearsBlock` (tvClick the `AR` header → Live tab lacks all AR categories).
- [ ] **Step 3: Run** unit → FAIL; `compileDebugAndroidTestKotlin` → FAIL.
- [ ] **Step 4: Implement** VM, screen, nav entry, Sources button + suffix, strings (`categories`, `categories_tab`, `select_all`, `clear_all`, `categories_hidden_suffix`, `categories_refresh_first`, `everything_hidden`).
- [ ] **Step 5: Run** `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.ui.CategoriesScreenTest` → PASS.
- [ ] **Step 6: Commit** `feat(ui): per-source Categories screen with prefix groups`

---

### Task 4: Browsing empty states, fake panel names, end-to-end

**Files:**
- Modify: `ui/live/LiveScreen.kt`, `ui/movies/MoviesScreen.kt`, `ui/series/SeriesScreen.kt` (+ their ViewModels expose `hasSources: StateFlow<Boolean>` from `repo.enabledSourceCount()`), `tools/fake-xtream/server.py`, `README.md`
- Test: `app/src/androidTest/.../ui/LiveScreenTest.kt` (+1), `MoviesScreenTest.kt` (+1)

- [ ] **Step 1: Write failing tests**: `LiveScreenTest.allHiddenShowsEverythingHiddenMessage` (hide both categories of both sources → text "Everything is hidden" displayed, not "No sources yet"); `MoviesScreenTest.allHiddenShowsEverythingHiddenMessage`. (Review Focus 5.)
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** the three screens' empty-state branch (`categories/rows empty && hasSources` → everything-hidden copy). Fake panel: live names `"{prefix}| News"` style with three prefixes (`AR|`, `UK|`, `FR|`), movies `"{prefix} - Action"`, series first-word (`"GERMANY Drama"`), so grouping is exercised.
- [ ] **Step 4: Run** `./gradlew :app:lintDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleRelease` → all PASS.
- [ ] **Step 5: Manual on the emulator with Dave's real provider**: Sources → Categories; screenshots `.context/cat-live.png` (groups, counts), `cat-after-clear.png` (Clear all then one group on), `cat-live-filtered.png` (Live sidebar reduced). Confirm the migration ran in place (his source still there after install).
- [ ] **Step 6: Commit** `feat: hidden-state messages, fake panel prefixes; README`

---

## Self-review notes

- Spec coverage: storage + migration (T1), prune rule (T1), filtered reads (T1), SourceSummary count (T1), grouping rule (T2), screen with tabs/counts/bulk/chips/group tri-state (T3), Sources button + suffix (T3), immediate save (T3), empty states (T4), fake panel names (T4), manual verification (T4).
- Types: `CategoryGroup.state: ToggleableState` (androidx.compose.ui.state); `setCategoriesHidden(sourceId, kind, ids, hidden)` used by VM for toggle (single id), group, select/clear all.
- Review Focus 1–5 mapped to T1, T2, T3, T1, T4.
