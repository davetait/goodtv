# OpenSubtitles English Subtitles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a movie or episode has no English subtitle track of its own (the file's own subtitles always win), offer "English · OpenSubtitles" in the player's Subtitles panel. The subtitles are downloaded only when chosen and kept for next time, and a timing adjustment fixes subtitles that run early or late.

**Architecture:**
- **Client and data:** a small OkHttp client for the OpenSubtitles REST API, and a pure picker that chooses the best English result. A repository searches, downloads once and caches the `.srt` file in app storage, along with a per-title timing offset.
- **Rendering:** the player renders downloaded subtitles itself, through the existing `SubtitleLayer`. It parses them with its own SRT parser and shifts them by the offset, so changing the timing never re-buffers the stream.
- **Input:** navigation supplies the title's TMDB ID, plus season and episode for series.

**Tech Stack:** Kotlin, OkHttp + kotlinx.serialization (as `TmdbApi`), Media3 ExoPlayer, Compose for TV, JUnit + MockWebServer (`mockwebserver3`), Compose UI tests on the `GoogleTV_API34` emulator.

**Spec:** the decisions below, agreed in conversation on 2026-10-21, are the spec. There is no separate document.

## Decisions (the spec)

1. **Source:** OpenSubtitles.com REST API `https://api.opensubtitles.com/api/v1/`. The key comes from `local.properties` as `GOODTV_OPENSUBTITLES_API_KEY`; blank turns the feature off. Every request sends the `Api-Key` header and `User-Agent: GoodTV v0.1`.
2. **Searching:**
   - Movies: `GET /subtitles?languages=en&tmdb_id=<id>`.
   - Episodes: `GET /subtitles?languages=en&parent_tmdb_id=<series id>&season_number=<s>&episode_number=<e>`.
   - Titles without a TMDB ID get no online subtitles (no title-text search).
3. **The file's own subtitles always win** (added 2026-10-21: their timing matches the file, and they cost no downloads).
   - **When the file has English:** if it has an English subtitle track (language `en`/`eng`, not forced), GoodTV **never searches or downloads** from OpenSubtitles for it, and the online choice never appears.
   - **When to search:** only once the player knows the file's tracks (the first `onTracksChanged` with any groups), and only if there is no such track.
   - **When the choice appears:** only then, labelled **"English · OpenSubtitles"**, listed after the file's own tracks.
   - **Forced tracks:** a file whose only English track is forced (foreign-language lines only) counts as having no English.
4. **Download:** only when the user selects it, via `POST /download {"file_id": <id>, "sub_format": "srt"}`, then GET the returned `link`. The file is saved and reused, so a title costs one download ever.
5. **Login:** if `GOODTV_OPENSUBTITLES_USERNAME` and `GOODTV_OPENSUBTITLES_PASSWORD` are set (they are, as of 2026-10-21), `POST /login` once per process and send `Authorization: Bearer <token>` to the returned `base_url`.
   - **Verified live:** login succeeds, `base_url` is `api.opensubtitles.com`, and `/infos/user` reports `allowed_downloads: 20` (free tier, "Sub leecher").
   - **Without credentials:** use the key alone, which allows about 5 a day.
6. **No moviehash:** the IPTV account allows **1 connection**, so GoodTV never reads the stream to fingerprint it.
7. **Picking a result:**
   - Drop AI- or machine-translated results.
   - For episodes, drop results whose release or file name has an `SxxEyy` code for a different episode (seen live: "The.Bear.SE02E02" listed under S1E2).
   - Prefer, in order: not hearing-impaired, `from_trusted`, highest `download_count`.
8. **Timing:**
   - **The control:** while OpenSubtitles subtitles are on, the Subtitles panel shows a "Timing" row. Left/right move the subtitles earlier or later in 0.5 s steps, within ±30 s.
   - **Saving:** the offset is saved per title and applies only to downloaded subtitles.
9. **Failures are shown, never silent:**
   - **Daily limit used up (HTTP 406):** the panel says "Daily OpenSubtitles limit reached. Try again after 00:00 UTC", using the reset time the API returns.
   - **Other failures:** the panel says "Couldn't get subtitles from OpenSubtitles."
   - **What stays the same:** in both cases the current subtitle choice is unchanged.

## Global Constraints

- Never log or print the API key, username, password or bearer token; `toString()` of credential holders prints `***` (as `TmdbCredential`).
- At most 5 OpenSubtitles requests per second (the `RateLimit-Limit: 5` the API returned). One search per player opening is the normal load.
- No new dependencies beyond what the app already uses.
- Copy: "English · OpenSubtitles", "Timing", "+0.5 s" / "−0.5 s" / "0.0 s" (with a minus sign, not a hyphen), and the two failure messages in decision 9, verbatim.
- Back up the emulator app DB before any `connectedDebugAndroidTest` run: `adb exec-out run-as com.keyspaceapps.goodtv cat databases/goodtv.db` plus the `-wal` and `-shm` files. The run uninstalls the app.

## Review Focus

1. **A file with only a forced English track** (signs and foreign lines only): the online choice must still be offered. Pinned in Task 4: `onlineOfferedWhenOnlyForcedEnglish`.
2. **A file with its own English subtitles:** OpenSubtitles must not be searched or downloaded at all, not just hidden. Pinned in Task 5: `fileWithEnglishNeverSearchesOrDownloads`.
3. **An SRT with BOM, CRLF line endings, `<i>` and `{\an8}` tags, or a missing final blank line** must still parse, with the tags stripped. Pinned in Task 3: `parsesMessyRealWorldSrt`.
4. **Selecting it again after a download** must use the saved file and make no network call. Pinned in Task 2: `secondFetchUsesCacheWithoutNetwork`.
5. **A negative timing that pushes cues before 0**, and seeking while subtitles are on: the cues shown must follow the playhead, never stale ones. Pinned in Task 3: `cuesAtShiftsByOffsetAndClampsAtZero`.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/build.gradle.kts` (modify) | `BuildConfig.OPENSUBTITLES_API_KEY`, `_USERNAME`, `_PASSWORD` from `local.properties` |
| `data/subtitles/OpenSubtitlesApi.kt` (create) | HTTP client: login, search, request download, fetch text; error mapping |
| `data/subtitles/OpenSubtitlesDtos.kt` (create) | Serializable request/response shapes |
| `data/subtitles/SubtitlePicker.kt` (create) | Pure: choose the best English result for a query |
| `data/subtitles/OnlineSubtitles.kt` (create) | Repository: find (search cache), fetch (file cache), timing offset per title |
| `ui/player/Srt.kt` (create) | Pure SRT parser and `cuesAt(position, offset)` |
| `ui/player/PlayerTracks.kt` (modify) | `TrackChoice.online`, English-track detection |
| `ui/player/PlayerControls.kt` (modify) | Online choice, Timing row and notice in the Subtitles panel |
| `ui/player/PlayerScreen.kt` (modify) | Search on open, fetch on select, render external cues with the offset |
| `ui/nav/GoodTvNavigation.kt` (modify) | Build the `SubtitleQuery` from the catalog and pass the source |
| `AppGraph.kt` (modify) | `onlineSubtitles` (null when the key is blank or `GoodTvApp.disableOpenSubtitles`) |

All paths under `app/src/main/java/com/keyspaceapps/goodtv/`; tests mirror them under `app/src/test/...` and `app/src/androidTest/...`.

---

### Task 1: OpenSubtitles API client

**Files:**
- Create: `data/subtitles/OpenSubtitlesApi.kt`, `data/subtitles/OpenSubtitlesDtos.kt`
- Modify: `app/build.gradle.kts` (three `buildConfigField`s next to `TMDB_API_KEY`, all build types)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/subtitles/OpenSubtitlesApiTest.kt`

**Interfaces:**
- Produces:
  - `class OpenSubtitlesApi(client: OkHttpClient, apiKey: String, credentials: OpenSubtitlesLogin? = null, baseUrl: HttpUrl = "https://api.opensubtitles.com/api/v1/".toHttpUrl(), io: CoroutineDispatcher = Dispatchers.IO)`
  - `suspend fun search(query: SubtitleQuery): List<OsSubtitle>` (first page only)
  - `suspend fun downloadLink(fileId: Long): String`
  - `suspend fun fetchText(link: String): String`
  - `data class OpenSubtitlesLogin(val username: String, val password: String)`, whose `toString()` is `"OpenSubtitlesLogin(***)"`
  - `data class SubtitleQuery(val tmdbId: Long, val season: Int? = null, val episode: Int? = null)`, which is an episode when both are non-null. Its `key: String` is `"m<tmdbId>"` or `"e<tmdbId>s<season>e<episode>"`.
  - `data class OsSubtitle(val fileId: Long, val release: String, val fileName: String?, val language: String, val hearingImpaired: Boolean, val fromTrusted: Boolean, val aiTranslated: Boolean, val machineTranslated: Boolean, val downloadCount: Int)`
  - `class OpenSubtitlesException(message: String, val reason: Reason, val resetUtc: String? = null)` with `enum Reason { AUTH, QUOTA, RATE_LIMITED, NETWORK, SERVER, BAD_RESPONSE }`

- [ ] **Step 1: Write the failing tests**

Use the `TmdbApiTest` setup: `MockWebServer`, `mockwebserver3`, with JSON fixtures inline.

```kotlin
@Test fun movieSearchSendsKeyAgentAndTmdbId()            // GET /api/v1/subtitles; Api-Key header; User-Agent "GoodTV v0.1"; languages=en; tmdb_id=603; no parent_tmdb_id
@Test fun episodeSearchUsesParentIdSeasonAndEpisode()     // parent_tmdb_id=136315, season_number=1, episode_number=2, no tmdb_id
@Test fun searchMapsAttributes()                          // fixture -> OsSubtitle(fileId=9213502, release="The Bear.S01E02", fileName="The Bear.S01E02", language="en", hearingImpaired=false, fromTrusted=false, aiTranslated=false, machineTranslated=false, downloadCount=4724)
@Test fun downloadPostsFileIdAsSrtAndReturnsLink()        // POST /api/v1/download body {"file_id":9213502,"sub_format":"srt"}; returns "https://dl.example/x.srt"
@Test fun quotaExhaustedIsQuotaWithResetTime()            // 406 {"message":"You have downloaded your allowed 5 subtitles for 24h...","reset_time_utc":"2026-10-22T00:00:00.000Z"} -> reason QUOTA, resetUtc "2026-10-22T00:00:00.000Z"
@Test fun badKeyIsAuth()                                  // 401 -> AUTH; 429 -> RATE_LIMITED; 503 -> SERVER
@Test fun loginOnceThenBearerToReturnedBaseUrl()          // credentials set: first call POST /login {"username","password"} -> {"token":"t","base_url":"<server host>"}; later calls carry "Authorization: Bearer t"; a second search does not log in again
@Test fun credentialsNeverPrinted()                       // OpenSubtitlesLogin("u","p").toString() == "OpenSubtitlesLogin(***)"
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*OpenSubtitlesApiTest*'`
Expected: compilation fails (`OpenSubtitlesApi` doesn't exist).

- [ ] **Step 3: Implement the client**

- Mirror `TmdbApi`:
  - a private `get`/`post` helper with OkHttp on `io`;
  - JSON with `ignoreUnknownKeys = true`;
  - `IOException` becomes `NETWORK`, and a serialization failure becomes `BAD_RESPONSE`.
- Status mapping: 401 → `AUTH`, 406 → `QUOTA` (parse `reset_time_utc` from the body), 429 → `RATE_LIMITED`, ≥500 → `SERVER`, other non-2xx → `BAD_RESPONSE`.
- Login: guard it with a `Mutex`, so it happens once per instance. When the response includes `base_url`, later requests go to `https://<base_url>/api/v1/`.
- `fetchText`: a plain GET of the link, with no auth headers, returning the body as UTF-8.
- DTOs: `data[].attributes` holds `language`, `hearing_impaired`, `from_trusted`, `ai_translated`, `machine_translated`, `download_count`, `release`, and `files[0].file_id` / `file_name`.
- In `build.gradle.kts`, add `buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${localProp("GOODTV_OPENSUBTITLES_API_KEY")}\"")`, plus `_USERNAME` and `_PASSWORD` the same way.

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*OpenSubtitlesApiTest*'`
Expected: 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/keyspaceapps/goodtv/data/subtitles app/src/test/java/com/keyspaceapps/goodtv/data/subtitles/OpenSubtitlesApiTest.kt
git commit -m "feat(subtitles): OpenSubtitles API client"
```

---

### Task 2: Picker and repository (search cache, file cache, timing offset)

**Files:**
- Create: `data/subtitles/SubtitlePicker.kt`, `data/subtitles/OnlineSubtitles.kt`
- Modify: `AppGraph.kt` (add `val onlineSubtitles: OnlineSubtitles?`), `GoodTvApp.kt` (add `@Volatile var disableOpenSubtitles = false`), `androidTest/.../GoodTvTestRunner.kt` (set it to `true`)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/subtitles/SubtitlePickerTest.kt`, `OnlineSubtitlesTest.kt`

**Interfaces:**
- Consumes: Task 1 `OpenSubtitlesApi`, `SubtitleQuery`, `OsSubtitle`, `OpenSubtitlesException`
- Produces:
  - `object SubtitlePicker { fun best(results: List<OsSubtitle>, query: SubtitleQuery): OsSubtitle? }`
  - `interface OnlineSubtitleSource { suspend fun find(): OsSubtitle?; suspend fun fetch(choice: OsSubtitle): Result<String>; suspend fun offsetMs(): Long; suspend fun setOffsetMs(ms: Long) }`, bound to one query. This is what the player sees.
  - `class OnlineSubtitles(api: OpenSubtitlesApi, dir: File)` with `fun forQuery(query: SubtitleQuery): OnlineSubtitleSource`
  - Files: `dir/<query.key>.srt` holds the text, and `dir/<query.key>.offset` holds the offset in ms as text. `dir` is `context.filesDir/subtitles`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// SubtitlePickerTest
@Test fun dropsAiAndMachineTranslations()
@Test fun dropsEpisodeResultsNamedForAnotherEpisode()   // query S1E2: "The.Bear.SE02E02.WEBRip-DNSP" dropped; "BEAR 02 - ENG" (no code) kept; "The.Bear.S01E02.1080p" kept
@Test fun prefersNotHearingImpairedThenTrustedThenMostDownloaded()
@Test fun nothingUsableIsNull()
// OnlineSubtitlesTest (MockWebServer + temp dir)
@Test fun findSearchesOnceAndPicks()                     // two find() calls -> one request
@Test fun fetchDownloadsSavesAndReturnsText()            // POST /download then GET link; file dir/m603.srt contains the text
@Test fun secondFetchUsesCacheWithoutNetwork()           // request count unchanged on second fetch
@Test fun quotaFailureIsReturnedNotThrown()              // 406 -> Result.failure(OpenSubtitlesException(reason=QUOTA, resetUtc=...)); no file written
@Test fun offsetIsSavedPerTitle()                        // setOffsetMs(-1500) on m603; forQuery(m603).offsetMs() == -1500; forQuery(m604).offsetMs() == 0
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SubtitlePickerTest*' --tests '*OnlineSubtitlesTest*'`
Expected: compilation fails.

- [ ] **Step 3: Implement the picker**

- An episode code is `[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})`, also matching the uploader typo `SE02E02` as season 2.
- Read it from `release`, and if there's none there, from `fileName`. Drop the result when a code is present and doesn't equal (season, episode).
- Then sort descending by `(!hearingImpaired, fromTrusted, downloadCount)`.

- [ ] **Step 4: Implement `OnlineSubtitles`**

- Keep an in-memory search cache per `query.key` (`Mutex` + map).
- `fetch` reads the file if it exists. Otherwise it calls `downloadLink`, then `fetchText`, and writes atomically: write to `.tmp`, then rename.
- `CancellationException` is rethrown, and everything else is wrapped in `Result`.
- In `AppGraph`: `val onlineSubtitles by lazy { BuildConfig.OPENSUBTITLES_API_KEY.takeIf { it.isNotBlank() && !GoodTvApp.disableOpenSubtitles }?.let { OnlineSubtitles(OpenSubtitlesApi(okHttp, it, login), File(context.filesDir, "subtitles")) } }`, where `login` is non-null only when both username and password are set.

- [ ] **Step 5: Run the tests to confirm they pass**

Run: the same command as Step 2.
Expected: 9 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(subtitles): pick the best English OpenSubtitles result; cache downloads and timing per title"
```

---

### Task 3: SRT parser and cue timing

**Files:**
- Create: `ui/player/Srt.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/ui/player/SrtTest.kt`

**Interfaces:**
- Produces:
  - `data class TimedCue(val startMs: Long, val endMs: Long, val text: String)`
  - `object Srt { fun parse(text: String): List<TimedCue> }`, sorted by start, with tags stripped
  - `fun List<TimedCue>.at(positionMs: Long, offsetMs: Long): List<TimedCue>`. These are the cues active when the subtitles are shifted by `offsetMs`: positive shows them later, so a cue is active when `start + offset <= position < end + offset`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun parsesCuesAndMultiLineText()                   // "1\n00:00:01,000 --> 00:00:02,500\nHello\nthere\n\n2\n..." -> TimedCue(1000, 2500, "Hello\nthere")
@Test fun parsesMessyRealWorldSrt()                      // leading ﻿, \r\n endings, "<i>Hi</i>", "{\\an8}Top", no trailing blank line, a cue with "00:00:05.000" (dot) -> parsed; texts "Hi", "Top"
@Test fun skipsBrokenBlocks()                            // block with bad timestamp is skipped, others kept
@Test fun cuesAtShiftsByOffsetAndClampsAtZero()          // cue 1000..2000: at(1500, 0) has it; at(2500, +1000) has it; at(600, -500) has it; at(0, -5000) is empty and doesn't crash
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SrtTest*'`
Expected: compilation fails.

- [ ] **Step 3: Implement `Srt.parse` and `at`**

- Parse with plain Kotlin, not Media3's `SubripParser`, which needs Android's `Html` and so can't be unit-tested on the JVM.
- Split on blank lines, find the `-->` line, and accept `,` or `.` before milliseconds.
- Strip `<[^>]+>` and `\{\\[^}]*\}`.
- For `at`, a linear filter over the list is fine at 2,000 cues.

- [ ] **Step 4: Run the tests to confirm they pass**

Run: the same command as Step 2.
Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(player): SRT parser with timing offset"
```

---

### Task 4: Subtitles panel shows the online choice, Timing row and notices

**Files:**
- Modify: `ui/player/PlayerTracks.kt`, `ui/player/PlayerControls.kt`, `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/ui/player/TrackLabelsTest.kt` (add), `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/PlayerControlsTest.kt` (create; `PlayerControls` with fixed `PlayerUi`, no player)

**Interfaces:**
- Consumes: the existing `TrackChoice`, `PlayerUi`, `PlayerControls`
- Produces:
  - `TrackChoice` gains `val online: Boolean = false`. "Off" is `ref == null && !online`.
  - `fun hasEnglish(infos: List<TrackInfo>): Boolean` (pure) in `PlayerTracks.kt`. It's true when a non-forced track's language is `en`/`eng` (via `TrackLabels.twoLetter`).
  - `fun hasEnglishTrack(tracks: Tracks): Boolean`, which maps the supported text tracks to `TrackInfo` and calls `hasEnglish`.
  - `PlayerUi` gains three fields:
    - `onlineSubtitleOffsetMs: Long?`, non-null only while the online choice is on, which shows the Timing row;
    - `subtitleNotice: String?`;
    - `subtitleBusy: Boolean`, which shows "Downloading…" on the online row.
  - `PlayerControls` gains `onSubtitleTiming: (deltaMs: Long) -> Unit`.
  - Strings (exact):
    - `player_subtitles_online` "English · OpenSubtitles"
    - `player_subtitles_timing` "Timing"
    - `player_subtitles_downloading` "Downloading…"
    - `player_subtitles_quota` "Daily OpenSubtitles limit reached. Try again after %1$s UTC"
    - `player_subtitles_online_failed` "Couldn't get subtitles from OpenSubtitles."

- [ ] **Step 1: Write the failing tests**

```kotlin
// TrackLabelsTest (JVM), testing hasEnglish
@Test fun englishTrackDetected()                          // TrackInfo("eng", null) -> true; TrackInfo("fre", null) -> false
@Test fun onlineOfferedWhenOnlyForcedEnglish()            // TrackInfo("en", null, forced = true) -> hasEnglish false
// PlayerControlsTest (instrumented, no media)
@Test fun onlineChoiceListedAfterTheFilesTracks()         // subtitles = [Off, French, online] -> panel rows "Off", "French", "English · OpenSubtitles" in that order
@Test fun timingRowShowsOffsetAndLeftRightAdjust()        // onlineSubtitleOffsetMs = 500 -> row "Timing" with "+0.5 s"; focus it, DirectionLeft -> onSubtitleTiming(-500), DirectionRight -> onSubtitleTiming(+500)
@Test fun noticeIsShownUnderTheTitle()                    // subtitleNotice = "Daily OpenSubtitles limit reached. Try again after 00:00 UTC" -> text displayed
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TrackLabelsTest*'`, then (after backing up the DB) `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.ui.PlayerControlsTest`
Expected: compilation fails.

- [ ] **Step 3: Implement**

- **Timing row:** a focusable row under the choices. Show it only when `onlineSubtitleOffsetMs != null`.
  - Format: `"%+.1f s"` with a `−` sign, and `"0.0 s"` at zero.
  - Left/right call `onSubtitleTiming(∓500)` and consume the key.
  - The value clamps to ±30,000 ms in the screen, not here.
- **Notice:** shown the way `emptyText` is today; when both are present, show it in its place.
- **Selected state:** the online row is selected when the online choice is on.

- [ ] **Step 4: Run the tests to confirm they pass**

Run: the same commands as Step 2.
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(player): OpenSubtitles choice, timing row and notices in the Subtitles panel"
```

---

### Task 5: PlayerScreen searches, fetches and renders online subtitles

**Files:**
- Modify: `ui/player/PlayerScreen.kt`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/PlayerScreenTest.kt` (add); new asset `app/src/androidTest/assets/player_nosubs.mkv`

**Interfaces:**
- Consumes:
  - Task 2's `OnlineSubtitleSource`
  - Task 3's `Srt`, `TimedCue`, `at`
  - Task 4's `hasEnglishTrack`, the new `PlayerUi` fields and `onSubtitleTiming`
- Produces: `PlayerScreen(..., onlineSubtitles: OnlineSubtitleSource? = null)`

- [ ] **Step 1: Make the test asset**

Make a 20 s, 320×180 test pattern with one stereo AAC track and **no subtitle tracks**:

```bash
ffmpeg -f lavfi -i testsrc=size=320x180:rate=24:duration=20 -f lavfi -i sine=frequency=440:duration=20 -map 0:v -map 1:a -c:v libx264 -preset veryfast -crf 40 -pix_fmt yuv420p -c:a aac -b:a 32k -ac 2 app/src/androidTest/assets/player_nosubs.mkv
```

Expected: about 150 KB, and `ffprobe` lists one video and one audio stream.

- [ ] **Step 2: Write the failing tests**

They use a fake `OnlineSubtitleSource`:
- `find()` returns an `OsSubtitle` (or null);
- `fetch()` returns SRT text with a cue every 2 s reading "Online line N" (or a failure);
- offset state is kept in memory;
- a call counter is exposed.

```kotlin
@Test fun onlineChoiceOfferedWhenTheFileHasNoEnglish()         // player_nosubs.mkv: open Subtitles -> "English · OpenSubtitles" listed; fetch not yet called
@Test fun choosingItDownloadsAndDrawsCaptions()                // select it, play -> "Online line" text appears; fetch called once
@Test fun timingMovesCaptionsLater()                           // after selecting: Timing row Right x4 -> offset 2000 saved via setOffsetMs; row shows "+2.0 s"
@Test fun quotaFailureShowsNoticeAndKeepsOff()                 // fetch -> failure(QUOTA, "2026-10-22T00:00:00.000Z") -> notice "Daily OpenSubtitles limit reached. Try again after 00:00 UTC"; "Off" still selected
@Test fun fileWithEnglishNeverSearchesOrDownloads()            // player_tracks.mkv (has English): wait until the Audio button shows (tracks known) + 3 s; fake find() and fetch() call counts are both 0; online row absent
@Test fun searchWaitsForTheFilesTracks()                       // player_nosubs.mkv: find() is called exactly once, and only after tracks are known (the fake records whether hasEnglishTrack had been evaluated); online row then listed
```

- [ ] **Step 3: Run them to confirm they fail**

Back up the DB first, then run `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.ui.PlayerScreenTest`.
Expected: the new tests fail (no online row).

- [ ] **Step 4: Implement**

- **Search:**
  - `val tracksKnown = tracks.groups.isNotEmpty()` and `val needsOnline = tracksKnown && !hasEnglishTrack(tracks)`.
  - `LaunchedEffect(onlineSubtitles, needsOnline) { if (needsOnline) found = onlineSubtitles?.find() }`. The file's own English subtitles win, so nothing is searched when they exist. Failures are ignored, because search is best-effort.
  - `val offerOnline = needsOnline && found != null`. Append `TrackChoice(online label, selected = onlineOn, ref = null, online = true)` to `ui.subtitles` when `offerOnline`.
- **On select:**
  - Set `subtitleBusy` and call `fetch`.
  - On success: parse with `Srt.parse`, set `onlineOn = true`, load `offsetMs()`, and turn the file's text tracks off with `player.selectTrack(TEXT, null)`.
  - On failure: set `subtitleNotice` (QUOTA with the reset time shown as `HH:mm`; anything else uses the generic message) and leave the selection alone.
- **Rendering:** while `onlineOn`, run a 100 ms ticker `LaunchedEffect`. It sets `onlineCues = parsed.at(player.currentPosition, offset).map { Cue.Builder().setText(it.text).build() }`, and `SubtitleLayer` shows `onlineCues` instead of `cues`.
- **Leaving the online choice:** choosing "Off" or one of the file's tracks sets `onlineOn = false`.
- **Timing:** `onSubtitleTiming(delta)` clamps the offset to ±30,000 and calls `setOffsetMs`.

- [ ] **Step 5: Run the tests to confirm they pass**

Run: the same command as Step 3.
Expected: all `PlayerScreenTest` tests pass (14).

- [ ] **Step 6: Commit**

```bash
git add -A app/src/androidTest/assets app/src && git commit -m "feat(player): offer, download and draw OpenSubtitles English subtitles with adjustable timing"
```

---

### Task 6: Navigation supplies the query, plus a check on the device

**Files:**
- Modify: `ui/nav/GoodTvNavigation.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/ui/nav/SubtitleQueriesTest.kt`

**Interfaces:**
- Consumes:
  - `LibraryRepository.catalogMovie(sourceId, id): Movie?` and `catalogSeries(sourceId, id): Series?`
  - `PlayerKey.refKind/refId/seriesId/season/episode`
  - Task 2's `OnlineSubtitles.forQuery`
- Produces: `object SubtitleQueries { suspend fun forKey(key: PlayerKey, library: LibraryRepository): SubtitleQuery? }`. It returns:
  - for a movie, its `tmdbId`;
  - for an episode, the series' `tmdbId` plus `key.season`/`key.episode`;
  - null for live channels, missing IDs, or episodes without a season or number.

- [ ] **Step 1: Write the failing tests**

They use the existing `FakeLibraryStore`.

```kotlin
@Test fun movieUsesItsTmdbId()                // Movie tmdbId 603 -> SubtitleQuery(603)
@Test fun episodeUsesTheSeriesIdSeasonAndNumber()  // Series tmdbId 136315, key season 1 episode 2 -> SubtitleQuery(136315, 1, 2)
@Test fun liveOrMissingIdsGiveNothing()       // live key -> null; movie without tmdbId -> null; episode key with season null -> null
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SubtitleQueriesTest*'`
Expected: compilation fails.

- [ ] **Step 3: Implement and wire**

In the `PlayerKey` entry:
- `var online by remember(key) { mutableStateOf<OnlineSubtitleSource?>(null) }`;
- a `LaunchedEffect(key)` that sets it from `graph.onlineSubtitles?.let { s -> SubtitleQueries.forKey(key, graph.repository)?.let(s::forQuery) }`;
- pass `onlineSubtitles = online` to `PlayerScreen`.

- [ ] **Step 4: Run the full suites**

1. Back up the emulator DB.
2. Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest`.
   Expected: all pass, and lint reports no new issues.
3. Restore the DB by reinstalling and pushing the backup files back.

- [ ] **Step 5: Check on the device (uses 1 real download)**

1. Install the app and open a title whose file has no English track but whose TMDB ID OpenSubtitles knows. "The Bear (EN)" S1E2 had no file subtitles in the sample and 6 online results.
2. Open Subtitles: "English · OpenSubtitles" is listed. Select it, and captions appear.
3. Use Timing +0.5 s / −0.5 s to see them move.
4. Leave the player and play the title again, then select it again: it reappears without a second download. Check that the API's `remaining` count hasn't changed, or that there's no network call in logcat.
5. Save screenshots to `.context/`.

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(subtitles): look up OpenSubtitles by the title's TMDB id when playing"
```

---

## Out of scope (decided)

- Title-text search for titles without a TMDB ID; languages other than English; moviehash matching; timing for the file's own subtitle tracks; SubDL as a fallback; a settings screen for OpenSubtitles login (`local.properties` only for now).
