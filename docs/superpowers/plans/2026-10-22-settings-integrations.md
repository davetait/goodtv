# Settings and Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Settings tab (replacing the Sources tab) that opens Sources and a new Integrations screen. There users enter, check and save their TMDB key and their OpenSubtitles key and login. Debug builds keep the `local.properties` keys as defaults; release builds carry none.

**Architecture:**
- **Saving:** keys typed in Integrations go into their own DataStore file (`integrations`), which is excluded from backup. An `Integrations` class merges them with the build's defaults into live "effective" values plus a status.
- **Applying changes without a restart:**
  - `TmdbApi` reads its credential from a provider on every request.
  - `OpenSubtitlesApi` reads its access the same way and drops its login session when the access changes.
- **Status updates:** both APIs report accepted or rejected calls back to `Integrations`.
- **UI:** the Settings tab is a two-row list; Sources and Integrations open as full-screen nav entries.

**Tech Stack:** Kotlin, Compose for TV (tv-material3), DataStore Preferences, OkHttp, kotlinx.serialization, MockWebServer (mockwebserver3), JUnit4, Compose UI tests on the `GoogleTV_API34` emulator, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-10-22-settings-integrations-design.md`

## Global Constraints

- **Built-in keys:**
  - Only debug builds carry them. Release builds set `TMDB_API_KEY`, `OPENSUBTITLES_API_KEY`, `OPENSUBTITLES_USERNAME` and `OPENSUBTITLES_PASSWORD` to `""`, as `XTREAM_*` already are.
  - Proof: a release unit test, and a release-APK scan that fails the build if any configured value appears. The scan names the field, never the value.
- **Secrets:**
  - Never logged and never shown in full on screen. A key shows as `••••` plus its last 4 characters; a password shows as `••••••••`.
  - Kept out of cloud backup and device transfer.
  - Never print `local.properties`, and never put real keys in tests.
- **Precedence:** a typed value wins over the build's value, which wins over nothing. Remove on a typed value falls back to the build's value; the build's value can't be removed.
- **Applying:** new or removed keys apply without restarting the app.
- **Test switches:** `GoodTvApp.disableTmdb` / `disableOpenSubtitles` still force each service off (instrumented tests start with services off). `tmdbOverride` / `peopleOverride` stay.
- **Database:** Room stays at v13; nothing here touches Room.
- **Copy (exact strings):**
  - **Tab and rows:**
    - `tab_settings` "Settings"
    - `settings_sources` "Sources"
    - `settings_integrations` "Integrations"
    - `settings_sources_none` "No sources yet"
    - plurals `settings_sources_count` "%d source" / "%d sources"
    - `settings_sources_synced` "%1$s · last synced %2$s"
  - **Integrations status line** (two parts joined with " · "):
    - `integration_connected` "%1$s connected"
    - `integration_not_set` "%1$s not set up"
    - `integration_rejected` "%1$s rejected"
    - `integration_unchecked` "%1$s not checked yet"
  - **Integrations screen intro:**
    - `integrations_title` "Integrations"
    - `integrations_intro` "Optional services. GoodTV works without them; they add posters, ratings, cast and subtitles."
    - `integrations_phone_hint` "Tip: use the Google TV app on your phone to paste keys."
  - **TMDB card:**
    - `tmdb_name` "TMDB"
    - `tmdb_what` "Posters, ratings, summaries, genres, popular rows and cast."
    - `tmdb_where` "Create a free API key at %1$s", with "themoviedb.org" bold
    - `tmdb_field` "API key or read access token"
  - **OpenSubtitles card:**
    - `os_name` "OpenSubtitles"
    - `os_what` "English subtitles for titles that don't have their own."
    - `os_where` "Create an API key at %1$s", with "opensubtitles.com" bold
    - `os_why` "The API key lets GoodTV search for subtitles. Your OpenSubtitles username and password are needed to download them: without signing in, downloads are limited to a small daily allowance."
    - fields `os_key` "API key", `os_username` "Username", `os_password` "Password"
  - **Badges:**
    - `badge_not_set` "Not set up"
    - `badge_checking` "Checking…"
    - `badge_connected` "Connected"
    - `badge_rejected` "Rejected"
    - `badge_unchecked` "Couldn't check"
    - `from_build` "From this build"
  - **Messages:**
    - `tmdb_rejected` "TMDB didn't accept this key"
    - `os_key_rejected` "OpenSubtitles didn't accept this API key"
    - `os_login_rejected` "OpenSubtitles didn't accept this username or password"
    - `tmdb_unreachable` "Couldn't reach TMDB. Saved; it will be used when the connection is back."
    - `os_unreachable` "Couldn't reach OpenSubtitles. Saved; it will be used when the connection is back."
    - `os_need_both` "Enter both the username and the password"
    - `os_need_key` "Enter the API key too"
  - **Buttons:** `save` "Save", `remove` "Remove"
- **Tests:**
  - Unit tests: `./gradlew :app:testDebugUnitTest :app:lintDebug`. Expected: all pass, lint 0 errors.
  - Instrumented tests run only through `/tmp/goodtv-tools/runclass.sh <fully.qualified.Class>`: backup, run, reinstall, restore. One class per call.
  - Request focus from `onPlaced`. tv-material ignores touch, so drive tests with keys. Merged testTags need `useUnmergedTree = true`. Add a `Timeout` rule to UI tests.

## Review Focus

1. **A key that is revoked later.**
   - A real TMDB or OpenSubtitles call that gets HTTP 401/403 turns the stored status to Rejected, so Settings says so. A later successful call after "Couldn't check" turns it to Connected.
   - Tests: `rejectedCallMarksStatusRejected`, `successAfterUncheckedMarksConnected` (Task 3).
2. **Saving while offline.**
   - Values are saved, the badge shows "Couldn't check", and the service is used.
   - Test: `offlineSaveKeepsValuesAndSaysCouldntCheck` (Task 4).
3. **OpenSubtitles partial input.**
   - A good key with a wrong password saves nothing and names the login. A username without a password, and a login without a key, are refused before any network call.
   - Tests: `goodKeyBadLoginSavesNothing`, `loginNeedsBothFields`, `loginWithoutKeyAsksForKey` (Task 4).
4. **A key entered while Home is already open.**
   - Posters and popular rows start working without a restart. A TMDB lookup that ran before the key existed must not stay cached as "no data" for an hour.
   - Test: `memoForgottenWhenCredentialChanges` (Task 3).
5. **A release build on a machine with `local.properties` keys.**
   - Empty fields, and the APK scan passes.
   - Tests: `releaseBuildHasNoKeys` (Task 1), plus `./gradlew :app:assembleRelease` running `checkReleaseApkForKeys` (Task 1).

---

### Task 1: Release builds without keys, and a keys-only backup exclusion

**Files:**
- Modify: `app/build.gradle.kts` (release `buildConfigField`s; new `checkReleaseApkForKeys` task)
- Create: `app/src/testRelease/java/com/keyspaceapps/goodtv/ReleaseBuildConfigTest.kt`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`

**Interfaces:**
- Produces:
  - In release, `BuildConfig.TMDB_API_KEY`, `OPENSUBTITLES_API_KEY`, `OPENSUBTITLES_USERNAME` and `OPENSUBTITLES_PASSWORD` are all `""`.
  - Gradle task `:app:checkReleaseApkForKeys`. `assembleRelease` is finalized by it.
  - Backup rules exclude `domain="file" path="datastore/integrations.preferences_pb"` (the file Task 2 creates) in cloud backup, device transfer and the pre-12 `full-backup-content`.
- **`checkReleaseApkForKeys`:**
  - For each of the 4 `local.properties` entries (`GOODTV_TMDB_API_KEY`, `GOODTV_OPENSUBTITLES_API_KEY`, `GOODTV_OPENSUBTITLES_USERNAME`, `GOODTV_OPENSUBTITLES_PASSWORD`) that is non-blank and at least 6 characters long, it reads every entry of the release APK(s) under `build/outputs/apk/release/` as bytes and searches for the value's UTF-8 bytes.
  - Values shorter than 6 characters are skipped, and the task logs a line naming the entry as too short to scan.
  - **On a hit:** it fails with "Release APK contains the value of <ENTRY NAME>". It never prints the value.
  - **When nothing is configured:** it logs that it skipped and succeeds.

- [ ] **Step 1: Write `ReleaseBuildConfigTest.releaseBuildHasNoKeys`.** It asserts that the four fields equal `""`, and that `XTREAM_SERVER` is `""` as a control.
- [ ] **Step 2: Run `./gradlew :app:testReleaseUnitTest --tests '*ReleaseBuildConfigTest'`.** Expected: FAIL, because the TMDB field is non-empty on a machine with `local.properties` keys. If the machine has none, note that the RED can't show and continue.
- [ ] **Step 3: Implement.** Add the four empty release fields, the scan task and the backup exclusions. In the scan, read `local.properties` with the existing `localProp()` helper.
- [ ] **Step 4: Run these:**
  - `./gradlew :app:testReleaseUnitTest --tests '*ReleaseBuildConfigTest'` → PASS.
  - `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL, with the scan reporting it found no keys.
  - Show the scan works: temporarily remove one release `buildConfigField` locally, run `assembleRelease`, confirm it FAILS naming the entry, then restore the field. Don't commit that state; record the output (entry name only) in the report.
  - The unit-test command → PASS.
- [ ] **Step 5: Commit.** `git commit -m "build: release builds carry no TMDB or OpenSubtitles keys, and assembleRelease checks the APK"`

### Task 2: Saved integration values and their effective values

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/settings/Integrations.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/AppGraph.kt` (a `preferencesDataStore(name = "integrations")`, plus `val integrations: Integrations`)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/settings/IntegrationsTest.kt` (uses `testing/InMemoryDataStore`)

**Interfaces:**
- Produces:
  ```kotlin
  enum class KeyStatus { NOT_SET, UNCHECKED, CONNECTED, REJECTED_KEY, REJECTED_LOGIN }
  data class OpenSubtitlesAccess(val apiKey: String, val login: OpenSubtitlesLogin?)   // OpenSubtitlesLogin exists in data/subtitles
  data class BuildDefaults(val tmdbKey: String, val osKey: String, val osUsername: String, val osPassword: String)
  data class TmdbState(val key: String?, val fromBuild: Boolean, val status: KeyStatus)          // key null = off
  data class OpenSubtitlesState(val access: OpenSubtitlesAccess?, val fromBuild: Boolean, val status: KeyStatus)
  class Integrations(dataStore: DataStore<Preferences>, defaults: BuildDefaults, scope: CoroutineScope) {
      val tmdb: StateFlow<TmdbState>              // starts from defaults; NOT_SET when no key
      val openSubtitles: StateFlow<OpenSubtitlesState>
      suspend fun saveTmdb(key: String, status: KeyStatus)
      suspend fun removeTmdb()                      // drops the typed value
      suspend fun saveOpenSubtitles(access: OpenSubtitlesAccess, status: KeyStatus)
      suspend fun removeOpenSubtitles()
      /** Records a result for the value currently in effect; ignored if [forKey] is no longer the effective value. */
      suspend fun recordTmdbStatus(forKey: String, status: KeyStatus)
      suspend fun recordOpenSubtitlesStatus(forAccess: OpenSubtitlesAccess, status: KeyStatus)
  }
  ```
- **Status storage:** status is stored with a fingerprint of the value it belongs to (SHA-256 hex of the value, first 16 characters; never the value). When the effective value's fingerprint differs from the stored one, the status reads `UNCHECKED`.
  - So a build key that was never checked reads `UNCHECKED`, and so does a changed build key.
  - A missing value reads `NOT_SET`.
- **OpenSubtitles fields:** a blank username or password means no login (`login = null`).
- **AppGraph wiring:** builds `BuildDefaults` from the four `BuildConfig` fields, with `appScope`. A service whose test switch is set (`GoodTvApp.disableTmdb` / `disableOpenSubtitles`) gets empty defaults. Instrumented tests then see "not set up" rather than the debug build's keys.

- [ ] **Step 1: Write the failing tests:**
  - `buildDefaultsApplyWhenNothingTyped`: `fromBuild` is true, status UNCHECKED.
  - `noDefaultsAndNothingTypedIsNotSet`
  - `typedValueWinsOverBuild`
  - `removeFallsBackToBuildValue`
  - `statusFollowsTheValueItWasRecordedFor`: record CONNECTED, save a different key, and the status reads the new save's status.
  - `recordForAStaleValueIsIgnored`
  - `openSubtitlesBlankLoginMeansKeyOnly`
  - `valuesSurviveANewInstance`: a new `Integrations` on the same DataStore reads the same state.
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*IntegrationsTest'`.** Expected: FAIL (does not compile).
- [ ] **Step 3: Implement `Integrations` and its AppGraph wiring.**
- [ ] **Step 4: Run the same command, then the unit-test command.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(settings): typed integration keys over build defaults, with per-value status"`

### Task 3: Live API clients, checks and status feedback

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/tmdb/TmdbApi.kt`, `TmdbRepository.kt`, `PeopleRepository.kt`, `Popular.kt`, `TmdbGenreFetcher.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/subtitles/OpenSubtitlesApi.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/AppGraph.kt`
- Test: extend `TmdbApiTest.kt`, `TmdbRepositoryTest.kt`, `app/src/test/.../data/subtitles/OpenSubtitlesApiTest.kt` (the existing file); create `app/src/test/.../data/settings/IntegrationFeedbackTest.kt`

**Interfaces:**
- Consumes: Task 2 `Integrations`, `TmdbState`, `OpenSubtitlesState`, `KeyStatus`.
- **`TmdbApi`:**
  - The primary constructor takes `credential: () -> TmdbCredential?` and `onCredentialResult: (TmdbCredential, accepted: Boolean) -> Unit = { _, _ -> }`.
  - A secondary constructor keeps `TmdbApi(client, credential: TmdbCredential, …)` working for existing tests.
  - `val isConfigured: Boolean` is true when `credential()` is non-null.
  - Each request reads `credential()` once. Null throws `TmdbException(…, Reason.AUTH)`.
  - A 2xx response reports `accepted = true` for that credential; a 401 reports `false`.
- **`suspend fun TmdbApi.check(candidate: TmdbCredential): Boolean`:**
  - GET `configuration` with the candidate.
  - Returns true on 2xx and false on 401.
  - Throws `TmdbException` on network or server errors.
  - Doesn't call `onCredentialResult`.
- **Repositories:** `TmdbRepository`, `PeopleRepository`, `PopularRepository` and `TmdbGenreFetcher` treat "api null or not configured" as today's "api null". `isEnabled` becomes `api?.isConfigured == true`.
- **`TmdbRepository.forgetMemo()` and `PeopleRepository.forgetMemo()`** clear their in-memory memos.
- **`OpenSubtitlesApi`:**
  - The primary constructor takes `access: () -> OpenSubtitlesAccess?`, an `onResult: (OpenSubtitlesAccess, KeyStatus) -> Unit = { _, _ -> }` callback reporting CONNECTED / REJECTED_KEY / REJECTED_LOGIN, and the existing `baseUrl`, `io`, `loginScheme` and `allowInsecureLinks`.
  - A secondary constructor keeps `OpenSubtitlesApi(client, apiKey, credentials, …)` working.
  - `val isConfigured` is true when `access()` is non-null.
  - The cached session is dropped when `access()` differs from the access the session was made with.
  - Rejection mapping: a 401/403 on `/login` is REJECTED_LOGIN; a 401/403 on any other call is REJECTED_KEY.
- **`suspend fun OpenSubtitlesApi.check(candidate: OpenSubtitlesAccess): KeyStatus`:**
  - GET `infos/formats` with the candidate key; 401/403 → REJECTED_KEY.
  - Then, if there is a login, POST `login`; 401/403 → REJECTED_LOGIN.
  - Otherwise CONNECTED.
  - Throws `OpenSubtitlesException(NETWORK|SERVER|…)` when it can't decide.
  - Doesn't touch the live session or `onResult`.
- **`AppGraph`:**
  - One `tmdbApi` whose `credential` is `integrations.tmdb.value.key?.let(TmdbCredential::from)` unless `GoodTvApp.disableTmdb`.
  - `onCredentialResult` calls `integrations.recordTmdbStatus(...)` in `appScope`: CONNECTED for accepted, REJECTED_KEY for rejected.
  - One `OpenSubtitlesApi` wired the same way, unless `GoodTvApp.disableOpenSubtitles`.
  - `onlineSubtitles` becomes a getter: the `OnlineSubtitles` instance when `osApi.isConfigured`, else null.
  - A collector on `integrations.tmdb.map { it.key }.distinctUntilChanged().drop(1)` calls `tmdb.forgetMemo()` and `people.forgetMemo()`, then `genreFetcher.start()`.

- [ ] **Step 1: Write the failing tests:**
  - `TmdbApiTest`:
    - `credentialIsReadPerRequest`: change the provider between calls; each request carries the current key.
    - `noCredentialFailsAsAuth`
    - `resultsAreReported`: 200 → accepted, 401 → rejected.
    - `checkUsesTheCandidateNotTheProvider`
  - `TmdbRepositoryTest`: `memoForgottenWhenCredentialChanges`. A lookup with no credential returns success(null); set the credential and call `forgetMemo()`; the next lookup hits the server.
  - `OpenSubtitlesApiTest`:
    - `accessChangeDropsTheSession`
    - `loginRejectionReportsRejectedLogin`
    - `keyRejectionReportsRejectedKey`
    - `checkReportsEachOutcome`: 403 on formats, 401 on login, success, and 500 throws.
  - `IntegrationFeedbackTest`, with `Integrations` plus a `TmdbApi` against MockWebServer wired as AppGraph does:
    - `rejectedCallMarksStatusRejected`
    - `successAfterUncheckedMarksConnected`
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest`.** Expected: the new tests FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the unit-test command.** Expected: all PASS, including every existing TMDB, people, popular, genre and OpenSubtitles test.
- [ ] **Step 5: Commit.** `git commit -m "feat(settings): TMDB and OpenSubtitles clients follow the saved keys and report their status"`

### Task 4: Integrations screen

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/settings/IntegrationsScreen.kt`, `ui/settings/IntegrationsViewModel.kt`
- Modify: `ui/nav/NavKeys.kt` (`@Serializable data object IntegrationsKey : GoodTvKey`), `ui/nav/GoodTvNavigation.kt` (its entry), `res/values/strings.xml`
- Test: `app/src/test/.../ui/settings/IntegrationsViewModelTest.kt`; create `app/src/androidTest/.../ui/IntegrationsScreenTest.kt`

**Interfaces:**
- Consumes:
  - Task 2 `Integrations`.
  - Task 3 `TmdbApi.check` and `OpenSubtitlesApi.check`, which AppGraph exposes as `val integrationChecks: IntegrationChecks`.
  - Define in this task, in `IntegrationsViewModel.kt`:
    ```kotlin
    interface IntegrationChecks {
        suspend fun tmdb(key: String): Boolean
        suspend fun openSubtitles(access: OpenSubtitlesAccess): KeyStatus
    }
    ```
    Both throw on "couldn't decide". AppGraph builds it from the two APIs; tests use fakes.
- Produces:
  - `IntegrationsViewModel(integrations: Integrations, checks: IntegrationChecks)`, exposing per card a `StateFlow` of `CardUi(badge, message: Int? /* string res */, maskedKey: String?, maskedPassword: Boolean, username: String?, fromBuild: Boolean, busy: Boolean)`.
  - Methods: `saveTmdb(typed: String)`, `removeTmdb()`, `saveOpenSubtitles(key: String, username: String, password: String)`, `removeOpenSubtitles()`.
- **Rules:**
  - **Empty fields:** an empty field keeps that field's saved value. TMDB Save with an empty field does nothing.
  - **Trimming:** input is trimmed.
  - **OpenSubtitles validation, before any network call:**
    - exactly one of username or password given → `os_need_both`;
    - a username and password but no key, typed or saved → `os_need_key`.
  - **Save outcomes:** while the check runs the badge says Checking….
    - Accepted → save with CONNECTED.
    - Rejected → don't save, show the rejection message, and keep the typed text.
    - Check throws → save with UNCHECKED and show the unreachable message.
  - **A build value still UNCHECKED** when the screen opens is checked once in the background, and its result is recorded.
  - **Masking:** `maskedKey` = "••••" plus the last 4 characters (all dots for keys under 8 characters). Passwords always show "••••••••".
  - **Remove** is shown only when a typed value exists.
- **Screen layout:**
  - The title, intro and phone hint, then the TMDB card, then the OpenSubtitles card.
  - Each card shows its name, badge, what it adds and where (domain in bold), then the fields with `TvTextField` (password field masked), the message line and Save / Remove.
  - The "From this build" label sits next to the badge when `fromBuild`.
  - Initial focus is on the TMDB field, requested from `onPlaced`.

- [ ] **Step 1: Write the failing tests.**
  - `IntegrationsViewModelTest` (fake checks, `InMemoryDataStore`):
    - `acceptedKeySavesConnected`
    - `rejectedKeySavesNothingAndSaysSo`
    - `offlineSaveKeepsValuesAndSaysCouldntCheck`
    - `goodKeyBadLoginSavesNothing`
    - `loginNeedsBothFields`
    - `loginWithoutKeyAsksForKey`
    - `emptyFieldKeepsSavedValue`
    - `uncheckedBuildValueIsCheckedOnce`
    - `removeFallsBackToBuild`
    - `masking`: "abcd1234wxyz" → "••••wxyz"; "short" → "•••••".
  - `IntegrationsScreenTest` (instrumented; `graph.integrations` reset to an empty `InMemoryDataStore`-style instance or cleared at setup, and fake checks injected through a `@VisibleForTesting var integrationChecksOverride`):
    - `showsDomainsHintAndExplanation`: "themoviedb.org", "opensubtitles.com", the hint text and the `os_why` text are displayed.
    - `tmdbFieldHasInitialFocusAndDownReachesSave`
    - `savedKeyIsMaskedNeverShownInFull`: a saved "abcd1234wxyz" shows "••••wxyz", and no node has text "abcd1234wxyz".
- [ ] **Step 2: Run the unit tests (FAIL), then `runclass.sh com.keyspaceapps.goodtv.ui.IntegrationsScreenTest`** (FAIL).
- [ ] **Step 3: Implement the screen, view model, nav key, nav entry, strings and the `integrationChecks` wiring in AppGraph.**
- [ ] **Step 4: Run the unit-test command and the runclass call.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(settings): Integrations screen for TMDB and OpenSubtitles keys"`

### Task 5: Settings tab with Sources and Integrations

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/settings/SettingsScreen.kt`
- Modify:
  - `ui/nav/NavKeys.kt`: `HomeTab.SOURCES` → `HomeTab.SETTINGS`, and `@Serializable data object SourcesKey : GoodTvKey`.
  - `ui/nav/GoodTvNavigation.kt`: a `SourcesKey` entry rendering the existing `SourcesScreen`, with the top padding the tab row used to give.
  - `ui/home/HomeScreen.kt`: the tab label and content, the first-launch rule, and mapping a saved "SOURCES" to SETTINGS.
  - `res/values/strings.xml`
- Test: update the existing tests that select the Sources tab (`HomeTabsTest`, `SourcesFlowTest`, `CategoriesScreenTest`, `EmptyStatesTest` and any others `grep -rn "SOURCES\|tab_sources" app/src` finds) to go Settings → Sources. Create `app/src/androidTest/.../ui/SettingsScreenTest.kt`.

**Interfaces:**
- Consumes: Task 4 `IntegrationsKey`; Task 2 `Integrations` (status line); `LibraryRepository` (source count, last sync) as `SourcesViewModel` reads it.
- Produces: `@Composable fun SettingsScreen(onNavigate: (GoodTvKey) -> Unit, focusSourcesFirst: Boolean)`.
- **Rows:** two tv-material `ListItem`s with title, status line and a "›" trailing.
  - **Sources status:** `settings_sources_none`, or `settings_sources_synced` with the count plural and the latest `lastSyncAt` formatted as `SourcesScreen` formats times.
  - **Integrations status:** both services' parts joined with " · ".
- **Focus:**
  - Focus starts on the Sources row, via `onPlaced`.
  - Back from a pushed screen restores focus to the row that opened it (use `FocusMemory` and `rememberedFocus` as other screens do).
- **First launch with zero sources:** the app opens on SETTINGS with the Sources row focused. On `SourcesScreen` with no sources, "Add source" is focused, as today.

- [ ] **Step 1: Write the failing tests.** `SettingsScreenTest`:
  - `settingsTabReplacesSourcesTab`: the tab labelled "Settings" exists, no tab is labelled "Sources", and it's last in the row.
  - `firstLaunchOpensSettingsWithSourcesFocused`: Select then shows "Add source" focused.
  - `sourcesRowShowsCountAndLastSync`
  - `integrationsRowShowsBothStatuses`: "TMDB not set up · OpenSubtitles not set up" with the test runner's switches off and no defaults.
  - `backFromIntegrationsRefocusesItsRow`
  - `savedSourcesTabMapsToSettings`: `settings.setLastTab("SOURCES")` plus one source opens Settings.
- [ ] **Step 2: Run `runclass.sh com.keyspaceapps.goodtv.ui.SettingsScreenTest`.** Expected: FAIL.
- [ ] **Step 3: Implement and update the existing tests.** Change the tests' navigation only, never app behaviour, to satisfy them.
- [ ] **Step 4: Run these, each a separate call:**
  - the unit-test command;
  - `runclass.sh` for `SettingsScreenTest`, `HomeTabsTest`, `SourcesFlowTest`, `CategoriesScreenTest` and `IntegrationsScreenTest`;
  - the full connected suite once (`runclass.sh` with no argument). Known flaky: `OnlineSubtitlesTest` and `CategoriesScreenTest.uncheckingHidesFromLiveAndCountsUpdate`; rerun alone if only those fail.
- [ ] **Step 5: Commit.** `git commit -m "feat(settings): Settings tab with Sources and Integrations"`
