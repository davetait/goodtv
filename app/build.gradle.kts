import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional developer-only Xtream source, read from local.properties (gitignored).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String): String = localProps.getProperty(key, "")

android {
    namespace = "com.keyspaceapps.goodtv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.keyspaceapps.goodtv"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "com.keyspaceapps.goodtv.GoodTvTestRunner"

        buildConfigField("String", "XTREAM_SERVER", "\"${localProp("GOODTV_XTREAM_SERVER")}\"")
        buildConfigField("String", "XTREAM_USERNAME", "\"${localProp("GOODTV_XTREAM_USERNAME")}\"")
        buildConfigField("String", "XTREAM_PASSWORD", "\"${localProp("GOODTV_XTREAM_PASSWORD")}\"")
        // TMDB credential (v3 API key or read access token), all build types; blank turns TMDB off.
        buildConfigField("String", "TMDB_API_KEY", "\"${localProp("GOODTV_TMDB_API_KEY")}\"")
        // OpenSubtitles (English subtitles for titles without their own); blank key turns it off. Login is optional.
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${localProp("GOODTV_OPENSUBTITLES_API_KEY")}\"")
        buildConfigField("String", "OPENSUBTITLES_USERNAME", "\"${localProp("GOODTV_OPENSUBTITLES_USERNAME")}\"")
        buildConfigField("String", "OPENSUBTITLES_PASSWORD", "\"${localProp("GOODTV_OPENSUBTITLES_PASSWORD")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "XTREAM_SERVER", "\"\"")
            buildConfigField("String", "XTREAM_USERNAME", "\"\"")
            buildConfigField("String", "XTREAM_PASSWORD", "\"\"")
            // Release builds carry no developer keys; users enter their own in Settings.
            buildConfigField("String", "TMDB_API_KEY", "\"\"")
            buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"\"")
            buildConfigField("String", "OPENSUBTITLES_USERNAME", "\"\"")
            buildConfigField("String", "OPENSUBTITLES_PASSWORD", "\"\"")
            // Debug-signed so the minified build can be installed and exercised locally. Replace
            // with a real signing config before any distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Exported Room schemas feed the migration test.
    sourceSets.getByName("androidTest").assets.srcDirs("$projectDir/schemas")

    // TmdbLiveTest calls the real TMDB API; it only runs with -PtmdbLive (and a key in local.properties).
    testOptions.unitTests.all { test ->
        if (project.hasProperty("tmdbLive")) {
            test.systemProperty("goodtv.tmdbLiveKey", localProp("GOODTV_TMDB_API_KEY"))
            (project.findProperty("tmdbSample") as String?)?.let { test.systemProperty("goodtv.tmdbSample", it) }
            test.outputs.upToDateWhen { false }
        }
    }
}

// AGP only creates the debug unit-test variant by default; ReleaseBuildConfigTest needs the release one.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

// Fails assembleRelease if the release APK contains any local.properties key value. Only entry
// names are ever logged, never values. It finalizes assembleRelease only: bundleRelease and
// installRelease skip it. The real guard is the release build type's blanked BuildConfig fields;
// this scan is a backstop.
val checkReleaseApkForKeys = tasks.register("checkReleaseApkForKeys") {
    val scanned = listOf(
        "GOODTV_TMDB_API_KEY",
        "GOODTV_OPENSUBTITLES_API_KEY",
        "GOODTV_OPENSUBTITLES_USERNAME",
        "GOODTV_OPENSUBTITLES_PASSWORD",
    ).associateWith { localProp(it) }.filterValues { it.isNotBlank() }
    val minLength = 6
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    doLast {
        val (scannable, tooShort) = scanned.entries.partition { it.value.length >= minLength }
        tooShort.forEach { logger.lifecycle("checkReleaseApkForKeys: ${it.key} is too short to scan (under $minLength characters)") }
        if (scannable.isEmpty()) {
            logger.lifecycle("checkReleaseApkForKeys: skipped, no key values in local.properties to scan for")
            return@doLast
        }
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "checkReleaseApkForKeys: no release APK found under ${apkDir.get().asFile}" }
        fun ByteArray.contains(needle: ByteArray): Boolean {
            outer@ for (i in 0..size - needle.size) {
                for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
        val needles = scannable.associate { it.key to it.value.toByteArray(Charsets.UTF_8) }
        val hits = linkedSetOf<String>()
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    needles.forEach { (name, needle) -> if (bytes.contains(needle)) hits += name }
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(hits.joinToString("; ") { "Release APK contains the value of $it" })
        }
        logger.lifecycle("checkReleaseApkForKeys: found no key values in ${apks.size} release APK(s) (scanned ${needles.keys.joinToString()})")
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(checkReleaseApkForKeys) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver3)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
