# Instant Playback Phase C: Visibility-Triggered Prefetch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fire `triggerPrefetch()` when a list item scrolls into view (before the user taps) instead of on tap, giving Phase B's pre-buffer time to run while the user is still browsing.

**Architecture:** New `PredictivePrefetchController` attaches `RecyclerView.OnChildAttachStateChangeListener` to fire `triggerPrefetch()` as items become visible. One instance per list fragment — created in `onViewCreated`, detached in `onDestroyView`. Existing `ExtractionRateLimiter` and `GlobalStreamResolver` inside `StreamPrefetchService` handle deduplication and rate-limiting automatically. The tap-based calls are removed from all 8 fragments. Gated behind `KEY_PREDICTIVE_PREFETCH`.

**Tech Stack:** Kotlin, AndroidX RecyclerView, Hilt DI, JUnit 4 + Robolectric

---

## File Structure

| Action | File | Responsibility |
|---|---|---|
| Create | `player/PredictivePrefetchController.kt` | Attach to RecyclerView, fire triggerPrefetch on item attach |
| Modify | `player/PlaybackFeatureFlags.kt` | Add `KEY_PREDICTIVE_PREFETCH` flag |
| Modify | `ui/HomeFragment.kt` | Replace tap trigger with controller |
| Modify | `ui/VideosFragmentNew.kt` | Replace tap trigger with controller |
| Modify | `ui/SearchFragment.kt` | Replace tap trigger with controller |
| Modify | `ui/FeaturedListFragment.kt` | Replace tap trigger with controller |
| Modify | `ui/detail/tabs/ChannelShortsTabFragment.kt` | Replace tap trigger with controller |
| Modify | `ui/detail/tabs/ChannelVideosTabFragment.kt` | Replace tap trigger with controller |
| Modify | `ui/detail/tabs/ChannelLiveTabFragment.kt` | Replace tap trigger with controller |
| Modify | `ui/detail/PlaylistDetailFragment.kt` | Replace tap trigger with controller |
| Create | `test/.../player/PredictivePrefetchControllerTest.kt` | Unit tests |

---

### Task 1: Add `KEY_PREDICTIVE_PREFETCH` feature flag

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt`
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add BuildConfig field in `build.gradle.kts`**

In `defaultConfig`, after the segment preload flag:
```kotlin
buildConfigField("boolean", "ENABLE_PREDICTIVE_PREFETCH", "true")
```

- [ ] **Step 2: Add constant, property, setter, VALID_KEYS entry, getDiagnostics entry, and clearAll entries**

Constant:
```kotlin
const val KEY_PREDICTIVE_PREFETCH = "predictive_prefetch"
```

Property:
```kotlin
val isPredictivePrefetchEnabled: Boolean
    get() = resolveFlag(KEY_PREDICTIVE_PREFETCH, BuildConfig.ENABLE_PREDICTIVE_PREFETCH)
```

Setter:
```kotlin
fun setPredictivePrefetchEnabled(enabled: Boolean?) {
    setOverride(KEY_PREDICTIVE_PREFETCH, enabled)
    Log.i(TAG, "PREDICTIVE_PREFETCH override set to: $enabled (effective: $isPredictivePrefetchEnabled)")
}
```

Add `KEY_PREDICTIVE_PREFETCH` to `VALID_KEYS`, `clearAllOverridesInternal()`, `clearAllOverrides()`, and `getDiagnostics()` following the same pattern as the existing flags.

- [ ] **Step 3: Build and test**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
cd android && ./gradlew test --tests "com.albunyaan.tube.player.PlaybackFeatureFlagsTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt
git commit -m "[FEAT]: [ANDROID-PERF-03]: Add KEY_PREDICTIVE_PREFETCH feature flag"
```

---

### Task 2: Create `PredictivePrefetchController`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/player/PredictivePrefetchController.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/player/PredictivePrefetchControllerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/albunyaan/tube/player/PredictivePrefetchControllerTest.kt`:

```kotlin
package com.albunyaan.tube.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.recyclerview.widget.RecyclerView
import android.view.View

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PredictivePrefetchControllerTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var mockPrefetchService: StreamPrefetchService
    private lateinit var testScope: TestScope
    private lateinit var controller: PredictivePrefetchController

    @Before
    fun setUp() {
        mockPrefetchService = mock()
        testScope = TestScope()
    }

    private fun makeController(idResolver: (Int) -> String?) =
        PredictivePrefetchController(mockPrefetchService, testScope, idResolver)

    private fun makeView(position: Int): View {
        val v = View(context)
        v.tag = position
        return v
    }

    @Test
    fun `triggerPrefetch is called when child attaches and has valid videoId`() {
        val rv = RecyclerView(context)
        controller = makeController { pos -> if (pos == 0) "vid-123" else null }
        controller.attach(rv)

        val captor = argumentCaptor<RecyclerView.OnChildAttachStateChangeListener>()
        // Simulate the listener firing - use reflection to get listener
        // (Robolectric allows us to inspect registered listeners)
        val listeners = getAttachListeners(rv)
        assertEquals(1, listeners.size)
        
        val view = makeView(0)
        rv.addView(view)
        // Fire attach manually
        listeners[0].onChildViewAttachedToWindow(view)

        verify(mockPrefetchService, times(1)).triggerPrefetch(any(), any())
    }

    @Test
    fun `triggerPrefetch is NOT called when videoId resolver returns null`() {
        val rv = RecyclerView(context)
        controller = makeController { null }
        controller.attach(rv)

        val listeners = getAttachListeners(rv)
        val view = makeView(0)
        listeners[0].onChildViewAttachedToWindow(view)

        verify(mockPrefetchService, never()).triggerPrefetch(any(), any())
    }

    @Test
    fun `detach removes the listener`() {
        val rv = RecyclerView(context)
        controller = makeController { "vid-123" }
        controller.attach(rv)
        controller.detach()

        assertEquals(0, getAttachListeners(rv).size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getAttachListeners(rv: RecyclerView): List<RecyclerView.OnChildAttachStateChangeListener> {
        val field = RecyclerView::class.java.getDeclaredField("mOnChildAttachStateListeners")
        field.isAccessible = true
        return (field.get(rv) as? List<*>)
            ?.filterIsInstance<RecyclerView.OnChildAttachStateChangeListener>()
            ?: emptyList()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.PredictivePrefetchControllerTest" 2>&1 | tail -10
```
Expected: FAIL — `PredictivePrefetchController` not yet defined.

- [ ] **Step 3: Implement `PredictivePrefetchController`**

Create `android/app/src/main/java/com/albunyaan/tube/player/PredictivePrefetchController.kt`:

```kotlin
package com.albunyaan.tube.player

import android.util.Log
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

class PredictivePrefetchController(
    private val prefetchService: StreamPrefetchService,
    private val scope: CoroutineScope,
    private val videoIdResolver: (adapterPosition: Int) -> String?
) {
    companion object {
        private const val TAG = "PredictivePrefetch"
    }

    private var recyclerView: RecyclerView? = null

    private val listener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            val rv = recyclerView ?: return
            val position = rv.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val videoId = videoIdResolver(position) ?: return
            Log.d(TAG, "Predictive prefetch for pos=$position videoId=$videoId")
            prefetchService.triggerPrefetch(videoId, scope)
        }

        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    fun attach(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        recyclerView.addOnChildAttachStateChangeListener(listener)
    }

    fun detach() {
        recyclerView?.removeOnChildAttachStateChangeListener(listener)
        recyclerView = null
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd android && ./gradlew test --tests "com.albunyaan.tube.player.PredictivePrefetchControllerTest" 2>&1 | tail -10
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/player/PredictivePrefetchController.kt \
        android/app/src/test/java/com/albunyaan/tube/player/PredictivePrefetchControllerTest.kt
git commit -m "[FEAT]: [ANDROID-PERF-03]: Add PredictivePrefetchController"
```

---

### Task 3: Wire `PredictivePrefetchController` into all list fragments

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/HomeFragment.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/VideosFragmentNew.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/SearchFragment.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/FeaturedListFragment.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/tabs/ChannelShortsTabFragment.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/tabs/ChannelVideosTabFragment.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/tabs/ChannelLiveTabFragment.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailFragment.kt`

Apply this identical pattern to every fragment:

**a) Add a field:**
```kotlin
private var prefetchController: PredictivePrefetchController? = null
```

**b) In `onViewCreated` (after the RecyclerView is bound), add the controller — replace the tap-based `triggerPrefetch` call:**

Before (example from `HomeFragment.kt`):
```kotlin
// Old tap-based call (somewhere in an onClick / onItemClick listener):
prefetchService.triggerPrefetch(video.id, viewLifecycleOwner.lifecycleScope)
```

After — in `onViewCreated` after the adapter is set:
```kotlin
if (featureFlags.isPredictivePrefetchEnabled) {
    prefetchController = PredictivePrefetchController(
        prefetchService,
        viewLifecycleOwner.lifecycleScope
    ) { pos -> adapter.currentList.getOrNull(pos)?.id }
    prefetchController?.attach(binding.recyclerView) // use correct binding field name per fragment
}
```

The existing tap-based `prefetchService.triggerPrefetch(...)` call in the click listener is removed entirely (not commented out). `GlobalStreamResolver` deduplicates any in-flight jobs so there is no double-resolution risk.

**c) In `onDestroyView`:**
```kotlin
prefetchController?.detach()
prefetchController = null
```

**Adapter + RecyclerView field names per fragment:**

| Fragment | Adapter field | RecyclerView binding field | Item ID accessor |
|---|---|---|---|
| `HomeFragment` | `videoAdapter` | `binding.homeSectionsRecyclerView` | `.id` |
| `VideosFragmentNew` | `adapter` | `binding.videosRecyclerView` | `.id` |
| `SearchFragment` | `searchAdapter` | `binding.searchResultsRecyclerView` | `.id` |
| `FeaturedListFragment` | `adapter` | `binding.recyclerView` | `.id` |
| `ChannelShortsTabFragment` | `adapter` | `binding.recyclerView` | `.id` |
| `ChannelVideosTabFragment` | `adapter` | `binding.recyclerView` | `.id` |
| `ChannelLiveTabFragment` | `adapter` | `binding.recyclerView` | `.id` |
| `PlaylistDetailFragment` | `adapter` | `binding.playlistRecyclerView` | `.videoId` |

Note: `PlaylistDetailFragment` uses `.videoId` not `.id` because its item type is `PlaylistItem`.

Also in `PlaylistDetailFragment`, there are TWO tap-based calls (line ~124 for item tap, and line ~498 for first-item pre-resolve on load). Remove both.

- [ ] **Step 1: Apply the pattern to `HomeFragment.kt`**

Find the tap-based `prefetchService.triggerPrefetch(video.id, ...)` call and remove it. Add controller in `onViewCreated`, detach in `onDestroyView`.

Verify: `HomeFragment` has `@Inject lateinit var featureFlags: PlaybackFeatureFlags`. If not, add it.

- [ ] **Step 2: Apply to remaining 7 fragments**

Apply the same pattern to `VideosFragmentNew`, `SearchFragment`, `FeaturedListFragment`, `ChannelShortsTabFragment`, `ChannelVideosTabFragment`, `ChannelLiveTabFragment`, `PlaylistDetailFragment`.

- [ ] **Step 3: Build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run all tests**

```bash
cd android && ./gradlew test 2>&1 | tail -15
```
Expected: All pass.

- [ ] **Step 5: Commit**

```bash
git add \
  android/app/src/main/java/com/albunyaan/tube/ui/HomeFragment.kt \
  android/app/src/main/java/com/albunyaan/tube/ui/VideosFragmentNew.kt \
  android/app/src/main/java/com/albunyaan/tube/ui/SearchFragment.kt \
  android/app/src/main/java/com/albunyaan/tube/ui/FeaturedListFragment.kt \
  android/app/src/main/java/com/albunyaan/tube/ui/detail/tabs/ChannelShortsTabFragment.kt \
  android/app/src/main/java/com/albunyaan/tube/ui/detail/tabs/ChannelVideosTabFragment.kt \
  android/app/src/main/java/com/albunyaan/tube/ui/detail/tabs/ChannelLiveTabFragment.kt \
  android/app/src/main/java/com/albunyaan/tube/ui/detail/PlaylistDetailFragment.kt
git commit -m "[FEAT]: [ANDROID-PERF-03]: Wire PredictivePrefetchController into all list fragments"
```
