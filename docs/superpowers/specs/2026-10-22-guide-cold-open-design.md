# Live TV guide: fast cold open — design

Status: draft for Dave's approval, 2026-10-22.

## Why

Dave often turns the Streamer on and goes straight to Live TV, and he also arrives from other apps or from watching something in GoodTV. No path dominates, so a true cold start is normal, not an edge case.

**Measured on the emulator** (2026-10-22, Dave's real data, 12,643 visible channels, 9,057 rows):
- **Debug build:** about 40 s to the first guide.
- **Release build:** about 2.6 s to the first guide (three cold opens: 2.5, 2.7 and 2.6 s). The stages break down as:
  - lineup query 0.4–0.8 s;
  - sort 0.25–0.35 s;
  - channel identities 1.3–1.6 s;
  - rows 0.1 s;
  - first programmes 0.04–0.26 s.

The emulator runs on a fast Mac. Dave agreed to assume the Streamer is 3–6 times slower; it has not been measured. That puts a real cold open at roughly 8–15 s before anything shows.

## What Dave decided

1. **Channels right away.** On opening Live TV, the channel rows must appear immediately.
2. **The guide may follow.** A 10–15 s wait for the programme information is acceptable, as long as an indicator shows it is loading, so he knows it's working.
3. **The approach,** in his words "That looks good" to the proposal:
   1. Work out channel identities once, during the library sync, and save them, so opening Live TV reads a finished channel list.
   2. Start loading the guide as soon as the app launches.
   3. Show the channel list first, then fill in programmes with a loading indicator.

## How it works (Claude's calls unless marked)

- **A saved channel list.** After any change that affects the lineup, the app builds the guide's rows in the background and saves them in a database table. The rows are each channel's merged copies, name, logo choice, genre, network, market, region, loop show, "More" placement, and section and order. Changes that trigger this:
  - a sync (catalogue or live list);
  - the Sports/Events live-list refresh;
  - hiding or unhiding categories;
  - enabling, disabling or deleting a source;
  - a guide refresh that changes logos or links.

  The work is the same as today's in-memory build, moved earlier.
- **What is saved, and what isn't** (Claude's call, from the builder check):
  - The saved list holds only lineup data: rows, their feeds (with each feed's source and category), names, genres, network, market, region, loop show, placement and order.
  - Guide links and the epgshare01 logos are still resolved when the list is read. That is cheap (about 4 ms today), so a daily guide refresh never forces a rebuild.
  - The provider-logo choice (placeholder or not) is saved with the row.
- **Opening Live TV reads that table.** That is one query over about 9,000 rows, instead of recomputing identities for 12,600 channels. Favourites and the programmes for the visible window are read as today.
- **Staying correct.**
  - The saved list carries a fingerprint of the lineup it was built from (sources, visible categories, channel ids and names) and a rules version.
  - If the fingerprint or version doesn't match on open (for example the app was updated with new matching rules, or a sync finished while the app was closed), the guide shows the saved list at once and rebuilds in the background. The rows update when the rebuild is done.
  - **Hidden categories and disabled sources never show, even from a stale list** (Claude's call, from the builder check). When the saved list is read, feeds whose source is disabled or whose category is hidden are filtered out against the current settings, and rows left with no feeds are dropped. This is a cheap check on each read. A hide or disable therefore takes effect at once, while the rebuild tidies grouping and order afterwards.
  - If there is no saved list yet (first run, or straight after this update), the guide builds as it does today, with the indicator.
- **Early start.** When the app launches, it begins reading the saved list and the first window of programmes in the background, without delaying the app's first screen. A Live TV screen opened a moment later finds them ready.
  - It warms the "All" view for the current half-hour window (Claude's call). Other chips build from the same warmed list, so they're quick too.
- **Channels first, then programmes.**
  - The grid shows channel rows (logo and name) as soon as the list is read.
  - Until the window's programmes arrive, each row's programme area shows a quiet loading state, and a small "Loading guide…" indicator sits near the time header.
  - It never shows "No guide info" while loading. That text means only "this channel has no guide".
  - **When the indicator shows** (Claude's call, from the builder check):
    - It shows until the first programme query for the window returns. After that, a row with no programmes in the window shows "No guide info", as today.
    - While the guide itself is being downloaded (first run, or a row's guide not stored yet), the indicator reads "Downloading guide…" for as long as the refresher is working, so a long first download doesn't look stuck.
  - When programmes arrive they fill in place, and focus stays where it is.
- **Measuring.** The existing debug timing logs (tag GoodTvGuide) gain the two new stages: saved list read, and first programmes after it. When Dave can install on the Streamer, these numbers tell us whether more is needed.

## Goals, non-goals, anti-goals

- **Goals:**
  - On a cold open, channel rows appear within about 2 s on the Streamer (assumed speed). This is a goal to check once Dave can install on the Streamer, not a gate for shipping (Claude's call, from the builder check). The timing logs will show where the time goes.
  - Programmes appear within 10–15 s, with a visible loading indicator.
  - Returning to Live TV in the same session is still instant.
- **Non-goals:**
  - Making the sync itself faster.
  - Changing what the guide shows, how rows are grouped, or any matching rules.
  - Measuring on the Streamer now (Dave can't install on it at the moment).
- **Anti-goals:**
  - The saved list shows channels Dave has hidden, or a disabled source's channels, after the change is made. A stale list may show briefly while it rebuilds, but never past one rebuild.
  - Favourites or focus jump around when the rebuilt rows arrive.
  - "No guide info" flashes on channels that do have a guide.
  - The app's own first screen opens more slowly because of the early start.

## Constraints

- **Database:** Room is at v10; this needs a real migration to v11 that keeps all existing data.
- **Background only:** all building and saving runs off the main thread. The build over about 12,600 channels costs about 2 s on the emulator release build and more on the Streamer.
- **Writes:** saving the list is one transaction, so the guide never reads a half-written list.
- **Testing:** no change to guide behaviour beyond loading. All existing guide tests stay green. Instrumented tests follow the usual backup and restore of Dave's emulator data.

## Left to the builder

- The table shape and how feeds are stored: a row table plus a feed table, or a serialized feeds column.
- How the fingerprint is computed. It must be cheap compared with rebuilding.
- The exact look of the loading state and indicator, within the app's existing style.
- Where the early start hooks in, as long as it waits for the first frame.
