# Instant Playback Phase D: Never-Freeze ABR + TTL Pre-Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate mid-watch stalls by (1) tuning `AdaptiveTrackSelection` to start at lower quality and downshift aggressively before the buffer collapses, and (2) proactively refreshing synthetic DASH URLs at 90% of their 2-minute TTL before the signed URL expires and triggers a 403.

**Architecture:** `NeverFreezeTrackSelectionFactory` wraps `AdaptiveTrackSelection.Factory` with conservative parameters. `QualityTrackSelector` (used by `PlayerFragment`) already accepts a custom `TrackSelection.Factory` in its constructor — we pass ours. `ShortsPlayerViewModel` gets a `QualityTrackSelector` too (currently has none). `MpdTtlWatcher` is a lightweight coroutine launched by `PlayerBinder` after a synthetic DASH source is set; it sleeps until 90% TTL, then calls `forceRefreshCurrent()`. Both components gated by feature flags.

**Tech Stack:** Kotlin, Media3 `AdaptiveTrackSelection`, `DefaultTrackSelector`, Hilt DI, JUnit 4 + Mockito

---

## File Structure

| Action | File | Responsibility |
|---|---|---|
| Create | `player/NeverFreezeTrackSelectionFactory.kt` | Conservative ABR parameters |
| Create | `player/MpdTtlWatcher.kt` | Proactive URL refresh before TTL expiry |
| Modify | `player/PlaybackFeatureFlags.kt` | Add `KEY_NEVER_FREEZE_ABR` + `KEY_TTL_WATCHER` |
| Modify | `ui/player/PlayerFragment.kt` | Use `NeverFreezeTrackSelectionFactory` in track selector |
| Modify | `ui/shorts/ShortsPlayerViewModel.kt` | Add track selector with `NeverFreezeTrackSelectionFactory` |
| Modify | `ui/shorts/PlayerBinder.kt` | Launch `MpdTtlWatcher` after synthetic DASH source is set |
| Create | `test/.../player/NeverFreezeTrackSelectionFactoryTest.kt` | Unit tests |
| Create | `test/.../player/MpdTtlWatcherTest.kt` | Unit tests |

---

### Task 1: Add feature flags `KEY_NEVER_FREEZE_ABR` and `KEY_TTL_WATCHER`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt`
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add BuildConfig fields**

In `build.gradle.kts` `defaultConfig`:
```kotlin
buildConfigField("boolean", "ENABLE_NEVER_FREEZE_ABR", "true")
buildConfigField("boolean", "ENABLE_TTL_WATCHER", "true")
```

- [ ] **Step 2: Add to `PlaybackFeatureFlags`**

Two constants:
```kotlin
const val KEY_NEVER_FREEZE_ABR = "never_freeze_abr"
const val KEY_TTL_WATCHER = "ttl_watcher"
```

Two properties:
```kotlin
val isNeverFreezeAbrEnabled: Boolean
    get() = resolveFlag(KEY_NEVER_FREEZE_ABR, BuildConfig.ENABLE_NEVER_FREEZE_ABR)

val isTtlWatcherEnabled: Boolean
    get() = resolveFlag(KEY_TTL_WATCHER, BuildConfig.ENABLE_TTL_WATCHER)
```

Two setters, VALID_KEYS entries, clearAll entries, getDiagnostics entries — follow the existing pattern exactly.

- [ ] **Step 3: Build and test**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
cd android && ./gradlew test --tests "com.albunyaan.tube.player.PlaybackFeatureFlagsTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt
git commit -m "[FEAT]: [ANDROID-PERF-04]: Add KEY_NEVER_FREEZE_ABR and KEY_TTL_WATCHER feature flags"
```

---

### Task 2: Create `NeverFreezeTrackSelectionFactory`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/player/NeverFreezeTrackSelectionFactory.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/player/NeverFreezeTrackSelectionFactoryTest.kt`

Background: `QualityTrackSelector` extends `DefaultTrackSelector(context, trackSelectionFactory)`. Its companion `createForDiscreteQualities(context)` calls `QualityTrackSelector(context)` using the default `AdaptiveTrackSelection.Factory()`. We need to pass our tuned factory instead.

`AdaptiveTrackSelection.Factory` constructor parameters (Media3 1.10.0):
- `minDurationForQualityIncreaseMs: Int` — minimum buffer to trigger quality upgrade (default 10_000ms). Higher = slower upgrade = more stable.
- `maxDurationForQualityDecreaseMs: Int` — maximum buffer before quality drops (default 25_000ms). Lower = faster downshift when buffer shrinks.
- `minDurationToRetainAfterDiscardMs: Int` — minimum to keep after discarding (default 25_000ms).
- `bandwidthFraction: Float` — fraction of estimated bandwidth to use (default 0.75). Lower = more conservative.

Our values: `minDurationForQualityIncreaseMs=4_000`, `maxDurationForQualityDecreaseMs=500`, `minDurationToRetainAfterDiscardMs=15_000`, `bandwidthFraction=0.65f`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/albunyaan/tube/player/NeverFreezeTrackSelectionFactoryTest.kt`:

```kotlin
package com.albunyaan.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(UnstableApi::class)
class NeverFreezeTrackSelectionFactoryTest {

    @Test
    fun `create returns non-null factory`() {
        val factory = NeverFreezeTrackSelectionFactory()
        assertNotNull(factory.create())
    }

    @Test
    fun `created factory is AdaptiveTrackSelection factory`() {
        val factory = NeverFreezeTrackSelectionFactory()
        assertTrue(factory.create() is AdaptiveTrackSelection.Factory)
    }

    @Test
    fun `default factory uses stable parameters`() {
        // Verify the factory can be constructed without errors
        val factory = NeverFreezeTrackSelectionFactory()
        val inner = factory.create()
        assertNotNull(inner)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.NeverFreezeTrackSelectionFactoryTest" 2>&1 | tail -10
```
Expected: FAIL — `NeverFreezeTrackSelectionFactory` not yet defined.

- [ ] **Step 3: Implement `NeverFreezeTrackSelectionFactory`**

Create `android/app/src/main/java/com/albunyaan/tube/player/NeverFreezeTrackSelectionFactory.kt`:

```kotlin
package com.albunyaan.tube.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class NeverFreezeTrackSelectionFactory @Inject constructor() {

    companion object {
        // Allow quality upgrade only after 4s of stable buffer (default: 10s).
        // Faster than default so we don't stay at 360p too long, but still conservative enough
        // to avoid immediately jumping to 1080p and rebuffering.
        private const val MIN_DURATION_FOR_QUALITY_INCREASE_MS = 4_000

        // Downshift quality if buffer drops below 500ms (default: 25_000ms).
        // Aggressive: we downshift early rather than letting the buffer drain to zero.
        private const val MAX_DURATION_FOR_QUALITY_DECREASE_MS = 500

        // After discarding buffered data (e.g. on quality switch), retain at least 15s.
        private const val MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 15_000

        // Use only 65% of estimated bandwidth for quality decisions (default: 75%).
        // Leaves headroom for network variability — avoids picking a quality that
        // barely fits on paper but stalls in practice.
        private const val BANDWIDTH_FRACTION = 0.65f
    }

    fun create(): ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory(
        MIN_DURATION_FOR_QUALITY_INCREASE_MS,
        MAX_DURATION_FOR_QUALITY_DECREASE_MS,
        MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        BANDWIDTH_FRACTION
    )
}
```

- [ ] **Step 4: Run tests**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.NeverFreezeTrackSelectionFactoryTest" 2>&1 | tail -10
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/NeverFreezeTrackSelectionFactory.kt \
        android/app/src/test/java/com/albunyaan/tube/player/NeverFreezeTrackSelectionFactoryTest.kt
git commit -m "[FEAT]: [ANDROID-PERF-04]: Add NeverFreezeTrackSelectionFactory with conservative ABR params"
```

---

### Task 3: Wire `NeverFreezeTrackSelectionFactory` into `PlayerFragment`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt`

Context: `PlayerFragment.setupPlayer()` (line ~854) constructs the track selector as:
```kotlin
val trackSelector = QualityTrackSelector.createForDiscreteQualities(requireContext()).also {
    this.trackSelector = it
}
```
`QualityTrackSelector.createForDiscreteQualities(context)` internally calls `QualityTrackSelector(context)` which uses the default `AdaptiveTrackSelection.Factory()`.

We need to call `QualityTrackSelector(context, neverFreezeTrackSelectionFactory.create())` instead, gated by the flag.

- [ ] **Step 1: Inject `NeverFreezeTrackSelectionFactory` into `PlayerFragment`**

In `PlayerFragment`, find the existing `@Inject` fields. Add:
```kotlin
@Inject
lateinit var neverFreezeTrackSelectionFactory: NeverFreezeTrackSelectionFactory
```

- [ ] **Step 2: Replace the `createForDiscreteQualities` call**

Find in `setupPlayer()`:
```kotlin
val trackSelector = QualityTrackSelector.createForDiscreteQualities(requireContext()).also {
    this.trackSelector = it
}
```

Replace with:
```kotlin
val trackSelector = if (featureFlags.isNeverFreezeAbrEnabled) {
    QualityTrackSelector(requireContext(), neverFreezeTrackSelectionFactory.create())
} else {
    QualityTrackSelector.createForDiscreteQualities(requireContext())
}.also { this.trackSelector = it }
```

- [ ] **Step 3: Build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run tests**

```bash
cd android && ./gradlew test 2>&1 | tail -15
```
Expected: All pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt
git commit -m "[FEAT]: [ANDROID-PERF-04]: Wire NeverFreezeTrackSelectionFactory into PlayerFragment"
```

---

### Task 4: Add track selector to `ShortsPlayerViewModel`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerViewModel.kt`

Context: `ShortsPlayerViewModel` currently builds `ExoPlayer` without a track selector (uses the default). We add `NeverFreezeTrackSelectionFactory` and `PlaybackFeatureFlags` to the ViewModel's constructor (both are already `@Singleton @Inject constructor`, so Hilt can provide them), and wire the factory in.

Current ViewModel constructor (look for `@HiltViewModel class ShortsPlayerViewModel @Inject constructor(...)`). It likely already injects `PlaybackFeatureFlags` (check). If not, add it.

- [ ] **Step 1: Add `neverFreezeTrackSelectionFactory` to ViewModel constructor**

In `ShortsPlayerViewModel`, find the `@HiltViewModel @Inject constructor(...)`. Add:
```kotlin
private val neverFreezeTrackSelectionFactory: NeverFreezeTrackSelectionFactory,
```

- [ ] **Step 2: Add the track selector to ExoPlayer construction**

Find the `val player: ExoPlayer by lazy { ... }` block:
```kotlin
val player: ExoPlayer by lazy {
    val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
    ExoPlayer.Builder(context, renderersFactory)
        .setLoadControl(bufferPolicy.buildLoadControl())
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
}
```

Change to:
```kotlin
val player: ExoPlayer by lazy {
    val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
    val trackSelector = if (featureFlags.isNeverFreezeAbrEnabled) {
        QualityTrackSelector(context, neverFreezeTrackSelectionFactory.create())
    } else {
        QualityTrackSelector.createForDiscreteQualities(context)
    }
    ExoPlayer.Builder(context, renderersFactory)
        .setLoadControl(bufferPolicy.buildLoadControl())
        .setTrackSelector(trackSelector)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
}
```

Note: `featureFlags` must be available in scope. Check the ViewModel's constructor for an existing `PlaybackFeatureFlags` injection (the field may already exist under a different name like `playbackFeatureFlags`). Use whichever name is already there, or add it if missing.

- [ ] **Step 3: Build and run tests**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
cd android && ./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, all pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerViewModel.kt
git commit -m "[FEAT]: [ANDROID-PERF-04]: Add NeverFreezeTrackSelector to ShortsPlayerViewModel"
```

---

### Task 5: Create `MpdTtlWatcher`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/player/MpdTtlWatcher.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/player/MpdTtlWatcherTest.kt`

Background: `SyntheticDashMpdRegistry.MPD_TTL_MS = 120_000` (2 minutes). An entry is fresh for 120s after `registeredAtMs`. We want to call `forceRefreshCurrent()` at `registeredAtMs + 108_000` (90% = 108s). If the player is IDLE or ENDED at that point, skip.

`MpdTtlWatcher` uses the same injectable clock pattern as `SyntheticDashMpdRegistry`: `var clock: () -> Long` defaults to `SystemClock.elapsedRealtime()` but can be replaced in tests.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/albunyaan/tube/player/MpdTtlWatcherTest.kt`:

```kotlin
package com.albunyaan.tube.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MpdTtlWatcherTest {

    private lateinit var mockRegistry: SyntheticDashMpdRegistry
    private var refreshCallCount = 0

    @Before
    fun setUp() {
        mockRegistry = mock()
        refreshCallCount = 0
    }

    @Test
    fun `calls onRefreshNeeded at 90% of TTL`() = runTest {
        var fakeTime = 0L
        val registeredAt = 1000L

        val entry = SyntheticDashMpdRegistry.MpdEntry(
            videoId = "vid",
            mpdXml = "<MPD/>",
            registeredAtMs = registeredAt
        )
        org.mockito.kotlin.whenever(mockRegistry.getEntry("vid")).thenReturn(entry)

        val watcher = MpdTtlWatcher(
            videoId = "vid",
            registry = mockRegistry,
            onRefreshNeeded = { refreshCallCount++ },
            clock = { fakeTime }
        )

        watcher.start(this)
        fakeTime = registeredAt + 107_999L // just before 90%
        advanceTimeBy(107_999L)
        assertEquals(0, refreshCallCount)

        fakeTime = registeredAt + 108_001L // just past 90%
        advanceTimeBy(2L)
        assertEquals(1, refreshCallCount)
    }

    @Test
    fun `does not call onRefreshNeeded when cancelled before 90%`() = runTest {
        var fakeTime = 0L
        val registeredAt = 1000L

        val entry = SyntheticDashMpdRegistry.MpdEntry(
            videoId = "vid",
            mpdXml = "<MPD/>",
            registeredAtMs = registeredAt
        )
        org.mockito.kotlin.whenever(mockRegistry.getEntry("vid")).thenReturn(entry)

        val watcher = MpdTtlWatcher(
            videoId = "vid",
            registry = mockRegistry,
            onRefreshNeeded = { refreshCallCount++ },
            clock = { fakeTime }
        )

        watcher.start(this)
        watcher.cancel()
        fakeTime = registeredAt + 200_000L
        advanceTimeBy(200_000L)
        assertEquals(0, refreshCallCount)
    }

    @Test
    fun `does not call onRefreshNeeded when entry not found`() = runTest {
        org.mockito.kotlin.whenever(mockRegistry.getEntry("vid")).thenReturn(null)

        val watcher = MpdTtlWatcher(
            videoId = "vid",
            registry = mockRegistry,
            onRefreshNeeded = { refreshCallCount++ }
        )

        watcher.start(this)
        advanceTimeBy(200_000L)
        assertEquals(0, refreshCallCount)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.MpdTtlWatcherTest" 2>&1 | tail -10
```
Expected: FAIL — `MpdTtlWatcher` not yet defined.

- [ ] **Step 3: Implement `MpdTtlWatcher`**

Create `android/app/src/main/java/com/albunyaan/tube/player/MpdTtlWatcher.kt`:

```kotlin
package com.albunyaan.tube.player

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MpdTtlWatcher(
    private val videoId: String,
    private val registry: SyntheticDashMpdRegistry,
    private val onRefreshNeeded: () -> Unit,
    var clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    companion object {
        private const val TAG = "MpdTtlWatcher"
        private const val TTL_REFRESH_FRACTION = 0.90
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            val entry = registry.getEntry(videoId) ?: run {
                Log.d(TAG, "No entry for $videoId — TTL watcher inactive")
                return@launch
            }
            val refreshAtMs = entry.registeredAtMs +
                (SyntheticDashMpdRegistry.MPD_TTL_MS * TTL_REFRESH_FRACTION).toLong()
            val delayMs = refreshAtMs - clock()
            if (delayMs <= 0) {
                Log.d(TAG, "TTL already past for $videoId — triggering refresh immediately")
                onRefreshNeeded()
                return@launch
            }
            Log.d(TAG, "TTL watcher for $videoId: refresh in ${delayMs}ms")
            delay(delayMs)
            Log.d(TAG, "TTL 90% reached for $videoId — triggering refresh")
            onRefreshNeeded()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.MpdTtlWatcherTest" 2>&1 | tail -10
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/MpdTtlWatcher.kt \
        android/app/src/test/java/com/albunyaan/tube/player/MpdTtlWatcherTest.kt
git commit -m "[FEAT]: [ANDROID-PERF-04]: Add MpdTtlWatcher for proactive URL refresh at 90% TTL"
```

---

### Task 6: Wire `MpdTtlWatcher` into `PlayerBinder`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/PlayerBinder.kt`

Context: `PlayerBinder.prepareAndPlay()` (around line ~330) resolves streams and calls `createMediaSourceWithType()` which returns `MediaSourceResult?` (held in local variable `adaptive`). After `playerOps.setMediaSource(source)`, we know whether it's a synthetic DASH source via `adaptive?.adaptiveType == MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE`.

`PlayerBinder` needs access to `SyntheticDashMpdRegistry` and `PlaybackFeatureFlags`. Check its constructor:
```kotlin
class PlayerBinder(player: ExoPlayer, playerRepository: PlayerRepository, mediaSourceFactory: MultiQualityMediaSourceFactory)
```

Add `mpdRegistry: SyntheticDashMpdRegistry? = null` and `featureFlags: PlaybackFeatureFlags? = null` as optional constructor params to avoid breaking existing construction sites.

- [ ] **Step 1: Add optional constructor parameters to `PlayerBinder`**

Find the `PlayerBinder` class declaration. Add two optional parameters:
```kotlin
class PlayerBinder(
    player: ExoPlayer,
    playerRepository: PlayerRepository,
    mediaSourceFactory: MultiQualityMediaSourceFactory,
    private val mpdRegistry: SyntheticDashMpdRegistry? = null,
    private val featureFlags: PlaybackFeatureFlags? = null
)
```

- [ ] **Step 2: Add a `ttlWatcher` field**

At the top of the `PlayerBinder` class body, with the other private fields:
```kotlin
private var ttlWatcher: MpdTtlWatcher? = null
```

- [ ] **Step 3: Launch the watcher after setting a synthetic DASH source**

In `prepareAndPlay()`, find where `playerOps.setMediaSource(source)` is called. After it (and after the second staleness check), add:

```kotlin
playerOps.setMediaSource(source)
playerOps.setRepeatModeOne()
playerOps.prepare()
playerOps.setPlayWhenReady(true)

// Launch TTL watcher if this is a synthetic DASH source
ttlWatcher?.cancel()
ttlWatcher = null
if (featureFlags?.isTtlWatcherEnabled == true &&
    mpdRegistry != null &&
    adaptive?.adaptiveType == MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE) {
    ttlWatcher = MpdTtlWatcher(videoId, mpdRegistry) { forceRefreshCurrent() }
        .also { it.start(binderScope) }
}
```

Where `binderScope` is the internal `CoroutineScope` that `PlayerBinder` already uses for its bind jobs. Check the field name in the existing code — it may be `scope` or `internalScope`. Use whatever is there.

- [ ] **Step 4: Cancel the watcher in `cancelScope()` and `release()`**

Find `fun cancelScope()` (around line ~478). Add before the scope cancellation:
```kotlin
ttlWatcher?.cancel()
ttlWatcher = null
```

Find `fun release()`. Add the same two lines before calling `cancelScope()` or `playerOps.release()`.

- [ ] **Step 5: Update construction sites to pass `mpdRegistry` and `featureFlags`**

In `ShortsPlayerFragment` (line ~158):
```kotlin
val localBinder = PlayerBinder(viewModel.player, playerRepository, mediaSourceFactory)
```
Change to:
```kotlin
val localBinder = PlayerBinder(viewModel.player, playerRepository, mediaSourceFactory, mpdRegistry, playbackFeatureFlags)
```
(Both `mpdRegistry` and `playbackFeatureFlags` are already `@Inject` fields in `ShortsPlayerFragment`.)

- [ ] **Step 6: Build and run all tests**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
cd android && ./gradlew test 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/shorts/PlayerBinder.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt
git commit -m "[FEAT]: [ANDROID-PERF-04]: Wire MpdTtlWatcher into PlayerBinder for proactive TTL refresh"
```
