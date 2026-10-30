# GoodTV

GoodTV is the IPTV app I want for myself. It's free and supports the features I use. It's not available on the Play store yet, but you can sideload the APK or build it yourself from the source code.

GoodTV is still in heavy development, and I make changes as I think of new features or find things that annoy me. If you have a feature request or bug report, create an issue in Github. It's only been tested with my IPTV provider, so you will likely see issues with some other providers. These should be quick fixes.

## Features

### Home screen
- The home screen shows your saved and in-progress content so you can quickly get to what you want.
- Popular shows and movies also show on your home screen, so you don't have to wade through a hundred movies or shows if you don't want to.

### Live TV 
- Live TV has a full program guide with EPG support. On-guide live previews are optional (settings screen).
- Save your favourite TV channels so they show up at the top.
- Filter Live TV by channel category: network (eg ABC, CBS, etc), news, sports, etc).
- Search all Live TV channels by show name.
- Channel grouping. Eg instead of seeing 150 ABC network channels, it'll show you a few major ones from different time zones, then collapse the rest so they stay hidden until you want to see them.

### Sports
- Dynamically generates a sports screen from your playlist, so if your provider doesn't have its own group for Sports, you still get a dedicated sports feed.
- Uses ESPN's website to find all of today's games for major US sports, then collects all the channels each one is on.
- Click the game you want to see, then select from the channels the game is showing on.
- Supports NHL, NBA, NFL, and MLB. Will support more soon (or now, if I forget to update this readme). The goal is to let users select their preferred leagues in the settings menu, so you don't see leagues you're not interested in.

### Movies and Shows
- Filter by genre or do a text search across all your categories.
- Displays ratings, movie summary, actors, director, etc. from TMDB (add your free TMDB API key in the settings)
- Click on any of the names to see all of their movies and shows. Eg click on "Tom Hanks" from the Castaway summary, and you'll see all of his movies.
- Uses subtitles from your provider, but also loads them from OpenSubtitles if your provider doesn't have them. Add your free OpenSubtitles API key in settings.
- Add movies and shows to your watchlist, which displays on the home screen

### Sources
- If you don't use a third party app to edit your playlist, the app has a quick way to do it. I like to "clear all", then select the few categories I want. You don't need to spend a long time unchecking all the foreign content. I tried to make it quick and easy, but I will probably improve this again next time I switch providers.
- All your sources appear as a merged library. More options to merge or switch sources will be added later.
- Sources are refreshed and synced in the background, so it doesn't block you from using the app. You can choose to manually sync if you want.
- Only supports XStream right now because that's what I use. M3U is planned, but not my top priority unless requested.



# Information for developers or if you want to build your own apk

Dev platform: Kotlin, Jetpack Compose for TV, Media3 ExoPlayer.

## Prerequisites

- JDK 17+ on `PATH` (the project builds with Homebrew OpenJDK 21). If Gradle picks the wrong Java, set `JAVA_HOME`, for example to Android Studio's bundled runtime:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Android SDK with `ANDROID_HOME` set (platform 37, build-tools 36+, platform-tools, emulator, cmdline-tools).
- Android Studio is optional. The project opens directly in it; the Gradle wrapper is the source of truth.

## Build

```sh
./gradlew :app:assembleDebug      # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug          # includes Android TV manifest checks
```

## Google TV emulator

One-time setup (the system image is roughly 1 GB):

```sh
sdkmanager --install "system-images;android-34;google-tv;arm64-v8a"
avdmanager create avd -n GoogleTV_API34 -k "system-images;android-34;google-tv;arm64-v8a" -d tv_1080p
```

API 34 matches the Android 14 firmware the Google TV Streamer currently ships. A `google-tv` API 36 image is also available if you want a newer OS.

Run:

```sh
emulator -avd GoogleTV_API34 &          # add -no-window -no-audio for headless
adb wait-for-device
./gradlew :app:installDebug
adb shell am start -n com.keyspaceapps.goodtv/.MainActivity
./gradlew :app:connectedDebugAndroidTest
```

Useful while poking at it: `adb shell input keyevent KEYCODE_DPAD_DOWN` / `KEYCODE_DPAD_CENTER` / `KEYCODE_BACK`, and `adb exec-out screencap -p > shot.png`.

## Sources and the fake Xtream panel

The on-TV Sources tab is where real providers go: **Add source → Nickname / Server URL / Username / Password → Test & save**. The server URL can be typed without a scheme (`panel.example:8080`).

For development there is a stdlib-only fake panel in `tools/fake-xtream/` that serves a fixed catalog and redirects streams to public HLS test streams:

```sh
python3 tools/fake-xtream/server.py --port 8089 --name Home      # first provider
python3 tools/fake-xtream/server.py --port 8090 --name Second    # optional second provider
```

Credentials are `demo` / `demo`. From the emulator the host is `http://10.0.2.2:<port>`.

To skip typing on the emulator, debug builds read a prefilled source from `local.properties` (gitignored) and insert it once into an empty library:

```properties
GOODTV_XTREAM_SERVER=http://10.0.2.2:8089
GOODTV_XTREAM_USERNAME=demo
GOODTV_XTREAM_PASSWORD=demo
```

Release builds compile these as empty. Instrumented tests always start with no sources.

Stream format: live channels use `.ts` when the panel lists it in `allowed_output_formats` (most reliable on real panels), otherwise `.m3u8`. Movies and episodes use the panel's `container_extension`.

## Categories

Real providers expose hundreds of categories. On the Sources tab, each source has a **Categories** button: a per-source checklist split into Live / Movies / Series, grouped by the provider's name prefixes (`AR|`, `FR -`, first word). Everything is on by default; untick a category, or press a group header to hide a whole block in one go; **Select all** / **Clear all** work per content type and the prefix chips jump to a block. Choices save instantly, survive refreshes, and hidden categories (and their content) disappear from Live, Movies and Series. Only switched-off categories are stored, so new categories from the provider appear switched on.

## Real Google TV Streamer (adb over Wi-Fi)

1. On the TV: Settings → System → About → highlight **Android TV OS build** and press Select 7 times to unlock Developer options.
2. Settings → System → Developer options → turn on **Network debugging** (sometimes labelled USB debugging). Note the IP address shown.
3. On your Mac: `adb connect <tv-ip>:5555`, then accept the authorization prompt on the TV (tick "Always allow").
4. `./gradlew :app:installDebug` installs to the connected Streamer like any other device.

Enabling **Stay awake** in Developer options avoids the connection dropping when the device sleeps.

## Layout

```
app/src/main/java/com/keyspaceapps/goodtv/
  GoodTvApp.kt, AppGraph.kt       Application + manual dependency graph
  domain/Models.kt                Channel, Movie, Series, Episode, Catalog, PlayableItem, ...
  data/source/                    SourceConfig (per-type settings), ChannelSource interface, SourceRegistry
  data/xtream/                    Xtream Codes client: lenient DTOs, URL building, API, mapping
  data/db/                        Room cache (entities, DAOs, RoomLibraryStore)
  data/repo/                      LibraryStore interface, SyncManager, LibraryRepository
  data/settings/, data/debug/     DataStore settings, debug prefilled source
  ui/nav/                         Navigation 3 keys and NavDisplay
  ui/home/, ui/live/, ui/movies/, ui/series/, ui/sources/, ui/browse/, ui/player/
tools/fake-xtream/                fake Xtream panel for development
docs/superpowers/                 design spec and implementation plan
```

Tests: `./gradlew :app:testDebugUnitTest` (JVM: parsing, URLs, sync, view models) and `./gradlew :app:connectedDebugAndroidTest` (emulator: Room, Sources flow against a MockWebServer, Live/Movies/Series screens).
