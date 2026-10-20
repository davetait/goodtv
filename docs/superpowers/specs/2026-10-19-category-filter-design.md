# GoodTV: per-source category filter

Date: 2026-10-19. Status: draft for builder check.

## Intent

Real Xtream providers expose hundreds of categories and tens of thousands of channels, movies and
series, most of them in languages or genres the viewer never wants. Dave wants a setting, per
source, that lists every category the provider offers with each one switched on by default, lets him
switch off the unwanted ones (whole language blocks at a time), remembers the choice, and then keeps
everything in a switched-off category out of Live, Movies and Series, so the app shows only the
content he wants.

Dave's decisions: lives per source, reached from the Sources tab; a checklist per content type with
Select all / Clear all; quick jumps by the provider's name prefixes (`AR|`, `AFR|`, `UK|`, ...) so a
whole language block can be turned off at once.

## Goals

1. A "Categories" action on each source row opens that source's category list.
2. The list is split into Live / Movies / Series. Every category is shown and is on by default.
3. Turning a category off takes effect immediately and persists across restarts and syncs.
4. Hidden categories, and everything in them, disappear from Live, Movies, Series and "See all".
5. Whole prefix blocks can be turned off or on in one action, and the list can be jumped by prefix.
6. Nothing is deleted: turning a category back on restores its content without a resync.

## Non-goals

Hiding individual channels or titles; parental PIN; per-category ordering or renaming; applying one
source's choices to another; searching categories by typing.

## Anti-goals

- A "Save" button. On a TV, every toggle should stick on its own; Back just leaves.
- Losing the user's choices on refresh. Sync replaces categories; the hidden set must survive it.
- Losing the user's data on upgrade. This is the first schema change after v1 and must migrate.
- Slow lists. 500 categories in a `LazyColumn` is fine; 20k channels must never be loaded to compute
  anything on this screen.

## Design

### Data

- New Room table `hidden_categories(sourceId, kind, categoryId)`, primary key all three, foreign key
  to `sources` with cascade delete. Only switched-off categories are stored, so "everything on" is
  the empty table and new categories from a later sync are on automatically.
- Database version 2 with an explicit `Migration(1, 2)` that creates the table. A migration test
  (Room `MigrationTestHelper`, exported schemas) proves a v1 database with sources survives.
  `fallbackToDestructiveMigration` stays as the last resort for an unknown version only.
- `replaceCatalog` prunes hidden rows whose category no longer exists in the new catalog, but only
  for a content type whose incoming category list is non-empty: panels occasionally answer `[]` with
  HTTP 200 for one endpoint, and that must not wipe the user's choices. Orphan hidden rows are
  harmless and self-correct on the next good sync.
- The `hidden_categories` foreign key points at `sources` only, never at `categories`, because
  `replaceCatalog` deletes and re-inserts category rows on every sync.
- `SourceSummary` gains `hiddenCount` (subquery in `observeSummaries`); item counts stay totals.
- Hidden rows are filtered in SQL (`NOT EXISTS` against `hidden_categories`) in the category list
  and the grouped movie/series queries; per-category reads and detail/player paths are untouched.
- `LibraryStore` gains:
  - `observeHiddenCategories(sourceId): Flow<Set<Pair<ContentKind, String>>>`
  - `setCategoryHidden(sourceId, kind, categoryId, hidden)` and `setCategoriesHidden(sourceId, kind, ids, hidden)`
  - `observeAllCategories(sourceId, kind): Flow<List<Category>>` (unfiltered, for the settings screen)
- Every existing read used by the browsing screens excludes hidden categories:
  `observeCategories(kind)`, `observeMoviesByCategory`, `observeSeriesByCategory`. Live channels are
  only ever read through a selected category, so they need no change; the grid is reached only from
  a visible row.

### Screen: Categories (`CategoriesKey(sourceId)`)

- Header: source nickname, then a tv-material `TabRow`: Live, Movies, Series, each label carrying
  "shown / total" counts, e.g. "Live · 112 of 340".
- Under the tabs, a row of actions: **Select all**, **Clear all** (for the current content type),
  then prefix chips.
- The list: categories grouped by prefix. Dave's provider (919 live / 444 movie / 385 series
  categories; 57k channels, 182k movies, 49k series) names them `AR| BEIN SPORTS` for live,
  `FR - NETFLIX FILMS` for movies, and `GERMANY NETFLIX` / `ESPAÑA SERIES` for series. So the prefix
  is the text before the first `|` or ` - ` (space-dash-space, so `DOCU-MOVIES` is not split), else
  the first word; trimmed and upper-cased. `:` is not a separator (it would split `Sky Sports:
  Premier League`). Prefixes shared by only one category go into a trailing **Other** group. Each group starts with a header
  row showing the prefix, its "shown / total" count, and a checkbox that toggles the whole group;
  category rows below it each have their own checkbox. Row states: checked = shown, unchecked = hidden.
- Prefix chips scroll the list to that group's header and focus it. A group's checkbox is how a
  language block is switched off in one press. Dave's decision for a mixed block (some shown, some
  hidden): one press hides the whole block; the next press shows all of it. The header checkbox
  therefore reads as checked only when every category in the block is shown, and shows the
  tri-state "mixed" mark otherwise.
- Select on a row toggles it and saves immediately. Counts in the tab labels and group headers update
  live. Back returns to Sources.
- Sources tab: each source row gets a **Categories** button next to Refresh / Edit / Remove. Its
  status line adds "· N categories hidden" when N > 0.

### Interaction with the rest of the app

- Browsing screens already read categories from the store, so hiding is invisible to them beyond
  the query change. The `FocusMemory` keys keep working; a remembered item in a now-hidden category
  simply falls back to the default.
- Hidden sets are per source; two sources with the same category name are independent.

### Builder-check calls (made, vetoable)

- Live / Movies / Series show "Everything is hidden. Sources → Categories to show some." (gated on
  having at least one enabled source) instead of the "No sources yet" message when all categories
  of that type are hidden.
- A never-synced source's Categories screen shows "Refresh this source first" rather than an empty list.
- No confirmation on Clear all; Select all reverses it in one press.
- Every toggle writes straight to the store; checkboxes and counts derive from Room flows (one
  source of truth, no local optimistic state).
- Migration test needs `androidx.room:room-testing` and the exported schemas as androidTest assets;
  `2.json` is committed.
- The fake Xtream panel's category names gain `|` / ` - ` prefixes so manual runs exercise grouping.

## Testing

- Unit: prefix grouping (`AR| Sports` → `AR`; `BBC One` → Other; single-member prefixes → Other;
  separators `|`, `:`, ` - `), group counts, the ViewModel's toggle / group toggle / select all /
  clear all mutating the store.
- Instrumented: migration 1→2 keeps sources and catalog rows; store filters hidden categories out of
  `observeCategories` and the grouped queries and leaves `observeAllCategories` complete; the screen
  shows all categories checked by default, unchecking one hides it from Live, a group checkbox clears
  the block; hidden set survives `replaceCatalog`.
- Manual on the emulator with Dave's real provider.

## Left to the builder

- Exact list item styling; whether group headers stick while scrolling.
- Chip overflow behaviour when there are more prefixes than fit on one row (a `LazyRow` is fine).
- Whether to show the number of items per category (not required).
