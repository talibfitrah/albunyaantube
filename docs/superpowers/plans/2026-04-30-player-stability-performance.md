# Player Stability & Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the regular and Shorts players with YouTube client rotation, HLS probation probing, synthetic DASH URI cleanup, subtitle extraction with CC picker, and telemetry session tracking.

**Architecture:** Phase 1 (invisible stability): client rotation on 403, HEAD probe before HLS commit, single-rep DASH URI fix, Media3/NewPipe upgrades, session telemetry. Phase 2 (user-visible): subtitle extraction from NewPipe + CC button in both players using the same `SubtitlePickerDialog` pattern as `AudioLanguageDialog`.

**Tech Stack:** Kotlin, Media3 1.9.2, NewPipeExtractor 0.26.1, ExoPlayer, Hilt, Robolectric (unit tests), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-04-29-player-stability-design.md`

---

## File Map

### Phase 1 — New Files
- `android/app/src/main/java/com/albunyaan/tube/data/extractor/YoutubeClientRotator.kt`
- `android/app/src/test/java/com/albunyaan/tube/data/extractor/YoutubeClientRotatorTest.kt`
- `android/app/src/main/java/com/albunyaan/tube/player/HlsProbationChecker.kt`
- `android/app/src/test/java/com/albunyaan/tube/player/HlsProbationCheckerTest.kt`

### Phase 1 — Modified Files
- `android/app/build.gradle.kts` — version bumps
- `android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt` — two new flags
- `android/app/src/test/java/com/albunyaan/tube/player/PlaybackFeatureFlagsTest.kt` — tests for new flags
- `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt` — client rotation
- `android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt` — inject YoutubeClientRotator
- `android/app/src/main/java/com/albunyaan/tube/player/SyntheticDashMediaSourceFactory.kt` — syntheticdash:// URI fix
- `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt` — mpdRegistry, HLS probation
- `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt` — pass mpdRegistry + probationChecker
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt` — pass mpdRegistry + probationChecker
- `android/app/src/main/java/com/albunyaan/tube/player/StreamRequestTelemetry.kt` — session tracking fields
- `android/app/src/test/java/com/albunyaan/tube/player/StreamRequestTelemetryTest.kt` — session tests

### Phase 2 — New Files
- `android/app/src/main/java/com/albunyaan/tube/ui/shared/SubtitlePickerDialog.kt`
- `android/app/src/main/res/drawable/ic_closed_captions.xml`

### Phase 2 — Modified Files
- `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt` — fill subtitle TODO
- `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt` — SubtitleConfiguration
- `android/app/src/main/res/layout/fragment_player.xml` — CC button
- `android/app/src/main/res/layout-sw600dp/fragment_player.xml` — CC button
- `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt` — CC button wiring
- `android/app/src/main/res/values/strings.xml` — subtitle strings
- `android/app/src/main/res/layout/item_shorts_page.xml` — CC button
- `android/app/src/main/res/layout-sw600dp/item_shorts_page.xml` — CC button
- `android/app/src/main/res/layout-sw720dp/item_shorts_page.xml` — CC button
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPageViewHolder.kt` — CC button
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt` — subtitle picker

---

## Phase 1 — Stability

---

### Task 0: Fork Gate Check (Research — No Code Change)

**Goal:** Confirm that TeamNewPipe 0.26.1 is sufficient vs LibreTube's fork (`811098d32`). The fork's main value was poToken wiring, which this project handles via client rotation instead. This is a 15-minute diff — not a full audit.

- [ ] **Step 1: Fetch LibreTube fork extractor sources for client-selection diff**

```bash
cd /tmp
git clone --depth=1 https://github.com/libre-tube/NewPipeExtractor.git libre-npe
cd libre-npe
git fetch origin 811098d32 2>/dev/null || true
git log --oneline -20
```

- [ ] **Step 2: Diff only YouTube client selection and innertube request headers**

```bash
cd /tmp/libre-npe
git diff 811098d32 HEAD -- extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/ \
  | grep -E "^[+-].*[Cc]lient|ANDROID_VR|TVHTML5|innerTube|poToken|fetchIos" | head -50
```

- [ ] **Step 3: Decision gate**

If the diff shows meaningful YouTube client selection or stream URL improvements NOT in 0.26.1 (confirmed by checking the official 0.26.1 release notes):
→ Note the specific commits, cherry-pick if licensing permits, and file a follow-up ticket.

If the diff is limited to poToken wiring (expected result):
→ Write "Fork gate: N/A — fork adds poToken only, handled via client rotation" in a commit message and proceed.

```bash
git commit --allow-empty -m "[DOCS]: [ANDROID-SHORTS-01]: Fork gate check: LibreTube NPE fork adds poToken only, staying on 0.26.1"
```

---

### Task 1: Dependency Upgrades

**Files:**
- Modify: `android/app/build.gradle.kts:203`

- [ ] **Step 1: Change Media3 and NewPipeExtractor versions**

In `android/app/build.gradle.kts`, find and replace:
```kotlin
// Line ~203
val media3Version = "1.9.0"
```
→
```kotlin
val media3Version = "1.9.2"
```

And find:
```kotlin
implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.0")
```
→
```kotlin
// NewPipeExtractor v0.26.1 (2026-04-10): Fixes YouTube duration fetching, adds extractor logging
implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")
```

- [ ] **Step 2: Build to verify no API breaks**

```bash
cd android && ./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL` — 1.9.2 is a patch release with no public API changes.

- [ ] **Step 3: Run unit tests**

```bash
cd android && ./gradlew test
```
Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Bump Media3 1.9.0→1.9.2, NewPipeExtractor 0.26.0→0.26.1"
```

---

### Task 2: Feature Flags — Client Rotation & HLS Probation

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt`
- Modify: `android/app/src/test/java/com/albunyaan/tube/player/PlaybackFeatureFlagsTest.kt`

- [ ] **Step 1: Write failing tests for new flags**

Add to `PlaybackFeatureFlagsTest.kt`:
```kotlin
@Test
fun `client rotation uses build-time default when no override set`() {
    assertEquals(BuildConfig.ENABLE_CLIENT_ROTATION, featureFlags.isClientRotationEnabled)
}

@Test
fun `client rotation override true takes precedence over build-time default`() {
    featureFlags.setClientRotationEnabled(true)
    assertTrue(featureFlags.isClientRotationEnabled)
}

@Test
fun `client rotation override false disables even when build default is true`() {
    featureFlags.setClientRotationEnabled(false)
    assertFalse(featureFlags.isClientRotationEnabled)
}

@Test
fun `client rotation override null reverts to build-time default`() {
    featureFlags.setClientRotationEnabled(true)
    featureFlags.setClientRotationEnabled(null)
    assertEquals(BuildConfig.ENABLE_CLIENT_ROTATION, featureFlags.isClientRotationEnabled)
}

@Test
fun `hls probation uses build-time default when no override set`() {
    assertEquals(BuildConfig.ENABLE_HLS_PROBATION, featureFlags.isHlsProbationEnabled)
}

@Test
fun `hls probation override true takes precedence over build-time default`() {
    featureFlags.setHlsProbationEnabled(true)
    assertTrue(featureFlags.isHlsProbationEnabled)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "*.PlaybackFeatureFlagsTest" 2>&1 | tail -15
```
Expected: compilation error — `isClientRotationEnabled`, `ENABLE_CLIENT_ROTATION` not found yet.

- [ ] **Step 3: Add BuildConfig fields in build.gradle.kts**

In `android/app/build.gradle.kts`, inside the `defaultConfig` block after the existing flags (around line 86):
```kotlin
val enableClientRotation = localProps.getProperty("player.client.rotation.enabled", "true") == "true"
buildConfigField("boolean", "ENABLE_CLIENT_ROTATION", "$enableClientRotation")

val enableHlsProbation = localProps.getProperty("player.hls.probation.enabled", "true") == "true"
buildConfigField("boolean", "ENABLE_HLS_PROBATION", "$enableHlsProbation")
```

- [ ] **Step 4: Add constants and properties to PlaybackFeatureFlags**

In `PlaybackFeatureFlags.kt`:

Add to the `companion object` constants block (after `KEY_GENEROUS_CROP_BUDGET`):
```kotlin
const val KEY_CLIENT_ROTATION = "client_rotation"
const val KEY_HLS_PROBATION = "hls_probation"
```

Update `VALID_KEYS`:
```kotlin
private val VALID_KEYS = setOf(
    KEY_SYNTH_ADAPTIVE,
    KEY_MPD_PREFETCH,
    KEY_DEGRADATION_MANAGER,
    KEY_IOS_FETCH,
    KEY_GENEROUS_CROP_BUDGET,
    KEY_CLIENT_ROTATION,
    KEY_HLS_PROBATION
)
```

Add properties after `isGenerousCropBudgetEnabled`:
```kotlin
val isClientRotationEnabled: Boolean
    get() = resolveFlag(KEY_CLIENT_ROTATION, BuildConfig.ENABLE_CLIENT_ROTATION)

val isHlsProbationEnabled: Boolean
    get() = resolveFlag(KEY_HLS_PROBATION, BuildConfig.ENABLE_HLS_PROBATION)
```

Add setter methods after `setGenerousCropBudgetEnabled()`:
```kotlin
fun setClientRotationEnabled(enabled: Boolean?) {
    setOverride(KEY_CLIENT_ROTATION, enabled)
    Log.i(TAG, "CLIENT_ROTATION override set to: $enabled (effective: $isClientRotationEnabled)")
}

fun setHlsProbationEnabled(enabled: Boolean?) {
    setOverride(KEY_HLS_PROBATION, enabled)
    Log.i(TAG, "HLS_PROBATION override set to: $enabled (effective: $isHlsProbationEnabled)")
}
```

Update `clearAllOverridesInternal()` — add `.remove(KEY_CLIENT_ROTATION).remove(KEY_HLS_PROBATION)` to the chain.

Update `clearAllOverrides()` — same additions.

Update `getDiagnostics()` — add:
```kotlin
KEY_CLIENT_ROTATION to getFlagState(KEY_CLIENT_ROTATION, BuildConfig.ENABLE_CLIENT_ROTATION),
KEY_HLS_PROBATION to getFlagState(KEY_HLS_PROBATION, BuildConfig.ENABLE_HLS_PROBATION),
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "*.PlaybackFeatureFlagsTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt android/app/src/test/java/com/albunyaan/tube/player/PlaybackFeatureFlagsTest.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add KEY_CLIENT_ROTATION and KEY_HLS_PROBATION feature flags"
```

---

### Task 3: YoutubeClientRotator

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/extractor/YoutubeClientRotator.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/extractor/YoutubeClientRotatorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/albunyaan/tube/data/extractor/YoutubeClientRotatorTest.kt`:
```kotlin
package com.albunyaan.tube.data.extractor

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class YoutubeClientRotatorTest {

    private lateinit var rotator: YoutubeClientRotator
    private var testClock = 0L

    @Before
    fun setUp() {
        rotator = YoutubeClientRotator()
        testClock = 0L
        rotator.clock = { testClock }
    }

    @Test
    fun `initialClient returns IOS when ios enabled`() {
        assertEquals(YoutubeClientRotator.Client.IOS, rotator.initialClient(isIosEnabled = true))
    }

    @Test
    fun `initialClient returns ANDROID when ios disabled`() {
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.initialClient(isIosEnabled = false))
    }

    @Test
    fun `nextClient returns IOS on first call for new video`() {
        assertEquals(YoutubeClientRotator.Client.IOS, rotator.nextClient("vid1"))
    }

    @Test
    fun `nextClient returns ANDROID on second call for same video`() {
        rotator.nextClient("vid1")
        assertEquals(YoutubeClientRotator.Client.ANDROID, rotator.nextClient("vid1"))
    }

    @Test
    fun `nextClient returns null when all clients exhausted`() {
        rotator.nextClient("vid1") // IOS
        rotator.nextClient("vid1") // ANDROID
        assertNull(rotator.nextClient("vid1")) // exhausted
    }

    @Test
    fun `reset clears rotation state so next call starts from IOS`() {
        rotator.nextClient("vid1") // IOS → ANDROID state
        rotator.reset("vid1")
        assertEquals(YoutubeClientRotator.Client.IOS, rotator.nextClient("vid1"))
    }

    @Test
    fun `rotation state is independent per video`() {
        rotator.nextClient("vid1") // vid1: IOS
        rotator.nextClient("vid1") // vid1: ANDROID
        assertEquals(YoutubeClientRotator.Client.IOS, rotator.nextClient("vid2"))
    }

    @Test
    fun `expired state is evicted allowing rotation to restart from IOS`() {
        rotator.nextClient("vid1") // IOS at t=0
        testClock = 31L * 60 * 1000 // advance past 30-min TTL
        assertEquals(YoutubeClientRotator.Client.IOS, rotator.nextClient("vid1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "*.YoutubeClientRotatorTest" 2>&1 | tail -10
```
Expected: compilation error — class not found yet.

- [ ] **Step 3: Create YoutubeClientRotator**

Create `android/app/src/main/java/com/albunyaan/tube/data/extractor/YoutubeClientRotator.kt`:
```kotlin
package com.albunyaan.tube.data.extractor

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YoutubeClientRotator @Inject constructor() {

    enum class Client { IOS, ANDROID }

    companion object {
        private const val TAG = "YoutubeClientRotator"
        private const val EVICTION_TTL_MS = 30L * 60 * 1000
        private val ROTATION_ORDER = listOf(Client.IOS, Client.ANDROID)
    }

    private data class RotationState(val index: Int, val updatedAtMs: Long)

    private val states = ConcurrentHashMap<String, RotationState>()

    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    fun initialClient(isIosEnabled: Boolean): Client =
        if (isIosEnabled) Client.IOS else Client.ANDROID

    fun nextClient(videoId: String): Client? {
        evictExpired()
        val currentIndex = states[videoId]?.index ?: -1
        val nextIndex = currentIndex + 1
        if (nextIndex >= ROTATION_ORDER.size) {
            Log.d(TAG, "vid=$videoId all clients exhausted")
            return null
        }
        val next = ROTATION_ORDER[nextIndex]
        states[videoId] = RotationState(nextIndex, clock())
        Log.d(TAG, "vid=$videoId rotating to ${next.name} (attempt ${nextIndex + 1})")
        return next
    }

    fun reset(videoId: String) {
        states.remove(videoId)
    }

    private fun evictExpired() {
        val now = clock()
        states.entries.removeAll { (_, state) -> now - state.updatedAtMs > EVICTION_TTL_MS }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "*.YoutubeClientRotatorTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/extractor/YoutubeClientRotator.kt android/app/src/test/java/com/albunyaan/tube/data/extractor/YoutubeClientRotatorTest.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add YoutubeClientRotator for IOS→ANDROID rotation on 403"
```

---

### Task 4: Wire Client Rotation into NewPipeExtractorClient

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt`

- [ ] **Step 1: Add YoutubeClientRotator to NewPipeExtractorClient constructor**

In `NewPipeExtractorClient.kt`, change the class declaration (line 31) from:
```kotlin
class NewPipeExtractorClient(
    private val downloader: OkHttpDownloader,
    private val cache: MetadataCache,
    private val metrics: ExtractorMetricsReporter,
    private val featureFlags: PlaybackFeatureFlags,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : ExtractorClient {
```
to:
```kotlin
class NewPipeExtractorClient(
    private val downloader: OkHttpDownloader,
    private val cache: MetadataCache,
    private val metrics: ExtractorMetricsReporter,
    private val featureFlags: PlaybackFeatureFlags,
    private val clientRotator: YoutubeClientRotator = YoutubeClientRotator(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : ExtractorClient {
```

- [ ] **Step 2: Add applyClientSetting() private method**

Add this private method to `NewPipeExtractorClient`, after `applyIosFetchSetting()`:
```kotlin
private fun applyClientSetting(client: YoutubeClientRotator.Client) {
    val useIos = client == YoutubeClientRotator.Client.IOS
    YoutubeStreamExtractor.setFetchIosClient(useIos)
    if (BuildConfig.DEBUG) {
        android.util.Log.d(ADAPTIVE_PROBE_TAG, "applyClientSetting: client=${client.name} fetchIosClient=$useIos")
    }
}
```

- [ ] **Step 3: Use client rotation on forceRefresh in resolveStreams()**

In `NewPipeExtractorClient.resolveStreams()`, find the call to `applyIosFetchSetting()` that runs before each extraction. Replace the single call with a branch:

Find:
```kotlin
applyIosFetchSetting()
```
In the main extraction path (not the cached path), replace with:
```kotlin
if (forceRefresh && featureFlags.isClientRotationEnabled) {
    val nextClient = clientRotator.nextClient(videoId)
    if (nextClient != null) {
        applyClientSetting(nextClient)
    } else {
        applyIosFetchSetting()
    }
} else {
    applyIosFetchSetting()
    if (!forceRefresh) {
        clientRotator.reset(videoId) // fresh load → reset rotation state
    }
}
```

- [ ] **Step 4: Update DataModule to inject YoutubeClientRotator**

In `android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt`, update `provideNewPipeExtractorClient()`:
```kotlin
@Provides
@Singleton
fun provideNewPipeExtractorClient(
    downloader: OkHttpDownloader,
    cache: MetadataCache,
    metrics: ExtractorMetricsReporter,
    featureFlags: PlaybackFeatureFlags,
    clientRotator: YoutubeClientRotator
): NewPipeExtractorClient {
    return NewPipeExtractorClient(downloader, cache, metrics, featureFlags, clientRotator)
}
```

- [ ] **Step 5: Build to verify compilation**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run tests**

```bash
cd android && ./gradlew test 2>&1 | tail -10
```
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Wire YoutubeClientRotator into NewPipeExtractorClient — rotate IOS→ANDROID on forceRefresh 403"
```

---

### Task 5: SyntheticDashMediaSourceFactory — Replace data: URI with syntheticdash://

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/SyntheticDashMediaSourceFactory.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt`

- [ ] **Step 1: Update SyntheticDashMediaSourceFactory constructor and signatures**

In `SyntheticDashMediaSourceFactory.kt`, change the class declaration:
```kotlin
class SyntheticDashMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val mpdRegistry: SyntheticDashMpdRegistry
) {
```

Add the import at the top:
```kotlin
import com.albunyaan.tube.player.SyntheticDashMpdRegistry
import androidx.core.net.toUri
```

Change `createVideoSource()` signature:
```kotlin
fun createVideoSource(track: VideoTrack, durationSeconds: Long?, videoId: String): Result
```

Change `createAudioSource()` signature:
```kotlin
fun createAudioSource(track: AudioTrack, durationSeconds: Long?, videoId: String): Result
```

Thread `videoId` through to `generateDashSource()` — change its signature:
```kotlin
private fun generateDashSource(
    streamUrl: String,
    itagItem: ItagItem,
    durationSeconds: Long,
    streamType: String,
    videoId: String
): Result
```

And update each callsite inside `createVideoSource()` and `createAudioSource()` to pass `videoId`.

- [ ] **Step 2: Replace data: URI in generateDashSource()**

In `generateDashSource()`, replace the block starting at `// Create data: URI from MPD content for Media3`:
```kotlin
// Register MPD with in-process registry; served via syntheticdash:// scheme
mpdRegistry.register(videoId, mpdManifest)

val mediaItem = MediaItem.Builder()
    .setUri("${SyntheticDashDataSource.SCHEME}://$videoId".toUri())
    .setMimeType(MimeTypes.APPLICATION_MPD)
    .build()

val syntheticDataSourceFactory = SyntheticDashDataSource.Factory(mpdRegistry)
val source = DashMediaSource.Factory(syntheticDataSourceFactory)
    .createMediaSource(mediaItem)

Log.d(TAG, "Created synthetic DASH $streamType source via syntheticdash://$videoId (itag=${itagItem.id}, dur=${durationSeconds}s)")
Result.Success(source, "${SyntheticDashDataSource.SCHEME}://$videoId")
```

Remove the old `data:` URI lines and the `URLEncoder.encode(...)` call entirely.

- [ ] **Step 3: Add mpdRegistry to MultiQualityMediaSourceFactory**

In `MultiQualityMediaSourceFactory.kt`, update the constructor (line 67):
```kotlin
class MultiQualityMediaSourceFactory(
    private val context: Context,
    private val hlsPoisonRegistry: HlsPoisonRegistry? = null,
    private val multiRepFactory: MultiRepSyntheticDashMediaSourceFactory? = null,
    private val coldStartQualityChooser: ColdStartQualityChooser? = null,
    private val featureFlags: PlaybackFeatureFlags? = null,
    private val mpdRegistry: SyntheticDashMpdRegistry? = null,
    private val probationChecker: HlsProbationChecker? = null
) {
```

Add the import:
```kotlin
import com.albunyaan.tube.player.SyntheticDashMpdRegistry
import com.albunyaan.tube.player.HlsProbationChecker
```

At line 132, update the `SyntheticDashMediaSourceFactory` instantiation:
```kotlin
private val syntheticDashFactory by lazy {
    if (mpdRegistry != null) SyntheticDashMediaSourceFactory(cacheDataSourceFactory, mpdRegistry)
    else null
}
```

And replace the direct usage of `SyntheticDashMediaSourceFactory(cacheDataSourceFactory)` everywhere in the class with `syntheticDashFactory` (null-safe). In `createVideoMediaSource()` (line 658), pass `videoId` to `createVideoSource()` and `createAudioSource()`. The `videoId` is already available from `resolved.streamId`.

Update the callers inside `createVideoMediaSource()`:
```kotlin
val videoResult = syntheticDashFactory?.createVideoSource(videoTrack, resolved.durationSeconds?.toLong(), resolved.streamId)
    ?: return createProgressiveVideoSource(videoTrack, resolved)

val audioTrack = resolved.audioTracks.firstOrNull() ?: return createProgressiveVideoSource(videoTrack, resolved)
val audioResult = syntheticDashFactory?.createAudioSource(audioTrack, resolved.durationSeconds?.toLong(), resolved.streamId)
    ?: return createProgressiveVideoSource(videoTrack, resolved)
```

- [ ] **Step 4: Update PlayerFragment to pass mpdRegistry and probationChecker**

In `PlayerFragment.kt`, the `mediaSourceFactory` lazy (line 803):
```kotlin
private val mediaSourceFactory by lazy {
    com.albunyaan.tube.player.MultiQualityMediaSourceFactory(
        requireContext(),
        hlsPoisonRegistry,
        multiRepFactory,
        coldStartQualityChooser,
        featureFlags,
        mpdRegistry,
        probationChecker
    )
}
```

Add `@Inject` for `probationChecker` near the other `@Inject` fields:
```kotlin
@Inject lateinit var probationChecker: com.albunyaan.tube.player.HlsProbationChecker
```

`mpdRegistry` is already injected at line 101.

- [ ] **Step 5: Update ShortsPlayerFragment to inject and pass mpdRegistry + probationChecker**

In `ShortsPlayerFragment.kt`, add inject fields:
```kotlin
@Inject lateinit var mpdRegistry: com.albunyaan.tube.player.SyntheticDashMpdRegistry
@Inject lateinit var probationChecker: com.albunyaan.tube.player.HlsProbationChecker
```

Update the `MultiQualityMediaSourceFactory` instantiation (line 130):
```kotlin
val mediaSourceFactory = com.albunyaan.tube.player.MultiQualityMediaSourceFactory(
    requireContext(),
    hlsPoisonRegistry,
    multiRepFactory,
    coldStartQualityChooser,
    playbackFeatureFlags,
    mpdRegistry,
    probationChecker
)
```

- [ ] **Step 6: Build and test**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -15
cd android && ./gradlew test 2>&1 | tail -10
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/SyntheticDashMediaSourceFactory.kt android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt
git commit -m "[FIX]: [ANDROID-SHORTS-01]: Single-rep synthetic DASH uses syntheticdash:// registry instead of data: URI"
```

---

### Task 6: HLS Probation Checker

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/player/HlsProbationChecker.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/player/HlsProbationCheckerTest.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/albunyaan/tube/player/HlsProbationCheckerTest.kt`:
```kotlin
package com.albunyaan.tube.player

import org.junit.Assert.*
import org.junit.Test

class HlsProbationCheckerTest {

    private val checker = HlsProbationChecker()

    @Test
    fun `probe returns false for malformed URL`() {
        assertFalse(checker.probe("not-a-url", timeoutMs = 200))
    }

    @Test
    fun `probe returns false for unreachable host`() {
        assertFalse(checker.probe("https://this.host.does.not.exist.invalid/manifest.m3u8", timeoutMs = 200))
    }

    @Test
    fun `probe returns false when connect times out`() {
        // 203.0.113.0 is TEST-NET-3, guaranteed to be unreachable (RFC 5737)
        assertFalse(checker.probe("https://203.0.113.0/manifest.m3u8", timeoutMs = 300))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "*.HlsProbationCheckerTest" 2>&1 | tail -10
```
Expected: compilation error — class not found yet.

- [ ] **Step 3: Create HlsProbationChecker**

Create `android/app/src/main/java/com/albunyaan/tube/player/HlsProbationChecker.kt`:
```kotlin
package com.albunyaan.tube.player

import android.util.Log
import com.albunyaan.tube.util.HttpConstants
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HlsProbationChecker @Inject constructor() {

    companion object {
        private const val TAG = "HlsProbationChecker"
        private const val DEFAULT_TIMEOUT_MS = 500
    }

    fun probe(manifestUrl: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Boolean {
        return try {
            val url = URL(manifestUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", HttpConstants.YOUTUBE_IOS_USER_AGENT)
            conn.instanceFollowRedirects = false
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            val reachable = code in 200..299
            Log.d(TAG, "probation probe $manifestUrl → HTTP $code (reachable=$reachable)")
            reachable
        } catch (e: Exception) {
            Log.w(TAG, "probation probe failed for $manifestUrl: ${e.javaClass.simpleName}")
            false
        }
    }
}
```

- [ ] **Step 4: Wire probation into MultiQualityMediaSourceFactory.tryCreateAdaptiveSource()**

In `MultiQualityMediaSourceFactory.kt`, inside `tryCreateAdaptiveSource()`, find the block that starts `val hlsResult = if (!hlsPoisoned) {` and update it to add probation:

```kotlin
val hlsResult = if (!hlsPoisoned) {
    resolved.hlsUrl?.let { hlsUrl ->
        // HLS probation: HEAD probe before committing to HLS (avoids stall on 403)
        val probationEnabled = featureFlags?.isHlsProbationEnabled == true
        if (probationEnabled && probationChecker != null) {
            val reachable = probationChecker.probe(hlsUrl)
            if (!reachable) {
                if (videoId != null) hlsPoisonRegistry?.poisonHls(videoId, "PROBATION_FAIL")
                android.util.Log.d(TAG, "HLS probation failed for $videoId → poisoning, falling through to DASH")
                return@let null
            }
        }
        try {
            // ... existing HLS source creation code unchanged ...
```

The rest of the HLS source creation block remains identical.

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "*.HlsProbationCheckerTest" 2>&1 | tail -10
```
Expected: 3 tests pass (may take up to 5s for the timeout test).

- [ ] **Step 6: Full test run**

```bash
cd android && ./gradlew test 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/HlsProbationChecker.kt android/app/src/test/java/com/albunyaan/tube/player/HlsProbationCheckerTest.kt android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: HLS probation — HEAD probe before committing to HLS, poison on failure"
```

---

### Task 7: Telemetry Regression Matrix

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/StreamRequestTelemetry.kt`
- Modify: `android/app/src/test/java/com/albunyaan/tube/player/StreamRequestTelemetryTest.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt`

- [ ] **Step 1: Write failing tests for session tracking**

Add to `StreamRequestTelemetryTest.kt`:
```kotlin
@Test
fun `startSession records session with given videoId`() {
    telemetry.startSession("vid1", "IOS", "HLS")
    val session = telemetry.getActiveSession("vid1")
    assertNotNull(session)
    assertEquals("IOS", session!!.clientUsed)
    assertEquals("HLS", session.selectedSourceType)
}

@Test
fun `recordFirstFrame captures elapsed time after startSession`() {
    telemetry.startSession("vid1", "IOS", "HLS")
    advanceClock(350)
    telemetry.recordFirstFrame("vid1")
    val session = telemetry.getActiveSession("vid1")
    assertNotNull(session?.firstFrameMs)
    assertEquals(350L, session!!.firstFrameMs)
}

@Test
fun `recordRebuffer increments rebufferCount`() {
    telemetry.startSession("vid1", "IOS", "HLS")
    telemetry.recordRebuffer("vid1")
    telemetry.recordRebuffer("vid1")
    assertEquals(2, telemetry.getActiveSession("vid1")?.rebufferCount)
}

@Test
fun `record403 increments http403Count`() {
    telemetry.startSession("vid1", "IOS", "HLS")
    telemetry.record403("vid1")
    assertEquals(1, telemetry.getActiveSession("vid1")?.http403Count)
}

@Test
fun `recordAbrSwitch increments abrSwitchCount`() {
    telemetry.startSession("vid1", "IOS", "HLS")
    telemetry.recordAbrSwitch("vid1")
    telemetry.recordAbrSwitch("vid1")
    telemetry.recordAbrSwitch("vid1")
    assertEquals(3, telemetry.getActiveSession("vid1")?.abrSwitchCount)
}

@Test
fun `endSession clears active session and returns the record`() {
    telemetry.startSession("vid1", "IOS", "HLS")
    val record = telemetry.endSession("vid1")
    assertNotNull(record)
    assertNull(telemetry.getActiveSession("vid1"))
}
```

These tests require `telemetry.setTestClock { testTimeMs }` already established in `setUp()`.

- [ ] **Step 2: Run to verify failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "*.StreamRequestTelemetryTest" 2>&1 | tail -10
```
Expected: compilation errors for `startSession`, `getActiveSession`, etc.

- [ ] **Step 3: Add PlaybackSession data class and session API to StreamRequestTelemetry**

In `StreamRequestTelemetry.kt`, add after the `FailureRecord` data class:

```kotlin
data class PlaybackSession(
    val videoId: String,
    val startedAtMs: Long,
    val clientUsed: String,
    val selectedSourceType: String,
    var firstFrameMs: Long? = null,
    var rebufferCount: Int = 0,
    var http403Count: Int = 0,
    var abrSwitchCount: Int = 0,
    var recoveryStepCount: Int = 0
) {
    fun toLogJson(): String = buildString {
        append("{")
        append("\"videoId\":\"$videoId\",")
        append("\"clientUsed\":\"$clientUsed\",")
        append("\"selectedSourceType\":\"$selectedSourceType\",")
        append("\"firstFrameMs\":${firstFrameMs ?: -1},")
        append("\"rebufferCount\":$rebufferCount,")
        append("\"http403Count\":$http403Count,")
        append("\"abrSwitchCount\":$abrSwitchCount,")
        append("\"recoveryStepCount\":$recoveryStepCount,")
        append("\"sessionDurationMs\":${(currentTimeMs() - startedAtMs)}")
        append("}")
    }

    private fun currentTimeMs(): Long = System.currentTimeMillis()
}
```

Add a `ConcurrentHashMap<String, PlaybackSession>` field:
```kotlin
private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, PlaybackSession>()
```

Add session methods to `StreamRequestTelemetry`:
```kotlin
fun startSession(videoId: String, clientUsed: String, selectedSourceType: String) {
    activeSessions[videoId] = PlaybackSession(
        videoId = videoId,
        startedAtMs = testClock?.invoke() ?: System.currentTimeMillis(),
        clientUsed = clientUsed,
        selectedSourceType = selectedSourceType
    )
    Log.d(TAG, "session started: vid=$videoId client=$clientUsed source=$selectedSourceType")
}

fun recordFirstFrame(videoId: String) {
    activeSessions[videoId]?.let { session ->
        if (session.firstFrameMs == null) {
            val elapsed = (testClock?.invoke() ?: System.currentTimeMillis()) - session.startedAtMs
            session.firstFrameMs = elapsed
        }
    }
}

fun recordRebuffer(videoId: String) {
    activeSessions[videoId]?.rebufferCount = (activeSessions[videoId]?.rebufferCount ?: 0) + 1
}

fun record403(videoId: String) {
    activeSessions[videoId]?.http403Count = (activeSessions[videoId]?.http403Count ?: 0) + 1
}

fun recordAbrSwitch(videoId: String) {
    activeSessions[videoId]?.abrSwitchCount = (activeSessions[videoId]?.abrSwitchCount ?: 0) + 1
}

fun recordRecoveryStep(videoId: String) {
    activeSessions[videoId]?.recoveryStepCount = (activeSessions[videoId]?.recoveryStepCount ?: 0) + 1
}

fun getActiveSession(videoId: String): PlaybackSession? = activeSessions[videoId]

fun endSession(videoId: String): PlaybackSession? {
    val session = activeSessions.remove(videoId) ?: return null
    Log.i(TAG, "SESSION_END ${session.toLogJson()}")
    return session
}
```

Note: `StreamRequestTelemetry` already has a `var testClock: (() -> Long)? = null` field and a `fun setTestClock(clock: () -> Long)` method used in existing tests. The `startSession()` method reads time via `testClock?.invoke() ?: System.currentTimeMillis()` — this matches the existing pattern for injectable clocks in that class.

- [ ] **Step 4: Wire startSession/endSession/recordFirstFrame in PlayerFragment**

In `PlayerFragment.kt`:

At the point where playback begins (in the `StreamState.Ready` observer, where the player is prepared), add:
```kotlin
streamTelemetry.startSession(
    videoId = ready.streamId,
    clientUsed = "UNKNOWN", // will be improved once client is surfaced from NewPipeExtractorClient
    selectedSourceType = ready.sourceType ?: "UNKNOWN"
)
```

In the `Player.Listener` (where `onRenderedFirstFrame` is observed):
```kotlin
override fun onRenderedFirstFrame() {
    viewModel.currentVideoId()?.let { streamTelemetry.recordFirstFrame(it) }
}
```

In `onDestroyView()` (or where the player is released), end the session:
```kotlin
viewModel.currentVideoId()?.let { streamTelemetry.endSession(it) }
```

In the existing 403 handler (around line 2028 where `streamTelemetry.recordFailure(...)` is called), add:
```kotlin
streamTelemetry.record403(videoId)
```

- [ ] **Step 5: Run all tests**

```bash
cd android && ./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/StreamRequestTelemetry.kt android/app/src/test/java/com/albunyaan/tube/player/StreamRequestTelemetryTest.kt android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add playback session telemetry matrix (firstFrame, rebuffer, 403, ABR, recovery)"
```

---

## Phase 2 — Subtitles

---

### Task 8: Subtitle Extraction in NewPipeExtractorClient

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt`

`SubtitlesStream` API (verified from JAR):
- `content` → URL string
- `languageTag` → locale string e.g. `"en"`, `"ar"`
- `displayLanguageName` → human-readable e.g. `"English"`, `"Arabic"`
- `extension` → file extension e.g. `"vtt"`, `"srv3"`
- `isAutoGenerated` → Boolean

- [ ] **Step 1: Replace the subtitle TODO in NewPipeExtractorClient**

Find the comment at line 517:
```kotlin
// TODO: Extract subtitle tracks from StreamInfo when NewPipe adds support
return ResolvedStreams(
    ...
    subtitleTracks = emptyList(),
    ...
)
```

Replace `subtitleTracks = emptyList()` with:
```kotlin
subtitleTracks = streamInfo.subtitles.mapNotNull { sub ->
    val code = sub.languageTag?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    com.albunyaan.tube.data.extractor.SubtitleTrack(
        url = sub.content,
        languageCode = code,
        languageName = sub.displayLanguageName?.takeIf { it.isNotBlank() } ?: code,
        format = sub.extension.takeIf { it.isNotBlank() },
        isAutoGenerated = sub.isAutoGenerated
    )
},
```

Remove the `// TODO: Extract subtitle tracks...` comment.

- [ ] **Step 2: Build to verify compilation**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run tests**

```bash
cd android && ./gradlew test 2>&1 | tail -10
```
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Extract subtitle tracks from NewPipe StreamInfo"
```

---

### Task 9: Wire SubtitleConfiguration into MediaItem

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt`

- [ ] **Step 1: Add subtitle configuration builder helper**

In `MultiQualityMediaSourceFactory.kt`, add a private helper method:
```kotlin
private fun buildSubtitleConfigurations(
    subtitleTracks: List<com.albunyaan.tube.data.extractor.SubtitleTrack>
): List<androidx.media3.common.MediaItem.SubtitleConfiguration> {
    return subtitleTracks.mapNotNull { track ->
        val mimeType = when (track.format?.lowercase()) {
            "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
            "ttml", "xml" -> androidx.media3.common.MimeTypes.APPLICATION_TTML
            "srv3" -> androidx.media3.common.MimeTypes.TEXT_VTT // YouTube srv3 is VTT-compatible
            else -> androidx.media3.common.MimeTypes.TEXT_VTT
        }
        val roleFlags = if (track.isAutoGenerated) {
            androidx.media3.common.C.ROLE_FLAG_SUBTITLE or androidx.media3.common.C.ROLE_FLAG_DESCRIBES_VIDEO
        } else {
            androidx.media3.common.C.ROLE_FLAG_SUBTITLE
        }
        androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(track.url))
            .setMimeType(mimeType)
            .setLanguage(track.languageCode)
            .setRoleFlags(roleFlags)
            .setLabel(track.languageName)
            .build()
    }
}
```

- [ ] **Step 2: Apply subtitle configurations to HLS and DASH MediaItem builders**

In `tryCreateAdaptiveSource()`, update the HLS `MediaItem` builder:
```kotlin
val subtitleConfigs = buildSubtitleConfigurations(resolved.subtitleTracks)
val mediaItem = MediaItem.Builder()
    .setUri(hlsUrl)
    .setMimeType(MimeTypes.APPLICATION_M3U8)
    .apply { if (subtitleConfigs.isNotEmpty()) setSubtitleConfigurations(subtitleConfigs) }
    .build()
```

Apply the same change to the DASH `MediaItem` builder in the same function.

Also apply to progressive `MediaItem` builders in `createVideoMediaSource()` and `createAudioOnlySource()` so subtitles are available on all playback paths.

- [ ] **Step 3: Build and verify**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -10
cd android && ./gradlew test 2>&1 | tail -10
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add SubtitleConfiguration to all MediaItem builders"
```

---

### Task 10: SubtitlePickerDialog

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/shared/SubtitlePickerDialog.kt`
- Modify: `android/app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add string resources**

In `android/app/src/main/res/values/strings.xml`, add:
```xml
<string name="subtitle_picker_title">Subtitles</string>
<string name="subtitle_off">Off</string>
<string name="subtitle_auto_suffix"> (auto)</string>
<string name="subtitle_picker_cd">Select subtitle language</string>
```

Also add to `android/app/src/main/res/values/strings-ar.xml` if it exists (mirror entries in Arabic):
```xml
<string name="subtitle_picker_title">الترجمات</string>
<string name="subtitle_off">إيقاف</string>
<string name="subtitle_auto_suffix"> (تلقائي)</string>
<string name="subtitle_picker_cd">اختر لغة الترجمة</string>
```

- [ ] **Step 2: Create SubtitlePickerDialog**

Create `android/app/src/main/java/com/albunyaan/tube/ui/shared/SubtitlePickerDialog.kt`:
```kotlin
package com.albunyaan.tube.ui.shared

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.albunyaan.tube.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SubtitlePickerDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val codes = arguments?.getStringArray(ARG_CODES).orEmpty()
        val names = arguments?.getStringArray(ARG_NAMES).orEmpty()
        val autoFlags = arguments?.getBooleanArray(ARG_AUTO_FLAGS) ?: BooleanArray(codes.size)
        val currentCode = arguments?.getString(ARG_CURRENT_CODE)

        // "Off" is index 0; subtitle tracks start at index 1
        val labels = Array(codes.size + 1) { i ->
            if (i == 0) getString(R.string.subtitle_off)
            else {
                val trackIndex = i - 1
                val base = names.getOrNull(trackIndex).orEmpty()
                if (autoFlags.getOrNull(trackIndex) == true) {
                    base + getString(R.string.subtitle_auto_suffix)
                } else {
                    base
                }
            }
        }
        val allCodes = arrayOf(null) + codes  // null = Off

        val checkedIndex = if (currentCode == null) 0
        else codes.indexOfFirst { it == currentCode }.let { if (it >= 0) it + 1 else 0 }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subtitle_picker_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val code = allCodes.getOrNull(which)
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_SELECTED_CODE to code)
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "SubtitlePickerDialog"
        const val REQUEST_KEY = "subtitle_picker_request"
        const val RESULT_SELECTED_CODE = "selected_code" // null = Off

        private const val ARG_CODES = "codes"
        private const val ARG_NAMES = "names"
        private const val ARG_AUTO_FLAGS = "auto_flags"
        private const val ARG_CURRENT_CODE = "current_code"

        fun newInstance(
            tracks: List<Triple<String, String, Boolean>>, // (code, displayName, isAuto)
            currentCode: String?
        ): SubtitlePickerDialog {
            val codes = tracks.map { it.first }.toTypedArray()
            val names = tracks.map { it.second }.toTypedArray()
            val autoFlags = BooleanArray(tracks.size) { tracks[it].third }
            return SubtitlePickerDialog().apply {
                arguments = bundleOf(
                    ARG_CODES to codes,
                    ARG_NAMES to names,
                    ARG_AUTO_FLAGS to autoFlags,
                    ARG_CURRENT_CODE to currentCode
                )
            }
        }
    }
}
```

- [ ] **Step 3: Build to verify compilation**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/shared/SubtitlePickerDialog.kt android/app/src/main/res/values/strings.xml
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add SubtitlePickerDialog (shared, MaterialAlertDialog pattern)"
```

---

### Task 11: CC Button in Regular Player

**Files:**
- Create: `android/app/src/main/res/drawable/ic_closed_captions.xml`
- Modify: `android/app/src/main/res/layout/fragment_player.xml`
- Modify: `android/app/src/main/res/layout-sw600dp/fragment_player.xml`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt`

- [ ] **Step 1: Create CC icon drawable**

Create `android/app/src/main/res/drawable/ic_closed_captions.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <path
      android:fillColor="#FF000000"
      android:pathData="M19,4L5,4c-1.103,0 -2,0.897 -2,2v12c0,1.103 0.897,2 2,2h14c1.103,0 2,-0.897 2,-2L21,6c0,-1.103 -0.897,-2 -2,-2zM5,18L5,6h14l0.001,12L5,18zM7,15h3c0.552,0 1,-0.448 1,-1v-1H9v0.5H8v-3h1V11h2v-1c0,-0.552 -0.448,-1 -1,-1H7c-0.552,0 -1,0.448 -1,1v4c0,0.552 0.448,1 1,1zM14,15h3c0.552,0 1,-0.448 1,-1v-1h-2v0.5h-1v-3h1V11h2v-1c0,-0.552 -0.448,-1 -1,-1h-3c-0.552,0 -1,0.448 -1,1v4c0,0.552 0.448,1 1,1z"/>
</vector>
```

- [ ] **Step 2: Add CC button to fragment_player.xml**

In `android/app/src/main/res/layout/fragment_player.xml`, after the `audioLanguageButton` `ImageButton` block (line ~241), insert:
```xml
<!-- CC/Subtitle Button (hidden until video has subtitle tracks) -->
<ImageButton
    android:id="@+id/subtitleButton"
    android:layout_width="@dimen/touch_target_min"
    android:layout_height="@dimen/touch_target_min"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:src="@drawable/ic_closed_captions"
    android:contentDescription="@string/subtitle_picker_cd"
    android:visibility="gone"
    app:tint="@android:color/white" />
```

Apply the exact same addition to `android/app/src/main/res/layout-sw600dp/fragment_player.xml` at the same position.

- [ ] **Step 3: Wire CC button in PlayerFragment**

In `PlayerFragment.kt`, after the `audioLanguageButton.setOnClickListener` block (line ~467), add:
```kotlin
binding.subtitleButton.setOnClickListener {
    showSubtitlePicker()
}

childFragmentManager.setFragmentResultListener(
    com.albunyaan.tube.ui.shared.SubtitlePickerDialog.REQUEST_KEY,
    viewLifecycleOwner
) { _, result ->
    val code = result.getString(com.albunyaan.tube.ui.shared.SubtitlePickerDialog.RESULT_SELECTED_CODE)
    player?.trackSelectionParameters = player?.trackSelectionParameters
        ?.buildUpon()
        ?.setPreferredTextLanguage(code)
        ?.build()
        ?: return@setFragmentResultListener
}
```

Add the `showSubtitlePicker()` private method near `showAudioLanguagePicker()`:
```kotlin
private fun showSubtitlePicker() {
    val streamState = viewModel.state.value.streamState
    val subtitles = (streamState as? StreamState.Ready)
        ?.selection?.resolved?.subtitleTracks ?: return
    val tracks = subtitles.map { Triple(it.languageCode, it.languageName, it.isAutoGenerated) }
    val currentCode = player?.trackSelectionParameters?.preferredTextLanguage
    com.albunyaan.tube.ui.shared.SubtitlePickerDialog.newInstance(tracks, currentCode)
        .show(childFragmentManager, com.albunyaan.tube.ui.shared.SubtitlePickerDialog.TAG)
}
```

In the stream state observer (near line 1292 where `audioLanguageButton.isVisible` is updated), add:
```kotlin
val hasSubtitles = ready.selection.resolved.subtitleTracks.isNotEmpty()
binding.subtitleButton.isVisible = hasSubtitles
```

- [ ] **Step 4: Build and verify**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/drawable/ic_closed_captions.xml android/app/src/main/res/layout/fragment_player.xml android/app/src/main/res/layout-sw600dp/fragment_player.xml android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: CC button + SubtitlePickerDialog in regular player"
```

---

### Task 12: CC Button in Shorts Player

**Files:**
- Modify: `android/app/src/main/res/layout/item_shorts_page.xml`
- Modify: `android/app/src/main/res/layout-sw600dp/item_shorts_page.xml`
- Modify: `android/app/src/main/res/layout-sw720dp/item_shorts_page.xml`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPageViewHolder.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt`

- [ ] **Step 1: Add CC button to all three item_shorts_page.xml variants**

In `android/app/src/main/res/layout/item_shorts_page.xml`, after the `shortAudioTrackBtn` block (line ~78), insert:
```xml
<ImageButton
    android:id="@+id/shortSubtitleBtn"
    android:layout_width="56dp"
    android:layout_height="56dp"
    android:layout_marginTop="@dimen/spacing_md"
    android:background="?attr/selectableItemBackgroundBorderless"
    android:contentDescription="@string/subtitle_picker_cd"
    android:src="@drawable/ic_closed_captions"
    android:scaleType="center"
    android:visibility="gone"
    app:tint="#FFFFFFFF"/>
```

Apply the exact same change to `layout-sw600dp/item_shorts_page.xml` and `layout-sw720dp/item_shorts_page.xml`.

- [ ] **Step 2: Add CC button to ShortsPageViewHolder.bind()**

In `ShortsPageViewHolder.kt`, the `bind()` method signature currently takes `hasMultipleAudioTracks` and `onAudioTrackTap`. Add `hasSubtitles: Boolean` and `onSubtitleTap: () -> Unit`:

```kotlin
fun bind(
    item: ShortsItem,
    hasMultipleAudioTracks: Boolean,
    onAudioTrackTap: () -> Unit,
    hasSubtitles: Boolean,
    onSubtitleTap: () -> Unit
) {
    // ... existing bind code ...
    binding.shortSubtitleBtn.visibility = if (hasSubtitles) View.VISIBLE else View.GONE
    binding.shortSubtitleBtn.setOnClickListener { onSubtitleTap() }
}
```

Add a visibility update method:
```kotlin
fun setSubtitleButtonVisible(visible: Boolean) {
    binding.shortSubtitleBtn.visibility = if (visible) View.VISIBLE else View.GONE
}
```

- [ ] **Step 3: Update ShortsPagerAdapter.Callbacks to include subtitle callback**

In `ShortsPlayerFragment.kt`, find `ShortsPagerAdapter.Callbacks(...)` and add:
```kotlin
onSubtitleTap = { idx -> openSubtitlePicker(idx) },
```

In `ShortsPagerAdapter.kt`, add `onSubtitleTap: (Int) -> Unit` to the `Callbacks` data class and pass it through to `ShortsPageViewHolder.bind()`.

- [ ] **Step 4: Add openSubtitlePicker() to ShortsPlayerFragment**

In `ShortsPlayerFragment.kt`, add:
```kotlin
private fun openSubtitlePicker(pageIndex: Int) {
    val videoId = viewModel.getVideoIdAt(pageIndex) ?: return
    val subtitles = viewModel.subtitleTracksFor(videoId) ?: return
    if (subtitles.isEmpty()) return
    val tracks = subtitles.map { Triple(it.languageCode, it.languageName, it.isAutoGenerated) }
    val currentCode = viewModel.player.trackSelectionParameters.preferredTextLanguage
    val dialog = com.albunyaan.tube.ui.shared.SubtitlePickerDialog.newInstance(tracks, currentCode)
    dialog.show(childFragmentManager, com.albunyaan.tube.ui.shared.SubtitlePickerDialog.TAG)
}

// In the fragment result listener setup (near other setFragmentResultListener calls):
childFragmentManager.setFragmentResultListener(
    com.albunyaan.tube.ui.shared.SubtitlePickerDialog.REQUEST_KEY,
    viewLifecycleOwner
) { _, result ->
    val code = result.getString(com.albunyaan.tube.ui.shared.SubtitlePickerDialog.RESULT_SELECTED_CODE)
    viewModel.player.trackSelectionParameters = viewModel.player.trackSelectionParameters
        .buildUpon()
        .setPreferredTextLanguage(code)
        .build()
}
```

If `viewModel.subtitleTracksFor()` doesn't exist, add it to `ShortsPlayerViewModel.kt`.

First check how resolved streams are held — run:
```bash
grep -n "resolvedStreams\|ResolvedStreams\|subtitleTracks\|streamId" \
  android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerViewModel.kt | head -20
```

If resolved streams are stored in a `Map<String, ResolvedStreams>` field called e.g. `resolvedStreamsCache`:
```kotlin
fun subtitleTracksFor(videoId: String): List<com.albunyaan.tube.data.extractor.SubtitleTrack>? =
    resolvedStreamsCache[videoId]?.subtitleTracks
```

If they are held inside a `StateFlow` of items, find the state field (e.g. `_state`) and navigate:
```kotlin
fun subtitleTracksFor(videoId: String): List<com.albunyaan.tube.data.extractor.SubtitleTrack>? =
    _state.value.resolvedStreams[videoId]?.subtitleTracks
```

Use whichever matches the existing field. The subtitle list is already populated by Task 8.

- [ ] **Step 5: Build final**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -10
cd android && ./gradlew test 2>&1 | tail -10
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 6: Final commit**

```bash
git add android/app/src/main/res/layout/item_shorts_page.xml android/app/src/main/res/layout-sw600dp/item_shorts_page.xml android/app/src/main/res/layout-sw720dp/item_shorts_page.xml android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPageViewHolder.kt android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: CC button + SubtitlePickerDialog in Shorts player"
```

---

## Post-Implementation Checklist

- [ ] `./gradlew test` passes with zero failures
- [ ] `./gradlew assembleDebug` builds cleanly
- [ ] Verify on phone emulator: CC button hidden when no subtitles, visible when available
- [ ] Verify HLS probation logs appear in logcat when HLS is unreachable
- [ ] Verify `SESSION_END` JSON log appears in logcat after each video session
- [ ] Verify single-rep DASH uses `syntheticdash://` in logcat (not `data:application/dash+xml`)
- [ ] Invoke `superpowers:finishing-a-development-branch` before merging
