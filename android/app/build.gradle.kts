import java.util.Properties
import java.io.FileInputStream
import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
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

android {
    namespace = "com.albunyaan.tube"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.albunyaan.tube"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.albunyaan.tube.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true

        manifestPlaceholders["profileable"] = "false"

        // API Base URL configuration
        // Configure via local.properties: api.base.url=http://YOUR_IP:8080/
        // Default: Emulator localhost (10.0.2.2)
        val apiBaseUrl = localProperties.getProperty("api.base.url", "http://10.0.2.2:8080/")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("boolean", "ENABLE_THUMBNAIL_IMAGES", "true")

        // PR6.2: iOS client fetch feature flag
        // Enables NewPipeExtractor iOS client fetch for better HLS manifest availability.
        // Default OFF - enable in local.properties: npe.ios.fetch.enabled=true
        // WARNING: Requires iOS User-Agent for HLS playback (configured in MultiQualityMediaSourceFactory)
        val enableNpeIosFetch = localProperties.getProperty("npe.ios.fetch.enabled", "false").toBoolean()
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
        // Enable synthetic adaptive DASH from progressive streams.
        // Creates multi-representation DASH MPD from video-only progressive streams for ABR switching.
        // Default ON - disable in local.properties: playback.synth.adaptive.enabled=false
        val enableSynthAdaptive = localProperties.getProperty("playback.synth.adaptive.enabled", "true").toBoolean()
        buildConfigField("boolean", "ENABLE_SYNTH_ADAPTIVE", "$enableSynthAdaptive")

        // Enable MPD pre-generation during stream prefetch.
        // Pre-generates DASH MPD when user taps video to reduce first-frame latency.
        // Default ON - disable in local.properties: playback.mpd.prefetch.enabled=false
        val enableMpdPrefetch = localProperties.getProperty("playback.mpd.prefetch.enabled", "true").toBoolean()
        buildConfigField("boolean", "ENABLE_MPD_PREFETCH", "$enableMpdPrefetch")

        // Enable graceful degradation manager for playback recovery.
        // Implements per-video refresh budgets and automatic quality step-downs.
        // Default ON - disable in local.properties: playback.degradation.enabled=false
        val enableDegradation = localProperties.getProperty("playback.degradation.enabled", "true").toBoolean()
        buildConfigField("boolean", "ENABLE_DEGRADATION_MANAGER", "$enableDegradation")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    ksp {
        // Room schema export location for migration testing
        arg("room.schemaLocation", "$projectDir/schemas")
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

        unitTests.all {
            // Enforce 300s (5-minute) global test timeout per AGENTS.md policy
            // Prevents hanging tests from blocking CI/CD
            it.timeout = Duration.ofSeconds(300)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.paging:paging-runtime-ktx:3.3.5")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // AndroidX Media3 (replaces ExoPlayer 2.x)
    val media3Version = "1.6.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // NewPipeExtractor v0.25.2 (2026-02-05): Fixes "page reload required" error, removes obsolete TVHTML5 client
    // Release notes: https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.25.2
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.25.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("io.coil-kt:coil:2.7.0")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Google Cast SDK
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    // FFmpeg-kit for audio/video merging (min-gpl variant)
    // Using community fork since original arthenica/ffmpeg-kit was archived (June 2025)
    // https://central.sonatype.com/artifact/io.github.trongnhan136/ffmpeg-kit-min-gpl
    implementation("io.github.trongnhan136:ffmpeg-kit-min-gpl:7.1.2")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Room Database for local persistence (favorites, watch history)
    // Note: room-ktx merged into room-runtime in 2.7.0
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    testImplementation("androidx.room:room-testing:$roomVersion")

    // Core library desugaring for Java 10+ APIs (including java.nio for NewPipeExtractor compatibility)
    // Using desugar_jdk_libs_nio to include URLEncoder.encode(String, Charset) support
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.54")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.54")
    debugImplementation("androidx.fragment:fragment-testing:1.8.6")
}
