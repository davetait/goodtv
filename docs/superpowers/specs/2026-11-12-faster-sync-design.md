# Faster source sync — design

Status: approved by Dave in chat, 2026-11-12 ("Proceed. If 30s is out of reach, this is a good compromise").

## Why

Refreshing Dave's source from Settings → Sources takes over 3 minutes on the onn 4K Pro. He wants it under 30 seconds. If 30 seconds proves out of reach, the plan below, at about 25–35 s, is an acceptable compromise.

## What was measured (onn 4K Pro, release build, 2026-11-12)

The provider's catalog has 56,862 live channels, 184,707 movies, 50,177 series and 1,747 categories.

| Step | Time |
|---|---|
| Downloading and parsing the lists, at most two at a time | ≈ 18 s (live 3 s, movies 14 s, series 14 s) |
| `XtreamMappers.toCatalog` (DTO → domain) | **141 s** |
| ↳ `Genre.fromPanelText`, 50k series | 63 s |
| ↳ `DisplayTitle.parse`, 290k names | 45 s |
| ↳ `tmdbIdOf`, 235k values; it compiles a `Regex` on every call | 32 s |
| `replaceCatalog`, one transaction | 42 s |
| ↳ deletes | 13 s |
| ↳ inserts (live 3 s, movies 14 s, series 4 s) | 22 s |
| ↳ series genres | 5.5 s |
| **Refresh, start to done** | **202 s** (237 s on a first run, while the guide was also busy) |

The same mapping takes about 4 s on a Mac's JVM, so Android's ICU regex, `Normalizer` and allocation costs dominate on the box. The probe code is kept as git stash "sync-timing-instrumentation" and in the uncommitted `app/src/test/.../SyncTimingJvmProbe.kt`. It logs `SyncTiming` lines per step and is reusable for the final timing.

## What the sync must do (unchanged)

- **Same library:** the same categories, channels, movies, series and series genres, with the same display titles, languages, qualities, years, TMDB ids and genres, for every name and value the panel sends.
- **All or nothing:** a failed or cancelled sync leaves the previous library untouched, and reports the error as today.
- **Same follow-on steps:** hidden-category pruning, the lineup generation bump, `markSync` and the `SyncState` flow behave as today.
- **Multi-source:** a sync never touches another source's rows.

## Plan

1. **Cheap conversion.** The aim is 141 s down to a few seconds, with identical output.
   - `tmdbIdOf`: scan leading digits by hand, keeping today's rules: trim, leading digits, positive.
   - `Genre.fromPanelText`: memoise per distinct genre text within a sync, since panels repeat a few hundred strings. A faster fold for plain ASCII text may also help.
   - `DisplayTitle.parse`: cheap pre-checks before each regex. For example, skip `DECORATIONS` and `WIDE_SPACES` when no such character is present, and skip `TRAILING_TAG` and `PAREN_YEAR`/`TRAILING_YEAR` when the name can't end that way. Hoist `latestYear()` out of the per-name path.
   - Map the three lists in parallel on `Dispatchers.Default`, chunked if that helps.
2. **Downloads.** Fetch all three lists at once, set by the slowest list (about 14 s). The old cap of two was there for memory, after a 75 MB list ran a 192 MB heap out of memory. The box's heap is now 384 MB (`largeHeap`, growth limit 256 MB). Step 3 lowers peak memory by handling each list as it arrives and dropping it once written. Peak memory must not exceed today's.
3. **Overlap.** Each list is parsed, mapped and written as soon as it arrives. The old rows' removal also overlaps the downloads.
   - Holding Room's write transaction open across the downloads would block every other write (watch progress, guide refresh, TMDB cache) for up to about 15 s, so it isn't allowed.
   - The builder's call (suggested): write each list into source-scoped staging tables with no secondary indexes. Then, in one short final transaction: remove the source's old rows, copy staging into the real tables with `INSERT … SELECT`, empty staging, prune and bump. A failure before that transaction leaves the library as it was.
   - The builder may choose another design that meets the same constraints.
4. **Cheaper database work.** When the source being synced is the only source with rows in a table, clear the table with an unconditional `DELETE` (SQLite's truncate). Otherwise delete by source as today. Any other change that keeps the library identical is the builder's call.

## Goals, non-goals, anti-goals

- **Goals:**
  - Refresh from Settings on the onn 4K Pro finishes, start to "synced", in under 30 s. 25–35 s is acceptable.
  - The library is identical before and after the change.
- **Non-goals:**
  - The guide's lineup rebuild after a sync (it runs separately, after "synced").
  - The XMLTV guide download.
  - Writing only changed rows (diff sync). That is the fallback if the target is missed, as a separate piece of work.
  - Progress bars.
- **Anti-goals:**
  - A failed sync leaving a partial or empty library.
  - Other writes stalling for the length of a download.
  - Out-of-memory on the box.
  - Any visible change to titles, languages, qualities, years, genres or TMDB matches.

## Constraints

- **Credentials:** they live in the git-ignored `local.properties`. Never print or log them, and never put them in logs or probes.
- **Testing:**
  - Equivalence tests compare the new conversion with today's over real panel names. Fixtures exist under `app/src/test/resources/xtream`, and more names can be captured from the emulator DB. Values only, never credentials.
  - The existing `DisplayTitle`, `Genre`, mapper, `SyncManager` and store tests stay green.
  - Back up the emulator data with `~/.goodtv-tools/runclass.sh`.
  - The emulator hangs on a full real-provider sync, so do not run one there. Final timing is on the onn box with the probe build, then a clean build goes back on the box.
- **The onn box:** it is at 192.168.2.45 this session. adb needs the `nc` relay (127.0.0.1:15555).
