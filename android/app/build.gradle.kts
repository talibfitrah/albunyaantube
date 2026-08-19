import java.util.Properties
import java.io.FileInputStream
import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    // Plan B (ANDROID-AUTH-01): parses app/google-services.json (gitignored,
    // see app/google-services.json.template) to wire FirebaseApp init.
    id("com.google.gms.google-services")
}

// Load keystore properties for release signing
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Load API configuration from local.properties
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()

if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.albunyaan.tube"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.albunyaan.tube"
        minSdk = 26
        targetSdk = 35
        versionCode = 53
        versionName = "1.0.0-beta.39"

        testInstrumentationRunner = "com.albunyaan.tube.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true

        manifestPlaceholders["profileable"] = "false"

        // API Base URL configuration
        // Configure via local.properties: api.base.url=http://YOUR_IP:8080/
        // Default: Emulator localhost (10.0.2.2)
        val apiBaseUrl = localProperties.getProperty("api.base.url", "http://10.0.2.2:8080/")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("boolean", "ENABLE_THUMBNAIL_IMAGES", "true")

        // Plan B (ANDROID-AUTH-01) T7: Firebase Auth emulator override (debug
        // builds only — see AlBunyaanApplication). Empty host = use live
        // Firebase. On Android emulator: 10.0.2.2:9099. On real device:
        // your laptop's LAN IP. Set in local.properties:
        //   auth.emulator.host=10.0.2.2
        //   auth.emulator.port=9099
        val authEmulatorHost = localProperties.getProperty("auth.emulator.host", "")
        val authEmulatorPort = localProperties.getProperty("auth.emulator.port", "9099").toInt()
        buildConfigField("String", "AUTH_EMULATOR_HOST", "\"$authEmulatorHost\"")
        buildConfigField("int", "AUTH_EMULATOR_PORT", "$authEmulatorPort")

        // ANDROID-MULTI-01 Issue 4: public watch-page base URL. shareCurrentVideo()
        // emits https://host/api/watch/{id} so link unfurlers (WhatsApp / Telegram /
        // Slack / Skype) render the OpenGraph preview served by WatchPageController.
        // Set local.properties share.base.url= to force the in-app deep-link fallback.
        val shareBaseUrl = localProperties.getProperty("share.base.url", "https://app.fitrahtube.com")
        buildConfigField("String", "SHARE_BASE_URL", "\"$shareBaseUrl\"")

        // PR6.2: iOS client fetch feature flag
        // Enables NewPipeExtractor iOS client fetch for better HLS manifest availability.
        // Default ON: the iOS client provides the full HD/4K adaptive ladder and now works once the
        // WebView poToken is supplied (see WebViewPoTokenProvider.getIosClientPoToken). The android
        // client is SABR-gutted to a single 360p muxed stream, so iOS must stay on. Disable only for
        // debugging: npe.ios.fetch.enabled=false
        // WARNING: Requires iOS User-Agent for HLS playback (configured in MultiQualityMediaSourceFactory)
        val enableNpeIosFetch = localProperties.getProperty("npe.ios.fetch.enabled", "true").toBoolean()
        buildConfigField("boolean", "ENABLE_NPE_IOS_FETCH", "$enableNpeIosFetch")

        // ===================================================================================
        // Playback reliability feature flags (Phases 1-5)
        // ===================================================================================
        // ROLLOUT POLICY:
        // - All features default ON for both debug and release builds.
        // - This means release builds shipped to users have these features enabled by default.
        // - For staged rollout or emergency disable, use PlaybackFeatureFlags runtime toggles
        //   (accessible via hidden developer options: About → tap version 7×).
        // - For fleet-wide control, integrate PlaybackFeatureFlags with Firebase Remote Config
        //   or similar service that writes to SharedPreferences on app startup.
        // - To disable at build time (local dev/testing): set property in local.properties
        //
        // Enable MPD pre-generation during stream prefetch.
        // Pre-generates DASH MPD when user taps video to reduce first-frame latency.
        // Default ON - disable in local.properties: playback.mpd.prefetch.enabled=false
        val enableMpdPrefetch = localProperties.getProperty("playback.mpd.prefetch.enabled", "true").toBoolean()
        buildConfigField("boolean", "ENABLE_MPD_PREFETCH", "$enableMpdPrefetch")

        val enableClientRotation = localProperties.getProperty("playback.client.rotation.enabled", "true").toBoolean()
        buildConfigField("boolean", "ENABLE_CLIENT_ROTATION", "$enableClientRotation")
        // Predictive prefetch starts extraction when list cells attach so
        // tap-to-open is instant. OFF by default — turning it on with the
        // current controller fires for every visible cell, which on a
        // Me-feed or channel screen with 100 items burns the global
        // 10/min cap in seconds and locks foreground taps out for ~57s.
        // Re-enable only after the controller is scoped (Me-only) and
        // strictly capped (≤2 in flight). Override in local.properties:
        // playback.predictive.prefetch.enabled=true
        val enablePredictivePrefetch = localProperties.getProperty("playback.predictive.prefetch.enabled", "false").toBoolean()
        buildConfigField("boolean", "ENABLE_PREDICTIVE_PREFETCH", "$enablePredictivePrefetch")
        val enableSegmentPreload = localProperties.getProperty("playback.segment.preload.enabled", "true").toBoolean()
        val enableNeverFreezeAbr = localProperties.getProperty("playback.never.freeze.abr.enabled", "true").toBoolean()
        val enableTtlWatcher = localProperties.getProperty("playback.ttl.watcher.enabled", "true").toBoolean()
        buildConfigField("boolean", "ENABLE_SEGMENT_PRELOAD", "$enableSegmentPreload")
        buildConfigField("boolean", "ENABLE_NEVER_FREEZE_ABR", "$enableNeverFreezeAbr")
        buildConfigField("boolean", "ENABLE_TTL_WATCHER", "$enableTtlWatcher")
    }

    signingConfigs {
        // Re-enable v1 (JAR) signing alongside v2/v3. With minSdk 26, AGP disables
        // v1 by default (v2 covers API 24+), producing v2-only APKs. But the in-app
        // updater's pre-install signature check falls back to getPackageArchiveInfo
        // with GET_SIGNATURES on Android <= 8.1 (API < 28; API 26-27 given minSdk 26),
        // which can only read v1 signatures: a v2-only APK has no readable cert there,
        // so the update aborts with "signature mismatch" before install ever starts.
        // (API 28+ reads apkContentsSigners and was already fine.) Shipping v1+v2+v3
        // keeps the cert readable across all supported APIs (26+). Same signing key, so
        // existing v2-only installs still upgrade cleanly.
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            // Plan B ad-hoc: trim DEBUG APK to arm64-v8a only when -PslimApk is set,
            // so the build fits Telegram's 50 MB upload cap for local sideload testing.
            // Strips x86, x86_64, armeabi-v7a. Scoped to the debug build type so a
            // stray `assembleRelease -PslimApk` cannot ship a single-ABI release —
            // release builds must always include the full ABI matrix.
            if (project.hasProperty("slimApk")) {
                ndk {
                    abiFilters.clear()
                    abiFilters.add("arm64-v8a")
                }
            }
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use production signing if keystore exists, otherwise debug
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField("boolean", "ENABLE_THUMBNAIL_IMAGES", "true")
        }

        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            manifestPlaceholders["profileable"] = "true"
            buildConfigField("boolean", "ENABLE_THUMBNAIL_IMAGES", "false")
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = variant.versionName
            output.outputFileName = "fitrahtube-${versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    ksp {
        // Room schema export location for migration testing
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // Schemas live in variant assets so Robolectric MigrationTestHelper can
    // read them via the target context for testDebugUnitTest and
    // testBenchmarkUnitTest. The `release` variant is intentionally NOT
    // wired — see comment below.
    //
    // Schema-export build infra history:
    //  - 2026-05-15 (cubic R5): pre-fix the benchmark variant lacked schema
    //    assets so migration tests failed under testBenchmarkUnitTest with
    //    FileNotFoundException for AppDatabase/{2,3,7,8}.json. The fix wired
    //    `benchmark` + `debug` + `release` variant source sets.
    //  - 2026-05-16 (Codex cleanup review): caught that wiring `release`
    //    variant source set leaks ~50KB of Room version-history JSONs into
    //    the user-facing release APK. Tested moving schemas to the `test`
    //    (common parent) and `testRelease` (variant test) source sets so the
    //    release APK would not bundle them, but AGP does not propagate
    //    assets from `test*` source sets to `test*UnitTest` tasks — both
    //    attempts crashed testReleaseUnitTest with SIGABRT.
    //  - 2026-05-16 (cubic R1 P2 followup): dropped the `release` wiring
    //    entirely and excluded `AppDatabaseMigration*Test` from
    //    testReleaseUnitTest (see below). Migration tests verify schema
    //    correctness, which doesn't depend on the build variant —
    //    testDebugUnitTest + testBenchmarkUnitTest provide sufficient
    //    coverage. Release APK is now bloat-free.
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        getByName("debug").assets.srcDirs("$projectDir/schemas")
        // AGP creates the benchmark variant sourceSet lazily; force creation
        // via getByName so the assets wiring lands. Pre-fix findByName returned
        // null during evaluation order and migration tests silently lacked the
        // schemas at runtime under testBenchmarkUnitTest.
        getByName("benchmark").assets.srcDirs("$projectDir/schemas")
    }

    // testReleaseUnitTest cannot find Room schemas because the `release`
    // variant source set deliberately does not include them (would leak to
    // the user-facing APK). Migration test coverage is variant-agnostic —
    // testDebugUnitTest + testBenchmarkUnitTest already exercise every
    // AppDatabase migration path. Excluding these tests from the release
    // unit-test task keeps the suite green without compromising coverage.
    // ImportMigrationTest (10->11, ANDROID-IMPORT-01) is a MigrationTestHelper
    // test too but doesn't match the AppDatabaseMigration* naming, so it needs
    // its own exclude entry — any future schema-backed migration test must
    // either follow the naming or be added here.
    testOptions {
        unitTests.all {
            if (it.name == "testReleaseUnitTest") {
                it.exclude("**/AppDatabaseMigration*Test*")
                it.exclude("**/ImportMigrationTest*")
            }
        }
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    lint {
        // Create baseline to track existing issues without blocking builds
        // Run `./gradlew updateLintBaseline` to update the baseline
        baseline = file("lint-baseline.xml")

        // Don't abort build on lint errors (issues tracked in baseline)
        abortOnError = false

        // Generate reports for review
        htmlReport = true
        xmlReport = true
    }

    testOptions {
        // Return default values for unmocked Android framework calls (e.g., Log.d returns 0)
        // Required for unit tests that use classes containing android.util.Log calls
        unitTests.isReturnDefaultValues = true

        // Surface Android resources/assets to JVM unit tests. Required so
        // Robolectric's MigrationTestHelper can read exported Room schemas
        // from the assets folder (see AppDatabaseMigration2to3Test).
        unitTests.isIncludeAndroidResources = true

        unitTests.all {
            // Enforce 300s (5-minute) global test timeout per AGENTS.md policy.
            // Prevent hanging tests from blocking CI/CD, and keep Robolectric-heavy
            // full-suite runs from exhausting a long-lived Gradle test worker.
            it.timeout = Duration.ofSeconds(300)
            it.maxHeapSize = "1024m"
            it.forkEvery = 300
            it.jvmArgs("-XX:TieredStopAtLevel=1")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    // Pinned to 1.12.0 deliberately. Material 1.13/1.14 ship the M3 Expressive
    // NavigationBarView rework: the new item layout measures the bottom nav at
    // ~42dp regardless of the view's height (fixed 72dp or wrap_content), which
    // clips the tab labels to ~2dp. Verified on-device (COR-L29, sw360dp/480dpi).
    //
    // Moving off 1.12.0 is a nav restyle, not a version bump. What must change
    // together: layout/fragment_main_shell.xml (mainBottomNav height — 72dp is
    // below the library's own m3_bottom_nav_min_height of 80dp) and
    // @dimen/bottom_nav_height, which ~40 layouts including item_shorts_page.xml
    // consume as list bottom clearance, so it cannot move on its own.
    //
    // `strictly` (not a plain version request) so a future transitive bump fails
    // the build loudly instead of silently reintroducing the clipped labels —
    // same reasoning as the kotlinx-serialization-core pin above.
    implementation("com.google.android.material:material") {
        version { strictly("1.12.0") }
        because(
            "1.13+ NavigationBarView rework clips bottom-nav labels at this app's " +
                "nav height; re-attempt only with a deliberate nav restyle."
        )
    }
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.paging:paging-runtime-ktx:3.5.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // Single source of truth for the coroutines stack — kotlinx-coroutines-android
    // and the play-services bridge below must stay aligned (structured concurrency
    // misbehaves on version drift).
    val coroutinesVersion = "1.11.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    // Transitive override: kotlinx-serialization-core is pulled in by
    // kotlin-reflect / Firebase. We never import @Serializable directly — this
    // entry exists ONLY to keep the version off the broken 1.6.x line.
    //
    // The library declares a ClassValueReferences subclass of
    // java.lang.ClassValue. On pre-Android-12 devices (Huawei EMUI 9, stock
    // Android 9–11) ClassValue is declared in the SDK from API 26 but not
    // actually backed by ART until API 31, so the subclass class-init throws
    // NoClassDefFoundError. In 1.6.x that failure was uncaught and propagated
    // up through FirebaseApp bootstrap, dropping the entire auth subsystem
    // (user reports: "suddenly logged off, personal lists gone"). 1.7.x wraps
    // the cache factory in try/Throwable so the failure is caught and the
    // library transparently falls back to a ConcurrentHashMap-backed cache —
    // ART still logs "Rejecting re-init on previously-failed class" each cold
    // start, but the app boots cleanly. See Kotlin/kotlinx.serialization#2638.
    //
    // `strictly` (not a plain version request) makes this a hard fail if any
    // future transitive bump tries to drag us back to 1.6.x OR ahead into a
    // 2.x major that may break Kotlin 2.3.21 binary compat. Without strictly,
    // Gradle's conflict-resolution silently picks the higher version and the
    // protection evaporates without a build warning (Stage 1 review P1).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core") {
        version { strictly("1.11.0") }
        because(
            "Block 1.6.x ClassValue crash on Android < 12. Exact pin (not range) so any " +
                "future transitive bump — a 1.6.x downgrade or an untested 2.x major — " +
                "fails the build loudly instead of resolving silently."
        )
    }
    // Plan B (ANDROID-AUTH-01) T1: Firebase Auth + Google Sign-In.
    // BoM pins all firebase-* artifact versions transitively — DO NOT add
    // explicit versions to firebase-auth or other firebase- modules below.
    //
    // BoM 34.13.0+ ships firebase-auth compiled with Kotlin 2.3.0 metadata,
    // which requires the project Kotlin to be >= 2.3 (root build.gradle.kts,
    // currently 2.3.21). When bumping Kotlin or the Firebase BoM, re-verify
    // ABI compatibility, not just version recency.
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    // play-services-tasks ↔ kotlin coroutines bridge (`Task<T>.await()`).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")
    // AndroidX Media3 (replaces ExoPlayer 2.x)
    // Media3 1.10.1 includes the fix for the HLS chunk-load regression
    // tracked as androidx/media#3161, so the old 1.9.3 safety pin can move.
    val media3Version = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-datasource-cronet:$media3Version")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // NewPipeExtractor v0.26.5 (hotfix on v0.26.4, which fixes YouTube playlist
    // continuations — PR #1518). Carries forward v0.26.3's lockupViewModel
    // playlist-item fix (PR #1503) and v0.26.2's channel-tab fix (PR #1492):
    // YouTube moved playlist items to lockupViewModel, which made PlaylistInfo
    // report a correct streamCount but an empty item list ("Nothing here").
    // Release notes: https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.michaelrocks:libphonenumber-android:8.13.55")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("androidx.hilt:hilt-work:1.4.0")
    ksp("androidx.hilt:hilt-compiler:1.4.0")

    // Google Cast SDK
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("androidx.mediarouter:mediarouter:1.8.1")

    // FFmpeg-kit for audio/video merging (min-gpl variant)
    // Using community fork since original arthenica/ffmpeg-kit was archived (June 2025)
    // https://central.sonatype.com/artifact/io.github.trongnhan136/ffmpeg-kit-min-gpl
    implementation("io.github.trongnhan136:ffmpeg-kit-min-gpl:7.1.5")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Room Database for local persistence (favorites, watch history)
    // Note: room-ktx merged into room-runtime in 2.7.0
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    // ANDROID-PERSONAL-03 / T7: room-paging removed. The Me-feed grid no
    // longer uses Room's PagingSource<Int, T> return type — it observes
    // per-week ranges via Flow<List<ChannelVideoCache>> and renders one
    // sub-adapter per week. paging-runtime-ktx is still on the classpath
    // (line 212) for the unrelated content-paging path
    // (data.paging.CursorPagingSource).
    ksp("androidx.room:room-compiler:$roomVersion")
    testImplementation("androidx.room:room-testing:$roomVersion")

    // Core library desugaring for Java 10+ APIs (including java.nio for NewPipeExtractor compatibility)
    // Using desugar_jdk_libs_nio to include URLEncoder.encode(String, Charset) support
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("androidx.work:work-testing:2.11.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // ATOM fetcher unit tests use MockWebServer to drive 200/304/429/5xx
    // responses without touching YouTube. The androidTest classpath already
    // pulls this in; adding it to testImplementation makes it available to
    // the JVM/Robolectric source set as well. (ANDROID-PERSONAL-02 / T2)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.58")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.58")
    debugImplementation("androidx.fragment:fragment-testing:1.9.0")
}

// Force consistent AndroidX test versions across debug and androidTest configurations.
// fragment-testing:1.9.0 (debugImplementation) pulls older test:core / monitor into the
// runtime classpath. The Gradle consistent resolution strategy then creates {strictly X} constraints
// that conflict with the newer versions required by runner:1.7.0 and espresso:3.7.0.
// Fix: force all configurations to align on the versions required by the test dependencies.
configurations.all {
    resolutionStrategy {
        force(
            "androidx.test:core:1.7.0",
            "androidx.test:core-ktx:1.7.0",
            "androidx.test:monitor:1.8.0",
            "androidx.test:runner:1.7.0",
            "androidx.test:rules:1.7.0"
        )
    }
}
