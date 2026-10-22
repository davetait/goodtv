# Search by person — design

Status: draft for Dave's approval, 2026-10-22.

## Why

While watching a movie, Dave likes an actor and wants other things they're in, for example Tom Cruise. Today the info page shows the provider's cast and director as plain text, and search only matches titles.

## What Dave decided

1. **Names on info pages can be clicked.** Clicking a person opens Home search with their name filled in.
2. **Results are only what he can watch:** movies and shows in his library, not the person's whole career.
3. **Real work counts, appearances don't.** A person matches for acting roles, directing, producing (including executive producer), writing, and creating a series. Left out: appearing as themselves (talk shows, award shows, documentaries), guest roles in only one or two episodes of a series, and minor crew jobs ("Thanks", assistants and the like). Dave accepted this suggestion.
4. **Search finds people too.** Typing an actor's or director's name into the Home search finds their titles.
5. **The cast and director come from TMDB**, on movie and series info pages, replacing the provider's text where TMDB has people. Dave's check found that the provider often has no cast, and none for series. Titles TMDB can't place stay without clickable names; Dave accepts that.
6. **Layout: option A** from the mockup (`.superpowers/brainstorm/23032-1792702755/content/cast-on-info-page.html`). A "People" row of name buttons sits under the plot.
7. **No profile page.** Clicking a name lands on the search, not on a page about the person.
8. **One poster per title in the person's section.** Copies of the same film or show are collapsed to one poster, chosen the way the Popular rows choose. The ordinary title results below still show every copy. Dave accepted this suggestion.

## What the data says (checked 2026-10-22 against Dave's emulator data)

- **The provider's cast is unusable for this.** It comes only from the per-movie info call, made when a film is opened. The saved detail table holds 0 rows with cast. Series cast arrives in the series list, but it isn't stored.
- **TMDB ids are widespread.** 170,826 of 182,688 movies (94%) carry a TMDB id, covering 61,320 distinct films. 44,354 of 49,503 series (90%) do, covering 18,422 distinct shows.
- **TMDB can list a person's work.** For any person it lists every movie and TV show they appear in, as cast or crew. Matching those ids against the library gives "everything with Tom Cruise that you can play".

## How it works (Claude's calls unless marked)

- **People on info pages**
  - The app gets a title's credits from TMDB alongside the details it already fetches (rating, overview, genres), and caches them the same way.
  - The People row shows at most 10 names. The order is:
    - movies: directors first, then cast in TMDB's billing order;
    - series: creators first, then main cast.
  - Each button shows the name and a short role: the character for cast, "Director" or "Creator" for crew.
  - **Movie pages:** the row sits under the plot, as in mockup A. DOWN from the Play / Watch list buttons reaches it.
  - **Series pages:** the plot sits above the buttons there, and the seasons and episodes fill the space below. So the row goes under the plot and above the buttons; UP from the buttons reaches it. The seasons, the episodes and the starting focus stay as they are today.
  - On both pages, LEFT and RIGHT move along the row and Select opens search.
  - **When TMDB has no people** (no TMDB id, no match, offline, or no TMDB key), the row is left out. The provider's plain "Cast:" and "Director:" text shows as today, not clickable.
  - Nothing jumps around while credits load. The row appears with the rest of the TMDB details, or its space is reserved.
- **Clicking a person**
  - Opens a search screen on top of the info page. It looks like Home search: the search box with the name in it, scope All, and the same results grid. Back returns to the info page.
  - Whatever Dave had typed into Home search is left untouched.
  - The box can be edited. Editing turns it into an ordinary typed search, with names resolved as below.
  - Until then, the screen uses the clicked person's TMDB id, so two people with the same name don't mix.
- **Person results in search**
  - A "With Tom Cruise" section comes first. It holds the movies and series in the library whose provider TMDB id is among the person's counted credits (decision 3), one poster per title (decision 8), newest first.
  - Only titles whose provider sent a TMDB id can match (94% of movies, 90% of series). Titles TMDB placed by a name search show clickable people on their info page, but they don't appear in person results. The film Dave came from can therefore be missing from its own cast member's results (Claude's call: linking name matches back to library rows is much more work for about 1 title in 10).
  - Ordinary title matches follow in the existing grid, as today.
  - Every copy shows, as in search today: two sources or two qualities of the same film both appear. Hidden categories and disabled sources stay out.
  - The section shows how many titles it has, counting titles, not copies. If the person has nothing in the library, the section is left out; for a click, the search shows "No titles with Tom Cruise".
- **Typing a name**
  - When the query looks like a person's name, the app asks TMDB's person search. A section appears only for a confident match. The rules:
    - The query is at least two words, or the top result's name matches the query exactly (ignoring case and accents).
    - The person is known for acting or directing, or is popular enough not to be noise.
    - At most one person, unless two clearly match; for example "Chris" alone shows no person.
  - The person section comes after the debounce, like other results. It never delays the title results.
- **Caching**
  - A person's credits are cached for a while (for example a week), since filmographies change slowly. Repeat clicks and searches are then instant and work offline.
  - The cache obeys TMDB's existing 6-month limit (`MAX_CACHE_AGE_MS`).
- **Failures**
  - If TMDB is unreachable, a clicked person's search shows the ordinary title matches and a short note that people search needs the internet. It never shows an empty "no titles" that isn't true.

## Goals, non-goals, anti-goals

- **Goals:**
  - From a movie's info page, two presses reach a cast member and one more shows their titles in the library.
  - Typing "tom cruise" in search shows his titles.
  - It works for series as well as movies wherever TMDB knows the title.
- **Non-goals:**
  - A person profile page (photo, biography, full filmography).
  - Titles not in the library.
  - Using the provider's cast text for search.
  - People for live channels or episodes.
- **Anti-goals:**
  - A wrong person's titles appear; for example a click on one "Chris Evans" shows the other's work.
  - Typing a short or partial word fills search with random people.
  - Title search gets slower or worse because of person lookups.
  - "No titles" shows when the real cause is a network failure.
  - The info page moves under the focus as credits arrive.

## Constraints

- **Database:** Room is at v12. Any new cache tables need a real migration that keeps all data, with schema JSON committed and a migration test.
- **Credentials:** the TMDB key lives in `local.properties`. It is never logged and never appears in URLs printed to logs.
- **Testing:** TV focus behaviour gets instrumented tests, following the usual backup and restore of Dave's emulator data. Request initial focus from `onPlaced`.
- **Responsiveness:** no TMDB call on the main thread. Person searches go through the existing `TmdbApi` with its concurrency limit and retries.

## Left to the builder

- Whether credits come from `append_to_response=credits` on the existing details call, or a separate call.
- How credits and person filmographies are stored, and the exact cache lengths.
- The exact "confident match" thresholds for typed names, within the rules above.
- How the clicked person's TMDB id travels to the search (nav argument or view-model state).
- How many names fit before the row scrolls, within the at-most-10 rule.
- The exact cut-off for a "one or two episode" guest role, and which crew jobs count as minor. TMDB's TV credits give an episode count; its crew credits give a department and job.
- Whether the person search screen is a new nav key that reuses `SearchResultsGrid`, or a second Home forced to the Home tab, as long as Back returns to the info page.
