# Live TV as an on-screen guide: design

Status: draft for Dave's approval, 2026-10-21.

## Why

Dave opens Live TV to find something to watch **now**, not to plan ahead. He comes in one of three ways:
- **A show:** he wants a show without knowing which channel it's on.
- **A genre:** for example news or comedy.
- **A favourite channel.**

Today's Live screen is the provider's category list on the left and that category's channels on the right. Three things make it hard to use:
- **Bad categories.** Most are named after where the provider gets its streams ("AT&T", "PRIME", "ROKU"), not after what's on them.
- **Clutter.** The lists are full of headings, empty event slots and duplicate copies of the same channel.
- **No idea what's on.** It never says what's on a channel, even though the provider's guide is already downloaded.

## What Dave decided

1. **Live TV becomes an on-screen TV guide:**
   - each channel is a row;
   - time runs across in columns;
   - it covers what's on now and the next several hours, as far as the guide data allows.
2. **Channels are grouped by genre, a property of the channel** (the guide data has no usable programme genre from the provider):
   - **Favourites** (Dave's picks), first and grouped together.
   - **Network:** ABC, CBS, NBC, FOX, PBS and The CW, each grouped with its local stations by city. All the NBC channels sit together.
   - **News, Sports, Entertainment, Comedy, Movies, Kids, Documentary & Lifestyle.**
   - **Events:** PPV and event slots, shown only while something is on.
3. **Show the channel's logo** where one is available, in place of its name, on a white or light grey tile so dark logos stay readable. Approved as light grey `#ECEEF2`.
4. **Guide data:** use epgshare01 as the main source, with the provider's own guide as the fallback.
5. **Local stations: biggest markets only, the rest folded away** (answered after the builder check).
   - Each network shows only its stations in a few of the biggest markets. Dave: "By focusing on a few of the biggest cities, we should capture anything I want to watch."
   - Every other local station sits in a collapsed row at the end of that network's group, labelled like "More NBC". It opens to the rest, grouped by city.
   - This applies wherever the Network group appears (under All and under the Network chip), so there are never 1,000+ local rows by default.
6. **Approved mockup:** `.superpowers/brainstorm/81849-1792626000/content/guide-grid-v3.html` (git-ignored). Its layout is the approved layout:
   - genre chips and a search box above the grid;
   - a channel column with the logo and name;
   - half-hour columns with about 3 hours on screen and a red "now" line;
   - a details panel below the grid for the programme in focus.

## The data (measured 2026-10-21)

- **Dave's visible lineup** (he has hidden 826 of the provider's 919 live categories): 12,643 channels in 93 US categories.
  - **Regular channels:** about 5,500.
  - **PPV/event slots:** about 5,600. At the time of measuring, 1,266 said "No event streaming" and 247 said "Ended".
  - **24/7 loops:** about 1,700, each endlessly replaying one show, such as "24/7: 30 Rock".
  - **Heading rows:** 99, such as "####### NBC #######".
  - **Duplicates:** the same channel appears in several categories (A&E is under Entertainment, TV and AT&T).
- **The provider's guide:**
  - **Coverage:** only 465 of 7,026 regular channels (6%) have a guide id, sharing 380 guide feeds. They're mostly national cable channels; local stations have almost none (NBC: 1 of 566).
  - **Horizon:** about 24 hours ahead.
  - **Content:** titles and descriptions only. No genres, no catch-up (`tv_archive` is 0).
  - **Format:** the full file (`xmltv.php`, about 75 MB) was malformed once mid-file, so it must be parsed tolerantly.
- **epgshare01** (`https://epgshare01.online/epgshare01/`, free, refreshed daily, about 54 hours ahead):
  - **`epg_ripper_US_LOCALS1.xml.gz`:**
    - **Size:** 56 MB compressed, 556 MB unzipped.
    - **Channels:** 4,471 local stations keyed by call sign (for example `WNBC-DT.us_locals1`).
    - **Matches:** 519 of the 566 NBC channels match by call sign, as do 219 CBS, 187 FOX and 92 ABC.
  - **`epg_ripper_US2.xml.gz`:**
    - **Size:** 6 MB compressed, 76 MB unzipped.
    - **Channels:** 755 cable and national channels, with ids such as `AMC.HD.us2` and `Fox.News.Channel.HD.us2`.
    - **Matches:** the rate against Dave's channels hasn't been measured yet.
  - **`epg_ripper_US_SPORTS1.xml.gz`** exists but hasn't been measured.
  - **Programme detail:** nearly every programme has a description, one or more genres (Series, News, Sitcom, Comedy, Movie, Documentary, Children…) and a picture.
  - **Logos:** every channel has one (from `dtil.tmsimg.com`).
  - **Terms:** the site's stated condition is "free EPG for LEGAL use only". Dave knows this and chose to use it.

## How it works

### Channels: one row per channel

- **Not shown:**
  - heading rows;
  - event slots that are empty or ended;
  - channels in categories Dave has hidden;
  - channels from disabled sources.
- **Duplicates merge into one row.** Duplicates are copies of the same channel in several categories or feeds. Selecting the row offers a picker when there's more than one feed, ordered as the Sports picker orders them (North American, then English, then by name).
- **Regional feeds** such as East and West count as separate channels, because their times differ. (Claude's call.)
- **Quality and backup copies merge.** Copies that differ only in quality or backup markers (HD, FHD, UHD, 4K, RAW, "backup", "(D)", "(PC)") are one row with the feed picker. (Claude's call, from the builder check.)
  - **Different local stations** never merge: a different call sign means a different station.

### Genres and groups

- **Each channel gets one genre.** It comes from its matched guide entry, its name and its provider category name (for example "24/7 COMEDY" gives Comedy).
- **Network:** a broadcast network's national feeds and its local affiliates sit together under the network, with the locals named by city.
  - **Markets shown by default** (Claude's call on "a few of the biggest cities"):
    - New York, Los Angeles, Chicago, Philadelphia and Dallas–Fort Worth, the five largest US TV markets (LIKELY, by Nielsen's ranking).
    - This covers both coasts' schedules.
  - **The rest** go in the collapsed "More NBC" / "More CBS"… row.
  - **The market list** is a constant that's easy to change, not a setting.
- **24/7 shows** without their own genre go into the nearest one (crime, reality and classic shows into Entertainment). (Claude's call, accepted with the genre list.)
- **Favourites:**
  - Dave adds or removes one by holding Select (or pressing Menu) on a channel's name in the guide. It's the same small menu the watch list uses. (Claude's call; Dave asked for favourites but not how to add them.)
  - Favourites show first, together.
  - They're kept on the device and survive re-syncs, exactly as the watch list does.

### Guide data

- **Main source: epgshare01.**
  - The US local-stations and US cable files, downloaded about once a day.
  - Read as a stream, keeping only channels that match Dave's lineup. The local-stations file is too big to hold in memory.
- **Matching:**
  - **Local stations:** by call sign in the channel name. It must be a whole word next to a network or city word, so "Doctor WHO" and "Hong KONG" don't match.
  - **Cable channels:** by normalised name, as the Sports screen matches team names.
  - **Ambiguous names** match nothing rather than the wrong channel.
- **Fallback: the provider's guide** (`epg_channel_id`) for channels epgshare01 doesn't cover, and for everything when epgshare01 can't be reached.
  - **Never while playing:** the provider's guide download never runs while something is playing, so it can't compete with the account's single stream connection. (Claude's call, from the builder check.)
- **Event slots** (Events chip only, never under All, so about 4,000 slots can't flood the main guide; Claude's call from the builder check):
  - **When a slot shows:** only when its name names an event. Empty and ended slots are hidden, using the markers the Sports matcher already knows.
  - **Where it sits:** at its start time when the name carries one (the Sports `ChannelTimes` parser), otherwise spanning the row.
  - **Freshness:** slots are renamed through the day, so the Events view uses the live-list refresh the Sports screen already does (every 30 minutes, while open).
- **24/7 channels** have no guide anywhere. Their row shows their show's name across the whole visible time ("30 Rock · all day").
- **Channels with no guide at all** show one dim "No guide info" bar. They stay in the grid so they can still be found and played. (Claude's call, shown in the approved mockup.)

### The screen

- **Genre chips:** All, Favourites, Network, News, Sports, Entertainment, Comedy, Movies, Kids, Documentary, Events. They narrow the grid.
- **Search box:**
  - finds a show by title anywhere in the guide, now or later;
  - finds a channel by name;
  - finds 24/7 channels by the show they loop.
- **Grid:**
  - half-hour columns, about 3 hours on screen;
  - scrolls right as far as the guide goes (about 2 days);
  - a red "now" line.
  - **Focus** starts on the programme on now in the first row. Left and right move through time, and up and down through channels.
- **Channel column:**
  - **logo** on a light grey tile, from epgshare01 when matched;
  - else the provider's logo, unless it's a placeholder (one image shared by many channels);
  - else the name;
  - the channel name sits beside the logo, because local stations share a network's logo.
- **Details panel**, for the programme in focus:
  - picture;
  - title and channel;
  - time and genre;
  - time left if it's on now;
  - description.
- **Select:**
  - on a programme that's on now: plays the channel, through the feed picker if there are several;
  - on a later programme: details only. No reminders (not asked for).
- **Back** from the player returns to the same cell.

## Goals, non-goals, anti-goals

- **Goals:**
  - See what's on now on Dave's channels at a glance.
  - Find a show without knowing the channel.
  - Browse by genre.
  - Keep each network's channels together.
  - Put favourites first.
- **Non-goals:**
  - Reminders or recording.
  - Catch-up or restart (the provider has none).
  - Channels in hidden categories.
  - Editing genres by hand.
  - Using other guide sources such as open-epg.com (its files couldn't be found).
- **Anti-goals:**
  - The guide shows the wrong show for a channel, because of a bad match.
  - The screen is slow to open, or janky to scroll on the Google TV Streamer.
  - The daily guide download makes the app freeze or run out of memory.
  - Empty slots and duplicates clutter the grid.
  - Guide downloads use the IPTV account's single stream connection (they're plain HTTP downloads from another host, never streams).

## Constraints

- **Device:** the Google TV Streamer, with limited memory.
  - Guide files must be read as a stream and filtered, never loaded whole.
  - Keep only the hours the grid shows, about 2 days.
- **Lineup size:** 57,189 live channels in total, 12,643 visible. Grouping and matching must not run on the main thread.
- **No credentials anywhere:** none in logs, output or committed files.
  - The provider's credentials stay in `local.properties`.
  - epgshare01 needs no account.
- **Tests:**
  - Must not reach epgshare01 or the provider; use mock servers and fixture files.
  - Back up the emulator app's data before connected runs and restore it right after.
- **Durability:** volunteer-run guide sources can disappear or change format. A failed or malformed download leaves the last good guide in place, and the app never crashes on it.

## Left to the builder

- The storage design for guide data: table shapes and indices.
- When and how the daily download runs, as long as it doesn't slow the app's start.
- The exact matching rules, within the constraints above.
- Grid sizes, colours and the "now" line style, within the approved mockup.
- Whether genres are assigned from a hand-made list of known channel names, from rules, or both.
