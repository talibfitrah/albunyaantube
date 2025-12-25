# PR6.2: Extractor Adaptive-Manifest Backfill (Optional / Upstream) - Execution Plan

1) Baseline Measurement Plan (MANDATORY FIRST)
- How to collect 50-100 playback samples using existing AdaptiveAvail logs:
  - Use a DEBUG build (AdaptiveAvail logging is debug-only).
  - Optional: clear logcat buffer: `adb logcat -c`.
  - Start capture: `adb logcat -v raw -s AdaptiveAvail > pr6_2_adaptive_avail.log`.
  - Play 50-100 unique videoIds (avoid cache hits; NewPipeExtractorClient caches for ~30 minutes).
  - Include a mix of regular VOD, live streams, and short-form content.
  - Stop capture (Ctrl+C).
- Success metrics to compute:
  - % hasHls=true
  - % hasDash=true
  - % none (hasHls=false AND hasDash=false)
  - Breakdown by streamType (e.g., VIDEO_STREAM, LIVE_STREAM, AUDIO_LIVE_STREAM).
  - Duration buckets using durSec: unknown(-1), <60s, 60-299s, 300-899s, 900-1799s, >=1800s.
  - Correlation to entry-point if possible (prefetch vs direct play):
    - Capture `StreamPrefetch` and `PlayerViewModel` tags alongside AdaptiveAvail:
      `adb logcat -v time -s AdaptiveAvail StreamPrefetch PlayerViewModel > pr6_2_mixed.log`.
    - Classify entries by nearest timestamp to a prefetch log for the same videoId.
- How to store results and compare before/after:
  - Save baseline summary to `docs/plans/PR6_2_BASELINE.md`.
  - Include build info (device, locale, network, NPE version) and summary tables.
  - After any change (NPE bump or backfill), repeat the same procedure and compare deltas in
    hasDash/hasHls/none rates, especially for non-live VOD.
- Current status (2025-12-25):
  - Baseline captured in `docs/plans/PR6_2_BASELINE.md`.
  - iOS fetch experiment results recorded in `docs/plans/PR6_2_IOS_FINDINGS.md` with capture dirs.

2) Root-Cause Investigation Plan (MANDATORY, BEST-EFFORT)
Goal: pinpoint where NPE loses adaptive manifests and why it defaults to progressive.
- Current findings (2025-12-25):
  - iOS client responses include `streamingData.hlsManifestUrl` for VODs when iOS fetch is enabled.
  - WEB/ANDROID responses for the same VODs lack `dashManifestUrl`/`hlsManifestUrl` (manifest absent).
  - DASH remains absent in iOS responses; NPE `getDashMpdUrl()` does not use iOS streamingData.
  - Evidence: `docs/plans/PR6_2_IOS_FINDINGS.md`, `docs/plans/npe_capture_round4.tar`,
    `docs/plans/npe_capture_round6.tar`.

2.1) Classify failures (taxonomy)
- Manifest absent in response:
  - Signal: streamingData lacks dashManifestUrl/hlsManifestUrl in raw JSON.
  - Logs: AdaptiveAvail shows hasHls=false, hasDash=false.
  - Track counts still non-zero (adaptiveFormats and/or formats present).
  - Observed: WEB/ANDROID player responses for tested VODs lacked manifests; iOS responses
    included `hlsManifestUrl` for the same IDs.
- Manifest present but discarded/filtered:
  - Signal: JSON contains dashManifestUrl/hlsManifestUrl but StreamInfo returns empty string.
  - Compare raw JSON vs NPE output for same videoId.
- Manifest URL unusable (expired/blocked):
  - Signal: hasHls/hasDash true but Media3 HLS/DASH creation fails (403/410/5xx).
  - Logs: MultiQualityMediaSourceFactory warning about failed HLS/DASH source.
- Signature/cipher step failure:
  - Signal: streamingData entries have signatureCipher/cipher with missing URL,
    resulting in missing or blank stream URLs.
  - Logs: ParsingException/ExtractionException or reduced track counts.
- Client-profile mismatch:
  - Signal: manifests appear for one client in curl replay but not for the NPE request.
  - Correlate with client used (android reel, web embedded, iOS) per request capture.

2.2) NPE internal failure point mapping
- Identify extractor paths:
  - App entry: `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt`
    -> `StreamInfo.getInfo(extractor)` -> `StreamInfo.getDashMpdUrl()` / `getHlsUrl()`.
  - NPE YouTube extractor (v0.24.8):
    - `YoutubeStreamExtractor.onFetchPage()` decides which clients are queried.
    - `getDashMpdUrl()` reads `streamingData.dashManifestUrl` from Android/HTML5 data.
    - `getHlsUrl()` reads `streamingData.hlsManifestUrl` from iOS first, then Android/HTML5.
    - `setFetchIosClient(boolean)` controls whether iOS streamingData is fetched.
    - Note: iOS streamingData is not used for DASH by design; only HLS.
- Trace plan:
  - Confirm where dashMpdUrl/hlsUrl should be set and where they are lost.
  - Confirm adaptiveFormats parsing and any filtering in NPE (mimeType, DRM, isUrl/content blank).
  - Identify signature/cipher parsing path (YoutubeParsingHelper / JS player manager).
- Temporary diagnostic logging (debug-only or behind a debug flag):
  - Add request/response capture for youtubei endpoints (URL, headers, body, response code).
  - Log whether response JSON contains dashManifestUrl/hlsManifestUrl and counts of
    adaptiveFormats/formats.
  - Log NPE outputs (StreamInfo dash/hls strings) and track counts to map drop-off stage.

2.3) Direct YouTube via curl (or similar) comparison experiments (REQUIRED)
- Capture the exact request NPE makes for a failing videoId:
  - Log URL, method, headers, and JSON body (sanitized).
  - Persist raw response JSON for inspection.
- Replay with curl and inspect JSON:
  - Check `streamingData.dashManifestUrl` and `streamingData.hlsManifestUrl`.
  - Check `streamingData.adaptiveFormats` and `streamingData.formats` counts.
- Compare NPE request vs curl replay and vary ONE parameter at a time:
  - Endpoint: reel/reel_item_watch vs player.
  - Client context: WEB_EMBEDDED_PLAYER vs ANDROID.
  - Client context: IOS (NPE request capture or curl with iOS client context).
  - Headers: User-Agent (e.g., Safari macOS) or origin/referrer.
  - Keep total attempts bounded (max one extra request per variant, no loops).
- Decision rule:
  - If curl shows adaptive fields but NPE output lacks them -> NPE parsing/client selection bug
    -> upstream patch target.
  - If curl also lacks adaptive fields -> likely YouTube-side gating -> document and focus on
    NPE upgrade + graceful fallback.
  - If iOS client shows `hlsManifestUrl` while ANDROID/WEB do not -> enable iOS fetch
    via NPE as primary fix; document HLS-only scope.

3) Primary Approach (Preferred): NPE Version Bump / Upstream Patch (DEFAULT PATH)
- Preferred first step: enable NPE iOS fetch (public API) under a feature flag and re-measure.
  - Rationale: iOS responses include `hlsManifestUrl` for VODs; NPE already prioritizes iOS HLS.
  - Note: This adds one additional youtubei request per stream extraction.
- Priority 0 (BLOCKER): Resolve-stream latency/crash regression before any rollout
  - Observed symptoms: "Resolve stream..." errors, long load times after list scrolling,
    some VODs not playing, and app crash during playback.
  - Crash signature to capture: `ForegroundServiceDidNotStartInTimeException`
    in `PlaybackService` start (see `PlayerFragment.startPlaybackServiceForForeground`).
  - Evidence (logcat around ~17:33):
    - FATAL EXCEPTION at 17:33:42: `ForegroundServiceDidNotStartInTimeException`.
    - Prior window shows HLS 403 on load -> auto-recovery refresh for the same VOD.
    - Stream resolve failures observed ("The page needs to be reloaded", age-restricted).
    - Log slices: `docs/plans/PR6_2_CRASH_1730.txt`, `docs/plans/PR6_2_CRASH_1733_1734.txt`.
  - **IMPLEMENTED (2025-12-25): HTTP 403 Telemetry and Recovery Improvements**
    - Added `StreamRequestTelemetry` for detailed 403 failure logging:
      - Records request URL (host + path hash), headers, response code/headers/body
      - Classifies failures: URL_EXPIRED, GEO_RESTRICTED, RATE_LIMITED, UNKNOWN_403
      - Tracks stream age and estimates TTL remaining (~6hr YouTube URL TTL)
    - Added `StreamUrlRefreshManager` for preemptive URL refresh:
      - Tracks stream resolution times for TTL estimation
      - Provides `shouldRefreshBeforeOperation()` for pre-seek/quality-change checks
      - Schedules preemptive refresh when TTL < 30min threshold
    - Updated PlayerFragment error handling for 403-specific recovery:
      - GEO_RESTRICTED: No recovery - shows user-facing "not available in your region" message
      - RATE_LIMITED: Exponential backoff using retry-after header or 2^n seconds
      - URL_EXPIRED: Force refresh with immediate retry
      - UNKNOWN_403: Exponential backoff + forced refresh as last resort
    - Added `InstrumentedHttpDataSourceFactory` for future instrumented playback telemetry
    - New strings: `player_geo_restricted`, `player_stream_expired`, `player_rate_limited`
  - Repro plan:
    - Go to video list screen, scroll to at least the middle of the list.
    - Play 3-5 videos sequentially; record which attempt triggers error/crash.
    - Record time-to-first-frame for each attempt.
    - Capture logcat tags: `PlayerViewModel`, `StreamPrefetch`, `AdaptiveAvail`, `AndroidRuntime`.
  - Investigation focus:
    - Foreground service start timing (must call `startForeground()` within OS limit).
    - Excess concurrent stream resolves or prefetch backlog causing latency.
    - Any repeated extraction loops or state emissions on scroll/tap.
  - Acceptance gate:
    - No crashes across 10+ play attempts.
    - Time-to-play returns to pre-change baseline.
    - No repeated resolve loops in logs (single resolve path per tap).
- Identify current NewPipeExtractor dependency location/version:
  - Android: `android/app/build.gradle.kts` -> v0.24.8.
  - Backend: `backend/build.gradle.kts` -> pinned dev commit a0607b2c49e... (innerTube fixes).
- Research (plan only): review NPE upstream for fixes to:
  - missing dashMpdUrl/hlsUrl
  - streamingData/adaptiveFormats parsing
  - signature/cipher changes
  - client profile selection stability and iOS fetch defaults/gating
- Upgrade PR checklist:
  - Bump dependency version (prefer tagged release; align with backend if safe).
  - Update any integration touchpoints or API changes in NewPipeExtractorClient.
  - Add a feature flag (default OFF) to enable iOS fetch and log AdaptiveAvail deltas.
  - Smoke tests: compile + targeted playback of baseline NO_ADAPTIVE list.
  - Re-run AdaptiveAvail baseline and record before/after deltas.
- Acceptance gate:
  - If VOD HLS availability improves materially (target >=80% HLS for VOD) with acceptable
    request overhead and no limiter starvation, STOP and do not plan app-layer backfill.

4) Secondary Approach (ONLY IF #3 is insufficient): Opt-in bounded backfill design
- Only pursue if iOS fetch is insufficient or too costly (rate-limit or latency regression).
- Trigger only when hasHls=false AND hasDash=false.
- Attempt-once gating design:
  - Per-videoId in-flight dedupe (shared promise/deferred).
  - **Negative caching TTL: 60 seconds.**
    - Rationale: Balances user experience (can retry after a minute) against tap-spam
      storms (prevents dozens of attempts in rapid succession).
    - 60s aligns with ExtractionRateLimiter.GLOBAL_WINDOW_MS (1 minute) and is short
      enough that users don't perceive a permanent failure.
    - Too short (5-10s): rapid re-taps would still storm the extractor.
    - Too long (5-10min): users might think the feature is broken and give up.
  - Hard timeout; no retries.
- Rate-limit design:
  - Use existing `RequestKind.PREFETCH` bucket (lowest priority, blocked if budget pressure).
    - **Confirmed in codebase**: `ExtractionRateLimiter.kt:88` defines PREFETCH.
    - PREFETCH is blocked when per-video budget is near exhaustion (lines 217-226).
  - AUTO_RECOVERY guardrails are protected:
    - **Confirmed in codebase**: `ExtractionRateLimiter.kt:66-67` reserves 2 attempts.
    - **Confirmed in codebase**: `ExtractionRateLimiter.kt:332-337` bypasses global limits.
    - Backfill using PREFETCH cannot starve AUTO_RECOVERY because:
      1. AUTO_RECOVERY has reserved budget independent of PREFETCH/MANUAL.
      2. AUTO_RECOVERY bypasses global rate limit entirely.
      3. PREFETCH is preemptively blocked when budget is tight (preserves headroom).
- Feature-flag strategy:
  - Default OFF (debug-only or internal toggle).
- Observability additions:
  - New log tag `AdaptiveBackfill` with: trigger reason, limiter decision, outcome,
    time spent, manifests gained yes/no.

5) Risk Analysis + Why this won't create loops (CRITICAL)
- Code paths that resolve streams:
  - Normal play: PlayerViewModel -> PlayerRepository -> NewPipeExtractorClient.
  - Tap-to-prefetch: StreamPrefetchService -> NewPipeExtractorClient.
  - Auto-recovery refresh ladder: PlayerViewModel.forceRefreshForAutoRecovery().
  - Manual refresh: PlayerViewModel.forceRefreshCurrentStream().
  - Live proactive refresh: PlayerViewModel.performLiveStreamRefresh().
- Loop prevention:
  - Backfill triggers only once per videoId and only when both manifests are absent.
  - In-flight dedupe prevents concurrent duplicate attempts (prefetch + play).
  - Negative cache with TTL prevents repeated attempts from repeated taps.
  - Backfill never runs on forceRefresh paths (manual or auto-recovery) to avoid
    repeated extraction loops.
  - Limiter decision is terminal: no delayed retries or scheduled backfill loops.
- iOS fetch risk:
  - `setFetchIosClient(true)` adds one extra youtubei call per extraction.
  - Keep it feature-flagged; monitor PR5 limiter budgets and app latency.

6) File-level work breakdown
- `android/app/build.gradle.kts`: NPE version bump (primary path).
- `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt`:
  enable iOS fetch behind a feature flag; optional debug diagnostics.
- `android/app/src/main/java/com/albunyaan/tube/data/extractor/OkHttpDownloader.kt` or
  `android/app/src/main/java/com/albunyaan/tube/di/NetworkModule.kt`:
  debug request/response capture for youtubei endpoints.
- Feature flag source (e.g., BuildConfig or a small config wrapper) to control iOS fetch.
- `android/app/src/main/java/com/albunyaan/tube/player/ExtractionRateLimiter.kt`:
  optional BACKFILL kind or PREFETCH-based integration.
- `android/app/src/main/java/com/albunyaan/tube/player/StreamPrefetchService.kt`:
  ensure dedupe compatibility if backfill interacts with prefetch.
- `docs/plans/PR6_2_BASELINE.md`: baseline results.
- `docs/plans/PR6_2_IOS_FINDINGS.md`: iOS fetch experiment summary and evidence.
- `docs/plans/PR6_2_CURL_MATRIX.md`: curl experiment outcomes.

7) Test Plan
- Unit tests required for (if backfill is implemented):
  - Attempt-once gating.
  - Negative caching TTL behavior.
  - Limiter interactions and priority safety (AUTO_RECOVERY protected).
  - No repeated extraction loops invariant (no backfill on refresh paths).
- Manual verification (iOS fetch path):
  - With flag OFF: VODs should match baseline (mostly NONE).
  - With flag ON: play known VODs (e.g., `V2Brp_esIVI`) and confirm AdaptiveAvail logs HLS_ONLY.
- Run existing test command:
  - `cd android && timeout 300 ./gradlew test`
- Pass criteria:
  - All tests pass under 300s timeout policy; no flaky behavior.

8) Rollout Plan
- Keep changes off by default (feature flag for iOS fetch/backfill).
- Enable safely in debug/internal builds first; expand gradually if rate-limit safe.
- Monitor:
  - `adb logcat -s AdaptiveAvail` for manifest availability.
  - `adb logcat -s AdaptiveBackfill` for backfill outcomes and limiter decisions.
- Watch rate-limit metrics (PREFETCH/AUTO_RECOVERY) and request volume changes.
- Rollback:
  - Disable flag (backfill off) or revert NPE version if regression observed.

9) Manual Regression Test Checklist (POST-IMPLEMENTATION)
Execute these tests after implementing PR6.2 changes to verify stability.

**9.1 P0 Blocker Tests**
- [ ] **ForegroundService crash test**: Play 10+ videos in sequence without crashes.
  - `adb logcat -s AndroidRuntime | grep ForegroundService` should show no crashes.
  - Note: Previously crashed with `ForegroundServiceDidNotStartInTimeException`.
- [ ] **HLS 403 test (iOS fetch ON)**: With `npe.ios.fetch.enabled=true` in local.properties:
  - Play 5 VODs and confirm HLS streams work without 403 errors.
  - `adb logcat -s PlayerFragment | grep -E "http=403|HTTP.*403|Response code: 403"` should be empty.
  - Note: HTTP 403 errors appear in two formats: PlayerFragment logs (`http=$httpResponseCode`) and Media3 exceptions (`Response code: 403`).
- [ ] **Notification permission denial test** (Android 13+):
  - Deny POST_NOTIFICATIONS when prompted.
  - Play a video and background the app.
  - Confirm no crash (notification silently dropped is expected).
  - Check logs: `adb logcat -s PlaybackService | grep "Notifications are disabled"`.

**9.2 Feature Flag Tests**
- [ ] **iOS fetch OFF baseline**: With `npe.ios.fetch.enabled=false` (default):
  - Play known VODs and verify AdaptiveAvail logs show baseline behavior (mostly NONE).
  - Count NONE entries: `adb logcat -s AdaptiveAvail | grep '"adaptive":"NONE"' | wc -l`
  - Alternative (warning logs): `adb logcat -s AdaptiveAvail | grep "NO_ADAPTIVE" | wc -l`
- [ ] **iOS fetch ON improvement**: With `npe.ios.fetch.enabled=true`:
  - Play the same VODs and verify HLS availability improves.
  - Count HLS entries: `adb logcat -s AdaptiveAvail | grep '"hasHls":true' | wc -l`
  - Or by adaptive field: `adb logcat -s AdaptiveAvail | grep '"adaptive":"HLS_ONLY"' | wc -l`
  - Verify playback starts within reasonable time (no multi-second regressions).

**9.3 Rate-Limiter & Recovery Tests**
- [ ] **Rapid play test**: Play 5 videos in quick succession (< 30s between taps).
  - Confirm no rate-limit blocks for normal playback.
  - `adb logcat -s ExtractionRateLimiter | grep "Blocked"` should show no per-video blocks
    within normal usage patterns.
- [ ] **Auto-recovery priority test**: Simulate a playback failure (e.g., network toggle):
  - Confirm auto-recovery is not blocked by rate limiter.
  - `adb logcat -s ExtractionRateLimiter | grep "AUTO_RECOVERY bypasses"` should appear.
- [ ] **403 telemetry test**: Trigger a 403 error (e.g., long playback of VOD > 6 hours or simulated):
  - Confirm StreamTelemetry logs failure details: `adb logcat -s StreamTelemetry`.
  - Verify failure type classification in logs (URL_EXPIRED, GEO_RESTRICTED, etc.).
  - Confirm appropriate recovery action (forced refresh for URL_EXPIRED, toast for GEO_RESTRICTED).

**9.4 Notification & Background Playback Tests**
- [ ] **Background playback notification**: Play a video, press home button.
  - Notification should appear with play/pause controls.
  - Controls should work (pause from notification, resume from notification).
- [ ] **Notification tap navigation**: Tap notification while app is backgrounded.
  - App should return to foreground with player visible.
- [ ] **Notification dismiss (paused)**: Pause video, swipe notification away.
  - Service should stop cleanly.
  - `adb logcat -s PlaybackService | grep "ACTION_DISMISS"` should show clean handling.

**9.5 Multi-Device Smoke Tests**
- [ ] **Phone emulator**: Run tests on API 26+ phone emulator.
- [ ] **Tablet emulator** (if available): Verify layout correctness on sw600dp+.
- [ ] **RTL locale** (Arabic): Verify player UI renders correctly with RTL layout.

**Logging commands for test session:**
```bash
# Start comprehensive logging
adb logcat -c
adb logcat -v time -s AndroidRuntime PlaybackService ExtractionRateLimiter \
  AdaptiveAvail MultiQualityMediaSourceFactory PlayerViewModel > pr6_2_regression.log

# After testing, search for issues:
grep -E "FATAL|ForegroundService|403|Blocked|disabled" pr6_2_regression.log
```
