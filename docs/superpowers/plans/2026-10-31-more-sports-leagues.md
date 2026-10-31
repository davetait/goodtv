# More Sports Leagues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 21 leagues to the Sports screen (all off by default). Let Settings → Sports switch each league on or off. Sort the Sports screen's rows alphabetically, and give it a filter for showing one league.

**Architecture:**
- `League` becomes a catalogue. Each entry carries a sport, a Settings group, a schedule source, a default, and the words that name it in channel names.
- `SportsRepository` fetches only the leagues switched on in `AppSettings`.
- `GameMatcher` uses the league words to decide which league a channel belongs to, which replaces its fixed reject list.
- The Sports screen gets a `TvDropdown` filter, and soccer and cricket cards read "Home v Away".
- A new Sports settings screen follows the Live TV settings screen.

**Tech Stack:** Kotlin, Compose for TV (tv-material3), DataStore Preferences, OkHttp, kotlinx.serialization, JUnit4, compose ui-test.

**Spec:** `docs/superpowers/specs/2026-10-31-more-sports-leagues-design.md`

## Global Constraints

- **Defaults:** MLB, NBA, NFL, NHL, PWHL and WNBA are on by default. Every new league is off. Nothing is stored until a switch changes. A league added in a later version starts off.
- **Order:** Sports screen rows are in alphabetical order of their labels, case-insensitive. In Settings, leagues are alphabetical within the headings North America, World soccer, Cricket (in that order).
- **Fetching:** only switched-on leagues are fetched, two requests a minute each (yesterday and today) while the Sports screen is collected. Switching a league on fetches it promptly.
- **Sources:** free and keyless.
  - ESPN `https://site.api.espn.com/apis/site/v2/sports/<path>/scoreboard?dates=YYYYMMDD`.
  - ESPN cricket by-date `https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel?dates=YYYYMMDD`.
  - HockeyTech `https://lscluster.hockeytech.com/feed/` with each league's public site key.
  - User agent stays `SPORTS_USER_AGENT` ("GoodTV/0.2").
- **CFL, Canadian Premier League and The Hundred are not added.** Ruling: ESPN carries no id for The Hundred that could be confirmed on 2026-10-31, and the spec says to drop it if unconfirmed.
- **Existing behaviour:** the six current leagues keep working and matching as today, along with polling, keeping the last good read, the failure row, the channel picker, and focus memory.
- **Cards:** soccer and cricket cards read "Home v Away". Every other sport keeps "Away @ Home".
- **Tests:**
  - Use captured fixtures under `app/src/test/resources/sports/`, never live calls.
  - In instrumented tests, drive TV focus with keys and request initial focus from `onPlaced`.
  - Run instrumented classes only through `~/.goodtv-tools/runclass.sh <class>`, one class per call; it backs up and restores Dave's emulator data.
- **Fixtures** (captured 2026-10-31, already in the tree, uncommitted until Task 1 commits them):
  - `espn_eng1.json` (Premier League, 2026-11-01, Arsenal at Liverpool)
  - `espn_usa1.json` (MLS, 2026-11-01, 6 games incl. Toronto FC at Atlanta United FC)
  - `espn_cricket_8043.json` (Sheffield Shield, 2026-10-31, 3 games, 2 live)
  - `cricket_scorepanel.json` (2026-11-01, all cricket, incl. T20I New Zealand v India and ODI matches)
  - `ahl.json` (AHL HockeyTech scorebar, 17 games)

## Review Focus

1. **A cricket side whose ESPN `location` is a country name** ("South Australia", location "Australia"). It must not match a channel for an Australia national-team match. The location is a name form only when the display name starts with it. *(Task 2 test `locationIsANameOnlyWhenTheDisplayNameStartsWithIt`)*
2. **A "Toronto FC" channel while MLS is off.** It must never reach the Maple Leafs, because the sport word "fc" limits it to soccer. *(Task 2 test `soccerWordKeepsAChannelOffHockeyWhenNoSoccerIsOn`)*
3. **Switching a league on while Sports is open.** Its games appear without waiting for the next 60 s poll. *(Task 3 test `switchingALeagueOnFetchesItAtOnce`)*
4. **A stored league name the app doesn't know** (from an older or newer version). It is ignored, with no crash, and the rest of the choice holds. *(Task 3 test `unknownStoredLeagueIsIgnored`)*
5. **The filtered league running out of games**, or being switched off. The filter goes back to All leagues and focus lands on a card. *(Task 4 test `filterGoesBackToAllWhenItsLeagueLeaves`)*

---

### Task 1: League catalogue and schedule sources

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/sports/SportsModels.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/sports/EspnScoreboard.kt`
- Rename and modify: `data/sports/PwhlScorebar.kt` → `data/sports/HockeyTechScorebar.kt` (`git mv`)
- Create: `app/src/main/java/com/keyspaceapps/goodtv/data/sports/EspnCricketScorepanel.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/AppGraph.kt:255` (the sources map)
- Tests:
  - `app/src/test/java/com/keyspaceapps/goodtv/data/sports/LeagueTest.kt` (new)
  - `EspnScoreboardTest.kt`
  - `PwhlScorebarTest.kt` → `HockeyTechScorebarTest.kt`
  - `EspnCricketScorepanelTest.kt` (new)
- Fixtures: the five files listed in Global Constraints

**Interfaces:**
- Produces, in `SportsModels.kt`:
```kotlin
enum class Sport { BASEBALL, BASKETBALL, CRICKET, FOOTBALL, HOCKEY, LACROSSE, SOCCER }
enum class LeagueGroup { NORTH_AMERICA, WORLD_SOCCER, CRICKET }
sealed interface LeagueSource {
    data class Espn(val path: String) : LeagueSource
    data class HockeyTech(val clientCode: String, val key: String) : LeagueSource
    data object EspnInternationalCricket : LeagueSource
}
/** [words]: normalised phrases that name the league in a channel's name or category. */
enum class League(val label: String, val sport: Sport, val group: LeagueGroup, val source: LeagueSource, val onByDefault: Boolean, val words: List<String>) {
    // entries below; the constant order no longer decides display order
}
val League.homeFirst: Boolean get() = sport == Sport.SOCCER || sport == Sport.CRICKET
```
- `League.espnPath` is removed. `EspnScoreboard` reads `(league.source as LeagueSource.Espn).path`.
- `HockeyTechScorebar(client, baseUrl, io, zone) : ScheduleSource` takes `clientCode` and `key` from `league.source as LeagueSource.HockeyTech`. It replaces `PwhlScorebar`, and `realNickname()` drops a nickname equal to the league's label.
- `EspnCricketScorepanel(client, baseUrl = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/".toHttpUrl(), io) : ScheduleSource` returns the day's men's international matches: those whose `competitions[0].class.internationalClassId` is `"1"`, `"2"` or `"3"` (Test, ODI, T20I).
- `EspnScoreboard`'s event-to-game mapping becomes `internal` and is shared with the scorepanel, which wraps events as `scores[].events[]`.
- For cricket only, `detail` is `status.summary` when present (for example "NSW trail by 253 runs"), else `shortDetail`.
- AppGraph builds the sources by kind:
  - `LeagueSource.Espn` → the one `EspnScoreboard`
  - `LeagueSource.HockeyTech` → the one `HockeyTechScorebar`
  - `LeagueSource.EspnInternationalCricket` → `EspnCricketScorepanel`

The 27 entries. Label, sport, group, source, on by default, words:

| Constant | Label | Sport | Group | Source | On | Words |
|---|---|---|---|---|---|---|
| AHL | AHL | HOCKEY | NORTH_AMERICA | HockeyTech("ahl", "50c2cd9b5e18e390") | no | ahl |
| BIG_BASH | Big Bash | CRICKET | CRICKET | Espn("cricket/8044") | no | big bash, bbl |
| BUNDESLIGA | Bundesliga | SOCCER | WORLD_SOCCER | Espn("soccer/ger.1") | no | bundesliga |
| CHAMPIONS_LEAGUE | Champions League | SOCCER | WORLD_SOCCER | Espn("soccer/uefa.champions") | no | champions league, ucl |
| COLLEGE_BASKETBALL_MEN | College Basketball (Men's) | BASKETBALL | NORTH_AMERICA | Espn("basketball/mens-college-basketball") | no | ncaa, ncaab, ncaam, college basketball |
| COLLEGE_BASKETBALL_WOMEN | College Basketball (Women's) | BASKETBALL | NORTH_AMERICA | Espn("basketball/womens-college-basketball") | no | ncaa, ncaab, ncaaw, college basketball |
| COLLEGE_FOOTBALL | College Football | FOOTBALL | NORTH_AMERICA | Espn("football/college-football") | no | ncaa, ncaaf, cfb, college football |
| EUROPA_LEAGUE | Europa League | SOCCER | WORLD_SOCCER | Espn("soccer/uefa.europa") | no | europa league, uel |
| INTERNATIONAL_CRICKET | International Cricket | CRICKET | CRICKET | EspnInternationalCricket | no | odi, t20i |
| IPL | IPL | CRICKET | CRICKET | Espn("cricket/8048") | no | ipl, indian premier league |
| LA_LIGA | La Liga | SOCCER | WORLD_SOCCER | Espn("soccer/esp.1") | no | la liga, laliga |
| LIGA_MX | Liga MX | SOCCER | NORTH_AMERICA | Espn("soccer/mex.1") | no | liga mx, ligamx |
| LIGUE_1 | Ligue 1 | SOCCER | WORLD_SOCCER | Espn("soccer/fra.1") | no | ligue 1 |
| MLB | MLB | BASEBALL | NORTH_AMERICA | Espn("baseball/mlb") | yes | mlb |
| MLS | MLS | SOCCER | NORTH_AMERICA | Espn("soccer/usa.1") | no | mls |
| NBA | NBA | BASKETBALL | NORTH_AMERICA | Espn("basketball/nba") | yes | nba |
| NFL | NFL | FOOTBALL | NORTH_AMERICA | Espn("football/nfl") | yes | nfl |
| NHL | NHL | HOCKEY | NORTH_AMERICA | Espn("hockey/nhl") | yes | nhl |
| NLL | NLL | LACROSSE | NORTH_AMERICA | Espn("lacrosse/nll") | no | nll |
| NWSL | NWSL | SOCCER | NORTH_AMERICA | Espn("soccer/usa.nwsl") | no | nwsl |
| PLL | PLL | LACROSSE | NORTH_AMERICA | Espn("lacrosse/pll") | no | pll |
| PREMIER_LEAGUE | Premier League | SOCCER | WORLD_SOCCER | Espn("soccer/eng.1") | no | premier league, epl |
| PWHL | PWHL | HOCKEY | NORTH_AMERICA | HockeyTech("pwhl", "446521baf8c38984") | yes | pwhl |
| SERIE_A | Serie A | SOCCER | WORLD_SOCCER | Espn("soccer/ita.1") | no | serie a |
| UFL | UFL | FOOTBALL | NORTH_AMERICA | Espn("football/ufl") | no | ufl |
| WNBA | WNBA | BASKETBALL | NORTH_AMERICA | Espn("basketball/wnba") | yes | wnba |
| WORLD_CUP | World Cup | SOCCER | WORLD_SOCCER | Espn("soccer/fifa.world") | no | world cup |

- Constant names are stored in settings and in game keys: never rename them.
- Keep the existing six constant names (`MLB`, `NBA`, `NFL`, `NHL`, `PWHL`, `WNBA`).

- [ ] **Step 1: Write the failing tests**
  - `LeagueTest`:
    - `defaultsAreTheOriginalSix`: `League.entries.filter { it.onByDefault }.map { it.name }.toSet() == setOf("MLB","NBA","NFL","NHL","PWHL","WNBA")`
    - `twentySevenLeagues`: `League.entries.size == 27`
    - `soccerAndCricketPutHomeFirst`: `League.PREMIER_LEAGUE.homeFirst && League.IPL.homeFirst && !League.NHL.homeFirst`
    - `wordsAreNormalised`: every word equals `GameMatcher.normalize(word)` and is non-empty.
  - `EspnScoreboardTest`:
    - `premierLeagueFixture`: `espn_eng1.json` for `League.PREMIER_LEAGUE` gives one game. Away is "Arsenal", home is "Liverpool", state SCHEDULED, and the key is `"PREMIER_LEAGUE:<event id>"`.
    - `sheffieldShieldFixtureReadsCricketScores`: `espn_cricket_8043.json` read as `League.BIG_BASH` (any Espn cricket league) gives 3 games, 2 LIVE. Tasmania v New South Wales has away score "174/8 (60 ov)", home score "427", and detail "NSW trail by 253 runs".
  - `HockeyTechScorebarTest`:
    - The existing PWHL cases still pass through `HockeyTechScorebar`.
    - `ahlFixture`: `ahl.json` for `League.AHL` on the day of its first game gives that day's AHL games, with `league == League.AHL`. The request URL carries `client_code=ahl` and `key=50c2cd9b5e18e390`.
  - `EspnCricketScorepanelTest`:
    - `onlyMensInternationalsFromTheScorepanel`: `cricket_scorepanel.json` for 2026-11-01 gives only matches with `internationalClassId` 1–3. That includes New Zealand v India (T20I), with home "New Zealand", away "India" and league `INTERNATIONAL_CRICKET`. It excludes Sheffield Shield and Women's Big Bash League matches.
    - `httpErrorIsASportsException`: a 502 throws `SportsException`.
  - Serve fixtures through `MockWebServer`, as the existing source tests do.
- [ ] **Step 2: Run them and confirm they fail**
  - Run: `./gradlew :app:testDebugUnitTest --tests '*.data.sports.*'`
  - Expected: FAIL. `homeFirst`, `HockeyTechScorebar` and `EspnCricketScorepanel` are unresolved.
- [ ] **Step 3: Implement the catalogue, the sources and the AppGraph wiring.** Update every reference to `League.espnPath` and `PwhlScorebar`. Existing callers of `League.entries` keep compiling. `GameMatcher.LEAGUE_WORDS` becomes `League.entries.flatMap { it.words }.toSet()` until Task 2 replaces it.
- [ ] **Step 4: Run them and confirm they pass**
  - Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
  - Expected: all unit tests pass; lint reports 0 errors.
- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/keyspaceapps/goodtv/data/sports app/src/main/java/com/keyspaceapps/goodtv/AppGraph.kt app/src/test/java/com/keyspaceapps/goodtv/data/sports app/src/test/resources/sports
git commit -m "feat(sports): catalogue of 27 leagues with ESPN, HockeyTech and cricket sources"
```

### Task 2: Channel matching across many leagues

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/sports/GameMatcher.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/sports/GameMatcherTest.kt`

**Interfaces:**
- Consumes: `League.words` and `League.sport` from Task 1.
- Produces: the same `GameMatcher.match(games, channels, now)` signature, with these rules:
  1. **Leagues not in the app.** If the normalised name plus category contains a word of a league the app doesn't have, the channel matches nothing. Those words are `cfl, echl, ohl, whl, qmjhl, chl, xfl, khl`, held as `OUTSIDE_LEAGUES`.
  2. **The named leagues.** The leagues a channel names are those whose `words` appear as whole phrases (`" $word "` in `" $context "`). Sport words add every league of that sport: `fc`, `cf`, `sc` and `soccer` → SOCCER; `cricket` → CRICKET (`SPORT_WORDS`).
  3. **Limiting candidates.** If that set is non-empty, candidate games are limited to games of those leagues. This replaces both `OTHER_LEAGUES` and the "league word decides between several" step.
  4. **Location as a name form.** A team's `location` is a name form only when the normalised `displayName` starts with the normalised `location`.
  5. **Everything else is unchanged:** two different words for the two teams, the closed and replay checks, the date window, and nearest-start for a series. When several games still fit, the channel matches none.
  6. **Team forms.** League words never act as team forms: `LEAGUE_WORDS` is all `League.words` plus `OUTSIDE_LEAGUES` and the sport words.

- [ ] **Step 1: Write the failing tests** in `GameMatcherTest`, using its existing helpers:
  - `soccerWordKeepsAChannelOffHockeyWhenNoSoccerIsOn`: games are an NHL game, Montreal Canadiens at Toronto Maple Leafs. The channel is "Toronto FC vs CF Montreal 7:30 PM". Result: the NHL game has no channels.
  - `fcChannelReachesMlsWhenMlsIsOn`: games are the same NHL game plus an MLS game, CF Montréal at Toronto FC. The same channel gives the MLS game that one channel, and the NHL game none.
  - `leagueWordLimitsCandidates`: a channel "NBA: Toronto at Boston" with a Raptors–Celtics NBA game and a Maple Leafs–Bruins NHL game on the same day matches only the NBA game.
  - `outsideLeagueWordStillRejects`: "CFL: Toronto at Montreal" matches nothing, whatever games exist.
  - `locationIsANameOnlyWhenTheDisplayNameStartsWithIt`:
    - The cricket game Tasmania v South Australia (team South Australia has location "Australia") and the cricket game New Zealand v Australia, both today.
    - Channel "Australia vs New Zealand T20I" → only the New Zealand v Australia game.
    - Channel "Tasmania v South Australia" → only the Shield game.
  - `existingSixUnchanged`: every existing `GameMatcherTest` case passes unchanged. If one encodes the old rule (a league word only breaking ties), the implementer reports it rather than changing it.
- [ ] **Step 2: Run and confirm failure**
  - Run: `./gradlew :app:testDebugUnitTest --tests '*GameMatcherTest'`
  - Expected: the new tests fail, `fcChannelReachesMlsWhenMlsIsOn` and `locationIsANameOnlyWhenTheDisplayNameStartsWithIt` among them.
- [ ] **Step 3: Implement the rules above.** Precompute the word → leagues map once. This runs for about 57,000 channels.
- [ ] **Step 4: Run and confirm pass**
  - Run: `./gradlew :app:testDebugUnitTest`
  - Expected: all pass.
- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/keyspaceapps/goodtv/data/sports/GameMatcher.kt app/src/test/java/com/keyspaceapps/goodtv/data/sports/GameMatcherTest.kt
git commit -m "feat(sports): league and sport words decide which league a channel belongs to"
```

### Task 3: Switched-on leagues: storage and fetching

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/settings/AppSettings.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/data/sports/SportsRepository.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/AppGraph.kt` (`sportsLeaguesSetting`, repository construction)
- Tests:
  - `app/src/test/java/com/keyspaceapps/goodtv/data/settings/AppSettingsTest.kt`
  - `app/src/test/java/com/keyspaceapps/goodtv/data/sports/SportsRepositoryTest.kt`

**Interfaces:**
- Consumes: `League.onByDefault` and `League.label` from Task 1.
- Produces:
  - `AppSettings.sportsLeagues: Flow<Set<League>>`.
    - Stored under `stringSetPreferencesKey("sports_leagues")` as constant names.
    - With nothing stored, it is `League.entries.filter { it.onByDefault }.toSet()`.
    - Stored names that aren't constants are ignored.
  - `suspend fun AppSettings.setLeagueOn(league: League, on: Boolean)`.
    - Reads the current stored set, or the defaults when nothing is stored, inside one `dataStore.edit`.
    - Adds or removes `league.name` and writes the whole set back.
  - `AppGraph.sportsLeaguesSetting: StateFlow<Set<League>?>`: `settings.sportsLeagues.stateIn(appScope, SharingStarted.Eagerly, null)`.
  - `SportsRepository(sources: Map<League, ScheduleSource>, enabled: Flow<Set<League>>, store, refresher, clock, zone, pollMs, compute)`.
    - Reads only leagues that are in `enabled` and have a source.
    - A change to `enabled` restarts the read loop at once.
    - `SportsBoard.leagues` is sorted by `league.label.lowercase()`.
    - `lastGood` survives a restart of the loop.

- [ ] **Step 1: Write the failing tests**
  - `AppSettingsTest`:
    - `sportsLeaguesDefaultToTheOriginalSix`
    - `setLeagueOnAddsToTheDefaults`: from nothing stored, `setLeagueOn(MLS, true)` gives the six plus MLS.
    - `setLeagueOffRemoves`: `setLeagueOn(NHL, false)` gives the defaults minus NHL.
    - `unknownStoredLeagueIsIgnored`: write the raw set `{"NHL", "CFL_LEGACY"}` directly to the store; then `sportsLeagues.first() == setOf(NHL)`.
  - `SportsRepositoryTest` (pass `enabled = MutableStateFlow(...)`):
    - `onlyEnabledLeaguesAreRead`: with enabled `{NHL, MLS}`, `reads` holds only NHL and MLS.
    - `rowsAreAlphabetical`: with enabled `{NHL, BUNDESLIGA, MLS, AHL}`, each with a game today, the board's leagues are `[AHL, BUNDESLIGA, MLS, NHL]`.
    - `switchingALeagueOnFetchesItAtOnce`:
      - Collect with enabled `{NHL}` and `runCurrent()`.
      - Set enabled `{NHL, MLS}` and `runCurrent()` with no time advanced.
      - MLS was read, and the latest board has an MLS row.
    - `switchingALeagueOffDropsItsRow`: removing NHL drops its row on the next board, without waiting for a poll.
- [ ] **Step 2: Run and confirm failure**
  - Run: `./gradlew :app:testDebugUnitTest --tests '*AppSettingsTest' --tests '*SportsRepositoryTest'`
  - Expected: compile failure on `sportsLeagues` and the `enabled` parameter.
- [ ] **Step 3: Implement.**
  - The read loop is `enabled.distinctUntilChanged().flatMapLatest { set -> flow { while (true) { emit(readAll(set)); delay(pollMs) } } }`.
  - `build` orders by label.
  - Update AppGraph to pass `settings.sportsLeagues`.
- [ ] **Step 4: Run and confirm pass**
  - Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
  - Expected: all pass; lint reports 0 errors.
- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/keyspaceapps/goodtv/data/settings/AppSettings.kt app/src/main/java/com/keyspaceapps/goodtv/data/sports/SportsRepository.kt app/src/main/java/com/keyspaceapps/goodtv/AppGraph.kt app/src/test/java/com/keyspaceapps/goodtv/data/settings/AppSettingsTest.kt app/src/test/java/com/keyspaceapps/goodtv/data/sports/SportsRepositoryTest.kt
git commit -m "feat(sports): fetch only the leagues switched on, in alphabetical rows"
```

### Task 4: Sports screen: league filter, and Home v Away cards

**Files:**
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/ui/sports/SportsViewModel.kt`
- Modify: `app/src/main/java/com/keyspaceapps/goodtv/ui/sports/SportsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Tests:
  - `app/src/test/java/com/keyspaceapps/goodtv/ui/sports/SportsViewModelTest.kt` (new)
  - `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/SportsScreenTest.kt`

**Interfaces:**
- Consumes: `League.homeFirst` (Task 1); the alphabetical `SportsBoard` (Task 3).
- Produces:
  - `SportsViewModel.filter: StateFlow<League?>` (null means all).
  - `fun selectLeague(league: League?)`.
  - `SportsViewModel.shown: StateFlow<SportsBoard?>`: the board limited to the filter's league. When the board no longer has a row for the selected league, `filter` goes to null and `shown` is the whole board.
  - Strings, exact:
    - `sports_filter_all` = "All leagues"
    - `sports_filter_with_count` = "%1$s · %2$s" (league label, game count)
    - `sports_versus` = "v"
  - Test tag `sports_league_filter` on the dropdown.
- Behaviour:
  - The dropdown sits above the rows, at 48 dp start padding. It's a `TvDropdown` with width 320 dp, like `GenreBrowse`.
  - Options are null first, then each board row in board order.
  - Labels are "All leagues", "<label> · <games>", or just "<label>" for a failed row.
  - The dropdown shows only when the board has at least one row.
  - Initial focus still goes to the first card, as today. UP from the first row reaches the dropdown.
  - Cards and the picker title:
    - With `homeFirst`: the home team's cell, then "v", then the away team's cell, and the score reads "home – away".
    - Otherwise unchanged: "Away @ Home".

- [ ] **Step 1: Write the failing tests**
  - `SportsViewModelTest` (unit; the board comes from a `MutableStateFlow`):
    - `filterLimitsToOneLeague`
    - `filterGoesBackToAllWhenItsLeagueLeaves`: select MLS, then emit a board without MLS; `filter.value == null`, and `shown` is the whole new board.
    - `allIsTheDefault`
  - `SportsScreenTest` (instrumented, `board = flowOf(...)`):
    - `filterShowsOneLeague`: the board has NHL and MLS rows. Press UP from the first card to the filter, Select, DOWN to the MLS option, Select. Only the MLS row is displayed, and focus is on an MLS card or the filter.
    - `soccerCardReadsHomeVAway`: an MLS game with home Atlanta United FC and away Toronto FC shows "v" between the team cells, with the home team's cell first, left of the away team's. An NHL card still shows "@".
    - The existing tests still pass.
- [ ] **Step 2: Run and confirm failure**
  - Run: `./gradlew :app:testDebugUnitTest --tests '*SportsViewModelTest'`, then `~/.goodtv-tools/runclass.sh com.keyspaceapps.goodtv.ui.SportsScreenTest`
  - Expected: FAIL (`filter` unresolved; no `sports_league_filter` node).
- [ ] **Step 3: Implement the view model, the screen changes and the strings.**
- [ ] **Step 4: Run and confirm pass**
  - The same commands, plus `./gradlew :app:lintDebug`.
  - Expected: all pass.
- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/keyspaceapps/goodtv/ui/sports app/src/main/res/values/strings.xml app/src/test/java/com/keyspaceapps/goodtv/ui/sports/SportsViewModelTest.kt app/src/androidTest/java/com/keyspaceapps/goodtv/ui/SportsScreenTest.kt
git commit -m "feat(sports): league filter, and soccer and cricket cards read Home v Away"
```

### Task 5: Settings → Sports

**Files:**
- Create: `app/src/main/java/com/keyspaceapps/goodtv/ui/settings/SportsSettingsScreen.kt`
- Modify:
  - `app/src/main/java/com/keyspaceapps/goodtv/ui/settings/SettingsScreen.kt`
  - `app/src/main/java/com/keyspaceapps/goodtv/ui/nav/NavKeys.kt`
  - `app/src/main/java/com/keyspaceapps/goodtv/ui/nav/GoodTvNavigation.kt`
  - `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/SportsSettingsTest.kt` (new)

**Interfaces:**
- Consumes: `League.group` and `League.label` (Task 1); `AppSettings.setLeagueOn` and `AppGraph.sportsLeaguesSetting` (Task 3).
- Produces:
  - `data object SportsSettingsKey : GoodTvKey`, with its `entry<SportsSettingsKey>` beside `LiveTvSettingsKey`'s.
  - Strings, exact:
    - `settings_sports` = "Sports"
    - plurals `settings_sports_leagues_on`: one = "%d league on", other = "%d leagues on"
    - `sports_settings_title` = "Sports"
    - `sports_group_north_america` = "North America"
    - `sports_group_world_soccer` = "World soccer"
    - `sports_group_cricket` = "Cricket"
- Behaviour:
  - **Settings:** a fourth row, "Sports", after Live TV. Its status is the count of switched-on leagues; it's empty while `sportsLeaguesSetting` is null. Its focus key is `SPORTS_ROW = "sports"`, added to the `targetIn` list.
  - **Sports settings screen:**
    - The title, then a `LazyColumn` with the three group headings in `LeagueGroup` order.
    - Under each heading, that group's leagues sorted by label case-insensitively, one `ListItem` each.
    - Each `ListItem` has a `Switch(checked = on, onCheckedChange = null)` and `semantics { toggleableState = ToggleableState(on) }`, the same pattern as `LiveTvSettingsScreen`.
    - Select flips the league with `setLeagueOn`. Nothing happens while the setting is still null.
    - The first league is focused from `onPlaced`.

- [ ] **Step 1: Write the failing test** `SportsSettingsTest`:
  - Seed and launch the way `LiveTvSettingsTest` does, and restore the setting in `@After` (write the defaults back).
  - `settingsRowCountsLeaguesOn`: with nothing stored, the Settings tab's Sports row shows "6 leagues on".
  - `headingsAndAlphabeticalLeagues`: Sports settings shows the three headings in order. Under North America, AHL comes before College Basketball (Men's), which comes before MLB, judged by `positionInRoot().y`.
  - `selectFlipsALeague`: MLS shows off. Move DOWN to it, press Select, and it shows on (`ToggleableState.On`), and `settings.sportsLeagues.first()` contains MLS. Press Select again and it's off.
- [ ] **Step 2: Run and confirm failure**
  - Run: `~/.goodtv-tools/runclass.sh com.keyspaceapps.goodtv.ui.SportsSettingsTest`
  - Expected: FAIL (no "Sports" row).
- [ ] **Step 3: Implement the screen, the Settings row, the nav key and the strings.**
- [ ] **Step 4: Run and confirm pass**
  - Run the same class, then `SettingsScreenTest` and `LiveTvSettingsTest` through `runclass.sh`, one call each, then `./gradlew :app:testDebugUnitTest :app:lintDebug`.
  - Expected: all pass.
- [ ] **Step 5: Run the full instrumented suite once**
  - Run: `~/.goodtv-tools/runclass.sh`, with no argument.
  - Expected: every test passes. The known flaky tests are `OnlineSubtitlesTest`, `CategoriesScreenTest.uncheckingHidesFromLiveAndCountsUpdate` and `LivePreviewUiTest.pausingInFullScreenThenBackPlaysInThePanel`. If only those fail, rerun them alone.
- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/keyspaceapps/goodtv/ui/settings app/src/main/java/com/keyspaceapps/goodtv/ui/nav app/src/main/res/values/strings.xml app/src/androidTest/java/com/keyspaceapps/goodtv/ui/SportsSettingsTest.kt
git commit -m "feat(settings): Sports section to switch leagues on and off"
```
