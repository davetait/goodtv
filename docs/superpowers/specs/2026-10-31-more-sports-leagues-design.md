# More sports leagues — design

Status: draft for Dave's approval, 2026-10-31.

## Why

The Sports screen has six leagues today: NHL, NBA, MLB, NFL, WNBA and PWHL. It works well; Dave wants more choice. He wants the other major North American leagues, the most popular world soccer leagues and cricket. He also wants to choose which leagues appear on the Sports screen from a new Sports section in Settings. With many leagues switched on, a league filter on the Sports screen makes a single league easy to find.

## What Dave decided

1. **More leagues.** Add the leagues listed under "Leagues" below. Dave approved the list ("Looks good"), including the items marked as Claude's calls.
2. **Defaults.** The six current leagues are on by default. Every new league is off by default.
3. **CFL is skipped for now.** ESPN lists the CFL but carries none of its games. The alternatives need a key: TheSportsDB's paid key, or the CFL's own API key on request.
4. **Alphabetical order.** League rows on the Sports screen are in alphabetical order.
5. **A league filter.** A dropdown on the Sports screen filters it to one league.
6. **A Sports section in Settings** with one switch per league.
7. **Mockup:** `.context/sports-leagues-mockup.html` (git-ignored), approved ("looks good"), including the items marked as Claude's guesses below.

## Leagues

Each entry gives the label shown, then the source. ESPN means `https://site.api.espn.com/apis/site/v2/sports/<path>/scoreboard?dates=YYYYMMDD`, the source the app already uses. Coverage was checked live on 2026-10-31, for real dates in each league's season.

**On by default (unchanged)**

| Label | Source |
|---|---|
| MLB | ESPN `baseball/mlb` |
| NBA | ESPN `basketball/nba` |
| NFL | ESPN `football/nfl` |
| NHL | ESPN `hockey/nhl` |
| PWHL | HockeyTech scorebar (existing `PwhlScorebar`) |
| WNBA | ESPN `basketball/wnba` |

**New, off by default**

| Group | Label | Source |
|---|---|---|
| North America | AHL | HockeyTech scorebar, `client_code=ahl` with the AHL's own public site key `50c2cd9b5e18e390`, not PWHL's (27 games found over 3 days). `PwhlScorebar` generalises to take a client code and key. |
| North America | College Basketball (Men's) | ESPN `basketball/mens-college-basketball` |
| North America | College Basketball (Women's) | ESPN `basketball/womens-college-basketball` |
| North America | College Football | ESPN `football/college-football` |
| North America | Liga MX | ESPN `soccer/mex.1` |
| North America | MLS | ESPN `soccer/usa.1` |
| North America | NLL | ESPN `lacrosse/nll` |
| North America | NWSL | ESPN `soccer/usa.nwsl` |
| North America | PLL | ESPN `lacrosse/pll` |
| North America | UFL | ESPN `football/ufl` |
| World soccer | Bundesliga | ESPN `soccer/ger.1` |
| World soccer | Champions League | ESPN `soccer/uefa.champions` |
| World soccer | Europa League | ESPN `soccer/uefa.europa` |
| World soccer | La Liga | ESPN `soccer/esp.1` |
| World soccer | Ligue 1 | ESPN `soccer/fra.1` |
| World soccer | Premier League | ESPN `soccer/eng.1` |
| World soccer | Serie A | ESPN `soccer/ita.1` |
| World soccer | World Cup | ESPN `soccer/fifa.world`. It has games only during a World Cup (Claude's call). |
| Cricket | Big Bash | ESPN cricket, league `8044` |
| Cricket | International Cricket | ESPN cricket: men's Tests, ODIs and T20Is, including ICC tournaments, gathered into one entry (Claude's call) |
| Cricket | IPL | ESPN cricket, league `8048` |
| Cricket | The Hundred | ESPN cricket, if ESPN carries it (Claude's guess). Not yet confirmed; drop it if not. |

The exact labels are Claude's; the builder may adjust wording.

**Cricket's shape.**
- ESPN files cricket under numbered competitions, not paths. The by-date feed `https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel?dates=YYYYMMDD` returns every cricket competition that day.
- International series come as tours ("India tour of New Zealand 2026/27"), each with its own competition id. The match class marks the format (`Test`, `ODI`, `T20I`, versus `First-class` and others). "International Cricket" means the men's Test, ODI and T20I matches between national teams.
- A Test lasts up to five days, so a Test still in play shows as live on each of its days.
- ESPN lists no TV networks for cricket, so channels are found from team names alone.
- **Cards** (Claude's call): soccer and cricket cards read "Home v Away", following those sports' convention; the other leagues keep "Away @ Home". The score line is formatting only, using ESPN's own summary for cricket (for example "IND 245/6 · NZ 180"). `SportsGame` keeps one score string per side.
- The IPL's own scoreboard returned no games for an in-season date. The builder should confirm how to read a named cricket league, for example from the scorepanel filtered by league id.

**Left out (Claude's calls, approved):**
- CFL and the Canadian Premier League need a key (see decision 3).
- Junior hockey (OHL, WHL, QMJHL) would come from HockeyTech too.
- Smaller soccer leagues: Eredivisie, Scottish Premiership, Primeira Liga.
- Rugby.
- Women's and domestic cricket.
- Sports without two teams playing each other: F1, NASCAR, golf, tennis and UFC. The Sports screen is built around two-team games and matches channels by team names.

## How it works

- **Settings**
  - Settings gets a fourth row, **Sports**, after Live TV. Its status line counts the leagues switched on, for example "6 leagues on" (Claude's guess).
  - The row opens a Sports settings screen with one on/off switch per league, under three headings: North America, World soccer and Cricket. Leagues are alphabetical within each heading (Claude's guess). The list scrolls.
  - The choice is stored with the app's other settings (DataStore). It applies at once, including to a Sports screen that's already open.
  - Defaults: the six current leagues on, the rest off. Nothing is stored until someone changes a switch; until then the defaults apply. Once someone changes a switch, their choice holds. A league added in a later version starts off.
- **Sports screen**
  - It shows only switched-on leagues, as today, with one row per league that has live or upcoming games today. A league with no games today doesn't show.
  - **Order:** rows are in alphabetical order of their labels.
  - **Fetching:** only switched-on leagues are fetched (Claude's guess). Each one costs two requests a minute (yesterday and today) while the screen is open. Turning a league off stops its requests; turning one on fetches it promptly.
- **League filter**
  - A dropdown at the top-left of the Sports screen, in the style of the genre filter on Movies and Series. It starts on "All leagues".
  - It lists "All leagues" plus each switched-on league that has games today, with a game count beside each (Claude's guess). This way an empty league can't be picked.
  - Picking a league shows only that league's row. Picking "All leagues" shows them all.
  - It goes back to "All leagues" when the app restarts, or when the chosen league has no games left that day (Claude's guess).
  - A switched-on league whose schedule couldn't be read is listed without a count; picking it shows its "couldn't load" row, as the screen does today (Claude's call).
  - With only one league showing, the filter can still be shown, or hidden; that's the builder's call.
- **College sports volume:** Men's college basketball alone has about 60 games a week, so a college row can be long. It is shown like any other league; the filter is the way to narrow the screen (Claude's call).

## Goals, non-goals, anti-goals

- **Goals:**
  - The listed leagues show their games, with channels found, when switched on.
  - Choosing leagues in Settings is quick.
  - With many leagues on, the filter gets to one league in a couple of presses.
- **Non-goals:**
  - CFL and Canadian Premier League (for now).
  - Reordering leagues by hand.
  - Favourite teams.
  - Sports other than two-team games.
  - Showing a league that has no games today.
- **Anti-goals:**
  - The Sports screen slows down, or hammers ESPN, because of leagues that are switched off.
  - A channel for one league's game attaches to another league's game: same city, different sport, for example Toronto FC versus the Maple Leafs.
  - Turning on new leagues breaks channel matching for the six existing ones.
  - Someone who never opens Settings sees a different Sports screen than today, apart from the new row order and the filter.

## Constraints

- **Matching channels to games:**
  - `GameMatcher` currently rejects any channel whose name or category mentions another league's word: `OTHER_LEAGUES` lists `mls`, `fc`, `ahl`, `ncaa`, `nwsl`, `ufl`, `nll` and others. This protects the six leagues from same-city teams in other sports.
  - With those leagues added, a league word must instead point the channel at that league's games. It must still keep it off the other leagues' games.
  - A league word for a league that is switched off still keeps the channel off every other league's games, as today. For example, with MLS off, a "Toronto FC" channel never reaches the Maple Leafs.
  - When a channel still fits games in more than one league, it matches none, the existing rule ("better 'On 2 channels' than starting the wrong game"). Men's and women's college basketball share school names, so some of their channels will match nothing; that's acceptable (Claude's call).
  - Soccer clubs and national cricket teams are often named differently in channel names ("Man Utd", "Spurs", "IND v NZ"). How far matching goes for these is left to the builder, but the anti-goal above stands.
- **Existing behaviour:** the six current leagues keep working and matching as today. The existing Sports screen behaviours stay: polling, failure handling, keeping the last good read, and the channel picker.
- **Sources:** free and keyless, as today. ESPN's API is unofficial and may change without notice, as the existing code already accepts. HockeyTech uses the public site key, as `PwhlScorebar` does. User agent `GoodTV/0.2` as today.
- **Testing:**
  - Use captured schedule fixtures (as the sports work did), not live calls, in tests.
  - TV focus is driven by keys in instrumented tests, with initial focus requested from `onPlaced`.
  - Back up and restore Dave's emulator data with `~/.goodtv-tools/runclass.sh`.

## Left to the builder

- The data model for leagues and groups. For example, extend `League` with a group, a default and a source, or move to a data table.
- How cricket competitions are read and filtered, within the "International Cricket" definition above.
- How league words in channel names map to leagues, and any team-name aliases.
- The Sports settings screen's layout, following the Settings, Integrations and Live TV screens.
- The filter's exact look and behaviour details, within the above.
