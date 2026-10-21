# Sports screen: design

Status: draft for Dave's approval, 2026-10-21 (builder-checked).

## Why

Dave knows the team he wants to watch, but not which channel the game is on. Today he picks a category and scrolls through channels to find it. The Sports screen lists today's games that are on now or still to come, grouped by league, with team logos. Selecting a game starts it.

## What Dave decided

1. **Leagues, in this order:** NHL, NBA, MLB, NFL, WNBA, PWHL.
2. **What's shown:** games that are on now come first; games later today follow. Finished games are not shown.
3. **Selecting a game:**
   - On exactly one channel: it starts playing straight away.
   - On several channels: a short list of those channels appears, and Dave picks one.
4. **A game with no matching channel is still shown.** In place of a channel it shows the broadcaster text the schedule source lists (for example "ESPN+ · NBC Sports BA"), or "Channel not known" if it lists none. Selecting it plays nothing.

## How it works (decided in conversation; the source choices are Claude's calls, verified live on 2026-10-20/21)

- **The day's schedule and team logos come from schedule sources, not from channel names:**
  - **NHL, NBA, MLB, NFL, WNBA:** ESPN's public scoreboard, `https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard?dates=YYYYMMDD` (`hockey/nhl`, `basketball/nba`, `baseball/mlb`, `football/nfl`, `basketball/wnba`).
    - Each event gives its start time, status (scheduled, in progress, final), both teams (display name, short name, abbreviation, logo URL), the score while live, and `broadcasts[].names`.
    - It needs no key. It's unofficial and undocumented, so it can change without notice.
  - **PWHL:** the HockeyTech feed behind the league's site, `https://lscluster.hockeytech.com/feed/?feed=modulekit&view=scorebar&client_code=pwhl&key=446521baf8c38984&fmt=json&lang=en&numberofdaysahead=1&numberofdaysback=1`.
    - Games are in `SiteKit.Scorebar[]`: `GameDateISO8601`, `HomeLongName`/`VisitorLongName`, `HomeLogo`/`VisitorLogo`, `GameStatusString`.
    - The key is public, embedded in the league website. The response may be wrapped in `( … )` (JSONP); strip it.
    - It's also unofficial. The PWHL season is off until late November, so today it has no games.
- **Channels come from the provider's live channels.** Event channels name the game, for example:
  - "LIVE | NHL MONTREAL - SAN JOSE | Tue 20 Oct 19:05 EDT (US) | 8K EXCLUSIVE | US: VIAPLAY PPV 3"
  - "US (Peacock 005) | 76ers vs. Knicks (2026-10-20 18:30:05)"
  - "NHL: San Jose vs Montreal @ Oct 20 7:00 PM :Sportsnet+ 15"
  - "Next | 76ers vs. Knicks | all | 20-10-2026 | 01:00 (GMT) | …"

  There are at least six naming styles. They use full names, city only, nickname only, or abbreviation plus nickname ("NYK - KNICKS"), joined by "vs", "vs.", "at", "x", "-" or "@". The provider's TV guide is too thin to use ("Live: NHL Hockey").
- **Matching:** a channel belongs to a game when its name mentions both teams. Each team can be recognised by its display name, city or location, nickname, or abbreviation as the schedule source gives them, compared case- and accent-insensitively on whole words.
  - Abbreviations count only when they appear as a token ("NYK", "MTL"), never inside a word.
  - A channel whose name starts with "ENDED", "End" or "- NO EVENT STREAMING -" is never a match.
  - **Dates and times in names:** when a channel name carries a date or time that can be read, it must be within **30 hours** of the game's start, so last week's rematch doesn't match. Names without a readable time can match.
    - The window is wide because names misstate dates. "Next | 76ers vs. Knicks | 20-10-2026 | 01:00 (GMT)" is about 22 hours from the real start.
    - When several channels name the same matchup, the one nearest the start time wins the "is this today's game" question (Claude's call).
  - **Shared names (Claude's call, from the builder check):**
    - **The problem:** many names belong to more than one team.
      - Cities: Boston, New York, Chicago, Los Angeles, Toronto.
      - Nicknames: Kings, Rangers, Jets, Panthers, Giants, Cardinals.
      - Real example: "Next | Jets vs. Blues", where Jets could be NHL or NFL.
    - **The rule:**
      1. A channel is matched only against **today's** games across all six leagues.
      2. If its name fits more than one of them, a league word in the channel name or its category ("NHL", "NBA", "MLB", "NFL", "WNBA", "PWHL") decides.
      3. If it still fits more than one game, the channel is not matched to any. It's better to show "On 2 channels" than to start the wrong game.
    - Because a single-channel game plays immediately, this rule is what keeps the anti-goal below from happening.
  - **"Next" / "NEXT" prefixes are normal matches** (715 channels use them): on this provider each event channel is a slot dedicated to one game, and "Next" means the game hasn't started yet. In the picker, channels saying "LIVE" sort before "Next".
- **Only enabled sources and visible (not hidden) categories are searched,** like everywhere else in the app.
- **Fresh channel names:** event channels are renamed through the day, but the app only re-syncs the library every 12 hours.
  - Opening Sports refreshes the live channel list for every enabled source whose live list is more than 30 minutes old. Only the live list is fetched, not movies or series. Claude's call: one request per source catches renamed channels in every category, instead of guessing which categories carry games.
  - The screen shows the games it already has while the refresh runs, then updates.
- **Refreshing the screen:** while it's open, the schedule is re-read every 60 seconds, so live scores and the on-now/later split stay current (Claude's call).
- **Player:** a picked channel plays in the existing player, exactly as it would from the Live tab.

## The screen (Claude's calls unless marked)

- **Where:** a new "Sports" tab between Live and Movies.
- **Layout:** one row per league that has games today, in Dave's league order. A league with no games today is left out.
  - Within a row, cards that are on now come first, then later ones by start time.
  - When no league has anything, the screen says "No games on today" and lists the next day with games, if the sources give one cheaply.
- **A game card:**
  - Away team logo and name, then home team logo and name. "@" for ESPN's away/home order, the usual North American convention.
  - When on now: a red "LIVE" badge, the score, and the period or inning if the source gives it.
  - When later: the local start time, such as "7:30 PM".
  - The bottom line, in order of preference:
    1. "On 3 channels" when matched;
    2. the broadcaster text when not matched;
    3. "Channel not known".
  - A missing logo falls back to the team abbreviation in a circle.
- **The channel picker** (Dave decided a picker; its contents are Claude's calls):
  - A side panel styled like the player's Subtitles panel, titled "Away @ Home".
  - Each row shows the channel's cleaned name, its language and quality (as the app already shows for channels, e.g. "(EN · 4K)"), and its category name.
  - Rows are ordered:
    1. North American channels first: English, then French, then others;
    2. names that say "LIVE" before others;
    3. then by name.
  - Dave's last pick for the same feed family is not remembered (not asked for).
- **Focus:** first card of the first row on entry. Back from the player returns to the same card. Back from the picker returns to the card.

## Goals, non-goals, anti-goals

- **Goals:**
  - Find tonight's game for a given team in a few presses.
  - The right channel starts.
  - Live games are obvious.
- **Non-goals:**
  - Finished games and highlights.
  - Leagues beyond the six.
  - Soccer, college sports and the CFL. Event channels exist for them, but Dave didn't pick them.
  - Reminders or notifications.
  - Choosing a channel automatically when there are several.
- **Anti-goals (would make it a failure even if it "works"):**
  - Selecting a game starts the wrong game, or a channel showing "ENDED".
  - A refresh or a slow source hangs the screen.
  - Games quietly go missing because a source failed. If a source can't be reached, its leagues say so ("Couldn't load NHL schedule") rather than looking empty.

## Constraints

- **One stream at a time:** the IPTV account allows a single connection. Nothing on this screen opens a stream except the chosen channel.
- **The live list is large:** about 57,000 channels. Parse it as a stream, never into one string, as the existing sync does.
- **Credentials:** never print or log the provider's credentials. The schedule sources need none of the app's.
- **Times:** shown in the device's time zone. "Today" is the device's local day; a game that started late yesterday and is still live counts.
- **Tests must not reach the real sources:** use fakes and mock servers, as the TMDB and OpenSubtitles code does.

## Left to the builder

- **Code organisation:** class and file names, and the cache table or file for schedules (keep the last good schedule for the day so a brief outage shows the last state).
- **Text normalisation:** for matching, including the lists of "ended" markers and connector words, as long as the rules above hold.
- **Logos:** image loading (Coil is already used) and the card's exact sizes.
- **Testing:** test against the real name styles quoted above, plus ones taken from the provider's current list.
