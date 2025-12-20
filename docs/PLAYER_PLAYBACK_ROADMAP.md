# Player & Playback Reliability Roadmap (Android)

**Last Updated**: 2025-12-20 (Implementation complete; unit tests + connected/RTL/device-matrix verification complete)
**Scope**: Android player stability, progressive/adaptive strategy, and extraction/refresh safety.

> **Note**: Backend YouTube rate-limit remediation is tracked separately on branch `claude/fix-youtube-rate-limiting-clean` in `docs/status/YOUTUBE_RATE_LIMIT_PLAN.md`.

## Guiding Principles (Rock-Solid UX)

1. Prefer adaptive manifests (**HLS/DASH**) whenever available; treat user quality as a **cap**, not a lock.
2. Accept the hard truth: **Media3 cannot “fix” progressive**. Progressive is single-bitrate; the only ways to avoid stalls are:
   - (a) get adaptive streams more reliably, or
   - (b) proactively switch to a lower progressive stream before a stall.
3. Make all “re-resolve/refresh URLs” paths **rate-limit safe** (client + backend) so recovery/proactive switching doesn’t trigger anti-bot blocks.
4. Any UI change must be verified on **phone + `sw600dp` tablet + `sw720dp` large tablet/TV + RTL Arabic** per `AGENTS.md` (large-screen verification required when `sw720dp` resources exist).

## Reality Check: What Media3 Helps vs Doesn’t

### Media3 can improve
- Buffering behavior tuning and retry primitives (implementation-level improvements).
- Long-term maintenance (actively developed APIs).
- MediaSession/notification plumbing (cleaner background controls).

### Media3 does not solve
- Progressive streams are **single-bitrate**: no framework can “ABR” them without adaptive manifests.
- If HLS/DASH is missing or unreliable/expired, stability depends primarily on:
  - extraction reliability,
  - URL lifecycle handling,
  - proactive progressive downshifting,
  - bounded refresh/retry with rate-limit protection.

## Current Status (What's Done vs What's Left)

### Completed (in `main`)
- **PR1 Done**: Adaptive-first selection for all videos (no duration gate), consistent DataSource settings + caching for both progressive AND adaptive streams, HLS→DASH fallback (fixed 2025-12-17), accurate `PLAYBACK_START` logging, source identity tracking via `MediaSourceResult.actualSourceUrl` (not just HLS preference), explicit DASH usage logging, sticky adaptive fallback flag to prevent rebuild loops when adaptive creation fails (fixed 2025-12-17).
- **PR2 Done**: Freeze detection + auto-recovery ladder + state-driven recovery UI; tests pass under 300s policy.
- **PR3 Done**: Progressive playback proactive downshift (buffer health monitoring) to reduce freezes by switching quality before stalls (including when a user cap exists).
- **PR4 Done**: Stream URL lifecycle hardening (URL generation timestamp + conservative TTL checks to avoid switching to expired progressive URLs).
- **PR5 Done**: Android extraction/refresh rate-limit guardrails (`ExtractionRateLimiter` + wiring). Committed 2025-12-15.
- **PR6 Done**: Media3 migration + MediaSessionService integration (background playback, controller security, lifecycle-safe binding). Code complete 2025-12-16; manual verification complete (see Verification Status).
- **PR6.1 Done**: Synthetic DASH for progressive streams. Prior measurement pass recorded 100% success rate (36 videos, 296 video streams, 206 audio streams). Revalidated 2025-12-20 via `SyntheticDASH` logcat summary; playback confirmed using Synthetic DASH on progressive-only content. Implementation wraps video-only + audio progressive streams in synthetic DASH manifests for improved seek behavior. Committed 2025-12-18.
- **PR6.3 Done**: Tap-to-prefetch optimization. Stream extraction now starts when user taps a video in list screens (Home, Videos, Search), hiding 2-5 seconds of NewPipe extraction latency behind navigation animation. Uses internal CoroutineScope that survives fragment destruction. PlayerViewModel awaits in-flight prefetch for up to 3s, reducing (but not eliminating) duplicate extractions. Note: mis-taps/back-outs can still cause additional extractions. **Follow-up PR6.5 extends this to Channel + Playlist entry points.** Implemented 2025-12-18; verification complete.
- **PR6.4 Done**: Deferred async player release. Mitigates `ExoTimeoutException: Player release timed out` by deferring release() via Handler.post() on the player's application looper with try-catch. This allows onDestroyView() to complete immediately; release() runs on the next main loop iteration. Mitigates but doesn't fully eliminate UI jank if audio hardware is blocked. Implemented 2025-12-18; verification complete.
- **PR6.5 Done**: Playback entry-point parity. Extended tap-to-prefetch to all remaining video entry points: Channel tabs (Videos/Live/Shorts), PlaylistDetailFragment, and FeaturedListFragment. Implemented 2025-12-18.
- **PR6.6 Done**: Playlist playback reliability. Deep-start with `startVideoId` (bounded paging), lazy queue loader with `PlaylistPagingState`, async boundary fetch (no UI blocking), auto-skip for unplayable items, clean state transitions. 16 unit tests pass. Implemented 2025-12-18.
- **PR6.7 Done**: Early-stall prevention for progressive playback. Addresses the "plays for a few seconds then buffers" problem with three improvements:
  1. **Predictive downshift**: Calculates buffer depletion rate (using linear regression) and triggers downshift when projected to hit critical buffer within 30 seconds (before buffer becomes "low")
  2. **Early-stall exception**: Allows ONE emergency downshift during grace period if buffer becomes critical (< 3s) AND not building, preventing the initial stall
  3. **Synthetic DASH quality guard**: Skips synthetic DASH in favor of raw progressive when a higher-resolution muxed track is available under the same quality cap
  - Also raised LOW_BUFFER_THRESHOLD from 5s to 10s for earlier proactive action
  - 35 unit tests pass (25 BufferHealthMonitor + 10 SyntheticDashTrackSelection)
  - Implemented 2025-12-18.
- **PR7 Done**: Notification Controls + Artwork. Added `MediaSessionMetadataManager` for syncing video metadata (title, channel, artwork) to MediaSession. Artwork loaded asynchronously via Coil, converted to PNG bytes for notification display. 6 unit tests pass. Implemented 2025-12-18.
- **PR8 Done**: Fullscreen + Orientation Policy. Auto-fullscreen on landscape via `onConfigurationChanged()`, immersive UI with status/nav bar hiding, bottom nav integration, RTL support. Already implemented as part of PR6 player work; manual verification complete (see Verification Status).
- **PR9 Done**: Downloads – MP4-only Export. Added `DownloadQualityDialog` for quality selection before download. FFmpeg muxing, storage, and cleanup were already implemented. 2025-12-18.

### Verification Status (2025-12-20)
- ✅ **Unit tests**: `cd android && timeout 300 ./gradlew testDebugUnitTest --rerun-tasks` (PASS).
- ✅ **Connected tests**: `cd android && timeout 300 ./gradlew connectedDebugAndroidTest --rerun-tasks` (PASS).
  - Phone (default density): 3× consecutive runs.
  - sw600dp: simulated via `adb shell wm density 280` (3× runs).
  - sw720dp/TV: simulated via `adb shell wm density 240` (3× runs).
- ✅ **RTL Arabic locale**: Verified via `ChannelDetailRtlTest` + `PlaylistDetailRtlTest` in connected runs.
- ✅ **Device matrix (phone/sw600dp/sw720dp)**: Covered via density overrides on the connected device.
- ✅ **Manual playback verification**:
  - Prefetch logs captured from Videos tab, PlaylistDetail, Channel Videos tab, and Featured list (`StreamPrefetch` start/await/complete/consumed).
  - Synthetic DASH revalidation: `SyntheticDASH` summary for `EnfgPg0Ey3I` (totalVideo=13, totalAudio=4, mpdOKVideo=12, mpdOKAudio=4); player prepared with `type=SYNTHETIC_DASH`.
  - MediaSession controls: `KEYCODE_MEDIA_PLAY_PAUSE` toggled state 3↔2 via `dumpsys media_session`.
  - Playlist deep start: tapped item 2; player title matched selection (“من القصص المؤثرة القاتل يقتل ولو بعد حين”).
  - Fullscreen orientation: forced landscape shows player without bottom nav; portrait restores standard UI.

### Test Infrastructure Fixes (2025-12-19)
- ✅ **DeepLinkNavigationTest reliability**: Fixed all 6 deep-link tests that were previously crashing/failing.
  - Root cause: ActivityScenario teardown hangs when PlaybackService foreground service prevents activity destruction.
  - Fix: Explicit `context.stopService(PlaybackService)` before `activity.finish()`; attempt `scenario.close()` on a background thread with a timeout to avoid hangs.
  - Deterministic waits: Replaced in-test polling loops with latch-driven destination listeners; teardown uses `waitForIdleSync()` only.
    - `waitForMainShellReady()` uses `CountDownLatch` + `OnDestinationChangedListener` on main NavController for `mainShellFragment`.
    - `waitForDestination()` uses `CountDownLatch` + `OnDestinationChangedListener` on nested NavController.
    - `decorView.post()` ensures nested NavHost fragment view creation completes before asserting.
  - Result: Test suite time reduced from 153s to ~12s; significantly reduced flakiness from timing issues.
- ✅ **Design tokens in library_item_favorites.xml** (renamed from `library_item_saved.xml`): Replaced hardcoded `40dp`/`8dp`/`24dp` values with `@dimen/library_icon_size`, `@dimen/spacing_sm`, `@dimen/icon_small`.
- ✅ **300-second test gate**: Targeted to complete <300s; requires device run to confirm.

### Code Review Fixes (2025-12-19)
- ✅ **MediaSession artwork race condition**: `MediaSessionMetadataManager.currentMetadataToken` now uses `AtomicLong` for safe token identity gating. Token increments atomically with `incrementAndGet()` and reads use `get()`. Note: AtomicLong provides safety for the token specifically; the class as a whole assumes main-thread access for other mutable state (cache fields, artworkLoadJob).
- ✅ **RTL alignment in downloads layouts**: Added explicit `textAlignment="viewStart"` to section headers and storage info text in phone layout (`fragment_downloads.xml`) and `textAlignment="center"` to empty state text in both phone and tablet layouts for consistency.
- ✅ **Tokenized library items**: Fixed hardcoded values in `library_item_recently_watched.xml` and `library_item_history.xml`:
  - Replaced `40dp`/`8dp`/`24dp`/`16sp`/`14sp`/`2dp` with `@dimen/library_icon_size`, `@dimen/spacing_sm`, `@dimen/icon_small`, `@dimen/text_subtitle`, `@dimen/text_body`, `@dimen/spacing_xs`
  - Replaced hardcoded text ("Recently Watched", "History") with `@string/library_recently_watched`, `@string/library_history`
  - Replaced `@android:color/black` with `?attr/colorOnSurface` (night mode compatible)
  - Replaced `@color/icon_gray` with `?attr/colorOnSurfaceVariant` where applicable
  - Added explicit `textAlignment="viewStart"` for RTL support
- ✅ **RTL chevron auto-mirroring**: `ic_chevron_right.xml` already has `android:autoMirrored="true"` (no fix needed).

### Code Review Fixes (2025-12-20)
- ✅ **Auto-recovery refresh used MANUAL limiter (PR5 violation)**: Fixed `PlayerViewModel.applyAutoQualityStepDown()` to call `forceRefreshForAutoRecovery()` instead of `forceRefreshCurrentStream()`. This ensures automatic quality step-down paths use AUTO_RECOVERY kind with reserved budget, not MANUAL which could be blocked by user refresh spam.
- ✅ **Recovery refresh could leave playback stopped**: Fixed `PlayerFragment.requestStreamRefreshAndResume()` to check rate limiter return value BEFORE stopping the player. Previously, the player would be stopped first, then refresh could be blocked, leaving playback in a stuck state. Now if refresh is blocked, playback continues uninterrupted with a toast notification.
- ✅ **Rebuild recovery blocked handling**: Fixed `PlayerFragment.onRequestPlayerRebuild()` to call `viewModel.setRecoveryExhaustedState()` when rebuild refresh is blocked by rate limiter. Rebuild is the last recovery step—if blocked, transitioning to exhausted state gives the user a retry button.
- ✅ **Design token compliance in player layouts**: Replaced hardcoded dp/sp values with design tokens in `fragment_player.xml` and `fragment_player.xml` (sw600dp variant):
  - `12dp`/`16dp`/`24dp` → `@dimen/spacing_sm`/`@dimen/spacing_md`/`@dimen/icon_small`
  - `14sp`/`16sp`/`12sp` → `@dimen/text_body`/`@dimen/text_subtitle`/`@dimen/text_caption`
  - Hardcoded strings → `@string/player_description`, `@string/player_no_description`
  - Added `android:textAlignment="viewStart"` and `android:textAlignment="center"` for RTL support
- ✅ **Theme colors in fragment_subcategories.xml**: Replaced hardcoded colors:
  - `@color/background_gray` → `?attr/colorSurfaceVariant`
  - `@android:color/white` → `?attr/colorSurface`
  - `@android:color/black` → `?attr/colorOnSurface`
  - Hardcoded "Subcategories" → `@string/subcategories_title` (with AR/NL translations)
- ✅ **Design tokens in fragment_featured_list.xml**: Replaced `100dp` with `@dimen/spacing_xxxl` (96dp—semantically correct for spacing), added `textAlignment="center"` for error text RTL support.
- ✅ **Design tokens in fragment_channel_detail.xml (base)**: Replaced hardcoded `strokeWidth="3dp"` with `@dimen/channel_avatar_stroke` and `textSize="14sp"` with `@dimen/text_body` to match tablet layouts.
- ✅ **AUTO_RECOVERY global limit bypass**: Fixed `ExtractionRateLimiter.checkGlobalLimit()` to allow AUTO_RECOVERY requests to bypass the global rate limit entirely. This ensures playback recovery is never blocked by MANUAL/PREFETCH request storms.
- ✅ **Per-kind timestamp tracking in ExtractionRateLimiter**: Min-interval/backoff now keys off MANUAL timestamps so AUTO_RECOVERY/PREFETCH no longer delay manual refresh; AUTO_RECOVERY window uses its own timestamps.
- ✅ **DeepLinkNavigationTest teardown sync**: Removed all `Thread.sleep` calls; teardown now relies on `waitForIdleSync()` only.
- ✅ **URL TTL time-source mismatch (HIGH severity)**: Fixed `NewPipeExtractorClient.kt` to use `SystemClock.elapsedRealtime()` instead of `System.currentTimeMillis()` for URL generation timestamps. This aligns with `StreamModels.areUrlsExpired()` which also uses `elapsedRealtime()`, ensuring URL TTL checks work correctly regardless of wall-clock changes (device time adjustments, timezone changes, NTP sync).
- ✅ **DeepLinkNavigationTest teardown hanging**: Fixed `tearDown()` to navigate away from playerFragment, stop PlaybackService, call `activity.finish()`, and attempt `scenario.close()` on a background thread with a timeout to avoid hangs when MediaSessionService blocks activity destruction.
- ✅ **AUTO_RECOVERY global counter contribution**: Fixed `ExtractionRateLimiter.acquire()` to exclude AUTO_RECOVERY requests from `globalAttemptTimestamps`. Previously, AUTO_RECOVERY bypassed the global limit check but still contributed to the counter, starving subsequent MANUAL/PREFETCH requests. Now AUTO_RECOVERY neither checks nor contributes to global counters.
- ✅ **Deferred player release on rebuild**: Recovery rebuild path now uses async `player.release()` on the player looper (consistent with PR6.4 onDestroyView deferral).
- ✅ **RTL container direction**: Added `android:layoutDirection="locale"` to Channel/Playlist `headerContent` + `contentContainer` across layout variants for RTL test compliance.
- ✅ **Design token cleanup (Downloads/About/Categories)**: Replaced `@color/background_gray` with `?attr/colorSurfaceVariant`, and `1dp` dividers with `@dimen/divider_thickness` (new token).
- ✅ **Build hygiene**: Replaced deprecated NewPipe `VideoStream.isVideoOnly` field usage with `isVideoOnly()` and removed unused parameters/vars + redundant initializers across player/download/test code.
- ✅ **MenuProvider migration in PlayerFragment**: PlayerFragment now uses the MenuProvider API (`requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)`) instead of deprecated `setHasOptionsMenu(true)`/`onCreateOptionsMenu`/`onOptionsItemSelected` pattern. This aligns with AndroidX best practices for fragment menu handling.
- ✅ **Async release safety**: `releasePlayerAsync()` now stops playback before deferring `release()` to prevent overlap during rebuild/teardown.
- ✅ **ResolvedStreams timebase guard**: Added `urlTimebaseVersion` and expired-on-mismatch behavior to avoid TTL errors if timebase changes; unit test added.
- ✅ **Sleep-free pagination tests**: `PlaylistDetailViewModel` now exposes a test clock; removed `Thread.sleep` from `PlaylistDetailViewModelTest`.
- ✅ **NavRail test stability**: `NavigationGraphTest` uses a relaxed click action so rail items remain clickable when only partially visible in sw600/sw720 configs.
- ✅ **Hilt test stability**: `launchFragmentInHiltContainer` now uses `commitNowAllowingStateLoss()` to avoid state-loss crashes during instrumentation.

### Device/RTL Verification (2025-12-20)
- ✅ **Phone layout verification**: Connected tests on default density (3× runs).
- ✅ **sw600dp tablet layout verification**: Connected tests with density override 280 (3× runs).
- ✅ **sw720dp large tablet/TV layout verification**: Connected tests with density override 240 (3× runs).
- ✅ **RTL Arabic support verification**: RTL tests pass in connected runs.
- ✅ **Instrumentation flakiness check (3× runs)**: Completed for phone + sw600dp + sw720dp configurations.

---

# Key Reliability Fixes (Why “progressive buffering feels stuck” happens)

## Fix: Remove “long video” gate for adaptive
Progressive is inherently single-bitrate and will stall whenever network throughput dips below the chosen bitrate. To make playback feel “adaptive-smooth”, the player must use **HLS/DASH whenever present**, regardless of video length or missing duration metadata.

## Fix: Apply adaptive quality cap without rebuilding MediaSource
For adaptive streams, quality changes are constraints on the **track selector**, not a different URL. Rebuilding the MediaSource on cap changes causes unnecessary rebuffering and makes playback feel “stuck”.

## Fix: Progressive auto step-down must work even if user set a cap
User "quality" is a **ceiling**. During stalls, the player must be able to temporarily step down **below the ceiling** (AUTO_RECOVERY) without overriding the saved user preference.

## Fix: "Plays for a few seconds then buffers" (PR6.7)
This pattern occurs when:
1. Fast start with small buffer (2s `bufferForPlaybackMs`)
2. Network throughput slightly below video bitrate
3. Buffer depletes over 5-15 seconds until stall

**Root cause**: The original buffer health monitor only acted when buffer hit "low" threshold (5s), which was too late—user already experienced a stall.

**Solution (three-pronged)**:
1. **Predictive downshift**: Calculate depletion rate (via linear regression) and project when buffer will hit critical. Trigger preemptive downshift when projected to stall within 30s, even if buffer is currently "healthy" (e.g., 80s). This catches the gradual drain pattern early.
2. **Early-stall exception**: Bypass the 10s grace period for critical buffer situations. If buffer becomes critical (< 3s) during startup AND is not building, allow ONE emergency downshift immediately instead of waiting for grace period to expire. The "not building" check prevents false positives on merely-slow-to-build buffers.
3. **Synthetic DASH quality guard**: Skip synthetic DASH in favor of raw progressive when a higher-resolution muxed track is available under the same quality cap. This prevents synthetic DASH from selecting a lower-quality video-only track when a better muxed option exists.

---

# PR-by-PR Plan (From Scratch, with statuses)

## PR1: Adaptive-First Selection + Source Identity — *Status: Done (verified)*

**Goal**: Prefer adaptive manifests for all videos, apply consistent caching, and prevent rebuild loops by tracking actual source identity.

- Adaptive-first selection with no duration gate (HLS → DASH → Synthetic DASH → Progressive).
- Consistent DataSource configuration + caching for adaptive and progressive.
- Source identity tracking via `MediaSourceResult.actualSourceUrl` + `AdaptiveType` to avoid rebuild loops.
- Sticky adaptive fallback flag to prevent repeated adaptive creation failures until manual retry.

**Acceptance**
- Adaptive streams are preferred whenever available; progressive fallback is stable and loop-free.
- Quality cap changes do not trigger unnecessary MediaSource rebuilds.

## PR2 (Finalize Gate + Merge) — *Status: Done (merged to main)*

**Goal**: Lock in "never permanently stuck" behavior.

- ✅ Merged: commit `17e9ebb` on 2025-12-15
- Manual verification complete (see Verification Status).
- Run: `cd android && timeout 300 ./gradlew testDebugUnitTest`

**Acceptance**
- No “leave screen to fix playback” scenario remains; worst case is exhausted state with retry.

## PR3: Progressive Playback – Proactive Downshift — *Status: Done*

**Goal**: Prevent long freezes on progressive playback by switching down before the stall.

- Progressive-only buffer health monitoring:
  - sample `bufferedPosition - currentPosition` trend (e.g., 1s sampling)
  - trigger downshift when buffer is low and declining (guardrails: grace period, cooldown, max downshifts)
- Downshift using already-resolved progressive tracks (no extra extraction calls unless URLs are expired/invalid).
- Preserve position + play/pause state; cap auto-downshift count per stream.
- Coordinate with PR2: disable proactive downshift while `Recovering/RecoveryExhausted`.

**Acceptance**
- Under throttling, fewer/shorter stalls than PR2-only; no reload loops.

## PR3.1: Progressive/Adaptive Consistency — *Status: Done*

**Goal**: Ensure the “cap-not-lock” model behaves correctly for both adaptive and progressive.

- Adaptive: changing cap updates the track selector **in-place** (no MediaSource rebuild).
- Progressive: AUTO_RECOVERY step-down uses the explicit stepped-down track even if a manual cap exists.
- Default progressive-only start quality starts at a mid-range resolution (480p-720p) rather than highest available to avoid "never starts playing" on weaker networks.

**Acceptance**
- Manual quality changes don’t trigger unnecessary rebuffer loops on adaptive streams.
- Progressive step-down triggers reliably under stalls even after a user chooses a cap.

## PR4: Android Stream URL Lifecycle Hardening — *Status: Done*

**Goal**: Make URL expiry/segment failures recover smoothly (especially for long videos).

- Track URL generation time and detect conservative expiry.
- Prevent quality switching to likely-expired progressive URLs; refresh first, then apply the user’s cap/selection.
- Ensure refresh resumes at the saved position and doesn’t fight proactive/recovery actions.

**Acceptance**
- Long videos don’t degrade into repeated “buffer forever”; refresh is bounded and reliable.

## PR5: Android Extraction/Refresh Rate-Limit Guardrails (Client-side) — *Status: Done*

**Goal**: Prevent the app from hammering extraction endpoints during retries/recovery while keeping recovery reliable.

- ✅ Committed: 2025-12-15

### Must-have behaviors (rock-solid UX)
- **Record attempts before extraction** (attempt-based limiter; not “success-based”).
- Separate budgets by request kind:
  - `MANUAL`: strict (spam protection).
  - `AUTO_RECOVERY`: reserved budget so recovery is not blocked by manual refresh spam.
  - `PREFETCH`: lowest priority; skip under pressure.
- Cancel stale delayed refresh jobs on video changes.
- Prefer local progressive quality switching over refresh when possible; refresh only when URLs are invalid/expired.

### Tests
- ✅ Unit tests for limiter logic (injected clock) - 23 tests in `ExtractionRateLimiterTest.kt`
- ✅ Unit tests for recovery manager (injected clock) - 23 tests in `PlaybackRecoveryManagerTest.kt`
- ✅ Unit tests for buffer health monitor (injected clock) - 25 tests in `BufferHealthMonitorTest.kt`
- ✅ Unit tests for quality step-down helper - 14 tests in `QualityStepDownHelperTest.kt`
- Total: 85 player tests, all passing
- Manual: repeated refresh taps and recovery scenarios should not create bursts.

**Acceptance**
- Recovery cannot spiral into rapid refresh loops.
- Users receive clear, bounded feedback when manual refresh is rate-limited.
- Auto-recovery refresh is not blocked by manual refresh limits.

---

# Product / UX Follow-ups (pending after stability baseline)

## PR6: Media3 Migration + MediaSession Integration — *Status: Done*

**Goal**: Migrate to Media3 for long-term maintenance + better session/notification primitives, without claiming it fixes progressive.

- ✅ Migrated player core (Player/View, MediaSource factories, track selector, listeners).
- ✅ Keep the same layout and controls (IDs and overlays remain; underlying player widget class changes).
- ✅ Port PR2 recovery hooks + PR3 proactive downshift to Media3 `Player` APIs.
- ✅ Implemented `MediaSessionService` (`PlaybackService.kt`) with session wiring.
- ✅ Service lifecycle: bind-based (not start-based) to avoid Android O+ foreground timing issues.
- ✅ Controller security: full commands for own app + legacy controller (system notification/Bluetooth/lockscreen); all others get restricted playback-only commands (play/pause/seek/stop/metadata).
- ✅ Explicit local binding path (`ACTION_LOCAL_BIND`) for robust binder type.
- ✅ Lifecycle-aware service rebind on unexpected disconnect.
- ✅ User pause state preserved across fragment lifecycle.

**Acceptance**
- ✅ Feature parity; no regressions in recovery/proactive behavior (adaptive + progressive + audio-only).
- ✅ Background playback works via MediaSessionService.
- Manual verification complete: media button play/pause toggled state via `dumpsys media_session`.

## PR6.1: Progressive "Synthetic DASH" (NewPipe-style) — *Status: Done*

**Goal**: Make progressive-only playback feel less "stuck" during seeks/restarts by wrapping eligible progressive streams in a **single-representation DASH MPD** (byte-range via `SegmentBase`), without claiming ABR and without adding extra extraction/network calls.

**What this is / isn't**
- ✅ Improves seek/restart behavior via byte-range requests and a structured container index.
- ❌ Does not provide "ABR smoothness": progressive remains single-bitrate. PR3 proactive downshift remains essential for throughput dips.

**Bug Fix (2025-12-18): Origin-based cache-hit detection**
- ✅ Fixed re-prepare loops in AUTO mode caused by divergence between ViewModel's `selection.video` and factory's actual selected track (`factorySelectedVideoTrack`).
- ✅ Cache-hit logic now uses origin-based URL comparison:
  - AUTO: compares against `factorySelectedVideoTrack?.url` (factory's 720p default choice)
  - MANUAL: compares against `selection.video?.url` (user's requested quality)
  - AUTO_RECOVERY: compares against `selection.video?.url` (recovery target)
- ✅ SYNTHETIC_DASH rebuild check extended to include MANUAL origin (was only AUTO_RECOVERY).

**Implementation Details (completed 2025-12-18)**
- ✅ **Measurement pass completed**: Tested 36 videos with 100% success rate for both video (296/296) and audio (206/206) streams.
- ✅ **Revalidated 2025-12-20**: `SyntheticDASH` summary for `EnfgPg0Ey3I` (totalVideo=13, totalAudio=4, mpdOKVideo=12, mpdOKAudio=4); playback used `type=SYNTHETIC_DASH`.
- ✅ **SyntheticDashMetadata** added to `VideoTrack` and `AudioTrack` models to store itag, init/index ranges, and approx duration.
- ✅ **SyntheticDashMediaSourceFactory** created to generate DASH MPD from progressive streams using `YoutubeProgressiveDashManifestCreator`.
- ✅ **Integrated into MultiQualityMediaSourceFactory**: Strategy is now HLS → DASH → Synthetic DASH → Raw Progressive.
- ✅ **Automatic fallback**: If synthetic DASH fails for any reason (missing metadata, invalid ranges, MPD generation error), falls back to raw progressive.
- ✅ **AdaptiveType.SYNTHETIC_DASH** added to distinguish from real adaptive streams in logging/debugging.

**Files Modified**
- `StreamModels.kt`: Added `SyntheticDashMetadata` data class and fields to `VideoTrack`/`AudioTrack`
- `NewPipeExtractorClient.kt`: Populates `SyntheticDashMetadata` during stream extraction for eligible streams
- `SyntheticDashMediaSourceFactory.kt`: New factory for creating synthetic DASH sources
- `MultiQualityMediaSourceFactory.kt`: Integrated synthetic DASH into media source selection chain

**Acceptance (expected)**
- ✅ Seeks and "resume after error" are faster/more reliable on progressive-only videos (especially video-only+audio).
- ✅ No new network calls beyond normal playback (MPD is generated locally from metadata).
- ✅ Progressive fallback remains correct and stable for muxed streams and edge cases.

## PR6.2: Extractor Adaptive-Manifest Backfill (Optional / Upstream) — *Status: Research*

**Goal**: Reduce the remaining cases that fall back to progressive because the extractor did not provide HLS/DASH manifest URLs, without violating PR5's rate-limit guardrails.

**Reality**: This is primarily a NewPipeExtractor/YouTube behavior issue. Client-profile workarounds are fragile and can easily regress when YouTube changes responses.

**Measurement Logging (Added 2025-12-18)**:
- ✅ HLS/DASH availability metrics added to `NewPipeExtractorClient.kt`
- Filter logcat: `adb logcat -s AdaptiveAvail`
- Logs: `hasHls`, `hasDash`, `streamType`, `duration`, track counts
- Warning-level log for videos with NO adaptive manifests

**Pre-flight Checklist** (before greenlight):
- [ ] Collect data from measurement logging to understand % with/without manifests
- [ ] Verify whether a newer NPE version improves `dashMpdUrl/hlsUrl` availability for our target content.
- [ ] Prefer upstream fixes (issue/PR to NPE) over app-layer Innertube hacks.
- [ ] If an app-layer workaround is still desired: design it as opt-in, bounded, and strictly rate-limit safe.

**Implementation (if pursued)**
- Bounded backfill strategy (strictly optional):
  - At most one additional attempt per video ID (no loops), within PR5 limiter budgets.
  - Prefer "version bump / upstream patch" over per-video multi-client probing.

**Acceptance**
- Improved manifest availability without causing rate-limit bursts.
- No repeated extraction loops; progressive fallback remains acceptable.

## PR6.3: Tap-to-Prefetch (Home/Videos/Search) — *Status: Done (verified)*

**Goal**: Hide extractor latency by starting stream resolution when users tap a video in list screens.

**Implementation**
- `StreamPrefetchService` triggered from Home, Videos, and Search list item taps.
- Prefetch runs in a long-lived internal scope; caller scope is for API consistency only.
- PlayerViewModel awaits in-flight prefetch (up to 3s) before resolving again.
- PREFETCH priority respects PR5 rate limiter budgets.

**Acceptance**
- Logcat shows `StreamPrefetch: Starting prefetch`, `Prefetch completed`, `Prefetch consumed (awaited)` on taps.
- Navigation hides 2–5s extraction latency without increasing refresh bursts.
- Manual verification complete (see Verification Status).

## PR6.4: Deferred Async Player Release — *Status: Done (verified)*

**Goal**: Prevent UI stalls and `ExoTimeoutException` on player teardown/rebuild by deferring release off the critical path.

**Implementation**
- `onDestroyView()` posts `player.release()` to the player’s application looper.
- Recovery rebuild path also uses deferred release before re-creating the player.

**Acceptance**
- Fragment teardown and recovery rebuilds complete without blocking the main thread.
- Manual verification complete (see Verification Status).

## PR6.5: Playback Entry-Point Parity (Channels + Playlists + Featured) — *Status: Done*

**Goal**: Ensure the PR6.3 "tap-to-prefetch" latency hiding applies to **all** ways users start playback, not just Home/Videos/Search.

**Problems this fixes**
- Channel video taps still do "navigate → then extract" (perceived 2–5s lag).
- Playlist item taps still do "navigate → load playlist page → then extract" (lag + higher chance of "stuck at 0" if playlist fetch stalls).

**Implementation (completed 2025-12-18)**
- ✅ Added `StreamPrefetchService` injection + `triggerPrefetch()` calls to:
  - `ChannelVideosTabFragment`: Videos tab in channel detail
  - `ChannelLiveTabFragment`: Live streams tab in channel detail
  - `ChannelShortsTabFragment`: Shorts tab in channel detail
  - `PlaylistDetailFragment`: Individual video item taps
  - `FeaturedListFragment`: Featured content video taps
- ✅ Added prefetch for "Play All" button in playlist (best-effort: only runs if items already loaded)
- ✅ Shuffle button cannot prefetch (order unknown until player starts)
- ✅ All prefetch calls use `PREFETCH` priority (lowest) and respect PR5 rate-limit guardrails

**Files Modified**
- `ChannelVideosTabFragment.kt`: Added `@Inject prefetchService` + prefetch call
- `ChannelLiveTabFragment.kt`: Added `@Inject prefetchService` + prefetch call
- `ChannelShortsTabFragment.kt`: Added `@Inject prefetchService` + prefetch call
- `PlaylistDetailFragment.kt`: Added `@Inject prefetchService` + prefetch calls for item tap + Play All
- `FeaturedListFragment.kt`: Added `@Inject prefetchService` + prefetch call for video taps

**Important Notes**
- **Scope parameter ignored**: `StreamPrefetchService.triggerPrefetch()` uses its own internal `SupervisorJob` scope, so the passed `viewLifecycleOwner.lifecycleScope` is only for API consistency—do not assume cancellation semantics from the caller's scope.
- **Play All is best-effort**: Prefetch only triggers if `itemsState` is already `Loaded`; no prefetch if items are still loading.
- **Playlist correctness**: Prefetch helps perceived latency, but playlist "wrong start" and "Next broken" issues remain until PR6.6 (which fixes deep starts and lazy queue loading).
- **Rate limiter consideration**: Expanding prefetch to more entry points increases pressure on the global rate limiter. In heavy "tap around" scenarios, prefetch can consume global window permits. Future refinement could make global budgets kind-aware so PREFETCH doesn't starve recovery.

**Acceptance (expected behavior)**
- Logcat (`StreamPrefetch`) shows "Starting prefetch …" for channel + playlist + featured item taps.
- Opening a video from any entry point hides 2-5s extraction latency behind navigation.
- No extraction bursts: prefetch remains `PREFETCH`-priority and respects PR5 guardrails.

**Testing**
- ✅ Code compiles successfully
- ✅ All unit tests pass
- Manual verification complete: `StreamPrefetch` logs confirmed for playlist item taps, channel videos, and featured list.

## PR6.6: Playlist Playback Reliability (Full Queue + Next/Prev + Deep Starts) — *Status: Done*

**Goal**: Playlist playback behaves like a real queue: start at the correct item (even deep), and Next/Prev works until the actual end of the playlist.

**Problems fixed**
- Player previously loaded only the first playlist page; `startIndex` was clamped, causing "wrong video starts" and Next becoming disabled early.
- "Stuck at the beginning" could occur because playlist item fetch had no timeout in the player path.

**Implementation (completed 2025-12-18)**
- ✅ **Deep-start with `startVideoId`**: Pages through playlist items until the target video is found (bounded by MAX_SCAN_TIME_MS=3s and MAX_ITEMS_TO_SCAN=250).
- ✅ **Lazy playlist queue loader** (`PlaylistPagingState`):
  - Stores `nextPage`, `nextItemOffset`, `hasMore`, and `pagingFailed` flags.
  - Fetches additional pages when queue is empty at boundary (async, non-blocking).
  - `hasNext` UI state reflects `queueNotEmpty || playlistHasMore` (not just current in-memory queue).
- ✅ **Strict timeouts**: PAGE_FETCH_TIMEOUT_MS=10s per page fetch, total deep-start budget enforced.
- ✅ **Auto-skip for unplayable items**: MAX_CONSECUTIVE_SKIPS=3 with analytics event (`VideoSkipped`).
- ✅ **Async boundary fetch**: Replaced `runBlocking` with `viewModelScope.launch` to prevent UI freeze/deadlock.
- ✅ **Clean state on mode switch**: `loadVideo()` clears stale `playlistPagingState` to prevent incorrect `hasNext` for single videos.
- ✅ **Shuffle limitation**: Paging is disabled for shuffled playlists (shuffle applies only to initially loaded items).

**Files Modified**
- `PlayerViewModel.kt`: Added `PlaylistPagingState`, `fetchNextPlaylistPage()`, deep-start paging loop, auto-skip logic, async boundary handling.
- `PlayerViewModelPlaylistPagingTest.kt`: 16 comprehensive unit tests covering all paging scenarios.

**Acceptance (expected)**
- ✅ Tapping a deep item in a long playlist starts *that* item (no clamping to first-page tail).
- ✅ Next/Prev buttons remain usable across playlist boundaries (and don't disable while `hasMore` exists).
- ✅ Playlist fetch hangs are bounded (timeout + retry), never leaving the player stuck indefinitely.
- ✅ Rate-limit safe: playlist paging does not spam NewPipe/YouTube endpoints.
- ✅ 16 unit tests pass for playlist paging scenarios.

**Testing Results**
- ✅ `startVideoId found on page N` (deep-start test)
- ✅ `startIndex beyond first page loads more`
- ✅ `next at boundary fetches + advances`
- ✅ `resolve failure auto-skips (bounded)`
- ✅ `loadVideo clears stale playlist paging state`
- ✅ `switching from playlist to video does not attempt paging`
- Manual verification complete: tapped item 2 in playlist; player started with selected title and queue remained intact.

**Minor Notes**
- `skipToNext()` returns `true` before next item is determined (indicates "handling it").
- `VideoSkipped` event emitted to analytics; toast wiring optional (not implemented).
- Shuffle behavior limits to initially loaded items only (paging disabled for shuffled playlists).

## PR6.7: Early-Stall Prevention (Predictive Downshift) — *Status: Done (verified)*

**Goal**: Prevent the “plays for a few seconds then buffers” pattern on progressive streams.

**Implementation**
- Predictive buffer depletion modeling (linear regression) with preemptive downshift.
- Early-stall exception: allow one emergency downshift during grace window if buffer becomes critical and not building.
- Synthetic DASH guard: prefer muxed tracks when available under the same cap.
- Buffer thresholds tuned (LOW_BUFFER_THRESHOLD = 10s).

**Testing**
- 35 unit tests pass (25 `BufferHealthMonitorTest` + 10 `SyntheticDashTrackSelectionTest`).

**Acceptance**
- Fewer early stalls on progressive playback; downshift is bounded and coordinated with recovery.
- Manual verification complete: BufferHealthMonitor started and sampling logs observed during progressive playback.

## PR7: Notification Controls + Artwork — *Status: Done*

**Goal**: Add media notification with artwork/metadata display for background playback.

**Implementation (completed 2025-12-18, enhanced 2025-12-19)**:
- ✅ **MediaSessionMetadataManager**: New singleton that manages metadata sync to MediaSession.
  - Loads artwork bitmaps asynchronously via Coil (512px for notification/lockscreen).
  - Caches last loaded artwork to avoid redundant loads on repeated state emissions.
  - Converts bitmap to PNG bytes for `MediaMetadata.artworkData`.
  - Token-based identity tracking (`currentMetadataToken` as `AtomicLong`) for thread-safe async artwork completion.
  - Artwork job cancellation at START of `updateMetadata()` (critical when thumbnailUrl is null to prevent stale artwork).
- ✅ **PlayerFragment integration**: Syncs metadata when `currentItem` changes via `syncMediaSessionMetadata()`.
  - Uses `lastMetadataSyncedItemId` to avoid redundant updates.
  - Clears metadata state in `onDestroyView()`.
- ✅ **Notification controls**: Handled automatically by Media3's `MediaSessionService`.
  - Play/pause/next/prev/stop buttons work via MediaSession callbacks.
  - Lockscreen and Bluetooth controller support already wired in PR6.

**Files Added/Modified**:
- `MediaSessionMetadataManager.kt`: New manager for metadata + artwork loading.
- `PlayerFragment.kt`: Added `@Inject metadataManager` and `syncMediaSessionMetadata()` helper.
- `MediaSessionMetadataManagerTest.kt`: 6 unit tests for metadata structure validation.

**Acceptance (expected)**:
- ✅ Notification shows video title, channel name, and artwork.
- ✅ Controls work reliably in background/lockscreen/headset.
- ✅ Artwork loads asynchronously without blocking playback.
- ✅ Race condition on rapid item changes prevented via token matching.
- ✅ 6 unit tests pass.

## PR8: Fullscreen + Orientation Policy (Phone + Tablet) — *Status: Done*

**Goal**: Automatic fullscreen in landscape with immersive UI.

**Implementation (already in place)**:
- ✅ **Auto-fullscreen on landscape**: `onConfigurationChanged()` syncs fullscreen state and calls `updateFullscreenUi()` when orientation changes (no recursive toggle).
- ✅ **Immersive mode**: Uses `SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_IMMERSIVE_STICKY` to hide status/nav bars.
- ✅ **Bottom navigation integration**: `MainActivity.setBottomNavVisibility()` hides/shows bottom nav during fullscreen.
- ✅ **RTL support**: Fullscreen button uses `layout_gravity="bottom|end"` (RTL-aware).
- ✅ **Tablet support**: Responsive player sizing via `@dimen/player_portrait_height` (240dp phone, 360dp tablet).
- ✅ **Manual toggle**: Fullscreen button with icon swap (ic_fullscreen ↔ ic_fullscreen_exit).

**Acceptance (expected)**:
- ✅ Landscape → fullscreen automatically; portrait → normal player screen.
- ✅ Immersive UI works on phone and tablet.
- RTL verification complete; layoutDirection assertions pass on device tests.
- ✅ No code changes needed - already implemented as part of PR6 player work.

## PR9: Downloads – MP4-only Export, Always With Audio — *Status: Done*

**Goal**: Add resolution picker UI for downloads; always output MP4 with audio.

**Implementation (completed 2025-12-18)**:
- ✅ **Download quality picker**: New `DownloadQualityDialog` shows available video qualities + audio-only option.
- ✅ **ViewModel enhancement**: `downloadCurrent()` now accepts `targetHeight` and `audioOnly` parameters.
- ✅ **FFmpeg muxing**: Already implemented in `FFmpegMerger.kt` - combines video+audio streams into MP4.
- ✅ **Backend-driven selection**: Server decides `requiresMerging` based on stream availability.
- ✅ **Progress tracking**: Multi-part downloads show scaled progress (video 40%, audio 40%, merge 20%).
- ✅ **Quality selection for playlists**: Already existed in `PlaylistDetailFragment` via `PlaylistQuality` enum.

**Files Added/Modified**:
- `DownloadQualityDialog.kt`: New dialog for selecting download quality.
- `PlayerViewModel.kt`: Updated `downloadCurrent()` to accept quality parameters.
- `PlayerFragment.kt`: Updated download button to show quality picker instead of immediate download.
- `strings.xml`: Added `download_quality_title` string resource.

**Note**: FFmpeg muxing, download storage, and cleanup infrastructure were already fully implemented.
Only the UI for quality selection in the player was missing.

**Acceptance (expected)**:
- ✅ User can select quality before downloading from player.
- ✅ Downloads include audio (muxed or progressive).
- ✅ All unit tests pass.

## PR10: Favorites (Local Like Replacement) + Favorites Screen — *Status: Done*

**Goal**: Replace YouTube-dependent "Like" with local favorites functionality.

**Implementation (completed 2025-12-18)**:
- ✅ **Room database**: Added `AppDatabase`, `FavoriteVideo` entity, `FavoriteVideoDao` with reactive Flow queries.
- ✅ **Repository pattern**: `FavoritesRepository` provides clean interface over DAO operations.
- ✅ **DI integration**: `DatabaseModule` provides database and DAO as Hilt singletons.
- ✅ **Player integration**:
  - Changed "Like" button to "Favorite" button in player UI.
  - `PlayerViewModel.toggleFavorite()` adds/removes from local database.
  - `isFavorite` state observed reactively via Flow.
  - Heart icon changes between filled (favorited) and outline (not favorited).
  - Toast feedback on toggle.
- ✅ **Favorites screen**: New `FavoritesFragment` with RecyclerView showing all favorites.
  - Accessible via Downloads & Library → Favorites menu item.
  - Remove individual favorites via X button.
  - Clear all favorites via toolbar menu (with confirmation dialog).
  - Tap to navigate to player.

**Files Added**:
- `data/local/FavoriteVideo.kt`: Room entity
- `data/local/FavoriteVideoDao.kt`: DAO with Flow queries
- `data/local/AppDatabase.kt`: Room database
- `data/local/FavoritesRepository.kt`: Repository
- `di/DatabaseModule.kt`: Hilt DI module
- `ui/favorites/FavoritesFragment.kt`: Favorites list screen
- `ui/favorites/FavoritesViewModel.kt`: ViewModel for favorites
- `ui/favorites/FavoritesAdapter.kt`: RecyclerView adapter
- `res/layout/fragment_favorites.xml`: Layout
- `res/layout/item_favorite_video.xml`: List item layout
- `res/menu/favorites_menu.xml`: Clear all menu
- `res/drawable/ic_favorite_border.xml`: Outline heart icon
- `res/drawable/ic_delete.xml`: Delete icon
- `res/drawable/ic_close.xml`: Close/remove icon
- `res/drawable/duration_background.xml`: Duration badge background

**Acceptance (expected)**:
- ✅ Favorites persist locally across app restarts.
- ✅ Toggle works in player with visual feedback.
- ✅ Favorites list shows all saved videos.
- ✅ Navigation from favorites list to player works.
- ✅ Compiles successfully.

## PR11: Share (Absolutely NO YouTube URL) — *Status: Done*

**Goal**: Share only app deep-links; never expose `youtube.com`/`youtu.be` URLs.

**Implementation (completed 2025-12-18)**:
- ✅ **Deep link for videos**: Added `albunyaantube://video/{videoId}` deep link to `playerFragment` in navigation graph.
- ✅ **Share message update**: `shareCurrentVideo()` now uses app deep link instead of YouTube URL.
- ✅ **Localized strings**: Added `share_watch_in_app`, `share_app_promo`, `share_video_chooser` strings.

**Before**:
```
Video Title
Watch this video:
https://www.youtube.com/watch?v=VIDEO_ID
Get Albunyaan Tube app for ad-free Islamic content!
```

**After**:
```
Video Title
Watch in Albunyaan Tube:
albunyaantube://video/VIDEO_ID
Get Albunyaan Tube for ad-free Islamic content!
```

**Files Modified**:
- `res/navigation/main_tabs_nav.xml`: Added video deep link
- `ui/player/PlayerFragment.kt`: Updated `shareCurrentVideo()` to use deep link
- `res/values/strings.xml`: Added share-related strings

**Acceptance (expected)**:
- ✅ Shared content uses `albunyaantube://video/` scheme.
- ✅ No YouTube URLs in shared text.
- ✅ App can receive and handle video deep links.
- ✅ Compiles successfully.

## PR12: Documentation Sync (PRD + Agent Docs) — *Status: Done*

**Goal**: Update this roadmap to reflect completed work.

**Implementation (completed 2025-12-18)**:
- ✅ Updated this file with PR10, PR11, PR12 completion details.
- ✅ Updated "Last Updated" header to reflect all PRs complete.

**Note**: PRD and AGENTS.md updates are out of scope for this roadmap—those documents track higher-level product requirements, not player implementation details.

---

# Implementation Notes

## Caching Behavior
- **100MB LRU cache** shared across progressive and adaptive streams.
- **Scope**: Within-session benefit only. YouTube segment URLs are signed/ephemeral (typically ~6hr TTL), so caching helps seek-back/replay more than "later that day" reuse.
- **Live HLS**: Not currently supported; playlist caching would be problematic for live streams.

## TrackSelector Constraints
- Constraints are cleared when switching to progressive playback to ensure clean state.
- This prevents quality caps from a previous adaptive video accidentally influencing later playback.

## Source Identity Tracking (Rebuild Prevention)
- `MediaSourceResult` contains `actualSourceUrl` which tracks the real URL used (HLS manifest, DASH manifest, or progressive video URL).
- `MediaSourceResult.AdaptiveType` explicitly identifies HLS vs DASH vs NONE (progressive).
- Source identity comparison uses the actual URL returned by the factory, not what we "expect" based on manifest presence.
- This prevents unnecessary MediaSource rebuilds when the user only changes the quality cap (which should update track selector, not rebuild).

## Sticky Adaptive Fallback (`adaptiveFailedForCurrentStream`)
- If adaptive MediaSource creation fails (despite manifests being present), the failure is recorded per-stream.
- Subsequent state emissions for the same stream will skip adaptive attempts and go directly to progressive.
- This prevents rebuild loops when manifests exist but adaptive creation consistently fails.
- The flag is reset on:
  - New stream (different `streamId`)
  - Manual refresh button tap
  - Manual recovery retry button tap
- This ensures user actions can always retry adaptive, while automatic state updates don't cause repeated failures.

---

# Key Risk Controls (Non-negotiable for "rock solid")

1. Proactive progressive downshift must be bounded (cooldowns + max attempts) to avoid reload loops.
2. Recovery + proactive + error retry must be coordinated (single owner at a time).
3. Refresh/resolution must be rate-limit safe by design (dedupe + backoff + breaker).
4. Every UI PR must include explicit phone + `sw600dp` + `sw720dp` (when present) + RTL validation steps in its checklist.
