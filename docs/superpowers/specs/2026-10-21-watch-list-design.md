# Watch list: design

Status: draft for Dave's approval, 2026-10-21.

## Why

While browsing Movies, Dave sees titles he wants to watch later but has no way to keep them. The watch list lets him save a movie or series as he browses and find it again on the Home screen.

## What Dave decided

1. **Saving while browsing:** hold Select on a movie or series poster to get a small menu with "Add to watch list". On a poster that's already saved, the menu says "Remove from watch list". It's the same hold-Select menu the Continue Watching row already uses.
2. **Saving from the detail page:** a "＋ Watch list" button next to Play on movie and series detail pages.
   - Once saved, it reads "✓ On watch list".
   - Pressing it again removes the title.
3. **Home row:** a "Watch list" row on Home, right after Continue Watching.
   - Holding Select on a card offers "Remove from watch list".
   - Selecting a card opens its detail page, as in Popular Movies.
4. **Leaving on its own:**
   - **A movie leaves** once it's watched to the end credits. It counts as watched under the app's existing "finished" rule: within the last 2 minutes or the last 5% of the film, whichever is bigger (`WatchRules.isFinished`). Stopping partway keeps it on the list.
   - **A series stays** until Dave removes it.
5. **Saved posters show a small bookmark corner** wherever posters appear, so saved titles are recognisable at a glance.
6. **Only the exact copy is saved** (answered after the builder check).
   - Saving the EN copy of "Heat" saves that copy only; the 4K or French copy shows no bookmark and can be saved separately, appearing as its own card.
   - Popular rows and Home search pick which copy to show each time they load. If they switch to a different copy, that card's bookmark follows the copy, not the film.

Dave approved the mockup: `.superpowers/brainstorm/96564-1792558323/content/watchlist-flow.html` (git-ignored).

## Claude's calls (shown in the mockup, approved with it, but not Dave's own words)

- **Row order:** most recently saved first.
- **Row size:** every saved title is shown, with no cap.
- **Empty list:** the row is hidden when nothing is saved, like Continue Watching.
- **Where hold-Select works:** everywhere a movie or series poster can be focused:
  - the Movies and Series screens (rows, category grids, genre browse, search results);
  - the Home screen's Popular Movies and Popular Shows rows;
  - Home search results for movies and series.

  Live channels are excluded. Continue Watching keeps its own menu (remove from Continue Watching).
- **Focus:** removing a card from the Watch list row moves focus to its neighbour, exactly as Continue Watching does today.

## How it behaves

- **What is saved:** the exact copy as the source lists it: source, kind (movie or series) and item id. That triple is the key; there is no matching across copies by TMDB id.
  - Its title and poster are stored too, so the row can draw without waiting.
- **Survives re-syncs:** a saved title keeps its place across the 12-hour library sync.
  - It's hidden from the row, but not deleted, when any of these is true. It comes back when the cause goes away.
    - Its source is disabled.
    - The title is no longer in the catalog.
    - Its category is hidden. This one is stricter than Continue Watching, so the row matches what every other poster screen shows. (Claude's call.)
  - **Deleting a source** deletes its saved titles, as it already deletes that source's watch progress. A re-added source gets a new id, so they couldn't be matched back anyway. (Claude's call.) The anti-goal about vanishing titles doesn't cover deleting the source.
- **"Watched to the end" for movies:** when the player reports a position that counts as finished for a saved movie, the movie is removed from the watch list.
  - This applies only to the saved copy itself. Finishing a different copy of the same film leaves the saved one on the list, which follows from Dave's exact-copy decision.
- **Episodes never remove a series.**
- **No account or cloud:** the list lives on the device, in the app's database, like watch progress.

## Goals, non-goals, anti-goals

- **Goals:**
  - Save a title in one hold-and-press from wherever it's seen.
  - Find it on Home.
  - Watched movies clear themselves.
- **Non-goals:**
  - Several lists or reordering.
  - Live channels.
  - Sharing or syncing between devices.
  - Saving single episodes.
  - Notifications when a saved series gets new episodes.
- **Anti-goals:**
  - A title vanishes from the list without Dave removing it, finishing the movie, or deleting its source, for example after a re-sync.
  - Stopping a movie partway removes it.
  - Hold-Select on a poster starts playback or opens something unexpected.
  - The Home screen gets slower to open.

## Constraints

- **Database:** Room is at version 8.
  - The new table needs a real migration (8 → 9), with a foreign key to its source that deletes on cascade.
  - Dave's data must survive the upgrade. The destructive fallback is only for unknown versions.
- **Focus:** Compose for TV focus rules from this project apply:
  - request initial focus from `onPlaced`;
  - removing the focused card hands focus to a neighbour.
- **Testing:** instrumented tests wipe the emulator app's data, so back it up before connected runs and restore it right after.

## Left to the builder

- The table and class names.
- How the bookmark corner is drawn: size, colour and placement on the poster.
- The exact menu and button copy, as long as it says "Add to watch list", "Remove from watch list" and "On watch list".
- How Home's first-focus rule includes the new row. The first card of the first row present gets focus.
