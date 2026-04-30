# Instant Playback Phase B: Segment Pre-Buffering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After stream URLs are resolved and the DASH MPD is pre-generated, write the first 3 seconds of the lowest-bitrate video track into `SimpleCache` using `CacheWriter`, so ExoPlayer's `CacheDataSource` serves those bytes instantly when the user taps.

**Architecture:** New `SegmentPreBuffer` singleton wraps `CacheWriter` and is called from `StreamPrefetchService.tryPreGenerateMpd()` after a successful MPD registration. Uses the existing 100MB `SimpleCache` — no new infrastructure. Errors are silently swallowed; a failed pre-buffer falls back to live streaming as today. Gated behind `KEY_SEGMENT_PRELOAD` feature flag.

**Tech Stack:** Kotlin, Media3 `CacheWriter`, `SimpleCache`, Hilt DI, JUnit 4 + Mockito

**Dependency:** Assumes Phase A (`CronetDataSourceFactory`) is deployed. If implementing standalone, inject `DefaultHttpDataSource.Factory()` instead.

---

## File Structure

| Action | File | Responsibility |
|---|---|---|
| Create | `player/SegmentPreBuffer.kt` | Write first N bytes of a URL into `SimpleCache` via `CacheWriter` |
| Modify | `player/PlaybackFeatureFlags.kt` | Add `KEY_SEGMENT_PRELOAD` flag |
| Modify | `player/StreamPrefetchService.kt` | Call `SegmentPreBuffer` after MPD registration |
| Modify | `di/DataModule.kt` | Provide `SegmentPreBuffer` and expose `SimpleCache` |
| Create | `test/.../player/SegmentPreBufferTest.kt` | Unit tests |

---

### Task 1: Add `KEY_SEGMENT_PRELOAD` feature flag

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt`
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add BuildConfig field**

In `android/app/build.gradle.kts` `defaultConfig`, after the Cronet flag:
```kotlin
buildConfigField("boolean", "ENABLE_SEGMENT_PRELOAD", "true")
```

- [ ] **Step 2: Add constant, property, and setter to `PlaybackFeatureFlags`**

In companion `VALID_KEYS` set, add `KEY_SEGMENT_PRELOAD`.

In `clearAllOverridesInternal()` and `clearAllOverrides()`, add `.remove(KEY_SEGMENT_PRELOAD)`.

Add constant:
```kotlin
const val KEY_SEGMENT_PRELOAD = "segment_preload"
```

Add property:
```kotlin
val isSegmentPreloadEnabled: Boolean
    get() = resolveFlag(KEY_SEGMENT_PRELOAD, BuildConfig.ENABLE_SEGMENT_PRELOAD)
```

Add setter:
```kotlin
fun setSegmentPreloadEnabled(enabled: Boolean?) {
    setOverride(KEY_SEGMENT_PRELOAD, enabled)
    Log.i(TAG, "SEGMENT_PRELOAD override set to: $enabled (effective: $isSegmentPreloadEnabled)")
}
```

Add to `getDiagnostics()`:
```kotlin
KEY_SEGMENT_PRELOAD to getFlagState(KEY_SEGMENT_PRELOAD, BuildConfig.ENABLE_SEGMENT_PRELOAD),
```

- [ ] **Step 3: Build and run tests**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
cd android && ./gradlew test --tests "com.albunyaan.tube.player.PlaybackFeatureFlagsTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt
git commit -m "[FEAT]: [ANDROID-PERF-02]: Add KEY_SEGMENT_PRELOAD feature flag"
```

---

### Task 2: Expose `SimpleCache` from `DataModule` and create `SegmentPreBuffer`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/player/SegmentPreBuffer.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/player/SegmentPreBufferTest.kt`

Context: `SimpleCache` is currently created inside `MultiQualityMediaSourceFactory.companion.getOrCreateCache()`. To inject it into `SegmentPreBuffer` via Hilt, we need to expose it from `DataModule`. `MultiQualityMediaSourceFactory` will then use the injected cache instead of its own companion.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/albunyaan/tube/player/SegmentPreBufferTest.kt`:

```kotlin
package com.albunyaan.tube.player

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SegmentPreBufferTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var cache: SimpleCache
    private lateinit var segmentPreBuffer: SegmentPreBuffer

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val dbProvider = StandaloneDatabaseProvider(context)
        cache = SimpleCache(tmpFolder.newFolder("cache"), NoOpCacheEvictor(), dbProvider)
        segmentPreBuffer = SegmentPreBuffer(cache, DefaultHttpDataSource.Factory())
    }

    @After
    fun tearDown() {
        cache.release()
    }

    @Test
    fun `preBuffer swallows IOException without throwing`() = runBlocking {
        // A non-existent URL will cause IOException — must not propagate
        segmentPreBuffer.preBuffer("http://localhost:9999/no-such-url", durationMs = 1000)
        // Reaching here means no exception was thrown
    }

    @Test
    fun `preBuffer swallows all errors without throwing`() = runBlocking {
        segmentPreBuffer.preBuffer("", durationMs = 500)
        segmentPreBuffer.preBuffer("not-a-url", durationMs = 500)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.SegmentPreBufferTest" 2>&1 | tail -10
```
Expected: FAIL — `SegmentPreBuffer` not yet defined.

- [ ] **Step 3: Create `SegmentPreBuffer`**

Create `android/app/src/main/java/com/albunyaan/tube/player/SegmentPreBuffer.kt`:

```kotlin
package com.albunyaan.tube.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class SegmentPreBuffer @Inject constructor(
    private val cache: SimpleCache,
    private val httpFactory: DataSource.Factory,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SegmentPreBuffer"
        private const val DEFAULT_DURATION_MS = 3_000L
        private const val BYTES_PER_MS_ESTIMATE = 500L // ~4Mbps at lowest quality
    }

    private val isLowRamDevice: Boolean by lazy {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .isLowRamDevice
    }

    suspend fun preBuffer(videoUrl: String, durationMs: Long = DEFAULT_DURATION_MS) {
        if (videoUrl.isBlank()) return
        if (isLowRamDevice) return // avoid storage/memory pressure on low-end devices
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(videoUrl)
                val bytesToCache = durationMs * BYTES_PER_MS_ESTIMATE
                val dataSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setLength(bytesToCache)
                    .build()
                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                val writer = CacheWriter(
                    cacheDataSourceFactory.createDataSource() as CacheDataSource,
                    dataSpec,
                    null,
                    null
                )
                try {
                    writer.cache()
                    Log.d(TAG, "Pre-buffered ${bytesToCache}B for $uri")
                } finally {
                    // CacheWriter does not implement Closeable; nothing to release
                }
            } catch (e: Exception) {
                Log.d(TAG, "Pre-buffer failed for $videoUrl (non-fatal): ${e.message}")
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.SegmentPreBufferTest" 2>&1 | tail -10
```
Expected: PASS.

- [ ] **Step 5: Expose `SimpleCache` from `DataModule`**

In `android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt`, add these imports at the top:
```kotlin
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import com.albunyaan.tube.player.SegmentPreBuffer
import androidx.media3.datasource.DefaultHttpDataSource
```

Add a new `@Provides` method (place it near the other player-related providers):
```kotlin
@Provides
@Singleton
fun provideSimpleCache(@ApplicationContext context: Context): SimpleCache {
    val cacheDir = java.io.File(context.cacheDir, "media3")
    val dbProvider = StandaloneDatabaseProvider(context)
    val evictor = LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024L)
    return SimpleCache(cacheDir, evictor, dbProvider)
}

@Provides
@Singleton
fun provideSegmentPreBuffer(
    cache: SimpleCache,
    // Use CronetDataSourceFactory if Phase A is deployed, else DefaultHttpDataSource.Factory
    httpFactory: DefaultHttpDataSource.Factory
): SegmentPreBuffer {
    return SegmentPreBuffer(cache, httpFactory)
}

@Provides
@Singleton
fun provideDefaultHttpDataSourceFactory(): DefaultHttpDataSource.Factory {
    return DefaultHttpDataSource.Factory()
        .setUserAgent(com.albunyaan.tube.player.HttpConstants.YOUTUBE_USER_AGENT)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(20000)
        .setAllowCrossProtocolRedirects(true)
}
```

**NOTE:** If Phase A (`CronetDataSourceFactory`) is already deployed on this branch, replace `DefaultHttpDataSource.Factory` in `provideSegmentPreBuffer` with `CronetDataSourceFactory` and call `.createForAndroidUA()`.

- [ ] **Step 6: Update `MultiQualityMediaSourceFactory.companion` to use the injected cache**

In `MultiQualityMediaSourceFactory.kt`, find `getOrCreateCache(context)` calls in `cacheDataSourceFactory` and `hlsCacheDataSourceFactory` property declarations. These currently call `getOrCreateCache(context)` which creates a new SimpleCache. If the app now has a Hilt-provided SimpleCache, `MultiQualityMediaSourceFactory` must use it — otherwise two `SimpleCache` instances will conflict (Media3 requires only one instance per cache directory).

The safest approach given that `MultiQualityMediaSourceFactory` is not Hilt-injected: accept the `SimpleCache` as an optional constructor parameter:

```kotlin
class MultiQualityMediaSourceFactory(
    private val context: Context,
    private val hlsPoisonRegistry: HlsPoisonRegistry? = null,
    private val multiRepFactory: MultiRepSyntheticDashMediaSourceFactory? = null,
    private val coldStartQualityChooser: ColdStartQualityChooser? = null,
    private val featureFlags: PlaybackFeatureFlags? = null,
    private val mpdRegistry: SyntheticDashMpdRegistry? = null,
    private val probationChecker: HlsProbationChecker? = null,
    private val cronetDataSourceFactory: CronetDataSourceFactory? = null,
    private val simpleCache: SimpleCache? = null   // ← add this
)
```

Then in `cacheDataSourceFactory` and `hlsCacheDataSourceFactory` property declarations, change `getOrCreateCache(context)` to:
```kotlin
simpleCache ?: getOrCreateCache(context)
```

- [ ] **Step 7: Inject `SimpleCache` in both fragments and pass it to `MultiQualityMediaSourceFactory`**

In `ShortsPlayerFragment` and `PlayerFragment`, add:
```kotlin
@Inject
lateinit var simpleCache: SimpleCache
```

And add `simpleCache` as the last argument when constructing `MultiQualityMediaSourceFactory`.

- [ ] **Step 8: Build and test**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
cd android && ./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/SegmentPreBuffer.kt \
        android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt \
        android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt \
        android/app/src/test/java/com/albunyaan/tube/player/SegmentPreBufferTest.kt
git commit -m "[FEAT]: [ANDROID-PERF-02]: Add SegmentPreBuffer + expose SimpleCache via Hilt"
```

---

### Task 3: Wire `SegmentPreBuffer` into `StreamPrefetchService`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/StreamPrefetchService.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt`

Context: `DefaultStreamPrefetchService` is provided in `DataModule.provideStreamPrefetchService(...)`. Its constructor currently takes `(globalResolver, rateLimiter, mpdGenerator, mpdRegistry, featureFlags)`. We add `segmentPreBuffer: SegmentPreBuffer` as the 6th parameter.

- [ ] **Step 1: Add `segmentPreBuffer` to `DefaultStreamPrefetchService` constructor**

In `StreamPrefetchService.kt`, find `class DefaultStreamPrefetchService(...)`. Add `private val segmentPreBuffer: SegmentPreBuffer` as the last constructor parameter.

- [ ] **Step 2: Call `segmentPreBuffer.preBuffer()` in `tryPreGenerateMpd()`**

Find `tryPreGenerateMpd()` (line ~290). Inside the `is MultiRepresentationMpdGenerator.Result.Success ->` branch, after the `mpdRegistry.registerWithMetadata(...)` call:

```kotlin
is MultiRepresentationMpdGenerator.Result.Success -> {
    mpdRegistry.registerWithMetadata(
        videoId = videoId,
        mpdXml = mpdResult.mpdXml,
        videoTracks = mpdResult.videoTracks,
        audioTrack = mpdResult.audioTrack,
        codecFamily = mpdResult.codecFamily
    )
    Log.d(TAG, "MPD pre-generated for $videoId: ${mpdResult.videoTracks.size} reps (${mpdResult.codecFamily})")

    // Pre-buffer first 3s of lowest-bitrate track into SimpleCache
    if (featureFlags.isSegmentPreloadEnabled) {
        val lowestTrackUrl = mpdResult.videoTracks
            .filter { !it.url.isNullOrBlank() }
            .minByOrNull { it.bitrate ?: Int.MAX_VALUE }
            ?.url
        if (lowestTrackUrl != null) {
            // Launch as sibling coroutine — cancelled if prefetch scope is cancelled
            kotlinx.coroutines.coroutineScope {
                launch { segmentPreBuffer.preBuffer(lowestTrackUrl) }
            }
        }
    }
}
```

Note: `tryPreGenerateMpd` is currently NOT a suspend function. Make it `suspend` so `segmentPreBuffer.preBuffer()` (which is suspend) can be called. Update the call site in `triggerPrefetch()` to use `withContext(Dispatchers.IO)` if not already in a coroutine context — `serviceScope.launch` already provides this.

Actually, `tryPreGenerateMpd` is called from inside `serviceScope.launch { }` (line ~145), so it's already in a coroutine context. Add `suspend` modifier to `tryPreGenerateMpd`:

Change:
```kotlin
private fun tryPreGenerateMpd(videoId: String, resolved: ResolvedStreams) {
```
To:
```kotlin
private suspend fun tryPreGenerateMpd(videoId: String, resolved: ResolvedStreams) {
```

- [ ] **Step 3: Update `DataModule.provideStreamPrefetchService()`**

```kotlin
@Provides
@Singleton
fun provideStreamPrefetchService(
    globalResolver: GlobalStreamResolver,
    rateLimiter: ExtractionRateLimiter,
    mpdGenerator: MultiRepresentationMpdGenerator,
    mpdRegistry: SyntheticDashMpdRegistry,
    featureFlags: PlaybackFeatureFlags,
    segmentPreBuffer: SegmentPreBuffer
): StreamPrefetchService {
    return DefaultStreamPrefetchService(globalResolver, rateLimiter, mpdGenerator, mpdRegistry, featureFlags, segmentPreBuffer)
}
```

- [ ] **Step 4: Build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run all tests**

```bash
cd android && ./gradlew test 2>&1 | tail -15
```
Expected: All pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/StreamPrefetchService.kt \
        android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt
git commit -m "[FEAT]: [ANDROID-PERF-02]: Wire SegmentPreBuffer into StreamPrefetchService.tryPreGenerateMpd()"
```
