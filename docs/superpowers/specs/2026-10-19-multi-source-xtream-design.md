# GoodTV: multi-source library with Xtream Codes as the first source

Date: 2026-10-19. Status: builder-checked, awaiting Dave's approval.

## Intent

GoodTV is an IPTV player for the Google TV Streamer. Today it plays three hard-coded test streams. This round turns it into a real client: the user adds one or more **sources**, the app pulls each source's live channels, movies, and series, caches them on the device, and presents one merged library browsable with the remote. Xtream Codes is the only source type built now; M3U playlists come next, so the source layer must not assume Xtream.

Dave's decisions (not defaults): on-TV form for credentials plus a debug-only prefilled source; several sources merged into one library; live + movies + series in this round; sidebar layout for Live, category rows for Movies and Series; nothing else from the cut list.

## Goals

1. Add, edit, remove, refresh Xtream sources from a Sources screen on the TV.
2. Browse Live (sidebar of categories, list of channels), Movies and Series (category rows of poster cards), across all sources at once.
3. Play live channels, movies, and series episodes full screen.
4. Library survives restarts and works offline-from-cache; a dead source never blocks the others.
5. Adding M3U later means implementing one interface and one form, nothing in the UI or cache changes.

## Non-goals (this round)

EPG, search, resume position, favourites, parental controls, M3U, catch-up/timeshift, VOD trailers, multiple user profiles, Play Store packaging.

## Anti-goals

- Typing more than necessary on the remote. Credentials are entered once; everything else is D-pad.
- Blocking the UI on network. The library renders from cache first; refresh is a background state.
- Trusting Xtream field types. Panels return the same field as string in one response and number in another. Parsing must be lenient or the app breaks on real providers.
- Merging categories by name across sources. "Sports" from provider A and "Sports" from provider B stay separate rows, labelled with the source.

## Constraints

- Stack already in place: Kotlin, Compose for TV (tv-material 1.1.0), Media3 1.11.1, AGP 9.4 built-in Kotlin (KGP 2.2.10). New Gradle plugins must be 2.2.10-compatible: `org.jetbrains.kotlin.plugin.serialization` 2.2.10 and KSP `2.2.10-2.0.2` both exist on Maven Central.
- Target device is Android 14 (API 34). minSdk 28.
- Test against a local fake Xtream server (Python, stdlib only, serves the public HLS test streams). Dave tests a real account himself afterwards; real credentials never appear in the repo or chat.
- No backend of our own.
- Credentials sit in the Room database in app-private storage. The database is excluded from Android cloud backup (`android:fullBackupContent` / `dataExtractionRules`) so passwords never leave the device.
- The player's HTTP data source allows cross-protocol redirects (http → https), needed both for the fake server's redirects to the public https test streams and for real panels that bounce to https.
- The debug default source is skipped when an instrumentation argument says so, so instrumented tests can start from zero sources on a machine whose `local.properties` has the fake server configured.

## Xtream Codes API (verified against two independent sources)

Base: `{server}/player_api.php?username=U&password=P[&action=...]`. Server may include a port (default 25461 / 8080) and may be http.

| Action | Returns |
|---|---|
| (none) | `{ user_info: { auth: 1, status: "Active", exp_date, max_connections, allowed_output_formats: ["ts","m3u8"] }, server_info: { url, port, https_port, server_protocol } }` |
| `get_live_categories` / `get_vod_categories` / `get_series_categories` | `[ { category_id: "1", category_name, parent_id } ]` |
| `get_live_streams[&category_id]` | `[ { stream_id: 123, name, stream_icon, category_id: "1", epg_channel_id, num, tv_archive, ... } ]` |
| `get_vod_streams[&category_id]` | `[ { stream_id, name, stream_icon, category_id, container_extension: "mkv", rating, rating_5based, added } ]` |
| `get_vod_info&vod_id=` | `{ info: { movie_image, plot, genre, cast, director, releasedate, duration_secs, rating, backdrop_path: [..] }, movie_data: { stream_id, name, container_extension } }` |
| `get_series[&category_id]` | `[ { series_id, name, cover, plot, cast, genre, releaseDate, rating, category_id, episode_run_time } ]` |
| `get_series_info&series_id=` | `{ info: {...}, seasons: [ { season_number, name, cover } ], episodes: { "1": [ { id: "1001", episode_num, title, container_extension, season, info: { movie_image, plot, duration_secs } } ] } }` |

Stream URLs (built from the server URL the user typed, never from `server_info`, which is often wrong behind proxies):
- Live: `{server}/live/{U}/{P}/{stream_id}.ts` when `allowed_output_formats` contains `ts`, otherwise `.m3u8`. Raw TS is the format most IPTV clients default to because many panels' live HLS stalls; Media3 plays both. `allowed_output_formats` is persisted on the source so URLs build offline.
- Movie: `{server}/movie/{U}/{P}/{stream_id}.{container_extension}`
- Episode: `{server}/series/{U}/{P}/{episode.id}.{container_extension}`

Typing caveats seen in the wild: `category_id` string, `stream_id` int, episode `id` string, `rating` string or number, `rating_5based` string or number, `backdrop_path` array or absent, `episodes` is a map keyed by season number as string (some panels return an array). All IDs are modelled as `String`; numbers go through a string-or-number serializer; unknown keys ignored.

## Architecture

Single module, package `com.keyspaceapps.goodtv`, layered by package:

```
data/
  source/        SourceType enum, Source entity, ChannelSource interface, SourceRegistry
  xtream/        XtreamApi (OkHttp + kotlinx.serialization DTOs), XtreamChannelSource, XtreamUrls
  db/            Room: SourceEntity, CategoryEntity, LiveChannelEntity, MovieEntity, SeriesEntity,
                 SeasonEntity, EpisodeEntity, DAOs, GoodTvDatabase
  repo/          LibraryRepository (reads cache, exposes Flows), SyncManager (refresh orchestration)
  settings/      DataStore: last refresh time, selected tab, debug default source
domain/          UI-facing models: Channel, Movie, Series, Season, Episode, Category, SourceSummary, SyncState
ui/
  nav/           Navigation 3 back stack, NavKeys (Home, Player, MovieDetail, SeriesDetail, Sources, EditSource)
  home/          HomeScreen with tv-material TabRow: Live | Movies | Series | Sources
  live/          LiveScreen: category sidebar (left) + channel list (right)
  movies/        MoviesScreen: category rows of poster cards; MovieDetailScreen
  series/        SeriesScreen: category rows; SeriesDetailScreen (seasons → episodes)
  sources/       SourcesScreen (list, add, refresh, remove), EditSourceScreen (form)
  player/        PlayerScreen (existing, generalised to any PlayableItem)
  theme/
```

### Source abstraction

```kotlin
interface ChannelSource {
    val type: SourceType                       // XTREAM now, M3U later
    suspend fun validate(config: SourceConfig): Result<SourceInfo>     // sign-in check, used by the form
    suspend fun fetchCatalog(config: SourceConfig): Result<Catalog>    // everything: categories + live + movies + series
    suspend fun fetchSeriesDetail(config: SourceConfig, seriesId: String): Result<SeriesDetail>  // lazily on open
    suspend fun fetchMovieDetail(config: SourceConfig, movieId: String): Result<MovieDetail>     // lazily on open
    fun streamUrl(config: SourceConfig, item: PlayableRef): String
}
```

`SourceConfig` is a sealed class (`Xtream(server, username, password)`; later `M3u(url)`), persisted as JSON in `SourceEntity.configJson` alongside `type`, `nickname`, `enabled`, `lastSyncAt`, `lastError`. `SourceRegistry` maps `SourceType` to its `ChannelSource`.

Catalog fetch is whole-source, not per-category: Xtream list endpoints return everything in one call each (4 calls per source: categories x3 + streams x3, actually 6), which is faster over Wi-Fi than paging and lets the cache be replaced atomically per source.

### Cache and sync

- Room is the single source of truth for the UI. Every entity carries `sourceId`; deleting a source cascades.
- `SyncManager.refresh(sourceId)` runs: fetchCatalog → in one transaction delete that source's rows → insert new rows → update `lastSyncAt`. On failure: keep old rows, record `lastError`. Sources refresh concurrently; one failing never affects another.
- Triggers: every time the app comes to the foreground (Activity `onStart`, because TV processes live for days) when any enabled source's `lastSyncAt` is older than 12 h; after add/edit; manual "Refresh" on the Sources screen. `SyncState` (idle / syncing(sourceIds) / error(sourceId, message)) is exposed as a Flow so screens can show a small indicator.
- Series detail (seasons/episodes) and movie detail are fetched on demand when the detail screen opens and cached in Room too, so reopening is instant.
- Expected scale: a real provider has 5k to 20k live channels and 10k+ movies. Lists come from Room via Flow with `LIMIT`-free queries for categories (hundreds) and per-category queries for items; the Live list only loads the selected category's channels. Movies/Series rows load each category's first 30 items; a "See all" tile opens a grid. (Builder may size these; the principle is never load the whole library into one composable.)

### UI

- **Home**: tv-material `TabRow` across the top: Live, Movies, Series, Sources. Tabs remember their own scroll/selection while the app runs. First launch with zero sources lands on Sources with an "Add source" button focused.
- **Live**: left column lists categories (prefixed with source nickname when more than one source is enabled, e.g. "Sports · Home"), right column lists channels of the selected category (logo, name). Up/down on the right scrolls channels; left jumps to the sidebar. Select plays. Back from player returns to the same position.
- **Movies / Series**: vertical list of category rows (title: "Action · Home"), each a horizontal rail of poster cards (2:3 poster, title underneath). Select opens detail.
- **Movie detail**: backdrop, poster, title, year, genre, duration, rating, plot, big Play button focused by default.
- **Series detail**: same header; a row of season chips; below it the selected season's episodes as a list (number, title, duration). Select plays the episode.
- **Sources**: list of sources with nickname, type, status line ("3,120 live · 8,400 movies · 410 series · synced 2 h ago" or the last error). Actions per source: Refresh, Edit, Remove (with confirm). "Add source" opens the form.
- **Edit source**: fields Nickname (optional), Server URL, Username, Password; "Test & save" calls `validate`, shows the result (expiry date, max connections) or the error, then saves and triggers sync. Password field uses password transformation. Uses standard Compose `TextField`s (tv-material has no text field); the TV's IME handles entry.
- **Player**: existing screen, now takes a `PlayableItem` (title, url, kind). Live keeps no controls; movies/episodes get Media3's default seek on D-pad left/right and play/pause on center (builder picks the simplest Media3 Compose way to do this).

### Debug default source

Debug builds read `GOODTV_XTREAM_SERVER`, `GOODTV_XTREAM_USERNAME`, `GOODTV_XTREAM_PASSWORD` from `local.properties` (gitignored) into `BuildConfig`. On first launch with zero sources, if all three are present, the app inserts that source silently. Release builds compile these as empty. The fake server's values are documented in README so the emulator flow is one command.

### Fake Xtream server (dev tooling, in `tools/fake-xtream/`)

Python 3 stdlib HTTP server. Implements `player_api.php` default/auth, the six list actions, `get_vod_info`, `get_series_info`, and the three stream routes as HTTP 302 redirects to the public HLS test streams (so playback through the full URL-building path is exercised). Serves a fixed catalog of about 3 live categories / 12 channels, 2 movie categories / 6 movies, 1 series category / 2 series with 2 seasons each. Deliberately returns mixed types (string `stream_id` in one entry, number in another) to keep the lenient parsing honest. Started with `python3 tools/fake-xtream/server.py --port 8089`; the emulator reaches it at `http://10.0.2.2:8089`.

## Testing

- Unit (JVM): DTO parsing against captured JSON fixtures including the mixed-type cases; `XtreamUrls` stream URL building; `SyncManager` with a fake `ChannelSource` (success, failure keeps old rows, concurrent sources); Room DAOs with Robolectric-free in-memory Room via instrumented tests if JVM Room proves awkward.
- Instrumented (emulator): add a source through the form against the fake server, library shows merged content from two fake sources, D-pad flow Live → play, Movies → detail → play, Series → detail → episode → play.
- Manual: screenshots of each screen on the Google TV emulator, as last round.

## Left to the builder

- Exact Room schema and indices; migration strategy is "destructive, version 1" this round.
- Coil configuration for posters and logos; placeholder art.
- How "See all" grids and row item limits are sized.
- Media3 controls wiring for VOD seeking.
- Error copy and empty states.
