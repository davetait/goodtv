# Multi-source library with Xtream Codes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn GoodTV from three hard-coded streams into a multi-source IPTV client whose first source type is Xtream Codes, with a cached, merged library of live channels, movies, and series browsable and playable on a Google TV.

**Architecture:** A `ChannelSource` interface (Xtream now, M3U later) produces a whole-source `Catalog`; `SyncManager` swaps each source's rows in Room atomically; the UI reads only from Room via Flows. Navigation 3 drives six screens under a tv-material `TabRow`. Manual DI through one `AppGraph` on the `Application`.

**Tech Stack:** Kotlin (AGP 9.4 built-in Kotlin, KGP 2.2.10), Compose for TV (tv-material 1.1.0), Media3 1.11.1, Room 2.8.5 + KSP 2.2.10-2.0.2, kotlinx.serialization 1.11.0 (plugin 2.2.10), OkHttp 5.5.0, DataStore 1.2.1, Coil 3.6.3, Navigation 3 1.2.0, lifecycle 2.11.0. Tests: JUnit4, kotlinx-coroutines-test 1.11.0, mockwebserver3 5.5.0, Compose UI test.

**Spec:** `docs/superpowers/specs/2026-10-19-multi-source-xtream-design.md`

## Global Constraints

- New Gradle plugins must match KGP 2.2.10: `org.jetbrains.kotlin.plugin.serialization` 2.2.10, `com.google.devtools.ksp` 2.2.10-2.0.2.
- minSdk 28, compileSdk/targetSdk 37. Package `com.keyspaceapps.goodtv`.
- All Xtream IDs are `String` in domain and DTOs; every numeric-looking field parses whether the panel sends a string or a number; unknown JSON keys are ignored.
- Stream URLs are built from the server URL the user typed. Live: `.ts` if `allowedOutputFormats` contains `"ts"`, else `.m3u8`. Movie: `/movie/{U}/{P}/{id}.{ext}`. Episode: `/series/{U}/{P}/{id}.{ext}`.
- Categories are never merged across sources. Row/sidebar labels are `"{category} · {nickname}"` when more than one source is enabled, else just `"{category}"`.
- Refresh a source when the app foregrounds and its `lastSyncAt` is older than 12 h (43 200 000 ms), after add/edit, or on manual Refresh. A failed refresh keeps the old rows and records `lastError`.
- Database excluded from cloud backup. Credentials never logged.
- Player HTTP data source allows cross-protocol redirects. Both OkHttp and the player send `User-Agent: GoodTV/0.2`.
- Debug builds read `GOODTV_XTREAM_SERVER`, `GOODTV_XTREAM_USERNAME`, `GOODTV_XTREAM_PASSWORD` from `local.properties` into `BuildConfig`; release values are empty strings. Prefill is skipped when instrumentation argument `goodtv.skipDebugSource=true`.
- Commits go on the current branch `davetait/google-tv-streamer-app`; one commit per task.

## Review Focus

1. Server URL typed as `my.panel.com:8080/` or `panel.com/some/path/` (no scheme, trailing slash, path): URL building must yield `http://my.panel.com:8080/player_api.php` and stream URLs under the same base. Test in Task 4.
2. Panel returns `episodes` as a JSON array of per-season arrays instead of a map keyed by season: parsing must still produce episodes with correct season numbers. Test in Task 3.
3. Panel answers HTTP 200 with `"auth": 0` for bad credentials, or an HTML error page with HTTP 200/503: the form must show a readable message ("Invalid username or password" / "Server returned an unexpected response"), never crash. Tests in Task 5.
4. A source that times out during refresh while another succeeds: the good source's rows update, the bad source's old rows stay, `SyncState` reports the one error. Test in Task 8.
5. First launch with zero sources: app opens on the Sources tab with "Add source" focused; adding a source lands back on Sources showing counts, and Live becomes non-empty without restart. Test in Task 9.

---

## File map

```
app/build.gradle.kts                                  plugins, deps, BuildConfig fields, buildConfig=true
gradle/libs.versions.toml                             new versions/libraries/plugins
app/src/main/AndroidManifest.xml                      application name, backup rules
app/src/main/res/xml/backup_rules.xml, data_extraction_rules.xml
app/src/main/java/com/keyspaceapps/goodtv/
  GoodTvApp.kt                                        Application; holds AppGraph
  AppGraph.kt                                         manual DI: json, okhttp, db, store, registry, repository, syncManager, settings
  domain/Models.kt                                    ContentKind, Category, LiveChannel, Movie, Series, Season, Episode, MovieDetail, SeriesDetail, Catalog, PlayableRef, PlayableItem, SourceInfo, SourceRecord, SourceSummary, CategoryWithSource
  data/source/SourceType.kt, SourceConfig.kt, ChannelSource.kt, SourceRegistry.kt
  data/xtream/XtreamDtos.kt, FlexibleSerializers.kt, XtreamJson.kt, XtreamUrls.kt, XtreamApi.kt, XtreamChannelSource.kt, XtreamMappers.kt
  data/db/Entities.kt, Daos.kt, GoodTvDatabase.kt, RoomLibraryStore.kt, Mappers.kt
  data/repo/LibraryStore.kt, SyncManager.kt, SyncState.kt, LibraryRepository.kt
  data/settings/AppSettings.kt
  data/debug/DebugSource.kt
  ui/nav/NavKeys.kt, GoodTvNavigation.kt
  ui/home/HomeScreen.kt                               TabRow shell (replaces old HomeScreen)
  ui/sources/SourcesScreen.kt, SourcesViewModel.kt, EditSourceScreen.kt, EditSourceViewModel.kt, TvTextField.kt
  ui/live/LiveScreen.kt, LiveViewModel.kt
  ui/browse/CategoryRows.kt, PosterCard.kt, CategoryGridScreen.kt, CategoryGridViewModel.kt, Labels.kt
  ui/movies/MoviesScreen.kt, MoviesViewModel.kt, MovieDetailScreen.kt, MovieDetailViewModel.kt
  ui/series/SeriesScreen.kt, SeriesViewModel.kt, SeriesDetailScreen.kt, SeriesDetailViewModel.kt
  ui/player/PlayerScreen.kt (modify), PlayerFactory.kt, PlayerKeys.kt
  ui/ViewModelFactory.kt                              `goodTvViewModel { graph -> VM }` helper
tools/fake-xtream/server.py, README.md
app/src/test/java/com/keyspaceapps/goodtv/...         JVM tests
app/src/test/resources/xtream/*.json                  fixtures
app/src/androidTest/java/com/keyspaceapps/goodtv/...  instrumented tests
```

Delete `model/Channel.kt` (replaced by `domain/Models.kt`) in Task 2.

---

### Task 1: Build setup, Application, backup rules

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/keyspaceapps/goodtv/GoodTvApp.kt`, `AppGraph.kt`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/BuildConfigTest.kt`

**Interfaces:**
- Produces: `class GoodTvApp : Application { val graph: AppGraph }`; `class AppGraph(context: Context)` with lazy members filled in by later tasks (start with `val json: Json`, `val okHttp: OkHttpClient`); `BuildConfig.XTREAM_SERVER/XTREAM_USERNAME/XTREAM_PASSWORD: String`; helper `fun Context.appGraph(): AppGraph`.

- [ ] **Step 1: Add versions, libraries, plugins to `libs.versions.toml`**

Versions: `ksp = "2.2.10-2.0.2"`, `kotlinSerializationPlugin = "2.2.10"`, `kotlinxSerialization = "1.11.0"`, `room = "2.8.5"`, `datastore = "1.2.1"`, `okhttp = "5.5.0"`, `coil = "3.6.3"`, `navigation3 = "1.2.0"`, `coroutinesTest = "1.11.0"`. Libraries: `kotlinx-serialization-json`, `androidx-room-runtime`, `androidx-room-ktx`, `androidx-room-compiler`, `androidx-datastore-preferences`, `okhttp`, `okhttp-mockwebserver3` (`com.squareup.okhttp3:mockwebserver3`), `coil-compose` (`io.coil-kt.coil3:coil-compose`), `coil-network-okhttp`, `androidx-navigation3-runtime`, `androidx-navigation3-ui`, `androidx-lifecycle-viewmodel-compose`, `androidx-lifecycle-viewmodel-navigation3`, `kotlinx-coroutines-test`. Plugins: `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlinSerializationPlugin" }`, `ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }`.

- [ ] **Step 2: Apply plugins and dependencies in `app/build.gradle.kts`; add BuildConfig fields**

Apply `kotlin.serialization` and `ksp` (also `apply false` in root `build.gradle.kts`). `buildFeatures { compose = true; buildConfig = true }`. Read `local.properties` with `java.util.Properties` (file may not exist); in `defaultConfig`, `buildConfigField("String", "XTREAM_SERVER", "\"${props.getProperty("GOODTV_XTREAM_SERVER", "")}\"")` and the two others; in `buildTypes.release`, override all three to `"\"\""`. Dependencies: `implementation` for serialization, room-runtime, room-ktx, datastore, okhttp, coil-compose, coil-network-okhttp, nav3 runtime+ui, viewmodel-compose, viewmodel-navigation3; `ksp(libs.androidx.room.compiler)`; `testImplementation` junit, coroutines-test, mockwebserver3, kotlinx-serialization-json; `androidTestImplementation` mockwebserver3, coroutines-test, room-runtime (already transitive).

- [ ] **Step 3: Write `BuildConfigTest`**

```kotlin
class BuildConfigTest {
    @Test fun debugFieldsExistAndAreStrings() {
        assertNotNull(BuildConfig.XTREAM_SERVER); assertNotNull(BuildConfig.XTREAM_USERNAME); assertNotNull(BuildConfig.XTREAM_PASSWORD)
    }
}
```

- [ ] **Step 4: Create `GoodTvApp`, `AppGraph`, backup rules, manifest changes**

`GoodTvApp.onCreate` builds `AppGraph(this)`. `AppGraph` exposes `val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }` and `val okHttp = OkHttpClient.Builder().connectTimeout(15s).readTimeout(30s).addInterceptor { User-Agent: GoodTV/0.2 }.build()`. `fun Context.appGraph() = (applicationContext as GoodTvApp).graph`. Manifest: `android:name=".GoodTvApp"`, `android:fullBackupContent="@xml/backup_rules"`, `android:dataExtractionRules="@xml/data_extraction_rules"`. Both XML files exclude `domain="database"` (and `sharedpref`), for `cloud-backup` and `device-transfer` in the extraction rules.

- [ ] **Step 5: Build and run unit test**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL; `BuildConfigTest` passes. If KSP or the serialization plugin fails under built-in Kotlin, record the error; fallback is `android.builtInKotlin=false` plus `org.jetbrains.kotlin.android` 2.2.10 (do not take it without checking the error first).

- [ ] **Step 6: Commit** `build: add serialization, KSP/Room, OkHttp, DataStore, Coil, Nav3; Application + backup rules`

---

### Task 2: Domain models and source abstraction

**Files:**
- Create: `domain/Models.kt`, `data/source/SourceType.kt`, `SourceConfig.kt`, `ChannelSource.kt`, `SourceRegistry.kt`
- Delete: `model/Channel.kt` (update `MainActivity`/`HomeScreen`/`PlayerScreen` minimally so the build passes; they are rewritten in Tasks 9–10)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/source/SourceConfigTest.kt`

**Interfaces (Produces):**

```kotlin
enum class ContentKind { LIVE, MOVIE, SERIES }
data class Category(val sourceId: Long, val kind: ContentKind, val id: String, val name: String)
data class LiveChannel(val sourceId: Long, val id: String, val categoryId: String?, val name: String, val logoUrl: String?, val number: Int?)
data class Movie(val sourceId: Long, val id: String, val categoryId: String?, val name: String, val posterUrl: String?, val containerExtension: String, val rating: Double?, val addedAt: Long?)
data class Series(val sourceId: Long, val id: String, val categoryId: String?, val name: String, val coverUrl: String?, val plot: String?, val rating: Double?)
data class Catalog(val categories: List<Category>, val live: List<LiveChannel>, val movies: List<Movie>, val series: List<Series>)
data class MovieDetail(val movie: Movie, val plot: String?, val genre: String?, val cast: String?, val director: String?, val releaseDate: String?, val durationSecs: Int?, val backdropUrl: String?)
data class Season(val number: Int, val name: String, val coverUrl: String?)
data class Episode(val sourceId: Long, val seriesId: String, val id: String, val season: Int, val number: Int, val title: String, val containerExtension: String, val durationSecs: Int?, val plot: String?, val imageUrl: String?)
data class SeriesDetail(val series: Series, val plot: String?, val genre: String?, val cast: String?, val releaseDate: String?, val backdropUrl: String?, val seasons: List<Season>, val episodes: List<Episode>)
sealed interface PlayableRef { data class Live(val id: String): PlayableRef; data class Movie(val id: String, val ext: String): PlayableRef; data class Episode(val id: String, val ext: String): PlayableRef }
data class PlayableItem(val title: String, val url: String, val isLive: Boolean)
data class SourceInfo(val status: String, val expiresAt: Long?, val maxConnections: Int?, val allowedOutputFormats: List<String>)
data class SourceRecord(val id: Long, val type: SourceType, val nickname: String, val config: SourceConfig, val enabled: Boolean, val lastSyncAt: Long?, val lastError: String?)
data class SourceSummary(val id: Long, val nickname: String, val type: SourceType, val liveCount: Int, val movieCount: Int, val seriesCount: Int, val lastSyncAt: Long?, val lastError: String?)
data class CategoryWithSource(val category: Category, val sourceNickname: String)

enum class SourceType { XTREAM }   // M3U later
@Serializable sealed class SourceConfig {
    @Serializable @SerialName("xtream")
    data class Xtream(val server: String, val username: String, val password: String, val allowedOutputFormats: List<String> = emptyList()) : SourceConfig()
    val type: SourceType get() = when (this) { is Xtream -> SourceType.XTREAM }
}
object SourceConfigJson { fun encode(json: Json, c: SourceConfig): String; fun decode(json: Json, s: String): SourceConfig }

interface ChannelSource {
    val type: SourceType
    suspend fun validate(config: SourceConfig): Result<SourceInfo>
    suspend fun fetchCatalog(sourceId: Long, config: SourceConfig): Result<Catalog>
    suspend fun fetchMovieDetail(sourceId: Long, config: SourceConfig, movieId: String): Result<MovieDetail>
    suspend fun fetchSeriesDetail(sourceId: Long, config: SourceConfig, seriesId: String): Result<SeriesDetail>
    fun streamUrl(config: SourceConfig, ref: PlayableRef): String
}
class SourceRegistry(sources: List<ChannelSource>) { fun forType(type: SourceType): ChannelSource }
class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)   // user-readable message
```

- [ ] **Step 1: Write `SourceConfigTest`**

`xtreamRoundTripsThroughJson` (encode then decode equals original, including `allowedOutputFormats = listOf("ts","m3u8")`); `decodeFailsOnUnknownType` (string `{"type":"bogus"}` throws `SerializationException`).

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*SourceConfigTest*'` → FAIL (unresolved references).

- [ ] **Step 3: Create the files with the signatures above; delete `model/Channel.kt`; patch `MainActivity`/`HomeScreen`/`PlayerScreen` to use `PlayableItem` with the three test streams**

`SourceRegistry.forType` throws `IllegalArgumentException("No source for $type")` when missing.

- [ ] **Step 4: Run tests and build**

Run: `./gradlew :app:testDebugUnitTest --tests '*SourceConfigTest*' :app:assembleDebug` → PASS, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** `feat: domain models, SourceConfig, ChannelSource interface`

---

### Task 3: Xtream DTOs with lenient parsing

**Files:**
- Create: `data/xtream/FlexibleSerializers.kt`, `XtreamDtos.kt`, `XtreamJson.kt`
- Create fixtures: `app/src/test/resources/xtream/auth_ok.json`, `auth_bad.json`, `live_categories.json`, `live_streams_mixed.json`, `vod_streams_mixed.json`, `vod_info.json`, `series.json`, `series_info_map.json`, `series_info_array.json`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/xtream/XtreamDtosTest.kt`

**Interfaces (Produces):**

```kotlin
object FlexStringSerializer : KSerializer<String?>      // accepts string, number, bool, null → String?
object FlexIntSerializer : KSerializer<Int?>            // "12", 12, 12.0, "" → Int?
object FlexDoubleSerializer : KSerializer<Double?>
object FlexLongSerializer : KSerializer<Long?>
object FlexStringListSerializer : KSerializer<List<String>>  // array, single string, or absent → list
typealias FlexString = @Serializable(FlexStringSerializer::class) String?   // use annotation form on properties

@Serializable data class XtAuthResponse(val user_info: XtUserInfo? = null, val server_info: XtServerInfo? = null)
@Serializable data class XtUserInfo(auth: Int?(flex), status: String?, exp_date: Long?(flex), max_connections: Int?(flex), allowed_output_formats: List<String>(flex list), message: String?)
@Serializable data class XtServerInfo(url: String?, port: String?(flex), https_port: String?(flex), server_protocol: String?)
@Serializable data class XtCategory(category_id: String?(flex), category_name: String?, parent_id: Int?(flex))
@Serializable data class XtLiveStream(stream_id: String?(flex), name: String?, stream_icon: String?, category_id: String?(flex), epg_channel_id: String?, num: Int?(flex))
@Serializable data class XtVodStream(stream_id: String?(flex), name: String?, stream_icon: String?, category_id: String?(flex), container_extension: String?, rating: Double?(flex), added: Long?(flex))
@Serializable data class XtVodInfoResponse(val info: XtVodInfo? = null, val movie_data: XtVodMovieData? = null)
@Serializable data class XtVodInfo(movie_image, plot, genre, cast, director, releasedate, duration_secs: Int?(flex), rating: Double?(flex), backdrop_path: List<String>(flex list))
@Serializable data class XtVodMovieData(stream_id: String?(flex), name, container_extension, category_id: String?(flex))
@Serializable data class XtSeries(series_id: String?(flex), name, cover, plot, cast, genre, releaseDate, rating: Double?(flex), category_id: String?(flex), backdrop_path: List<String>(flex list))
@Serializable data class XtSeriesInfoResponse(val info: XtSeriesInfo? = null, val seasons: List<XtSeason> = emptyList(), @Serializable(EpisodesSerializer::class) val episodes: List<XtEpisode> = emptyList())
@Serializable data class XtSeason(season_number: Int?(flex), name, cover)
@Serializable data class XtEpisode(id: String?(flex), episode_num: Int?(flex), title, container_extension, season: Int?(flex), info: XtEpisodeInfo?)
@Serializable data class XtEpisodeInfo(movie_image, plot, duration_secs: Int?(flex))
object EpisodesSerializer : KSerializer<List<XtEpisode>>   // map {"1":[...]} or array [[...],[...]]; fills episode.season from the map key when absent
object XtreamJson { val instance: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false } }
```

- [ ] **Step 1: Write fixtures** — copy the spec's example JSON, then make `live_streams_mixed.json` contain one entry with `"stream_id": 123, "category_id": "1"` and another with `"stream_id": "124", "category_id": 2, "num": "7"`; `vod_streams_mixed.json` with `rating` as `"8.5"` in one and `8.5` in another, `rating_5based` as string; `series_info_map.json` with `"episodes": {"1":[...],"2":[...]}` where season-2 episodes omit `"season"`; `series_info_array.json` with `"episodes": [[...],[...]]`; `auth_bad.json` with `"auth": 0, "status": "Disabled"`; `vod_info.json` with `backdrop_path` as a bare string.

- [ ] **Step 2: Write `XtreamDtosTest`**

Tests (load fixture via `javaClass.getResource("/xtream/x.json").readText()`):
`liveStreamsParseMixedTypes` (both IDs become "123"/"124", `num` 7), `vodRatingParsesStringOrNumber` (both 8.5), `authBadParses` (`auth == 0`), `authOkAllowedFormats` (`["ts","m3u8"]`), `seriesInfoMapFillsSeasonFromKey` (season-2 episodes have `season == 2`), `seriesInfoArrayParses` (episodes count equals sum of inner arrays; seasons from index+1), `vodInfoBackdropSingleString` (list of one), `unknownKeysIgnored` (extra `"foo": {}` in a category parses).

- [ ] **Step 3: Run, expect failure** `./gradlew :app:testDebugUnitTest --tests '*XtreamDtosTest*'` → FAIL.

- [ ] **Step 4: Implement serializers and DTOs** — each Flex serializer uses `JsonDecoder.decodeJsonElement()` and handles `JsonPrimitive`/`JsonNull`; `EpisodesSerializer` decodes a `JsonElement` and branches on `JsonObject` vs `JsonArray`.

- [ ] **Step 5: Run tests** → PASS.

- [ ] **Step 6: Commit** `feat(xtream): lenient DTOs and fixtures`

---

### Task 4: Xtream URL building

**Files:**
- Create: `data/xtream/XtreamUrls.kt`
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/xtream/XtreamUrlsTest.kt`

**Interfaces (Produces):**

```kotlin
object XtreamUrls {
    fun normalizeServer(input: String): String              // adds http:// if no scheme, strips trailing slashes, keeps port and path, trims whitespace
    fun api(cfg: SourceConfig.Xtream, action: String? = null, params: Map<String, String> = emptyMap()): HttpUrl
    fun stream(cfg: SourceConfig.Xtream, ref: PlayableRef): String
}
```

- [ ] **Step 1: Write `XtreamUrlsTest`**

`normalizeAddsSchemeAndStripsSlash` (`"my.panel.com:8080/"` → `"http://my.panel.com:8080"`), `normalizeKeepsHttpsAndPath` (`"https://p.com/base/"` → `"https://p.com/base"`), `apiUrlHasCredentialsAndAction` (query contains `username=u&password=p&action=get_live_streams&category_id=3`), `liveUsesTsWhenAllowed` (`allowedOutputFormats = ["ts","m3u8"]` → ends `/live/u/p/123.ts`), `liveFallsBackToM3u8` (`["m3u8"]` and `[]` → `.m3u8`), `movieAndEpisodeUrls` (`/movie/u/p/456.mkv`, `/series/u/p/1001.mp4`), `credentialsAreUrlEncoded` (username `"a b@c"` → `a%20b%40c` in both api and stream paths).

- [ ] **Step 2: Run, expect failure.** `./gradlew :app:testDebugUnitTest --tests '*XtreamUrlsTest*'`

- [ ] **Step 3: Implement** using `okhttp3.HttpUrl` for `api` and manual string assembly with `URLEncoder`-style path-segment encoding for `stream`.

- [ ] **Step 4: Run tests** → PASS. **Step 5: Commit** `feat(xtream): URL building`

---

### Task 5: XtreamApi and XtreamChannelSource

**Files:**
- Create: `data/xtream/XtreamApi.kt`, `XtreamChannelSource.kt`, `XtreamMappers.kt`
- Modify: `AppGraph.kt` (add `val sourceRegistry = SourceRegistry(listOf(XtreamChannelSource(XtreamApi(okHttp, XtreamJson.instance))))`)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/xtream/XtreamChannelSourceTest.kt` (MockWebServer)

**Interfaces (Produces):**

```kotlin
class XtreamApi(private val client: OkHttpClient, private val json: Json, private val io: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun auth(cfg: SourceConfig.Xtream): XtAuthResponse
    suspend fun liveCategories(cfg): List<XtCategory>; vodCategories(cfg); seriesCategories(cfg)
    suspend fun liveStreams(cfg): List<XtLiveStream>; vodStreams(cfg): List<XtVodStream>; series(cfg): List<XtSeries>
    suspend fun vodInfo(cfg, vodId: String): XtVodInfoResponse; seriesInfo(cfg, seriesId: String): XtSeriesInfoResponse
}
class XtreamChannelSource(private val api: XtreamApi) : ChannelSource   // type = XTREAM
object XtreamMappers { fun toCatalog(sourceId: Long, cats: Triple<...>, live, vod, series): Catalog; fun toMovieDetail(...); fun toSeriesDetail(...) }
```

Error mapping (in `XtreamApi.get`): non-2xx → `SourceException("Server returned HTTP ${code}")`; body that is not JSON (`SerializationException`/`IllegalArgumentException`) → `SourceException("Server returned an unexpected response")`; `IOException` → `SourceException("Could not reach server: ${e.message}")`. `validate`: `auth != 1` → `SourceException("Invalid username or password")`. `validate` returns `SourceInfo(status, exp_date*1000, max_connections, allowed_output_formats)`. `fetchCatalog` runs the six list calls with `coroutineScope { async }` and maps; skips entries with null `stream_id`/`series_id`/`category_id`.

- [ ] **Step 1: Write `XtreamChannelSourceTest`** with `MockWebServer` (mockwebserver3) and `runTest`; config server = `server.url("/").toString()`.

`validateOkReturnsInfo` (enqueue `auth_ok.json` → success, `allowedOutputFormats == ["ts","m3u8"]`, request path contains `username=u&password=p`), `validateAuthZeroIsInvalidCredentials` (`auth_bad.json` → failure, message "Invalid username or password"), `validateHtmlBodyIsUnexpectedResponse` (body `<html>503</html>` code 200 → message "Server returned an unexpected response"), `validateHttp503` (→ "Server returned HTTP 503"), `fetchCatalogMapsAllSix` (dispatcher by `action` query param serving fixtures; assert counts: categories = live+vod+series category counts with correct `kind`, live channel "124" present with `sourceId == 7`, movie ext "mkv"), `fetchCatalogFailsIfAnyCallFails` (one 500 → `Result.isFailure`), `streamUrlDelegatesToXtreamUrls`.

- [ ] **Step 2: Run, expect failure.** `./gradlew :app:testDebugUnitTest --tests '*XtreamChannelSourceTest*'`
- [ ] **Step 3: Implement** `XtreamApi`, mappers, source; register in `AppGraph`.
- [ ] **Step 4: Run tests** → PASS. **Step 5: Commit** `feat(xtream): API client and ChannelSource`

---

### Task 6: Room cache and `LibraryStore`

**Files:**
- Create: `data/repo/LibraryStore.kt`, `data/db/Entities.kt`, `Daos.kt`, `GoodTvDatabase.kt`, `Mappers.kt`, `RoomLibraryStore.kt`
- Modify: `AppGraph.kt` (`val db`, `val store: LibraryStore = RoomLibraryStore(db, json)`)
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/data/db/RoomLibraryStoreTest.kt` (in-memory Room on the emulator)

**Interfaces (Produces):**

```kotlin
interface LibraryStore {
    fun observeSources(): Flow<List<SourceRecord>>
    suspend fun getSources(): List<SourceRecord>
    suspend fun getSource(id: Long): SourceRecord?
    suspend fun insertSource(record: SourceRecord): Long           // record.id ignored
    suspend fun updateSource(record: SourceRecord)
    suspend fun deleteSource(id: Long)                              // cascades
    suspend fun replaceCatalog(sourceId: Long, catalog: Catalog)    // one transaction: delete source rows (categories, live, movies, series, seasons, episodes, details), insert new
    suspend fun markSync(sourceId: Long, syncedAt: Long?, error: String?)   // syncedAt null = keep existing
    fun observeSummaries(): Flow<List<SourceSummary>>
    fun observeCategories(kind: ContentKind): Flow<List<CategoryWithSource>>   // enabled sources only, ordered by source id then category name
    fun observeLiveChannels(sourceId: Long, categoryId: String): Flow<List<LiveChannel>>
    fun observeMovies(sourceId: Long, categoryId: String, limit: Int): Flow<List<Movie>>
    fun observeSeries(sourceId: Long, categoryId: String, limit: Int): Flow<List<Series>>
    suspend fun getMovie(sourceId: Long, id: String): Movie?
    suspend fun getSeries(sourceId: Long, id: String): Series?
    suspend fun getMovieDetail(sourceId: Long, id: String): MovieDetail?
    suspend fun saveMovieDetail(detail: MovieDetail)
    suspend fun getSeriesDetail(sourceId: Long, id: String): SeriesDetail?    // null if seasons/episodes never fetched
    suspend fun saveSeriesDetail(detail: SeriesDetail)
}
```

Entities (all with `sourceId: Long` + `@ForeignKey(SourceEntity, onDelete = CASCADE)` + index): `SourceEntity(id autogen, type: String, nickname, configJson, enabled, lastSyncAt: Long?, lastError: String?, createdAt)`, `CategoryEntity(PK (sourceId, kind, id))`, `LiveChannelEntity(PK (sourceId,id); index (sourceId, categoryId))`, `MovieEntity(PK (sourceId,id); index (sourceId, categoryId))`, `SeriesEntity`, `MovieDetailEntity(PK (sourceId, movieId))`, `SeriesInfoEntity(PK (sourceId, seriesId): plot, genre, cast, releaseDate, backdropUrl)`, `SeasonEntity(PK (sourceId, seriesId, number))`, `EpisodeEntity(PK (sourceId, id); index (sourceId, seriesId))`. `GoodTvDatabase` version 1, `exportSchema = false`, `fallbackToDestructiveMigration(true)`. Summary counts via one `@Query` joining counts per source.

- [ ] **Step 1: Write `RoomLibraryStoreTest`** (`Room.inMemoryDatabaseBuilder`, `runBlocking`)

`insertAndObserveSource` (config round-trips through JSON), `replaceCatalogSwapsRowsAtomically` (insert catalog A with 2 live, then catalog B with 1 live → `observeLiveChannels` has only B's), `replaceCatalogLeavesOtherSourceAlone`, `deleteSourceCascades` (no categories/live left; summaries empty), `summariesCountPerSource` (2 live, 1 movie, 1 series), `categoriesCarryNickname` and `disabledSourceCategoriesHidden`, `seriesDetailRoundTrip` (save 2 seasons/3 episodes, get returns same; `getSeriesDetail` null before save), `movieDetailRoundTrip`.

- [ ] **Step 2: Run, expect failure** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.keyspaceapps.goodtv.data.db.RoomLibraryStoreTest` (emulator `GoogleTV_API34` running).
- [ ] **Step 3: Implement entities, DAOs, mappers, `RoomLibraryStore`**; wire into `AppGraph`.
- [ ] **Step 4: Run tests** → PASS. **Step 5: Commit** `feat(db): Room cache and LibraryStore`

---

### Task 7: Settings and debug default source

**Files:**
- Create: `data/settings/AppSettings.kt`, `data/debug/DebugSource.kt`
- Modify: `AppGraph.kt` (`val settings: AppSettings`)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/debug/DebugSourceTest.kt`

**Interfaces (Produces):**

```kotlin
class AppSettings(private val dataStore: DataStore<Preferences>) {
    val debugSourceInserted: Flow<Boolean>; suspend fun setDebugSourceInserted(v: Boolean)
    val lastTab: Flow<String?>; suspend fun setLastTab(name: String)
}
object DebugSource {
    fun fromBuildConfig(server: String, username: String, password: String, skip: Boolean): SourceRecord?   // null unless all three non-blank and !skip; nickname "Dev", id 0
}
```

How `skip` is set: `GoodTvApp` exposes `companion object { @JvmStatic var skipDebugSource = false }`. A custom runner `app/src/androidTest/java/com/keyspaceapps/goodtv/GoodTvTestRunner.kt : AndroidJUnitRunner` sets it to `true` in `onCreate(arguments)` before the Application is created, and `app/build.gradle.kts` points `testInstrumentationRunner` at it. `GoodTvApp.onCreate` passes `skipDebugSource` into `DebugSource.fromBuildConfig`. (This replaces the instrumentation-argument wording in the spec; same effect, no reflection.)

- [ ] **Step 1: Write `DebugSourceTest`**: `returnsNullWhenAnyBlank`, `returnsNullWhenSkipped`, `buildsXtreamRecord` (server normalised via `XtreamUrls.normalizeServer`, nickname "Dev").
- [ ] **Step 2: Run, expect failure.** **Step 3: Implement** `AppSettings` (preferencesDataStore "settings"), `DebugSource`, `GoodTvTestRunner`, runner wiring.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest --tests '*DebugSourceTest*' :app:assembleDebugAndroidTest` → PASS. **Step 5: Commit** `feat: settings DataStore and debug default source`

---

### Task 8: SyncManager and LibraryRepository

**Files:**
- Create: `data/repo/SyncState.kt`, `SyncManager.kt`, `LibraryRepository.kt`
- Modify: `AppGraph.kt`, `GoodTvApp.kt` (insert debug source once on startup via `repository.ensureDebugSource()`)
- Test: `app/src/test/java/com/keyspaceapps/goodtv/data/repo/SyncManagerTest.kt`, `LibraryRepositoryTest.kt` with `FakeLibraryStore` (in-memory `MutableStateFlow`s) and `FakeChannelSource` (scripted results, optional delay) in `app/src/test/java/com/keyspaceapps/goodtv/testing/`

**Interfaces (Produces):**

```kotlin
sealed interface SyncState { data object Idle : SyncState; data class Syncing(val sourceIds: Set<Long>) : SyncState; data class Error(val sourceId: Long, val message: String) : SyncState }
class SyncManager(store: LibraryStore, registry: SourceRegistry, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, staleAfterMs: Long = 43_200_000L) {
    val state: StateFlow<SyncState>
    fun refresh(sourceId: Long): Job
    fun refreshStale(): Job                                        // enabled sources with lastSyncAt null or older than staleAfterMs
    fun refreshAll(): Job
    suspend fun refreshNow(sourceId: Long): Result<Unit>           // used by tests and add/edit
}
class LibraryRepository(store: LibraryStore, registry: SourceRegistry, sync: SyncManager, settings: AppSettings) {
    val sources: Flow<List<SourceSummary>>
    val syncState: StateFlow<SyncState>
    fun categories(kind: ContentKind): Flow<List<CategoryWithSource>>
    fun liveChannels(sourceId: Long, categoryId: String): Flow<List<LiveChannel>>
    fun movies(sourceId: Long, categoryId: String, limit: Int): Flow<List<Movie>>
    fun series(sourceId: Long, categoryId: String, limit: Int): Flow<List<Series>>
    suspend fun validate(config: SourceConfig): Result<SourceInfo>
    suspend fun addSource(nickname: String, config: SourceConfig): Long          // insert, then sync.refreshNow
    suspend fun updateSource(id: Long, nickname: String, config: SourceConfig)   // update, then refreshNow
    suspend fun removeSource(id: Long)
    suspend fun movieDetail(sourceId: Long, movieId: String): Result<MovieDetail>   // cache first, else fetch+save
    suspend fun seriesDetail(sourceId: Long, seriesId: String): Result<SeriesDetail>
    suspend fun playable(sourceId: Long, ref: PlayableRef, title: String): PlayableItem
    suspend fun ensureDebugSource(record: SourceRecord?)        // insert once if store empty and settings flag false
    fun enabledSourceCount(): Flow<Int>
}
```

Concurrency rule: `refresh` for a source already in `Syncing` is a no-op returning the running job. `refreshAll` launches each source in its own coroutine via `supervisorScope`; state goes `Syncing(all ids)` → removes ids as they finish → `Error(last failing)` if any failed else `Idle`.

- [ ] **Step 1: Write `SyncManagerTest`** (`runTest`, `StandardTestDispatcher`): `successReplacesCatalogAndMarksSync`, `failureKeepsOldRowsAndRecordsError` (store still has old live rows; `lastError == "Could not reach server: boom"`; state is `Error`), `twoSourcesOneFailsOtherUpdates` (Review Focus 4), `refreshStaleSkipsFreshSources` (one synced 1 h ago, one 13 h ago → only the stale one's `fetchCatalog` called), `duplicateRefreshIsNoop`.
- [ ] **Step 2: Write `LibraryRepositoryTest`**: `addSourceInsertsThenSyncs`, `movieDetailUsesCacheSecondTime` (source called once), `ensureDebugSourceOnlyOnce`, `playableBuildsUrlFromSource`.
- [ ] **Step 3: Run, expect failure.** **Step 4: Implement.** **Step 5: Run** `./gradlew :app:testDebugUnitTest` → all PASS. **Step 6: Commit** `feat: SyncManager and LibraryRepository`

---

### Task 9: Navigation shell, Sources screen, Edit source form

**Files:**
- Create: `ui/nav/NavKeys.kt`, `GoodTvNavigation.kt`, `ui/ViewModelFactory.kt`, `ui/home/HomeScreen.kt` (replace), `ui/sources/SourcesScreen.kt`, `SourcesViewModel.kt`, `EditSourceScreen.kt`, `EditSourceViewModel.kt`, `TvTextField.kt`, `ui/browse/Labels.kt`
- Modify: `MainActivity.kt` (set content to `GoodTvNavigation`; `onStart` → `graph.syncManager.refreshStale()`), `res/values/strings.xml`
- Test: `app/src/androidTest/java/com/keyspaceapps/goodtv/ui/SourcesFlowTest.kt`, `app/src/test/.../ui/sources/EditSourceViewModelTest.kt`, `app/src/test/.../ui/browse/LabelsTest.kt`

**Interfaces (Produces):**

```kotlin
@Serializable sealed interface GoodTvKey : NavKey
@Serializable data object HomeKey : GoodTvKey
@Serializable data class PlayerKey(val sourceId: Long, val refKind: String, val refId: String, val ext: String?, val title: String) : GoodTvKey
@Serializable data class MovieDetailKey(val sourceId: Long, val movieId: String) : GoodTvKey
@Serializable data class SeriesDetailKey(val sourceId: Long, val seriesId: String) : GoodTvKey
@Serializable data class CategoryGridKey(val sourceId: Long, val kind: ContentKind, val categoryId: String, val title: String) : GoodTvKey
@Serializable data class EditSourceKey(val sourceId: Long?) : GoodTvKey
enum class HomeTab { LIVE, MOVIES, SERIES, SOURCES }
@Composable fun GoodTvNavigation()      // rememberNavBackStack(HomeKey), NavDisplay with rememberSaveableStateHolderNavEntryDecorator + rememberViewModelStoreNavEntryDecorator; entries for all keys (Player/Movies/Series/Grid entries filled in Tasks 10–12; placeholder Text until then)
@Composable fun HomeScreen(initialTab: HomeTab, onNavigate: (GoodTvKey) -> Unit)   // TabRow; Live/Movies/Series bodies are placeholders until Tasks 10–12
inline fun <reified VM : ViewModel> goodTvViewModel(crossinline create: (AppGraph) -> VM): VM   // viewModel(factory = ...) using LocalContext.appGraph()
object Labels { fun categoryLabel(name: String, nickname: String, multiSource: Boolean): String }   // "Sports · Home" or "Sports"
class SourcesViewModel(repo) { val sources: StateFlow<List<SourceSummary>>; val syncState; fun refresh(id); fun remove(id) }
class EditSourceViewModel(repo, sourceId: Long?) { data class Form(nickname, server, username, password, testing: Boolean, result: String?, error: String?, saved: Boolean); val form: StateFlow<Form>; fun update(...); fun testAndSave() }
@Composable fun TvTextField(value, onValueChange, label, modifier, password: Boolean = false)   // BasicTextField in a focusable bordered Box; border colour changes on focus
```

Behaviour: `HomeScreen` initial tab = `SOURCES` when `enabledSourceCount == 0`, else `settings.lastTab ?: LIVE`; `SourcesScreen` lists summaries as `ListItem`s ("3,120 live · 8,400 movies · 410 series · synced 2 h ago" using `DateUtils.getRelativeTimeSpanString`, or `lastError`), with "Add source" button focused when the list is empty; per-source actions row: Refresh, Edit, Remove (Remove asks "Remove {nickname}?" with Confirm/Cancel). `EditSourceScreen` form; "Test & save" calls `repo.validate`, shows "Active · expires {date} · {n} connections" or the error, saves on success, pops back. `testAndSave` copies `allowedOutputFormats` from `SourceInfo` into the stored `SourceConfig.Xtream`.

- [ ] **Step 1: Write `LabelsTest`** (`categoryLabel("Sports","Home",true) == "Sports · Home"`, `multiSource=false` → "Sports") and `EditSourceViewModelTest` (fake repo: `testAndSaveShowsErrorOnFailure` (error text "Invalid username or password", not saved), `testAndSaveSavesWithAllowedFormats`, `blankServerIsValidationError` ("Server URL is required")).
- [ ] **Step 2: Write `SourcesFlowTest`** (`createAndroidComposeRule<MainActivity>()`, MockWebServer started in the test serving the Task 3 fixtures; runner sets `skipDebugSource`; database cleared in `@Before` via `appGraph().db.clearAllTables()`): `freshInstallLandsOnSourcesWithAddFocused` (node "Add source" `assertIsFocused`), `addSourceShowsCountsAndLiveCategories` (fill form via `performTextInput`, click "Test & save", wait until text containing "2 live" appears; switch to Live tab, `waitUntilAtLeastOneExists(hasText("Sports"))`). (Review Focus 5.)
- [ ] **Step 3: Run both, expect failure.**
- [ ] **Step 4: Implement** nav, home shell, sources, edit form, labels, `MainActivity.onStart` stale refresh.
- [ ] **Step 5: Run** `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest` → PASS (old `HomeScreenTest` is deleted in this task; its D-pad coverage moves to `LiveScreenTest` in Task 10).
- [ ] **Step 6: Commit** `feat(ui): Navigation 3 shell, Sources and Edit source screens`

---

### Task 10: Live screen and generalised player

**Files:**
- Create: `ui/live/LiveScreen.kt`, `LiveViewModel.kt`, `ui/player/PlayerFactory.kt`, `ui/player/PlayerKeys.kt`
- Modify: `ui/player/PlayerScreen.kt`, `ui/home/HomeScreen.kt` (Live tab body), `ui/nav/GoodTvNavigation.kt` (Player entry)
- Test: `app/src/androidTest/.../ui/LiveScreenTest.kt`, `app/src/test/.../ui/player/PlayerKeysTest.kt`

**Interfaces (Produces):**

```kotlin
class LiveViewModel(repo) { val categories: StateFlow<List<CategoryWithSource>>; val selected: StateFlow<CategoryWithSource?>; val channels: StateFlow<List<LiveChannel>>; fun select(c: CategoryWithSource) }   // selects first category by default
@Composable fun LiveScreen(onPlay: (PlayerKey) -> Unit)       // Row: left LazyColumn of category ListItems (width 320.dp, label via Labels.categoryLabel, selected = current), right LazyColumn of channel ListItems (logo AsyncImage 48.dp, name); first channel focused when list changes; sidebar item gains selection on focus, not only click
object PlayerFactory { fun create(context: Context): ExoPlayer }    // DefaultHttpDataSource.Factory().setUserAgent("GoodTV/0.2").setAllowCrossProtocolRedirects(true), DefaultMediaSourceFactory with that data source
enum class PlayerAction { TOGGLE_PLAY, SEEK_FORWARD, SEEK_BACK }
object PlayerKeys { fun actionFor(keyCode: Int, isLive: Boolean): PlayerAction? }   // CENTER/MEDIA_PLAY_PAUSE → TOGGLE (vod only), DPAD_RIGHT/MEDIA_FAST_FORWARD → SEEK_FORWARD, DPAD_LEFT/MEDIA_REWIND → SEEK_BACK; all null when isLive
@Composable fun PlayerScreen(item: PlayableItem, onBack: () -> Unit)    // uses PlayerFactory; VOD controls wired in Task 13
```

`PlayerKey` → `PlayableItem` conversion lives in `GoodTvNavigation` via `repo.playable(...)` in a `LaunchedEffect` (shows "Loading…" until resolved).

- [ ] **Step 1: Write `PlayerKeysTest`** (centre toggles for VOD, null for live; right seeks; unknown key null).
- [ ] **Step 2: Write `LiveScreenTest`** (seed store directly with two sources, 2 categories each, 2 channels per category, no network; sets content to `LiveScreen` under `GoodTvTheme`): `sidebarShowsCategoriesWithSourceLabel` ("Sports · A", "Sports · B" both displayed), `firstChannelFocusedAndDpadDownMoves` (replaces old HomeScreenTest coverage), `selectingChannelEmitsPlayerKey` (DPAD_CENTER → `onPlay` called with `refKind == "live"`, `sourceId` of source A), `dpadLeftReachesSidebarAndChangesCategory` (focus a sidebar item → channel list shows that category's channels).
- [ ] **Step 3: Run, expect failure.** **Step 4: Implement.** **Step 5: Run** unit + connected tests → PASS.
- [ ] **Step 6: Commit** `feat(ui): Live screen with category sidebar; player factory with redirects`

---

### Task 11: Movies rows, category grid, movie detail

**Files:**
- Create: `ui/browse/CategoryRows.kt`, `PosterCard.kt`, `CategoryGridScreen.kt`, `CategoryGridViewModel.kt`, `ui/movies/MoviesScreen.kt`, `MoviesViewModel.kt`, `MovieDetailScreen.kt`, `MovieDetailViewModel.kt`
- Modify: `HomeScreen.kt` (Movies tab), `GoodTvNavigation.kt` (MovieDetail, CategoryGrid entries)
- Test: `app/src/androidTest/.../ui/MoviesScreenTest.kt`, `app/src/test/.../ui/movies/MovieDetailViewModelTest.kt`

**Interfaces (Produces):**

```kotlin
data class RowItem(val sourceId: Long, val id: String, val title: String, val imageUrl: String?)
data class CategoryRow(val category: CategoryWithSource, val items: List<RowItem>, val hasMore: Boolean)
const val ROW_LIMIT = 30
@Composable fun CategoryRows(rows: List<CategoryRow>, onItem: (CategoryRow, RowItem) -> Unit, onSeeAll: (CategoryRow) -> Unit)   // LazyColumn of (label Text + LazyRow of PosterCard; last tile "See all" when hasMore)
@Composable fun PosterCard(title: String, imageUrl: String?, onClick: () -> Unit, modifier)   // tv-material Card, 2:3, 140.dp wide, AsyncImage with placeholder, title below
@Composable fun CategoryGridScreen(key: CategoryGridKey, onItem: (RowItem) -> Unit)   // LazyVerticalGrid, 6 columns, all items of the category
class MoviesViewModel(repo) { val rows: StateFlow<List<CategoryRow>> }   // combine categories(MOVIE) with movies(sourceId, catId, ROW_LIMIT + 1); hasMore = size > ROW_LIMIT
class MovieDetailViewModel(repo, key) { sealed State { Loading; Ready(detail); Error(msg) }; val state }
@Composable fun MovieDetailScreen(onPlay: (PlayerKey) -> Unit)     // backdrop AsyncImage dimmed, poster, title, year (first 4 chars of releaseDate), genre, duration (mm/h), rating, plot, Play button focused by default
```

- [ ] **Step 1: Write `MovieDetailViewModelTest`** (fake repo: `readyAfterLoad`, `errorMessageSurfaced`).
- [ ] **Step 2: Write `MoviesScreenTest`** (seeded store: one category with 31 movies, one with 3): `rowsShowLabelAndSeeAllOnlyWhenMore` ("See all" exists once), `selectingPosterOpensDetail` (callback with movie id), `detailPlayButtonFocusedAndEmitsPlayerKey` (set content to `MovieDetailScreen` with a fake repo returning a detail; "Play" `assertIsFocused`; CENTER → `refKind == "movie"`, `ext == "mkv"`).
- [ ] **Step 3: Run, expect failure.** **Step 4: Implement.** **Step 5: Run** → PASS. **Step 6: Commit** `feat(ui): Movies rows, category grid, movie detail`

---

### Task 12: Series rows and series detail

**Files:**
- Create: `ui/series/SeriesScreen.kt`, `SeriesViewModel.kt`, `SeriesDetailScreen.kt`, `SeriesDetailViewModel.kt`
- Modify: `HomeScreen.kt` (Series tab), `GoodTvNavigation.kt` (SeriesDetail entry; CategoryGrid handles `kind == SERIES`)
- Test: `app/src/androidTest/.../ui/SeriesScreenTest.kt`, `app/src/test/.../ui/series/SeriesDetailViewModelTest.kt`

**Interfaces (Produces):**

```kotlin
class SeriesViewModel(repo) { val rows: StateFlow<List<CategoryRow>> }      // same shape as MoviesViewModel, via repo.series
class SeriesDetailViewModel(repo, key) { sealed State { Loading; Ready(detail, selectedSeason: Int); Error(msg) }; fun selectSeason(n: Int) }   // default season = lowest number
@Composable fun SeriesDetailScreen(onPlay: (PlayerKey) -> Unit)    // header like movie detail; LazyRow of season chips (tv-material Button, selected styled); LazyColumn of episodes for selected season: "E{number} · {title} · {mm} min"; first episode focused after season change
```

- [ ] **Step 1: Write `SeriesDetailViewModelTest`** (`defaultsToLowestSeason`, `selectSeasonFiltersEpisodes`, `errorSurfaced`).
- [ ] **Step 2: Write `SeriesScreenTest`**: `rowsRenderSeries`, `detailShowsSeasonsAndEpisodes` (fake detail with seasons 1,2; season 2 chip → episodes of season 2 listed), `selectingEpisodeEmitsPlayerKey` (`refKind == "episode"`, `refId == "1001"`, `ext == "mp4"`).
- [ ] **Step 3: Run, expect failure.** **Step 4: Implement.** **Step 5: Run** → PASS. **Step 6: Commit** `feat(ui): Series rows and series detail`

---

### Task 13: VOD player controls

**Files:**
- Modify: `ui/player/PlayerScreen.kt`
- Test: extend `PlayerKeysTest` if mapping changes; manual verification on emulator

**Interfaces:**
- Consumes: `PlayerKeys.actionFor`, `rememberPlayPauseButtonState`, `rememberSeekBackButtonState`, `rememberSeekForwardButtonState` from `androidx.media3.ui.compose.state` (all `@UnstableApi`).

- [ ] **Step 1: Implement** — root `Box` with `Modifier.focusable().onKeyEvent { if (it.type == KeyDown) PlayerKeys.actionFor(it.nativeKeyEvent.keyCode, item.isLive)?.let { a -> dispatch(a); true } ?: false }`; for VOD show an overlay (title, "Paused"/position `mm:ss / mm:ss` text from `player.currentPosition`/`duration` polled every 500 ms) that appears on any key and hides after 3 s; live keeps the title-only overlay. Seek increments: 10 s back, 30 s forward via `player.seekTo(...)` (set `setSeekBackIncrementMs/setSeekForwardIncrementMs` in `PlayerFactory` so the Media3 state holders use them).
- [ ] **Step 2: Build** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 3: Manual check on emulator** (Task 14 covers the full flow). **Step 4: Commit** `feat(player): VOD play/pause and seek on D-pad`

---

### Task 14: Fake Xtream server, README, end-to-end verification

**Files:**
- Create: `tools/fake-xtream/server.py`, `tools/fake-xtream/README.md`
- Modify: `README.md`

**Interfaces (Produces):** `python3 tools/fake-xtream/server.py --port 8089 --name "Home"` serves: `player_api.php` (auth for user `demo`/pass `demo`; `allowed_output_formats: ["m3u8"]`; all six list actions; `get_vod_info`; `get_series_info` with map-form episodes), `/live/demo/demo/{id}.m3u8`, `/movie/demo/demo/{id}.mp4`, `/series/demo/demo/{id}.mp4` → `302` to one of the three public HLS test streams; wrong credentials → `{"user_info":{"auth":0}}`. Catalog: 3 live categories / 12 channels, 2 movie categories / 6 movies (one category with 31 movies would be nicer for "See all"; make the first movie category 31 entries), 1 series category / 2 series × 2 seasons × 3 episodes. Mixed types: half the live `stream_id`s are strings. `--name` sets a prefix on category names so two instances are distinguishable.

- [ ] **Step 1: Write `server.py`** (stdlib `http.server`, `argparse`, `json`; no third-party deps). **Step 2: Smoke test** `curl 'http://localhost:8089/player_api.php?username=demo&password=demo&action=get_live_streams' | python3 -m json.tool | head` → JSON array.
- [ ] **Step 3: Update `README.md`**: fake server usage; `local.properties` keys with `GOODTV_XTREAM_SERVER=http://10.0.2.2:8089`, `demo`/`demo`; note on `.ts` vs `.m3u8`; how to add a second fake source on port 8090 through the on-TV form.
- [ ] **Step 4: End-to-end on the emulator** — start two fake servers (8089 "Home", 8090 "Second"); set `local.properties`; `./gradlew :app:installDebug`; launch; expect debug source inserted and synced; add the second source via the form (type with `adb shell input text`); screenshots to `.context/`: `e2e-sources.png` (two sources with counts), `e2e-live.png` (sidebar shows "News · Home" and "News · Second"), `e2e-live-playing.png`, `e2e-movies.png` (rows + "See all"), `e2e-movie-detail.png`, `e2e-series-detail.png`, `e2e-episode-playing.png` with the VOD overlay visible after a key press. Confirm no `FATAL EXCEPTION` in logcat. Stop one fake server, press Refresh on it: its status line shows "Could not reach server", the other still refreshes.
- [ ] **Step 5: Run everything** `./gradlew :app:lintDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleRelease` → all PASS, lint 0 errors.
- [ ] **Step 6: Commit** `feat: fake Xtream server for development; README`

---

## Self-review notes

- Spec coverage: Sources CRUD (T9), merged Live/Movies/Series (T10–12), play live/movie/episode (T10, T13), offline cache + no-blocking (T6, T8), M3U-ready interface (T2), debug prefill (T7), backup exclusion (T1), cross-protocol redirects (T10), fake server (T14), 12 h foreground refresh (T8 + T9 `onStart`), see-all grids (T11). EPG/search/resume/favourites intentionally absent.
- Type consistency checked: `CategoryWithSource`, `PlayerKey(refKind: "live"|"movie"|"episode")`, `LibraryStore` names reused verbatim in T8–T12.
- Review Focus items map to T4, T3, T5, T8, T9.
