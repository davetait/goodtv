# Live Previews Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Settings → Live TV → "Live previews" switch. When it's on, the guide's details panel doubles in height and plays the highlighted channel live with sound, after a ~1 s rest. Selecting the channel continues the same stream full screen, growing out of the panel, and Back hands the stream back to the panel.

**Architecture:**
- **One owner for preview players:** a `LivePreviews` session in `AppGraph` (above navigation) owns at most one preview `ExoPlayer`. The same player can pass from the guide to the full-screen `PlayerScreen` and back, so a preview never restarts and two streams never run at once.
- **Testable timing:** a pure scheduler decides when to start, keep or stop a preview from focus events, behind a `PreviewEngine` interface so timing is unit-tested without ExoPlayer.
- **Guide side:** the panel draws the session's player with `PlayerSurface`.
- **Player side:** `PlayerScreen` can adopt a handed-over player instead of creating one.

**Tech Stack:** Kotlin, Compose for TV (tv-material3), Media3 1.11 (ExoPlayer, `media3-ui-compose` `PlayerSurface`), Navigation3, DataStore Preferences, kotlinx-coroutines-test, Compose UI tests on the `GoogleTV_API34` emulator.

**Spec:** `docs/superpowers/specs/2026-10-30-live-previews-design.md`

## Global Constraints

- **Setting:**
  - "Live previews" lives in a new Settings section, **Live TV** (third row, after Sources and Integrations).
  - Off by default, stored in `AppSettings` (DataStore `settings`), and applies at once, including to an open guide.
- **Panel:**
  - With previews on, the guide's details panel is **twice its current height**: 114 dp → **228 dp**, the whole time previews are on.
  - The picture is a **16:9** area at the panel's inner height, in place of today's 160×90 thumbnail. Text sits beside it as today.
- **Timing:**
  - A preview starts after focus **rests on a channel's row for 1 000 ms**. Every focus move to another row restarts the wait, so held arrows start nothing.
  - A preview belongs to the **row**: moving between cells of the same row keeps it. Moving to another row, or focus leaving the grid (`GuideFocus.outside`, search, chips), stops it at once.
- **Sound** is on; there's no mute.
- **Copy to play:** the first copy in the copy picker's order, `ChannelOrder.sort(row.feeds).first()`, resolved with `LibraryRepository.playable(sourceId, PlayableRef.Live(id), label)`, exactly as `PlayerKey` playback resolves it.
- **No preview for:** rows with no feeds, and event slots that aren't playable now (`GuideTime.eventPlayable` false). Those show the logo as today.
- **Loading** shows the logo with a small spinner. **Failure** shows the logo with "Preview unavailable", and the guide is unaffected.
- **While a preview plays,** `PlaybackState.enter()` is held, so guide downloads pause; `exit()` runs when it stops.
- **Stopping:** the preview stops when leaving Live TV (another tab, a pushed screen other than its own hand-off, Home or background), when focus leaves the grid, or when the setting is turned off.
- **One stream at a time:** the preview player and a full-screen player never both hold a connection.
- **Going full screen:**
  - Selecting a channel whose preview is **playing** (or the picker's chosen copy equals the previewing copy) passes the same player to `PlayerScreen`: no re-prepare, no new connection. The picture animates from the panel to full screen.
  - Otherwise the player opens exactly as today.
- **Back** from a handed-over channel returns to the same row, with the stream still playing in the panel and no restart. It shrinks back into the panel if the mechanism allows; otherwise it cuts.
- **Copy (exact strings):**
  - `settings_live_tv` "Live TV"
  - `settings_live_previews_on` "Live previews on"
  - `settings_live_previews_off` "Live previews off"
  - `live_tv_title` "Live TV"
  - `live_previews` "Live previews"
  - `live_previews_summary` "Play the highlighted channel in the guide, with sound, after a moment's pause on it."
  - `preview_unavailable` "Preview unavailable"
- **Tests:**
  - Unit: `./gradlew :app:testDebugUnitTest :app:lintDebug`. Expected: all pass, lint 0 errors.
  - Instrumented tests run only through `~/.goodtv-tools/runclass.sh <fully.qualified.Class>`, one class per call. With no argument it runs the full suite.
  - Request focus from `onPlaced`. tv-material ignores touch, so drive tests with keys. Merged testTags need `useUnmergedTree = true`. Add a `Timeout` rule to UI tests.
  - Previews in tests play local test media (`assets/player_tracks.mkv`), never a provider.

## Review Focus

1. **Holding DOWN through 20 channels.**
   - No preview starts, no stream opens, and the last row starts its own after 1 s.
   - Test: `heldArrowsStartNothingUntilRest` (Task 2).
2. **Selecting while the preview is still loading** (prepared, not yet playing).
   - Plays full screen without a second connection: the loading player is handed over, not left running beside a new one.
   - Test: `selectWhileLoadingHandsOverTheSamePlayer` (Task 2).
3. **The setting turned off while a preview plays,** or Home pressed.
   - Sound stops at once, and `PlaybackState` returns to not playing.
   - Tests: `turningOffStopsAndReleases`, `leavingStopsAndReleases` (Task 2).
4. **Back from full screen after scrolling the guide's window or changing chip.**
   - The handed-back player is kept only if the guide still focuses that channel's row within 3 s; otherwise it's released, never left playing out of sight.
   - Test: `unclaimedHandBackIsReleased` (Task 2).
5. **A failing stream.**
   - The panel shows "Preview unavailable", the next row still previews normally, and `PlaybackState` isn't left held.
   - Test: `failedPreviewReleasesTheGate` (Task 2), and the UI case in Task 3.

---

### Task 1: The Live TV settings section

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/settings/AppSettings.kt`, `ui/settings/SettingsScreen.kt`, `ui/nav/NavKeys.kt`, `ui/nav/GoodTvNavigation.kt`, `res/values/strings.xml`
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/settings/LiveTvSettingsScreen.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/settings/AppSettingsTest.kt` (create or extend); create `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/LiveTvSettingsTest.kt`

**Interfaces:**
- Produces:
  - `AppSettings.livePreviews: Flow<Boolean>` (false when unset) and `suspend fun AppSettings.setLivePreviews(on: Boolean)`.
  - `@Serializable data object LiveTvSettingsKey : GoodTvKey`, with its nav entry.
  - `@Composable fun LiveTvSettingsScreen()`: the title, then one tv-material `Switch` row with label `live_previews` and summary `live_previews_summary`. The row takes initial focus via `onPlaced`, and Select toggles it.
- **Settings row:** a third row, "Live TV", with status `settings_live_previews_on` or `settings_live_previews_off`. It opens `LiveTvSettingsKey`, and Back refocuses it, as the other rows do.

- [ ] **Step 1: Write the failing tests.**
  - Unit `livePreviewsDefaultsOffAndPersists`: `InMemoryDataStore`; false at first, true after `setLivePreviews(true)`, and true again on a new `AppSettings` over the same store.
  - Instrumented `LiveTvSettingsTest`:
    - `liveTvRowShowsStatusAndOpensTheSwitch`: the Settings tab shows "Live TV · Live previews off". Select opens the screen with the switch focused; Select turns it on; Back shows "Live previews on" with the Live TV row focused.
    - The test restores the setting to false in `@After`.
- [ ] **Step 2: Run the unit test (FAIL), then `~/.goodtv-tools/runclass.sh com.keyspaceapps.goodtv.ui.LiveTvSettingsTest`** (FAIL).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the unit command, then runclass for `LiveTvSettingsTest` and `SettingsScreenTest`.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(settings): Live TV section with a Live previews switch"`

### Task 2: The preview session and its timing

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/live/LivePreviews.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/AppGraph.kt`. Add `val livePreviews: LivePreviews`, built with `appScope`, `playback`, a resolver using `repository.playable` and an engine using `PlayerFactory.create(context, …)`. Add `@VisibleForTesting var previewResolverOverride`.
- Test: `app/src/test/java/com/keyspaceapps/goodtv/ui/live/LivePreviewsTest.kt` (`StandardTestDispatcher` with virtual time, and a fake engine)

**Interfaces:**
- Produces, in `LivePreviews.kt`:
  ```kotlin
  /** What a preview plays: one copy of one channel. */
  data class PreviewTarget(val rowKey: String, val sourceId: Long, val channelId: String, val label: String)
  /** The player behind a preview; ExoPlayer in the app, a fake in tests. */
  interface PreviewEngine {
      fun open(url: String): PreviewHandle
  }
  interface PreviewHandle {
      val state: StateFlow<PreviewStatus>     // LOADING, PLAYING, FAILED
      val player: Player?                     // for PlayerSurface / hand-off; null in fakes
      fun release()
  }
  enum class PreviewStatus { LOADING, PLAYING, FAILED }
  class LivePreviews(
      scope: CoroutineScope,
      playback: PlaybackState,
      resolveUrl: suspend (PreviewTarget) -> String,
      engine: PreviewEngine,
      restMs: Long = 1_000,
      handBackGraceMs: Long = 3_000,
  ) {
      val current: StateFlow<Pair<PreviewTarget, PreviewHandle>?>   // what the panel draws
      fun setEnabled(on: Boolean)
      fun focus(target: PreviewTarget?)        // null = focus left the grid / no playable copy
      fun leave()                              // guide gone (tab, Home, background, other screen)
      /** Selecting [target]: the live handle if it's [target]'s (loading or playing), detached from the session; else null. */
      fun takeFor(target: PreviewTarget): PreviewHandle?
      /** Full screen closed: keep [handle] for [target] if the guide focuses that row within the grace period, else release. */
      fun giveBack(target: PreviewTarget, handle: PreviewHandle)
  }
  ```
- **Rules:**
  - `focus` with a different `rowKey` cancels the pending start and releases the current handle at once, then starts the rest timer. The same `rowKey` with any target keeps the current preview.
  - Starting resolves the URL, opens a handle and calls `playback.enter()`. Every release of a session-held handle calls `playback.exit()` exactly once.
  - FAILED keeps the handle state for the panel, releases the player and exits the gate.
  - `setEnabled(false)` and `leave()` cancel and release.
  - A taken handle no longer belongs to the session (the gate passes to `PlayerScreen`, which already holds its own `enter()`). A handed-back handle re-enters the gate.
- **The app's engine** builds `PlayerFactory.create(context)`, sets the item, `playWhenReady = true` and prepares. It maps `onPlaybackStateChanged` / `onIsPlayingChanged` / `onPlayerError` to `PreviewStatus`.

- [ ] **Step 1: Write the failing tests** (fake engine counting opens and releases):
  - `restingOneSecondStartsAPreview`
  - `heldArrowsStartNothingUntilRest`: 20 rows at 150 ms apart give 0 opens; then 1 open, for the last row, 1 000 ms after it.
  - `movingAlongTheRowKeepsThePreview`
  - `movingToAnotherRowStopsAtOnce`: the release happens before the next row's timer completes.
  - `turningOffStopsAndReleases`
  - `leavingStopsAndReleases`
  - `gateIsHeldOnlyWhilePlaying`: `PlaybackState.playing` is true while previewing and false after release.
  - `failedPreviewReleasesTheGate`
  - `selectWhileLoadingHandsOverTheSamePlayer`: `takeFor` returns the LOADING handle, and the session no longer holds it or the gate.
  - `takeForAnotherCopyReturnsNull`
  - `handBackIsKeptWhenTheGuideReturnsToTheRow`: no new open.
  - `unclaimedHandBackIsReleased`: after 3 000 ms with no matching focus, the handle is released.
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*LivePreviewsTest'`.** Expected: FAIL (does not compile).
- [ ] **Step 3: Implement `LivePreviews`, the ExoPlayer engine and the AppGraph wiring.**
- [ ] **Step 4: Run the same command, then the unit command.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(live): preview session that plays the resting row and can hand its player over"`

### Task 3: The bigger panel that plays

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/ui/live/GuideDetails.kt`, `ui/live/GuideScreen.kt`, `res/values/strings.xml`
- Test: extend `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/GuideScreenTest.kt`, or create `LivePreviewUiTest.kt` beside it

**Interfaces:**
- Consumes: Task 1 `AppSettings.livePreviews`; Task 2 `LivePreviews` (`setEnabled`, `focus`, `leave`, `current`, `PreviewTarget`, `PreviewStatus`).
- **`GuideDetails`** gains `preview: Pair<PreviewTarget, PreviewHandle>?` and `previewsOn: Boolean`.
  - With previews on, the panel is 228 dp tall, and the picture area is 16:9 at its inner height, with testTag `guide_preview`.
  - When `preview` matches the focused row and is PLAYING, it draws `PlayerSurface(handle.player)` fitted (`resizeWithContentScale(ContentScale.Fit, …)`), with a small "LIVE" badge.
  - LOADING draws the logo plus a spinner; FAILED draws the logo plus `preview_unavailable`.
  - With previews off, it's today's 114 dp layout, unchanged.
- **`GuideScreen`:**
  - Collects `livePreviews` from settings and calls `setEnabled`.
  - On each `onFocused`, calls `focus(PreviewTarget(row.key, first.sourceId, first.id, first.label))` for a row with a playable copy now. "Playable now" means a channel cell, or a cell on now, or an event slot `GuideTime.eventPlayable`. Calls `focus(null)` when `focus.outside` or the row has no copy.
  - Calls `leave()` when the screen leaves composition, and on lifecycle `ON_STOP`.

- [ ] **Step 1: Write the failing tests** (instrumented; seed a guide like `GuideScreenTest`; set the setting on; `graph.previewResolverOverride` returns the local `player_tracks.mkv` URL):
  - `panelDoublesWithPreviewsOn`: the details panel is 228 dp tall, and 114 dp with previews off.
  - `restingOnARowPlaysItsPreview`: `guide_preview` shows a PLAYING session for that row within 5 s.
  - `movingAwayStopsThePreview`: after DOWN, `livePreviews.current` is for the new row or null, never the old one.
  - `failingStreamShowsUnavailable`: the resolver returns a missing file URL, and "Preview unavailable" shows.
  - `offShowsTodaysPanel`: no `guide_preview` and no session.
- [ ] **Step 2: Run runclass for the new or extended class.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run runclass for it and for `GuideScreenTest`.** Expected: PASS. Existing guide tests run with previews off and are unchanged.
- [ ] **Step 5: Commit.** `git commit -m "feat(live): the guide's panel doubles and plays the resting channel"`

### Task 4: Hand-off to full screen, the grow animation, and Back

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/ui/nav/GoodTvNavigation.kt`, `ui/player/PlayerScreen.kt`, `ui/live/GuideScreen.kt`
- Test: extend `LivePreviewUiTest` (or `GuideScreenTest`); extend `app/src/test/.../LivePreviewsTest.kt` if the session needs more API

**Interfaces:**
- Consumes: Task 2 `takeFor`, `giveBack`; Task 3's panel bounds.
- **`PlayerScreen`** gains `adopted: ExoPlayer? = null` and `onAdoptedClosed: ((ExoPlayer) -> Unit)? = null`.
  - With `adopted`, it uses that player instead of creating one. It skips `setMediaItem` / `prepare`, and attaches its listeners and traffic meter from that point.
  - On dispose it calls `onAdoptedClosed(player)` instead of `release()`.
  - All other behaviour is unchanged: keep-screen-on, Info box, picture fit, subtitles.
- **The guide's `play()`** for a live channel: when `livePreviews.takeFor(target)` returns a handle, it pushes the `PlayerKey` and parks the handle and the panel's picture bounds (in window coordinates) in `livePreviews` for the nav entry. The `PlayerKey` entry in `GoodTvNavigation` takes the parked handle when its channel matches.
- **Grow animation:**
  - The `PlayerKey` entry, when adopting, animates its video box from the parked panel bounds to full screen, with the background fading in from transparent. The nav transition for that push shows the guide underneath until it finishes; a custom `transitionSpec` with no default fade is fine.
  - If the mechanism can't keep the guide drawn underneath, cut to full screen without the animation, keeping the no-restart hand-off. Record which in the report.
- **Back:** `onAdoptedClosed` calls `livePreviews.giveBack(target, handle)`. The guide's first `focus()` for that row within 3 s reclaims it, with no new open.

- [ ] **Step 1: Write the failing tests** (instrumented, local media, previews on):
  - `selectingAPlayingPreviewDoesNotOpenASecondStream`: a counting engine in `previewResolverOverride` / engine override gives 1 open total from rest to full screen. The player screen shows; the adopted player is the session's.
  - `backReturnsTheStreamToThePanel`: after Back, the guide row is focused, `livePreviews.current` is the same handle and still PLAYING, and the open count is still 1.
  - `selectingWithoutAPreviewOpensNormally`: select before the 1 s rest gives the normal player and no preview open.
- [ ] **Step 2: Run runclass (FAIL).**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run these:**
  - runclass for the class, `PlayerScreenTest` and `GuideScreenTest`;
  - the unit command;
  - the full suite once (`~/.goodtv-tools/runclass.sh` with no argument; known flaky: `OnlineSubtitlesTest`, `CategoriesScreenTest.uncheckingHidesFromLiveAndCountsUpdate`).

  Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(live): a playing preview grows into full screen and comes back"`
- [ ] **Step 6: Real-device check, by the controller rather than the implementer.** Install a release on the onn box and confirm by eye: preview with sound, flipping, the grow animation and Back. The emulator renders playing video black.
