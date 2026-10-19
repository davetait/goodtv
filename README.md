# GoodTV

Android TV / Google TV IPTV player, built for the Google TV Streamer. Kotlin, Jetpack Compose for TV, Media3 ExoPlayer.

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
  MainActivity.kt        two-screen state: channel list or player
  model/Channel.kt       Channel data class + sample public test streams
  ui/HomeScreen.kt       focusable channel list (Compose for TV ListItem)
  ui/PlayerScreen.kt     full-screen ExoPlayer via media3-ui-compose
  ui/theme/Theme.kt      dark TV colour scheme
app/src/androidTest/     Compose UI test for focus and D-pad behaviour
```

## Next

- Xtream Codes playlist source (server, username, password) behind a `ChannelRepository`.
- Player controls overlay, channel switching with D-pad up/down while playing, EPG.
