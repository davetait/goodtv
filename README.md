# GoodTV

Android TV / Google TV IPTV player, built for the Google TV Streamer. Kotlin, Jetpack Compose for TV, Media3 ExoPlayer.

Add one or more **sources** (Xtream Codes today, M3U later) and browse one merged library: Live (category sidebar + channel list), Movies and Series (category rows with "See all" grids and detail pages). Everything is cached in Room, so the app opens from cache and refreshes sources in the background when they are older than 12 hours.

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

The Streamer has no USB data port, so debugging is over the network. These steps come from community guides rather than Google's own docs, so menu names may differ slightly by firmware:

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

## Next

- M3U playlist sources (implement `ChannelSource` + a form).
- EPG (now/next), search, resume position, favourites.
