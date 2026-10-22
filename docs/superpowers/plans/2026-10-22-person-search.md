# Search by Person Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clickable cast and crew on movie and series info pages (from TMDB) that open a search of everything the library has with that person. Typing a person's name in Home search finds the same titles.

**Architecture:**
- A new `PeopleRepository` (data/tmdb) does four things:
  - fetches a title's people from TMDB;
  - fetches a person's TMDB credits and keeps only the counted ones;
  - resolves typed names with TMDB person search;
  - caches the people and credits in three new Room tables (v13).
- Library matching is one SQL query per kind: the person's credit ids joined to `movies` / `series` by provider `tmdbId`, picking one copy per title the way `PopularDao` does.
- The search UI is pulled out of `HomeTabScreen` into a reusable pane:
  - Home uses it as today, plus a person section.
  - A new `PersonSearchKey` screen uses it with the clicked person pinned.

**Tech Stack:** Kotlin, Compose for TV (tv-material3), Room 2 (KSP, exported schemas), OkHttp + kotlinx.serialization, MockWebServer (mockwebserver3), JUnit4, Compose UI tests on the `GoogleTV_API34` emulator.

**Spec:** `docs/superpowers/specs/2026-10-22-person-search-design.md`

## Global Constraints

- Database: "Room is at v12. Any new cache tables need a real migration that keeps all data, with schema JSON committed and a migration test." This plan goes to v13 (`MIGRATION_12_13`).
- Credentials: "the TMDB key lives in `local.properties`. It is never logged and never appears in URLs printed to logs." Never print `local.properties`.
- Testing: "TV focus behaviour gets instrumented tests … Request initial focus from `onPlaced`." Before any connected run, back up the emulator app data. Run one class per call with `/tmp/goodtv-tools/runclass.sh <fully.qualified.Class>` (backup, run, reinstall, restore). A comma-separated class filter runs only the first class.
- No TMDB call on the main thread; all TMDB calls go through `TmdbApi` (its semaphore and retries).
- TMDB caching never exceeds `TmdbRepository.MAX_CACHE_AGE_MS` (6 months).
- People row: at most 10 names. Movies: directors first, then cast in billing order. Series: creators first, then main cast.
- Counted credits (spec decision 3):
  - Counted: acting, directing, producing (Producer, Executive Producer), writing, creating.
  - Not counted: appearing as oneself, TV guest roles of 1–2 episodes, other crew jobs.
- Person section: one poster per title, chosen with Popular's pick: English, then untagged, then any language; standard quality before 4K; then `sourceId`, then `id`. Enabled sources and visible categories only. Newest first by TMDB credit date.
- Only titles whose provider `tmdbId` is set can match.
- Copy (exact strings):
  - "People"
  - "Director"
  - "Creator"
  - "With %1$s · %2$d title" / "With %1$s · %2$d titles"
  - "No titles with %1$s"
  - "Searching by person needs the internet"
- Unit test command: `./gradlew :app:testDebugUnitTest :app:lintDebug`. Expected: all pass, lint 0 errors.

## Review Focus

1. **The same copy in both sections** (a Tom Cruise film whose title also matches the typed text). The grid must not crash on duplicate keys, and focus restore must land on the card that was opened. Test: `personAndTitleSectionsMayShareACopy` (Task 5).
2. **Offline after a click.** The ordinary results show with the offline note, never "No titles with …". Test: `clickedPersonOfflineShowsNoteNotNoTitles` (Task 5). With stale cached credits, those are used instead: `staleCreditsAreUsedWhenTmdbFails` (Task 3).
3. **Hidden categories and disabled sources never appear in the person section.** Test: `personTitlesSkipHiddenAndDisabledAndPickOneCopy` (Task 2).
4. **Typed names:**
   - "Chris" shows no person, and neither does "tom cru".
   - "TOM CRUISE" and "tom cruise" find him.
   - Two exact namesakes show at most two people.
   - Test: `confidentPeopleRules` (Task 1).
5. **Series info page focus.** People arriving above the buttons must not move focus off Resume or the first episode. Test: `seriesPeopleRowKeepsStartingFocus` (Task 4).

---

### Task 1: TMDB wire calls and the people rules

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/tmdb/TmdbDtos.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/tmdb/TmdbApi.kt`
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/tmdb/PeopleRules.kt`
- Create fixtures: `app/src/test/resources/tmdb/movie_credits_361743.json`, `tv_people_1396.json`, `person_credits_500.json`, `search_person_tom_cruise.json`. Hand-written, small, in TMDB's shape (below).
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/tmdb/PeopleRulesTest.kt`; extend `TmdbApiTest.kt`.

**Interfaces:**
- Produces (DTOs, all fields defaulted as in the existing file):
  - `TmdbCredits(cast: List<TmdbCastCredit>, crew: List<TmdbCrewCredit>)`
  - `TmdbCastCredit(id: Long, name: String, character: String?, order: Int)`
  - `TmdbCrewCredit(id: Long, name: String, job: String?, department: String?)`
  - `TmdbTvPeople(id: Long, created_by: List<TmdbNamed>, aggregate_credits: TmdbAggregateCredits?)`
  - `TmdbNamed(id: Long, name: String)`
  - `TmdbAggregateCredits(cast: List<TmdbAggregateCast>)`, `TmdbAggregateCast(id: Long, name: String, order: Int, roles: List<TmdbRole>)`, `TmdbRole(character: String?, episode_count: Int)`
  - `TmdbPersonCredits(cast: List<TmdbPersonCast>, crew: List<TmdbPersonCrew>)`
  - `TmdbPersonCast(id: Long, media_type: String, character: String?, release_date: String?, first_air_date: String?, episode_count: Int?, genre_ids: List<Int>)`
  - `TmdbPersonCrew(id: Long, media_type: String, job: String?, department: String?, release_date: String?, first_air_date: String?, episode_count: Int?)`
  - `TmdbPersonResult(id: Long, name: String, known_for_department: String?, popularity: Double?)`
- Produces (`TmdbApi`):
  - `suspend fun movieCredits(id: Long, language: String): TmdbCredits?`: `movie/{id}/credits`, null on 404.
  - `suspend fun tvPeople(id: Long, language: String): TmdbTvPeople?`: `tv/{id}` with `append_to_response=aggregate_credits`, null on 404.
  - `suspend fun personCredits(personId: Long, language: String): TmdbPersonCredits?`: `person/{id}/combined_credits`, null on 404.
  - `suspend fun searchPerson(query: String, language: String): List<TmdbPersonResult>`: `search/person`.
- Produces (`PeopleRules.kt`):
  - `@Serializable enum class PersonJob { CAST, DIRECTOR, CREATOR }`
  - `@Serializable data class TmdbPerson(val id: Long, val name: String, val role: String?, val job: PersonJob)`. `role` is the character for CAST, else null; the UI words DIRECTOR / CREATOR.
  - `data class PersonCredit(val kind: TmdbKind, val tmdbId: Long, val date: String?)`
  - `data class PersonMatch(val id: Long, val name: String)`
  - `object PeopleRules` with:
    - `const val MAX_PEOPLE = 10`
    - `fun moviePeople(c: TmdbCredits): List<TmdbPerson>`
    - `fun tvPeople(t: TmdbTvPeople): List<TmdbPerson>`
    - `fun countedCredits(c: TmdbPersonCredits): List<PersonCredit>`
    - `fun confidentPeople(query: String, results: List<TmdbPersonResult>): List<PersonMatch>`

**Rules to implement** (exact):
- **`moviePeople`:**
  - crew with `job == "Director"`, de-duplicated by id, in list order;
  - then cast sorted by `order`, skipping ids already listed;
  - total at most `MAX_PEOPLE`;
  - `role` = character trimmed, blank → null.
- **`tvPeople`:**
  - `created_by` as CREATOR;
  - then `aggregate_credits.cast` sorted by `order` as CAST, with role = first role's character, skipping ids already listed;
  - at most `MAX_PEOPLE`.
- **`countedCredits`** (`media_type` must be `movie` or `tv`; others dropped):
  - **Cast counts unless any of these holds:**
    - the character, lower-cased and trimmed, matches `^(him|her|them)sel(f|ves)\b|^self\b`;
    - it's TV with `episode_count` in 1..2;
    - it's TV and `genre_ids` holds 10767 (Talk), 10763 (News) or 10764 (Reality).
  - **Crew counts when either holds:**
    - `job` is one of Director, Producer, Executive Producer, Creator;
    - `department == "Writing"`.
  - **TV crew with `episode_count` in 1..2 is dropped**, except a Creator.
  - **Date:** `release_date` for movies, `first_air_date` for TV; blank → null.
  - De-duplicate by (kind, tmdbId), keeping the latest non-null date.
- **`confidentPeople`:**
  - `q = TitleCleaner.normalize(query)`; empty → empty.
  - Keep results where `TitleCleaner.normalize(name) == q` and `known_for_department` is one of Acting, Directing, Writing, Production, Creator.
  - A one-word `q` also needs popularity ≥ 10.0.
  - Sort by popularity descending; take at most 2.

- [ ] **Step 1: Write the failing tests.**
  - `PeopleRulesTest`:
    - `movieDirectorsComeFirstThenCastByOrderAtMostTen`: the fixture has 2 directors and 12 cast, and one director also in the cast. Expect 10 people, the first two DIRECTOR, no duplicate id, and "Tom Cruise" with role "Maverick".
    - `tvCreatorsComeFirstThenMainCast`.
    - `countedCreditsKeepRealWorkOnly`. The fixture person has:
      - a movie role (kept);
      - "Himself" on a talk show (dropped);
      - "Self - Guest" (dropped);
      - a 1-episode TV guest role (dropped);
      - a 5-episode TV role (kept);
      - Producer and Executive Producer (kept);
      - "Thanks" (dropped);
      - "Screenplay" in Writing (kept);
      - a TV Director with episode_count 1 (dropped);
      - the same movie as cast and Producer (one credit).
    - `confidentPeopleRules`:
      - "Chris" against a 5.0-popularity "Chris" → empty;
      - "tom cru" → empty;
      - "TOM CRUISE" and "tom cruise" → `[PersonMatch(500, "Tom Cruise")]`;
      - three exact namesakes → the top 2 by popularity;
      - an exact name known for "Crew" → empty.
  - `TmdbApiTest`:
    - `personCallsUseTheirPaths`: assert the paths `/3/movie/1/credits`, `/3/tv/2` with `append_to_response=aggregate_credits`, `/3/person/3/combined_credits` and `/3/search/person?query=…`;
    - 404 → null for the three details calls.
- [ ] **Step 2: Run them.** Run `./gradlew :app:testDebugUnitTest --tests '*PeopleRulesTest' --tests '*TmdbApiTest'`. Expected: compilation FAIL (types not defined).
- [ ] **Step 3: Add the DTOs, the four `TmdbApi` calls (reuse `url()` and `get()`) and `PeopleRules`.**
- [ ] **Step 4: Run the same command.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(people): TMDB credits calls and the counted-credits rules"`

### Task 2: Storage v13 for people and credits

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/db/Entities.kt`, `Daos.kt`, `GoodTvDatabase.kt` (version 13, `MIGRATION_12_13`, `people()` DAO)
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/db/RoomPeopleCache.kt`
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/tmdb/PeopleCache.kt` (interface plus `PersonTitle`)
- Create: `app/src/test/java/com/keyspaceapps/goodtv/testing/InMemoryPeopleCache.kt`
- Commit: `app/schemas/.../13.json` (generated by the build)
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/data/db/MigrationTest.kt` (add `migrate12To13AddsPeopleCache`); create `app/src/androidTest/java/com/keyspaceapps/goodtv/data/db/PeopleDaoTest.kt`

**Interfaces:**
- **Tables:**
  - `tmdb_title_people(kind TEXT, tmdbId INTEGER, people TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PK(kind, tmdbId))`. `people` is the JSON of `List<TmdbPerson>`; `[]` records "TMDB lists nobody".
  - `tmdb_people(personId INTEGER PK, name TEXT NOT NULL, fetchedAt INTEGER NOT NULL)`
  - `tmdb_person_credits(personId INTEGER, kind TEXT, tmdbId INTEGER, date TEXT, PK(personId, kind, tmdbId))`. FK → `tmdb_people(personId)` ON DELETE CASCADE, plus an index on `personId`.
- **Produces** (`PeopleCache.kt`):
  ```kotlin
  sealed interface PersonTitle {
      val date: String?
      data class OfMovie(val movie: Movie, override val date: String?) : PersonTitle
      data class OfSeries(val series: Series, override val date: String?) : PersonTitle
  }
  interface PeopleCache {
      suspend fun titlePeople(kind: TmdbKind, tmdbId: Long): Pair<List<TmdbPerson>, Long>?   // people, fetchedAt
      suspend fun putTitlePeople(kind: TmdbKind, tmdbId: Long, people: List<TmdbPerson>, fetchedAt: Long)
      suspend fun creditsFetchedAt(personId: Long): Long?
      /** Replaces the person's credits in one transaction. */
      suspend fun putCredits(personId: Long, name: String, credits: List<PersonCredit>, fetchedAt: Long)
      /** Library titles in the person's credits: one copy each, enabled and visible only, newest date first (null dates last), then title. */
      fun observeTitles(personId: Long): Flow<List<PersonTitle>>
      suspend fun prune(before: Long)
  }
  ```
- **Queries.** `observeTitles` is two DAO queries, movies and series, merged in Kotlin by date descending.
  - Each query copies `PopularDao.observeMovies` / `observeSeries`: a correlated subquery picks the copy per `tmdbId`. The outer table is `tmdb_person_credits c WHERE c.personId = :personId AND c.kind = 'MOVIE'` (or 'TV').
  - Each also returns `c.date`, e.g. via a row class `PersonMovieRow(@Embedded movie: MovieEntity, date: String?)`.
  - `LIMIT 500` each.

- [ ] **Step 1: Write the failing tests.**
  - `migrate12To13AddsPeopleCache`:
    - create v12 with one `tmdb_meta` row;
    - migrate with `MIGRATION_12_13` and validate;
    - the meta row survives;
    - the three new tables are empty and accept an insert;
    - deleting the `tmdb_people` row cascades its credits.
  - `PeopleDaoTest` (in-memory Room, as `RoomTmdbCacheTest` does):
    - `personTitlesSkipHiddenAndDisabledAndPickOneCopy`. Setup:
      - a movie in three copies: EN 4K, EN HD, DE;
      - one in a hidden category;
      - one in a disabled source;
      - a series;
      - credits with dates 2022, 1986, 2010 and null.
    - Expected: one copy per title (the EN copy without a quality tag), the hidden and disabled titles absent, order 2022, 2010, 1986, then null.
  - `putCreditsReplacesThePersonsCredits`.
  - `titlePeopleRoundTripIncludingEmpty`.
- [ ] **Step 2: Run them.** Run `./gradlew :app:testDebugUnitTest`. Expected: compile FAIL until the types exist. After the types exist, run `/tmp/goodtv-tools/runclass.sh com.keyspaceapps.goodtv.data.db.PeopleDaoTest` and expect the new tests to FAIL.
- [ ] **Step 3: Implement.**
  - Add the entities, `PeopleDao`, the migration (exact `CREATE TABLE` / `CREATE INDEX` SQL from Room's generated 13.json), `RoomPeopleCache` (JSON via kotlinx `Json` with `ignoreUnknownKeys`) and `InMemoryPeopleCache`.
  - `InMemoryPeopleCache.observeTitles` takes a `FakeLibraryStore`-style list of movies and series in its constructor and applies the same rules.
- [ ] **Step 4: Run the tests again.** Run `runclass.sh` for `PeopleDaoTest`, then for `MigrationTest` (separate calls), then the unit test command. Expected: all PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(people): storage v13 for title people and person credits"`. Include 13.json.

### Task 3: PeopleRepository

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/tmdb/PeopleRepository.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/AppGraph.kt`. Add `peopleOverride` and `people`, mirroring `tmdbOverride` / `tmdb`, built from `tmdbApi`, `RoomPeopleCache(db.people())` and `tmdbLanguage`.
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/tmdb/PeopleRepositoryTest.kt` (MockWebServer dispatcher, as in `TmdbRepositoryTest`)

**Interfaces:**
- Consumes: Task 1 `TmdbApi` calls and `PeopleRules`; Task 2 `PeopleCache`, `PersonTitle`.
- Produces:
  ```kotlin
  class PeopleRepository(api: TmdbApi?, cache: PeopleCache, language: () -> String, clock: () -> Long = System::currentTimeMillis) {
      val isEnabled: Boolean
      /** success(empty) = TMDB lists nobody or no api; failure = temporary (never cached). */
      suspend fun titlePeople(kind: TmdbKind, tmdbId: Long): Result<List<TmdbPerson>>
      /** Makes the person's counted credits available: fresh cache → success; else fetch; fetch fails with any cache → success (stale); none → failure. */
      suspend fun ensureCredits(personId: Long, name: String): Result<Unit>
      /** Confident matches for a typed name (PeopleRules.confidentPeople); failure when TMDB is unreachable. Memoised in memory for MEMO_TTL_MS per normalized query. */
      suspend fun findPeople(query: String): Result<List<PersonMatch>>
      fun observeTitles(personId: Long): Flow<List<PersonTitle>>
      companion object {
          const val TITLE_PEOPLE_TTL_MS = 30L * 24 * 3_600_000
          const val CREDITS_TTL_MS = 7L * 24 * 3_600_000
          const val MEMO_TTL_MS = 3_600_000L
      }
  }
  ```
- `ensureCredits` calls `cache.prune(clock() - TmdbRepository.MAX_CACHE_AGE_MS)` after each successful write.
- `titlePeople` for `TmdbKind.MOVIE` uses `movieCredits` → `moviePeople`; for TV it uses `tvPeople` → `PeopleRules.tvPeople`. A 404 caches `[]`.

- [ ] **Step 1: Write the failing tests:**
  - `titlePeopleAreFetchedOnceThenCached`
  - `titlePeopleRefetchAfterTtl`
  - `titlePeopleFailureIsNotCached`
  - `ensureCreditsStoresOnlyCountedCredits` (cache holds the Task 1 fixture's kept ids only)
  - `freshCreditsSkipTheNetwork`
  - `staleCreditsAreUsedWhenTmdbFails` (server 500 after the TTL → success, and titles still observed)
  - `noCreditsAndNoNetworkFails`
  - `findPeopleUsesRulesAndMemo` (a second call with "Tom  Cruise" makes no request)
  - `disabledRepositoryReturnsEmptySuccess` (api null)
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*PeopleRepositoryTest'`.** Expected: FAIL (class missing).
- [ ] **Step 3: Implement `PeopleRepository` and wire it in `AppGraph`.**
- [ ] **Step 4: Run the same command, then the full unit test command.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(people): PeopleRepository with cached people, credits and name search"`

### Task 4: People row on movie and series info pages

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/tmdb/PeopleRow.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/ui/movies/MovieDetailViewModel.kt`, `MovieDetailScreen.kt`, `ui/series/SeriesDetailViewModel.kt`, `SeriesDetailScreen.kt`, `ui/tmdb/TmdbUi.kt` (`TmdbCredit`), `ui/nav/NavKeys.kt`, `ui/nav/GoodTvNavigation.kt`, `res/values/strings.xml`
- Test: extend `app/src/test/java/com/keyspaceapps/goodtv/ui/movies/MovieDetailViewModelTest.kt`; create `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/PeopleRowTest.kt`

**Interfaces:**
- Consumes: Task 3 `PeopleRepository.titlePeople`.
- Produces:
  - `@Serializable data class PersonSearchKey(val personId: Long, val name: String) : GoodTvKey`
  - `@Composable fun PeopleRow(people: List<TmdbPerson>, onPerson: (TmdbPerson) -> Unit, modifier: Modifier = Modifier)`:
    - the label "People" (`R.string.detail_people`);
    - a `LazyRow` of tv-material `Button`s showing the name plus the role in a smaller, dimmer style;
    - the role is the character for CAST, `R.string.role_director` / `R.string.role_creator` otherwise;
    - each button has testTag `person-<id>`;
    - nothing is drawn when `people` is empty.
  - `MovieDetailScreen(key, onPlay, onPerson: (PersonSearchKey) -> Unit)` and the same new parameter on `SeriesDetailScreen`. Navigation pushes the key.
  - Both detail view models gain `people: StateFlow<List<TmdbPerson>>` (starts empty) and a constructor parameter `peopleRepo: PeopleRepository? = null`, last.
- **Loading:** the view models fetch the meta and people concurrently (`async`) once the catalog row's `tmdbId` is known.
  - `_people` is set, then `_tmdb`, in the same continuation, so both land in one frame.
  - If people fail, the result is empty.
  - The provider `tmdbId` is preferred; when it's null, use the id the meta lookup found (`meta.tmdbId`).
- **Movie page:** the `PeopleRow` goes after the plot. When people is non-empty, the provider "Cast:" and "Director:" lines are hidden; otherwise they show as today.
- **Series page:** the `PeopleRow` goes after the plot and `TmdbCredit`, before the buttons. The season row, the episode list and the existing `onPlaced` focus logic don't change.
- **`TmdbCredit(tmdb, hasPeople: Boolean = false)`** also shows when `hasPeople`.

- [ ] **Step 1: Write the failing tests.**
  - Unit test `peopleArriveWithTmdbMeta`: a fake TMDB server; `vm.people` holds the fixture people once `vm.tmdb` is non-null.
  - Unit test `peopleUseLookupIdWhenProviderHasNone`.
  - `PeopleRowTest` (instrumented, mock TMDB server as in `TmdbUiTest`, `graph.peopleOverride` set, a `Timeout` rule):
    - `moviePeopleRowIsBelowPlayAndOpensPersonSearch`:
      - DOWN from Play reaches the first person;
      - RIGHT moves to the next;
      - Select calls `onPerson` with `PersonSearchKey(<id>, "<name>")`;
      - the provider "Cast:" text is absent.
    - `seriesPeopleRowKeepsStartingFocus`:
      - with progress, focus starts on Resume after people arrive;
      - UP reaches a person;
      - without progress, focus starts on the first episode.
    - `noPeopleFallsBackToProviderCast`: TMDB 404 for credits, so "Cast: A, B" shows and no `person-` tag exists.
- [ ] **Step 2: Run the unit tests (expect FAIL), then `runclass.sh com.keyspaceapps.goodtv.ui.PeopleRowTest`** (expect FAIL).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the unit test command, `runclass.sh` for `PeopleRowTest`, then `runclass.sh com.keyspaceapps.goodtv.ui.TmdbUiTest`.** Expected: all PASS. Existing detail behaviour is unchanged.
- [ ] **Step 5: Commit.** `git commit -m "feat(people): clickable People row on movie and series info pages"`

### Task 5: Person results in search and the person search screen

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/ui/home/HomeSearchViewModel.kt`, `HomeTabScreen.kt`, `ui/nav/GoodTvNavigation.kt`, `res/values/strings.xml`
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/home/SearchPane.kt`, `ui/home/PersonSearchScreen.kt`
- Test: extend `app/src/test/java/com/keyspaceapps/goodtv/ui/home/HomeSearchViewModelTest.kt`; create `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/PersonSearchTest.kt`

**Interfaces:**
- Consumes: Task 3 `PeopleRepository` (`findPeople`, `ensureCredits`, `observeTitles`); Task 4 `PersonSearchKey`.
- Produces:
  ```kotlin
  sealed interface PersonSection {
      val name: String
      data class Found(val personId: Long, override val name: String, val titles: List<SearchResult>) : PersonSection
      data class NoTitles(override val name: String) : PersonSection        // pinned person only
      data class Offline(override val name: String) : PersonSection         // pinned person only
  }
  class HomeSearchViewModel(repo: LibraryRepository, people: PeopleRepository? = null, pinned: PersonSearchKey? = null)
      val people: StateFlow<List<PersonSection>>   // empty while blank / no match / no TMDB
  @Composable fun SearchPane(vm: HomeSearchViewModel, onNavigate: (GoodTvKey) -> Unit, idle: @Composable () -> Unit)
  @Composable fun PersonSearchScreen(key: PersonSearchKey, onNavigate: (GoodTvKey) -> Unit)
  ```
- **The pinned person:**
  - With `pinned`, the query starts as `pinned.name`.
  - While `query.trim().equals(pinned.name, ignoreCase = true)`, the person section uses `pinned.personId`:
    - `ensureCredits` failure gives `Offline`;
    - empty titles give `NoTitles`;
    - otherwise `Found`.
  - Any other query resolves typed names.
- **Typed names:**
  - Uses the same debounce as `results`.
  - `findPeople`: failure or empty gives an empty list.
  - Otherwise, for each match: `ensureCredits`, then `observeTitles` (skipped on failure); `Found` only when it has titles.
  - It's its own flow, so `results` never waits for it.
- **The scope filter:** MOVIES keeps the movie titles, SERIES the series, LIVE none.
- **`SearchPane`:**
  - It holds the scope dropdown, the search bar and the results area moved out of `HomeTabScreen`.
  - When `results == null` and there are no `people`, it shows `idle()`.
  - `HomeTabScreen` passes its rails as `idle`; `PersonSearchScreen` passes nothing.
- **The results grid:**
  - Each person section gets a full-width header (`GridItemSpan(maxLineSpan)`) with plurals `person_titles` ("With %1$s · %2$d title(s)"), then its posters, then the ordinary results.
  - Person poster keys are `"person:${personId}:${result.key}"`, so they never collide with ordinary keys. `FocusMemory` uses the same keys.
  - **The "no results" text** shows only when ordinary results are empty and no section exists.
  - **`NoTitles`** shows "No titles with %1$s" (`person_no_titles`) above any ordinary results.
  - **`Offline`** shows "Searching by person needs the internet" (`person_offline`) above them.
- **`PersonSearchScreen`:**
  - It uses `goodTvViewModel(key = "person-${key.personId}") { HomeSearchViewModel(it.repository, it.people, key) }` and fills the screen with `SearchPane`.
  - Initial focus goes to the first person poster when there is one, else the search field. Request it from `onPlaced`.
  - Back pops to the info page (NavDisplay default).
- **`HomeTabScreen`** constructs `HomeSearchViewModel(it.repository, it.people)`.

- [ ] **Step 1: Write the failing tests.**
  - `HomeSearchViewModelTest`, with a fake TMDB server and `InMemoryPeopleCache`:
    - `typedPersonNameAddsASection`
    - `partialNameAddsNoSection`
    - `personSectionNeverDelaysTitleResults` (TMDB delayed 5 s; `results` arrives at the debounce)
    - `pinnedPersonUsesItsIdNotTheName` (two namesakes; only the pinned id's titles)
    - `editingThePinnedQueryResolvesByName`
    - `clickedPersonOfflineShowsNoteNotNoTitles`
    - `clickedPersonWithNothingInLibraryShowsNoTitles`
    - `scopeFiltersThePersonSection`
  - `PersonSearchTest` (instrumented, mock TMDB server, `Timeout` rule):
    - `clickingAPersonOpensTheirTitlesAndBackReturns`:
      - from a movie page, DOWN to a person, then Select;
      - the header "With Tom Cruise · 2 titles" and both posters are displayed, with focus on the first;
      - Back shows the movie page with focus on that person.
    - `personAndTitleSectionsMayShareACopy`:
      - seed a movie titled "Tom Cruise: Live" that is also in his credits, so the query "Tom Cruise" puts the same copy in both sections;
      - both sections show the copy, and opening the second one and coming back restores focus to it.
    - `homeSearchFindsAPersonByTypedName`.
- [ ] **Step 2: Run the unit tests (expect FAIL), then `runclass.sh com.keyspaceapps.goodtv.ui.PersonSearchTest`** (expect FAIL).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run these, each a separate call.** Expected: all PASS.
  - the unit test command;
  - `runclass.sh` for `PersonSearchTest`;
  - `HomeTabScreenTest`;
  - `HomeTabsTest`;
  - `FocusRestoreTest`.
- [ ] **Step 5: Run the full connected suite once:** `/tmp/goodtv-tools/runclass.sh` with no class. Expected: all pass, except the known-flaky `OnlineSubtitlesTest`, which should pass when rerun alone.
- [ ] **Step 6: Commit.** `git commit -m "feat(people): person results in search and the person search screen"`
