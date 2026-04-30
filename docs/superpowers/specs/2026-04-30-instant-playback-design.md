# Instant Playback Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate perceived startup latency and mid-watch freezes across both the regular player and Shorts player, achieving sub-500ms first-frame on pre-resolved items and zero mid-watch stalls on adaptive streams.

**Architecture:** Four independent phases delivered on separate branches. Each phase is individually shippable and measurable via `StreamRequestTelemetry`. No big-bang rewrites — all changes extend or wrap existing infrastructure.

**Tech Stack:** Kotlin, Media3 1.10.0, Cronet (via `media3-datasource-cronet`), existing `SimpleCache`/`CacheDataSource`/`CacheWriter`, `ExtractionRateLimiter`, `GlobalStreamResolver`, `StreamPrefetchService`, `SyntheticDashMpdRegistry`, `PlayerBinder`, `AdaptiveBufferPolicy`

---

## Existing foundations (do not duplicate)

| Component | Location | Role |
|---|---|---|
| `StreamPrefetchService` | `player/StreamPrefetchService.kt` | Tap-triggered URL resolution + DASH MPD pre-generation |
| `GlobalStreamResolver` | `player/GlobalStreamResolver.kt` | Single-flight `Deferred` — deduplicates concurrent extraction |
| `SimpleCache` | `MultiQualityMediaSourceFactory` companion | 100MB LRU disk cache; `CacheDataSource.Factory` already wired into all media sources |
| `AdaptiveBufferPolicy` | `player/AdaptiveBufferPolicy.kt` | 3-tier device-aware buffer sizing |
| `ExtractionRateLimiter` | `player/ExtractionRateLimiter.kt` | Token bucket; PREFETCH is lowest priority |
| `SyntheticDashMpdRegistry` | `player/SyntheticDashDataSource.kt` | In-memory DASH manifest store; `MPD_TTL_MS = 120_000ms` |
| `PlayerBinder` | `ui/shorts/PlayerBinder.kt` | Binds stream resolution to ExoPlayer; has `forceRefreshCurrent()` |
| `PlaybackRecoveryManager` | `player/PlaybackRecoveryManager.kt` | Recovery steps for 403, stall, URL expiry |
| `BufferHealthMonitor` | `player/BufferHealthMonitor.kt` | Tracks buffer health during playback |
| `ColdStartQualityChooser` | `player/ColdStartQualityChooser.kt` | Context-aware initial quality selection |

---

## Phase A — Network layer (Media3 1.10.0 + Cronet)

### What

Upgrade Media3 from 1.9.2 to 1.10.0 and introduce HTTP/2 + QUIC for all media segment requests via Cronet. On devices without Google Play Services, falls back silently to the existing `DefaultHttpDataSource`.

### Why

HTTP/1.1 (current) opens a new TCP connection per segment request. HTTP/2 multiplexes requests over one connection. QUIC eliminates head-of-line blocking and survives network interface changes (WiFi → mobile). YouTube CDN supports both. Expected 20–40% reduction in segment download latency on mobile networks.

### Components

**`CronetDataSourceFactory`** — new file: `player/CronetDataSourceFactory.kt`
- Singleton, Hilt `@Singleton`
- On construction: attempts to create a `CronetEngine` via `CronetEngineWrapper` (Google Play Services). If unavailable, creates a `NoCronetEngine` stub.
- Exposes `create(): DataSource.Factory` — returns `CronetDataSource.Factory` wrapping the engine, or `DefaultHttpDataSource.Factory` if Cronet is unavailable.
- Forwards the same User-Agent string used today (iOS/Android rotation from `YoutubeClientRotator`).

**`MultiQualityMediaSourceFactory`** — modify: `player/MultiQualityMediaSourceFactory.kt`
- Accept `CronetDataSourceFactory` as constructor parameter (inject via Hilt, nullable for backward compat with tests).
- Replace the two inline `DefaultHttpDataSource.Factory()` constructions (regular + HLS data source factories) with `cronetDataSourceFactory?.create() ?: DefaultHttpDataSource.Factory()`.
- No other changes.

**`PlaybackFeatureFlags`** — modify: add `KEY_CRONET_ENABLED` (default ON).
- `MultiQualityMediaSourceFactory` skips Cronet if flag is OFF.

**`DataModule`** / Hilt wiring — modify: provide `CronetDataSourceFactory` as a `@Singleton` binding.

**`build.gradle.kts`** — modify:
- `media3Version = "1.10.0"`
- Add `implementation("androidx.media3:media3-datasource-cronet:$media3Version")`

### Error handling

Cronet fallback is construction-time only — if `CronetEngine` is unavailable at startup, we use `DefaultHttpDataSource` for the session. We do NOT retry with Cronet mid-stream or fall back mid-stream. ExoPlayer's existing retry/recovery handles individual request failures.

### Testing

- Unit test `CronetDataSourceFactory`: verify it returns `DefaultHttpDataSource.Factory` when Cronet is unavailable (mock `CronetEngineWrapper` to throw).
- Existing `MultiQualityMediaSourceFactory` unit tests continue to pass with `cronetDataSourceFactory = null`.
- Measure: `StreamRequestTelemetry` first-frame time before/after on a real device.

---

## Phase B — Segment pre-buffering

### What

After `StreamPrefetchService.tryPreGenerateMpd()` successfully pre-generates the DASH MPD, use Media3's `CacheWriter` to fetch the first 3 seconds of the lowest available video track into the existing `SimpleCache`. By the time the user taps, those bytes are already on disk — ExoPlayer's `CacheDataSource` serves them instantly.

### Why

Today's prefetch pre-generates the DASH manifest (zero latency at playback) but the video bytes themselves are fetched live. The first-frame bottleneck shifts from "manifest generation" to "first segment download." Pre-buffering 3 seconds eliminates that bottleneck on prefetched items.

### Components

**`SegmentPreBuffer`** — new file: `player/SegmentPreBuffer.kt`
- `@Singleton`, Hilt `@Inject constructor(cache: SimpleCache, httpFactory: DataSource.Factory)`
- When Phase A is deployed, inject `CronetDataSourceFactory.create()`. When implementing Phase B standalone, inject `DefaultHttpDataSource.Factory()`.
- Single method: `suspend fun preBuffer(videoUrl: String, durationMs: Long = 3_000)`.
- Creates a `CacheWriter` targeting the `SimpleCache` and writes up to `durationMs` worth of the stream at `videoUrl`.
- Cancellation-safe: wraps `CacheWriter` in a `try/finally`, calling `close()` always.
- All errors (network, disk full, etc.) are caught and logged at DEBUG. Never throws.

**`StreamPrefetchService`** — modify: `player/StreamPrefetchService.kt`
- In `tryPreGenerateMpd()`, after successful `mpdRegistry.registerWithMetadata(...)`, call `segmentPreBuffer.preBuffer(lowestVideoTrackUrl)` if `featureFlags.isSegmentPreloadEnabled`.
- `lowestVideoTrackUrl` = the URL of the lowest bitrate video track in the resolved streams (minimise bandwidth impact).
- The `preBuffer` call is launched as a child coroutine of the existing prefetch scope — cancelled automatically if the prefetch job is cancelled.

**`PlaybackFeatureFlags`** — modify: add `KEY_SEGMENT_PRELOAD` (default ON).

### Constraints

- Only pre-buffers the lowest quality track to minimise data usage. Never pre-buffers HLS — only progressive or synthetic DASH video tracks (direct URL available).
- Skips pre-buffering if `AdaptiveBufferPolicy` returns `LOW` profile (low-RAM device) to avoid memory/storage pressure.
- `SimpleCache` LRU evictor naturally handles eviction if the 100MB limit is hit.

### Testing

- Unit test `SegmentPreBuffer`: verify `CacheWriter.write()` is called with expected URL and duration; verify errors are swallowed; verify cancellation stops the write cleanly.
- Unit test `StreamPrefetchService`: verify `segmentPreBuffer.preBuffer()` is called after successful MPD registration; verify it is NOT called when `KEY_SEGMENT_PRELOAD` is OFF.
- Measure: first-frame time via `StreamRequestTelemetry` on prefetched vs cold items.

---

## Phase C — Visibility-triggered prefetch

### What

Replace the current tap-only `triggerPrefetch()` calls in all list fragments with visibility-triggered prefetch: fire `triggerPrefetch()` as items scroll into view, before the user taps. By tap time, resolution (and Phase B pre-buffering) is already underway or complete.

### Why

Current tap-to-prefetch calls `triggerPrefetch()` at the same moment the user initiates navigation — there is zero latency advantage over no prefetch at all for cold items. Moving the trigger to scroll-into-view gives a window of seconds (the time the user spends looking at the list) for the extraction and pre-buffer to complete.

### Components

**`PredictivePrefetchController`** — new file: `player/PredictivePrefetchController.kt`
- Not a singleton — one instance per list fragment, created in `onViewCreated`, destroyed in `onDestroyView`.
- Constructor: `(prefetchService: StreamPrefetchService, scope: CoroutineScope, videoIdResolver: (adapterPosition: Int) -> String?)`
- Method `attach(recyclerView: RecyclerView)`: registers `RecyclerView.OnChildAttachStateChangeListener`. When a view holder attaches, resolves its adapter position to a `videoId` via `videoIdResolver`, then calls `prefetchService.triggerPrefetch(videoId, scope)`.
- Method `detach()`: removes the listener.
- No rate limiting logic — `ExtractionRateLimiter` inside `StreamPrefetchService` handles it. Rapid scrolls that attach/detach a view quickly are handled because `GlobalStreamResolver` deduplicates in-flight jobs.

**Modify these fragments** (replace tap-triggered call with `PredictivePrefetchController`):
- `ui/HomeFragment.kt`
- `ui/VideosFragmentNew.kt`
- `ui/SearchFragment.kt`
- `ui/detail/tabs/ChannelShortsTabFragment.kt`
- `ui/detail/tabs/ChannelVideosTabFragment.kt`
- `ui/detail/tabs/ChannelLiveTabFragment.kt`
- `ui/detail/PlaylistDetailFragment.kt`
- `ui/FeaturedListFragment.kt`

Pattern in each fragment:
```kotlin
// onViewCreated
prefetchController = PredictivePrefetchController(prefetchService, viewLifecycleOwner.lifecycleScope) { pos ->
    adapter.currentList.getOrNull(pos)?.id
}
prefetchController.attach(binding.recyclerView)

// onDestroyView
prefetchController.detach()
```

The existing tap-based `triggerPrefetch()` calls are removed. They are redundant because `GlobalStreamResolver` deduplicates, but removing them keeps the code clean.

**`PlaybackFeatureFlags`** — modify: add `KEY_PREDICTIVE_PREFETCH` (default ON). If OFF, fragments fall back to the existing tap-based call.

### Testing

- Unit test `PredictivePrefetchController`: verify `triggerPrefetch()` is called when a view attaches; verify it is NOT called for a position that resolves to null; verify `detach()` prevents further calls.
- Integration: manually test that rapid scrolling does not cause visible lag or excessive network calls (rate limiter absorbs it).

---

## Phase D — ABR never-freeze + TTL pre-refresh

### What

Two independent sub-components:

1. **`NeverFreezeTrackSelectionFactory`**: tune `AdaptiveTrackSelection` to start lower and downshift faster, prioritising uninterrupted playback over initial quality.
2. **`MpdTtlWatcher`**: proactively call `PlayerBinder.forceRefreshCurrent()` at 90% of MPD TTL (108s of 120s) to refresh signed URLs before they expire and cause a 403.

### Why

**ABR**: The default `AdaptiveTrackSelection` is bandwidth-optimistic — it picks high quality early and downgrades reactively when the buffer shrinks. On mobile networks, this causes visible rebuffering. Starting at 360p/480p and upgrading after 4 seconds of stable buffer is less impressive at first frame but eliminates virtually all mid-watch stalls.

**TTL pre-refresh**: `SyntheticDashMpdRegistry.MPD_TTL_MS = 120_000ms`. Signed YouTube URLs expire after ~6 hours but we conservatively regenerate the MPD at 2 minutes. A user watching a video that takes longer than 2 minutes will hit a 403 today — `PlaybackRecoveryManager` catches it but it causes a visible stall. Pre-refreshing at 108s eliminates the stall entirely.

### Sub-component: `NeverFreezeTrackSelectionFactory`

New file: `player/NeverFreezeTrackSelectionFactory.kt`
- Wraps `AdaptiveTrackSelection.Factory` with these parameters:
  - `minDurationForQualityIncreaseMs = 4_000` (default 10s — we upgrade faster)
  - `maxDurationForQualityDecreaseMs = 500` (default 25s — we downshift aggressively)
  - `minDurationToRetainAfterDiscardMs = 15_000`
  - `bandwidthFraction = 0.65f` (default 0.75 — we use 65% of estimated bandwidth to leave headroom)
- Exposes `create(): TrackSelection.Factory`

**`PlayerViewModel`** — modify: inject `NeverFreezeTrackSelectionFactory` and pass to `DefaultTrackSelector` constructor.

**`ShortsPlayerViewModel`** — modify: same injection.

**`PlaybackFeatureFlags`** — modify: add `KEY_NEVER_FREEZE_ABR` (default ON).

### Sub-component: `MpdTtlWatcher`

New file: `player/MpdTtlWatcher.kt`
- Not a singleton — one instance per `PlayerBinder` binding, created in `bind()`, cancelled in `cancelScope()` / `release()`.
- Constructor: `(videoId: String, registry: SyntheticDashMpdRegistry, onRefreshNeeded: () -> Unit)`
- On start: launches a coroutine. Uses `registry.getEntry(videoId)?.registeredAtMs` to compute time until 90% TTL. Delays until that point, then calls `onRefreshNeeded()`.
- Uses the same injectable clock pattern as `SyntheticDashMpdRegistry.setTestClock()` for deterministic testing.
- Only active when the current source is a synthetic DASH source (check `MediaSourceResult.adaptiveType == SYNTH_ADAPTIVE`).
- Skips refresh if player is IDLE or ENDED (no point refreshing a stopped player).

**`PlayerBinder`** — modify:
- After successfully setting a synthetic DASH source, create and start `MpdTtlWatcher(videoId, mpdRegistry) { forceRefreshCurrent() }`.
- Cancel the watcher when `bind()` is called again (new video) or `cancelScope()` / `release()` is called.

**`PlaybackFeatureFlags`** — modify: add `KEY_TTL_WATCHER` (default ON).

### Testing

- Unit test `NeverFreezeTrackSelectionFactory`: verify parameter values are applied; verify factory falls back to defaults when flag is OFF.
- Unit test `MpdTtlWatcher`: inject test clock; advance clock to 90% TTL; verify `onRefreshNeeded()` is called exactly once; verify cancellation before 90% does not call it; verify player IDLE state skips the call.
- Measure: `StreamRequestTelemetry` rebuffer count before/after on a 3+ minute video.

---

## Phasing and branches

| Phase | Branch | Ticket |
|---|---|---|
| A | `feature/ANDROID-PERF-01-cronet-network-upgrade` | ANDROID-PERF-01 |
| B | `feature/ANDROID-PERF-02-segment-prebuffer` | ANDROID-PERF-02 |
| C | `feature/ANDROID-PERF-03-predictive-prefetch` | ANDROID-PERF-03 |
| D | `feature/ANDROID-PERF-04-never-freeze-abr` | ANDROID-PERF-04 |

Recommended order: A → C → B → D. Phase A (Cronet) makes Phase B's pre-buffering faster. Phase C (visibility trigger) gives Phase B more time to run. Phase D is independent.

## Expected user-visible outcomes

| Scenario | Before | After (A+B+C) |
|---|---|---|
| Tap prefetched Short (visible ≥2s) | 1–3s first frame | <300ms first frame |
| Tap cold item (just appeared) | 2–5s first frame | 1–3s first frame (C alone) |
| Mid-watch stall (URL expiry) | Visible rebuffer via recovery | No stall (D: TTL pre-refresh) |
| Mid-watch quality drop on slow network | Reactive, visible quality switch | Proactive, smooth (D: ABR) |
| Cronet unavailable (old device) | HTTP/1.1 as today | HTTP/1.1 as today (A fallback) |

## Anti-bot safety (unchanged)

All phases preserve the existing protections:
- Per-device extraction only (never server-side URL resolution)
- `ExtractionRateLimiter` token bucket (PREFETCH = lowest priority)
- `GlobalStreamResolver` single-flight deduplication
- `YoutubeClientRotator` iOS/Android client rotation
- `HlsProbationChecker` HEAD probe before HLS commitment
- No pre-resolving whole feeds — only visible items
