# Settings and Integrations — design

Status: draft for Dave's approval, 2026-10-22.

## Why

GoodTV's TMDB and OpenSubtitles keys are currently built into the app from Dave's `local.properties`. That works for development but means nobody else can install GoodTV with their own keys. Dave wants the app installable by someone else, who enters their own keys on the TV.

## What Dave decided

1. **No keys are built into the app, ever.** That covers every build, including Dave's own. The `local.properties` keys exist only to support development (for example, the live TMDB test). Keys entered in the app are the only ones the app uses.
2. **A Settings tab replaces the Sources tab** at the end of the top row (Home, Live, Sports, Movies, Series, Settings).
3. **Settings holds two entries:** Sources (the existing screen) and Integrations (new).
4. **Integrations** lets the user enter:
   - a TMDB API key;
   - an OpenSubtitles API key, username and password.
5. **Each service shows its web domain**, so the user knows where to create keys: **themoviedb.org** and **opensubtitles.com**.
6. **OpenSubtitles gets a short explanation** of why the username and password are needed along with the API key.
7. **Typing on a phone:** rely on the Google TV app's phone keyboard (type or paste on the phone into the focused TV field), with no QR code or web page. The screen shows the hint "Tip: use the Google TV app on your phone to paste keys."
8. **Save checks the key straight away.** It shows "Connected" when it works, or a plain message when the service rejects it, such as "TMDB didn't accept this key" or "OpenSubtitles didn't accept this username or password".

Layout reference: `.superpowers/brainstorm/78805-1792719238/content/settings-and-integrations.html` (Dave saw it; wording there is a first draft).

## How it works (Claude's calls unless marked)

- **Settings tab**
  - It's a short list of rows. Each row has a title, a status line and a chevron.
    - **Sources:** for example "2 sources · last synced 9:41", or "No sources yet".
    - **Integrations:** for example "TMDB connected · OpenSubtitles not set up".
  - Selecting a row opens that screen as its own full screen on top (no tab row), like a detail page. Back returns to Settings with focus on the same row.
  - The Sources screen itself is unchanged, including add, edit, refresh, remove and Categories.
  - **First launch with no sources** (today the app opens on the Sources tab): it opens on Settings with Sources focused. Select opens Sources with "Add source" focused, so setup is one press longer than today.
  - A saved "last tab" of SOURCES from older installs maps to Settings.
- **Integrations screen**
  - A one-line intro: the services are optional, and the app works without them.
  - **TMDB card:**
    - What it adds: posters, ratings, summaries, genres, popular rows and cast.
    - "Create a free API key at **themoviedb.org**".
    - One field labelled "API key or read access token". It accepts either kind, as the app already does (`TmdbCredential.from`).
    - Save and Remove buttons.
  - **OpenSubtitles card:**
    - What it adds: English subtitles for titles that don't have their own.
    - "Create an API key at **opensubtitles.com**".
    - Fields: API key, username and password.
    - The explanation, roughly: "The API key lets GoodTV search for subtitles. Your OpenSubtitles username and password are needed to download them: without signing in, downloads are limited to a small daily allowance." Avoid quoting exact limits, which OpenSubtitles changes.
    - Save and Remove buttons.
  - **A status badge on each card:**
    - **Not set up**
    - **Checking…**
    - **Connected**
    - **Rejected**, with the plain message
    - **Couldn't check:** "Couldn't reach TMDB. Saved; it will be used when the connection is back."
  - **Saved secrets are never shown in full.** The key shows as dots plus its last 4 characters, and the password as dots. Editing a field replaces its value.
  - **The phone hint sits under the intro.**
  - **TMDB check:** one cheap authenticated call.
  - **OpenSubtitles check:**
    - The key is checked with a cheap call.
    - When a username and password are given, a login is also made, so a wrong password is caught on Save.
    - A key with no username or password is allowed, with the small allowance.
  - **Rejected values are not saved.** The fields keep what was typed, so it can be corrected.
  - **OpenSubtitles saves all or nothing.**
    - The key is checked first, then the login. Save stores the card's values only when everything given is accepted.
    - If the key is good but the login is rejected, nothing is saved, and the message names the login.
    - A username without a password, or the reverse, asks for both before checking.
    - A username and password without a key asks for the key.
  - **Couldn't check:** when the service can't be reached, the values are saved and the badge says so. The next successful or rejected call updates the badge.
- **What the badges show later**
  - The Settings status line and the card badges show the last known result, which is saved with the values.
  - Nothing is re-checked just for opening the screen.
  - The status updates itself in two cases:
    - A real TMDB or OpenSubtitles call later fails because the service rejects the key or login (for example, a revoked key). The status becomes "Rejected", with the same plain message, so a key that stops working shows up in Settings instead of failing silently.
    - A call succeeds after "Couldn't check". The status becomes "Connected".
- **Taking effect**
  - New or removed keys apply at once, without restarting the app.
  - TMDB: posters, ratings, people, popular rows and the genre fetcher start or stop.
  - OpenSubtitles: the next subtitle search uses the new values.
  - Removing a key turns that service off, as with no key today. Cached TMDB data may still show until it expires; that's acceptable.
- **Storage**
  - The values are saved on the device in the app's private storage, like source passwords today.
  - **They're kept out of cloud backup and device transfer**, as source credentials already are. GoodTV's app settings live in DataStore files, which today's backup rules don't exclude, so the rules must cover wherever these values live.
  - Nothing is ever logged.
- **Development**
  - The `GOODTV_TMDB_API_KEY` and `GOODTV_OPENSUBTITLES_*` entries in `local.properties` stop flowing into the app (no BuildConfig fields). They stay usable by tests that need them, such as the opt-in live TMDB test.
  - Dave enters his keys once on each device, using the phone keyboard.
  - The debug-only developer Xtream source in `local.properties` is outside this change.

## Goals, non-goals, anti-goals

- **Goals:**
  - Someone else can install GoodTV, open Settings → Integrations, follow the domains to create keys, paste them from their phone, and see "Connected".
  - Sources remains one press from Settings.
- **Non-goals:**
  - A QR-code or web-page way to enter keys.
  - Other settings (playback, appearance, language).
  - Building TMDB or OpenSubtitles account creation into the app.
  - Moving the developer Xtream source.
- **Anti-goals:**
  - A key or password appears in full on screen, in logs, or in a cloud backup.
  - Any APK contains Dave's keys.
  - A wrong key saves quietly and the app silently stops showing posters or subtitles.
  - Entering a key needs an app restart.
  - Existing Sources behaviour or first-run setup gets harder.

## Constraints

- **Database:** Room is at v13. If storage needs a table, it needs a real migration; settings storage (DataStore) avoids that.
- **Testing:**
  - TV focus gets instrumented tests. Request focus from `onPlaced`.
  - Back up and restore Dave's emulator data around connected runs.
  - Tests that enter keys use fake TMDB / OpenSubtitles servers; real keys never go into test sources.
- **Dave's devices:** after this ships, his emulator and Streamer have no TMDB or OpenSubtitles until he enters keys in Integrations.

## Left to the builder

- The exact TMDB and OpenSubtitles check calls (cheap, authenticated).
- The storage mechanism (DataStore or encrypted preferences), within the backup-exclusion rule.
- How app components pick up changed keys (for example, a flow of current credentials that rebuilds the API clients).
- Test seams, as the builder's call: keep the existing test switches (`GoodTvApp.disableTmdb` / `disableOpenSubtitles` and the `tmdbOverride` / `peopleOverride` fields) so existing tests still start with services off. Add a way for Integrations tests to point the check calls at fake servers.
- Exact copy beyond the strings Dave fixed: the domains, the hint and the rejection messages.
