# Instant Playback Phase A: Cronet + Media3 1.10.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace HTTP/1.1 with HTTP/2 + QUIC for all media segment requests by upgrading Media3 to 1.10.0 and routing requests through Cronet, with silent fallback to `DefaultHttpDataSource` on unsupported devices.

**Architecture:** New `CronetDataSourceFactory` singleton creates a `CronetEngine` once at startup and exposes UA-specific `DataSource.Factory` instances. `MultiQualityMediaSourceFactory` accepts these factories as optional constructor parameters — null means use `DefaultHttpDataSource` (backward-compatible). `PlaybackFeatureFlags` gates the whole thing behind `KEY_CRONET_ENABLED`.

**Tech Stack:** Kotlin, Media3 1.10.0, `media3-datasource-cronet`, `CronetDataSource`, Hilt DI, JUnit 4

---

## File Structure

| Action | File | Responsibility |
|---|---|---|
| Create | `player/CronetDataSourceFactory.kt` | Build `CronetEngine` once, expose UA-specific `DataSource.Factory` |
| Modify | `build.gradle.kts` | Bump `media3Version`, add `media3-datasource-cronet` dep |
| Modify | `player/PlaybackFeatureFlags.kt` | Add `KEY_CRONET_ENABLED` flag |
| Modify | `player/MultiQualityMediaSourceFactory.kt` | Accept optional upstream HTTP factory param |
| Modify | `ui/shorts/ShortsPlayerFragment.kt` | Inject + pass `CronetDataSourceFactory` |
| Modify | `ui/player/PlayerFragment.kt` | Inject + pass `CronetDataSourceFactory` |
| Create | `test/.../player/CronetDataSourceFactoryTest.kt` | Unit tests |

---

### Task 1: Add `KEY_CRONET_ENABLED` feature flag

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt`
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add BuildConfig field in `build.gradle.kts`**

In `defaultConfig { ... }`, after the existing `buildConfigField("boolean", "ENABLE_HLS_PROBATION", "true")` line:

```kotlin
buildConfigField("boolean", "ENABLE_CRONET", "true")
```

- [ ] **Step 2: Run the build to verify it compiles**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Add the constant to `PlaybackFeatureFlags`**

In `PlaybackFeatureFlags.companion`, after `const val KEY_HLS_PROBATION = "hls_probation"`:
```kotlin
const val KEY_CRONET_ENABLED = "cronet_enabled"
```

Update `VALID_KEYS` to include it:
```kotlin
private val VALID_KEYS = setOf(
    KEY_SYNTH_ADAPTIVE,
    KEY_MPD_PREFETCH,
    KEY_DEGRADATION_MANAGER,
    KEY_IOS_FETCH,
    KEY_GENEROUS_CROP_BUDGET,
    KEY_CLIENT_ROTATION,
    KEY_HLS_PROBATION,
    KEY_CRONET_ENABLED
)
```

Update `clearAllOverridesInternal()` to remove it:
```kotlin
private fun clearAllOverridesInternal() {
    prefs.edit()
        .remove(KEY_SYNTH_ADAPTIVE)
        .remove(KEY_MPD_PREFETCH)
        .remove(KEY_DEGRADATION_MANAGER)
        .remove(KEY_IOS_FETCH)
        .remove(KEY_GENEROUS_CROP_BUDGET)
        .remove(KEY_CLIENT_ROTATION)
        .remove(KEY_HLS_PROBATION)
        .remove(KEY_CRONET_ENABLED)
        .apply()
}
```

Update `clearAllOverrides()` (the public one) the same way — add `.remove(KEY_CRONET_ENABLED)`.

Add the property after `isHlsProbationEnabled`:
```kotlin
val isCronetEnabled: Boolean
    get() = resolveFlag(KEY_CRONET_ENABLED, BuildConfig.ENABLE_CRONET)
```

Add setter after `setHlsProbationEnabled`:
```kotlin
fun setCronetEnabled(enabled: Boolean?) {
    setOverride(KEY_CRONET_ENABLED, enabled)
    Log.i(TAG, "CRONET_ENABLED override set to: $enabled (effective: $isCronetEnabled)")
}
```

Add to `getDiagnostics()` map:
```kotlin
KEY_CRONET_ENABLED to getFlagState(KEY_CRONET_ENABLED, BuildConfig.ENABLE_CRONET),
```

- [ ] **Step 4: Run the tests to verify no breakage**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.PlaybackFeatureFlagsTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL` with tests passing.

- [ ] **Step 5: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt
git commit -m "[FEAT]: [ANDROID-PERF-01]: Add KEY_CRONET_ENABLED feature flag"
```

---

### Task 2: Upgrade Media3 and add Cronet dependency

**Files:**
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Bump `media3Version` and add the Cronet artifact**

Find `val media3Version = "1.9.2"` at line ~206 and change to:
```kotlin
val media3Version = "1.10.0"
```

After the existing `implementation("androidx.media3:media3-session:$media3Version")` line, add:
```kotlin
implementation("androidx.media3:media3-datasource-cronet:$media3Version")
```

- [ ] **Step 2: Sync and build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`. If there are API incompatibilities from 1.9.2 → 1.10.0, fix them now (they are typically `@UnstableApi` annotation changes, not behavioural).

- [ ] **Step 3: Run full test suite to confirm no regressions**

```bash
cd android && ./gradlew test 2>&1 | tail -20
```
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "[CHORE]: [ANDROID-PERF-01]: Upgrade Media3 to 1.10.0, add media3-datasource-cronet"
```

---

### Task 3: Create `CronetDataSourceFactory`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/player/CronetDataSourceFactory.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/player/CronetDataSourceFactoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/albunyaan/tube/player/CronetDataSourceFactoryTest.kt`:

```kotlin
package com.albunyaan.tube.player

import androidx.media3.datasource.DefaultHttpDataSource
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CronetDataSourceFactoryTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `create returns non-null factory`() {
        val factory = CronetDataSourceFactory(context)
        assertNotNull(factory.createForAndroidUA())
        assertNotNull(factory.createForIosUA())
    }

    @Test
    fun `falls back gracefully when cronet unavailable`() {
        // Robolectric has no real Cronet — factory must not throw
        val factory = CronetDataSourceFactory(context)
        val androidFactory = factory.createForAndroidUA()
        val iosFactory = factory.createForIosUA()
        assertNotNull(androidFactory)
        assertNotNull(iosFactory)
        // Both factories should be usable (DefaultHttpDataSource or Cronet)
        assertTrue(androidFactory != iosFactory) // separate instances for different UAs
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.CronetDataSourceFactoryTest" 2>&1 | tail -10
```
Expected: FAIL — `CronetDataSourceFactory` not yet defined.

- [ ] **Step 3: Implement `CronetDataSourceFactory`**

Create `android/app/src/main/java/com/albunyaan/tube/player/CronetDataSourceFactory.kt`:

```kotlin
package com.albunyaan.tube.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class CronetDataSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CronetDataSourceFactory"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    private val engine: CronetEngine? by lazy { buildEngine() }

    private fun buildEngine(): CronetEngine? {
        return try {
            val providers = CronetProvider.getAllProviders(context)
            val provider = providers
                .filter { it.isEnabled && it.name != CronetProvider.PROVIDER_NAME_FALLBACK }
                .firstOrNull()
                ?: providers.firstOrNull { it.isEnabled }
                ?: return null
            provider.createBuilder().build().also {
                Log.i(TAG, "Cronet engine ready via ${provider.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cronet unavailable, will use DefaultHttpDataSource: ${e.message}")
            null
        }
    }

    fun createForAndroidUA(): DataSource.Factory =
        create(HttpConstants.YOUTUBE_USER_AGENT)

    fun createForIosUA(): DataSource.Factory =
        create(HttpConstants.YOUTUBE_IOS_USER_AGENT)

    private fun create(userAgent: String): DataSource.Factory {
        val cronetEngine = engine
        return if (cronetEngine != null) {
            CronetDataSource.Factory(cronetEngine, Executors.newCachedThreadPool())
                .setDefaultRequestProperties(mapOf("User-Agent" to userAgent))
                .setConnectionTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.CronetDataSourceFactoryTest" 2>&1 | tail -10
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/CronetDataSourceFactory.kt \
        android/app/src/test/java/com/albunyaan/tube/player/CronetDataSourceFactoryTest.kt
git commit -m "[FEAT]: [ANDROID-PERF-01]: Add CronetDataSourceFactory with Play Services fallback"
```

---

### Task 4: Wire Cronet into `MultiQualityMediaSourceFactory`

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt`

- [ ] **Step 1: Add optional `cronetFactory` parameter to the constructor**

The current constructor (line ~70):
```kotlin
class MultiQualityMediaSourceFactory(
    private val context: Context,
    private val hlsPoisonRegistry: HlsPoisonRegistry? = null,
    private val multiRepFactory: MultiRepSyntheticDashMediaSourceFactory? = null,
    private val coldStartQualityChooser: ColdStartQualityChooser? = null,
    private val featureFlags: PlaybackFeatureFlags? = null,
    private val mpdRegistry: SyntheticDashMpdRegistry? = null,
    private val probationChecker: HlsProbationChecker? = null
)
```

Change to:
```kotlin
class MultiQualityMediaSourceFactory(
    private val context: Context,
    private val hlsPoisonRegistry: HlsPoisonRegistry? = null,
    private val multiRepFactory: MultiRepSyntheticDashMediaSourceFactory? = null,
    private val coldStartQualityChooser: ColdStartQualityChooser? = null,
    private val featureFlags: PlaybackFeatureFlags? = null,
    private val mpdRegistry: SyntheticDashMpdRegistry? = null,
    private val probationChecker: HlsProbationChecker? = null,
    private val cronetDataSourceFactory: CronetDataSourceFactory? = null
)
```

- [ ] **Step 2: Replace the hardcoded `httpDataSourceFactory` and `hlsHttpDataSourceFactory`**

Find the two private val declarations (lines ~85-115) that look like:
```kotlin
private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setUserAgent(HttpConstants.YOUTUBE_USER_AGENT)
    .setConnectTimeoutMs(15000)
    .setReadTimeoutMs(20000)
    .setAllowCrossProtocolRedirects(true)
```
and:
```kotlin
private val hlsHttpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setUserAgent(HttpConstants.YOUTUBE_IOS_USER_AGENT)
    .setConnectTimeoutMs(15000)
    .setReadTimeoutMs(20000)
    .setAllowCrossProtocolRedirects(true)
```

Replace them with:
```kotlin
private val httpDataSourceFactory: DataSource.Factory =
    if (featureFlags?.isCronetEnabled == true && cronetDataSourceFactory != null) {
        cronetDataSourceFactory.createForAndroidUA()
    } else {
        DefaultHttpDataSource.Factory()
            .setUserAgent(HttpConstants.YOUTUBE_USER_AGENT)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)
    }

private val hlsHttpDataSourceFactory: DataSource.Factory =
    if (featureFlags?.isCronetEnabled == true && cronetDataSourceFactory != null) {
        cronetDataSourceFactory.createForIosUA()
    } else {
        DefaultHttpDataSource.Factory()
            .setUserAgent(HttpConstants.YOUTUBE_IOS_USER_AGENT)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)
    }
```

- [ ] **Step 3: Build to verify no compilation errors**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run all player tests**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.*" 2>&1 | tail -15
```
Expected: All pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt
git commit -m "[FEAT]: [ANDROID-PERF-01]: Wire CronetDataSourceFactory into MultiQualityMediaSourceFactory"
```

---

### Task 5: Inject `CronetDataSourceFactory` in both player fragments

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt`

- [ ] **Step 1: Add `@Inject` field in `ShortsPlayerFragment`**

In `ShortsPlayerFragment`, find the existing `@Inject` fields (e.g. `@Inject lateinit var hlsPoisonRegistry: HlsPoisonRegistry`). Add alongside them:

```kotlin
@Inject
lateinit var cronetDataSourceFactory: CronetDataSourceFactory
```

- [ ] **Step 2: Pass it to `MultiQualityMediaSourceFactory` in `ShortsPlayerFragment`**

Find the `MultiQualityMediaSourceFactory(...)` construction at line ~147:
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

Change to:
```kotlin
val mediaSourceFactory = com.albunyaan.tube.player.MultiQualityMediaSourceFactory(
    requireContext(),
    hlsPoisonRegistry,
    multiRepFactory,
    coldStartQualityChooser,
    playbackFeatureFlags,
    mpdRegistry,
    probationChecker,
    cronetDataSourceFactory
)
```

- [ ] **Step 3: Add `@Inject` field in `PlayerFragment`**

Find the `@Inject` fields in `PlayerFragment`. Add:
```kotlin
@Inject
lateinit var cronetDataSourceFactory: CronetDataSourceFactory
```

- [ ] **Step 4: Pass it to `MultiQualityMediaSourceFactory` in `PlayerFragment`**

Find the `mediaSourceFactory by lazy { ... }` block at line ~828:
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

Change to:
```kotlin
private val mediaSourceFactory by lazy {
    com.albunyaan.tube.player.MultiQualityMediaSourceFactory(
        requireContext(),
        hlsPoisonRegistry,
        multiRepFactory,
        coldStartQualityChooser,
        featureFlags,
        mpdRegistry,
        probationChecker,
        cronetDataSourceFactory
    )
}
```

- [ ] **Step 5: Build and run all tests**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
cd android && ./gradlew test 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt
git commit -m "[FEAT]: [ANDROID-PERF-01]: Inject CronetDataSourceFactory into both player fragments"
```
