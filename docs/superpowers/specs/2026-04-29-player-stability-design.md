# Player Stability & Performance Improvements
**Date:** 2026-04-29
**Branch:** feature/ANDROID-SHORTS-01-custom-shorts-player
**Ticket prefix:** ANDROID-SHORTS-01

---

## Overview

Eight targeted improvements to the regular player and Shorts player derived from a LibreTube/NewPipeExtractor research investigation. The existing player infrastructure (Media3, multi-tier fallback chain, recovery manager, buffer monitor) is strong — these improvements sharpen it rather than replace it.

**Two phases:**
- **Phase 1 — Stability** (invisible to user): client rotation, HLS probation hardening, DASH preference tightening, synthetic DASH URI fix, dependency upgrades, telemetry matrix
- **Phase 2 — Subtitles** (user-visible): subtitle extraction + CC button in both players

---

## Dependency Upgrades

| Dependency | Current | Target | Reason |
|---|---|---|---|
| `androidx.media3` | 1.9.0 | 1.9.2 | Patch: proven by LibreTube; evaluate 1.10.0 separately |
| `NewPipeExtractor` | 0.26.0 | 0.26.1 | Fixes YouTube duration fetching — directly affects DASH MPD generation |

**LibreTube fork decision:** Do NOT adopt LibreTube's NewPipeExtractor fork. Its primary value was poToken wiring, which this design handles via client rotation instead. Confirm 0.26.1 has no regressions vs fork for client selection (15-min diff at implementation start).

---

## Phase 1: Stability

### 1. YouTube Client Rotation

**Problem:** On a 403, the player re-extracts using the same YouTube internal client. Different clients have different poToken enforcement — cycling through them resolves most 403s without any WebView dependency.

**New class:** `YoutubeClientRotator` (`@Singleton`, injected into `NewPipeExtractorClient`)

- Priority list: `[IOS, ANDROID_VR, TVHTML5_SIMPLY_EMBEDDED_PLAYER]`
- On initial extraction: uses current default (IOS when `isIosFetchEnabled`, else ANDROID)
- On 403 signal from `PlaybackRecoveryManager`: `NewPipeExtractorClient.resolveStreams()` calls `YoutubeClientRotator.nextClient(videoId)` and re-extracts until list exhausted or valid URL returned
- Per-video client state evicts after 30 minutes
- Feature flag: `PlaybackFeatureFlags.KEY_CLIENT_ROTATION` (build-time default: **on**)

**Note:** WebView-based poToken (LibreTube approach) is deferred to Phase 3 if client rotation proves insufficient. No WebView dependency in this design.

### 2. HLS Probation Hardening

**Problem:** `MultiQualityMediaSourceFactory` commits to HLS before knowing if the manifest is reachable, causing a player stall on 403 before the recovery manager fires.

**Enhancement (inside `MultiQualityMediaSourceFactory`):**
After selecting HLS and confirming it is not already poisoned, fire a HEAD request to the manifest URL via `InstrumentedHttpDataSourceFactory`. Timeout: 500 ms.
- Non-2xx response → call `hlsPoisonRegistry.poisonHls(videoId, "PROBATION_FAIL")` → fall through to SYNTH_ADAPTIVE immediately. No error shown to user.
- 2xx response → proceed with HLS as today.

**Feature flag:** `PlaybackFeatureFlags.KEY_HLS_PROBATION` (build-time default: **on**)

### 3. Aggressive Synthetic DASH Preference

**Problem:** When `hlsPoisonRegistry.isHlsPoisoned(videoId)` is true, HLS construction is still attempted as a stale fallback under certain race conditions.

**Fix (inside `MultiQualityMediaSourceFactory`):** Tighten the guard: if poisoned AND no real DASH manifest URL is available → skip HLS construction entirely, jump directly to `SYNTH_ADAPTIVE`. Poisoned = synthetic DASH immediately, no HLS attempt.

### 4. Single-Rep Synthetic DASH URI Fix

**Problem:** `SyntheticDashMediaSourceFactory.generateDashSource()` (lines 153–193) creates a `data:application/dash+xml;charset=utf-8,<url-encoded-mpd>` URI. URL encoding adds parser edge cases and bypasses the existing `syntheticdash://` registry infrastructure.

**Fix:**
1. Add `videoId: String` as a parameter to `createVideoSource()` and `createAudioSource()` (callers in `MultiQualityMediaSourceFactory` already have the videoId in scope)
2. Call `SyntheticDashMpdRegistry.register(videoId, mpdManifest)`
3. Set `MediaItem` URI to `syntheticdash://$videoId`
4. Use `SyntheticDashDataSource.Factory` for the `DashMediaSource` instead of `dataSourceFactory`
5. Unregister via `SyntheticDashMpdRegistry.unregister(videoId)` when playback ends

### 5. Playback Regression Matrix (StreamRequestTelemetry)

Add structured fields to `StreamRequestTelemetry` logged as a single JSON line at playback end:

| Field | Type | Description |
|---|---|---|
| `firstFrameMs` | Long | Time from `play()` to first rendered frame |
| `extractionMs` | Long | Time spent in NewPipe extraction |
| `rebufferCount` | Int | Number of rebuffering events |
| `http403Count` | Int | Number of 403 responses during session |
| `selectedSourceType` | String | `HLS` / `DASH` / `SYNTH_ADAPTIVE` / `SYNTH_DASH` / `PROGRESSIVE` |
| `clientUsed` | String | `IOS` / `ANDROID_VR` / `TVHTML5_EMBEDDED` / `ANDROID` |
| `recoveryStepCount` | Int | Number of recovery ladder steps taken |
| `abrSwitchCount` | Int | Number of ABR quality switches |

Output: logcat only (existing telemetry pattern). No external analytics service. Grep-able during QA.

---

## Phase 2: Subtitles + CC Button

### 1. Subtitle Extraction (`NewPipeExtractorClient`)

Replace `// TODO: Extract subtitle tracks` at line 517 with:

```kotlin
val subtitleTracks = streamInfo.subtitles.mapNotNull { sub ->
    val code = sub.languageTag?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    SubtitleTrack(
        url = sub.content,
        languageCode = code,
        languageName = sub.displayLanguageName ?: code,
        format = sub.format?.suffix,
        isAutoGenerated = sub.isAutoGenerated
    )
}
```

Auto-generated tracks are included, ranked below human-authored tracks in the picker.

### 2. Media3 Wiring (`MultiQualityMediaSourceFactory`)

When building any `MediaSource`, convert subtitle tracks to `MediaItem.SubtitleConfiguration`:
- Human-authored: `roleFlags = C.ROLE_FLAG_SUBTITLE`
- Auto-generated: `roleFlags = C.ROLE_FLAG_SUBTITLE or C.ROLE_FLAG_DESCRIBES_VIDEO`

Media3 handles rendering automatically via its built-in text renderer. No custom renderer needed.

### 3. Shared Subtitle Picker (`SubtitlePickerBottomSheet`)

New `BottomSheetDialogFragment` shared between both players.

- First item: **Off** (clears subtitle preference)
- Remaining items: track language name + "(auto)" badge for auto-generated tracks
- RTL-safe: `textAlignment="viewStart"` throughout
- On selection: `player.trackSelectionParameters = parameters.buildUpon().setPreferredTextLanguage(code).build()`
- Dismissed immediately on selection

### 4. Regular Player UI (`PlayerFragment`)

- Add CC `ImageButton` to controls overlay alongside quality and audio-language buttons
- Visibility: `VISIBLE` when `resolvedStreams.subtitleTracks.isNotEmpty()`, `GONE` otherwise
- Tapping opens `SubtitlePickerBottomSheet`
- Applied to all layout variants: `layout/`, `layout-sw600dp/`, `layout-sw720dp/`

### 5. Shorts Player UI (`ShortsPageViewHolder` / `PlayerBinder`)

- Add CC `ImageButton` to Shorts overlay alongside the audio-language globe button
- Same visibility logic
- Same `SubtitlePickerBottomSheet`
- `PlayerBinder` holds the current `SubtitlePickerBottomSheet` instance and dismisses it on page change to prevent stale pickers

---

## Feature Flags Summary

| Flag key | Default | Controls |
|---|---|---|
| `KEY_CLIENT_ROTATION` | on | YouTube client rotation on 403 |
| `KEY_HLS_PROBATION` | on | HEAD probe before committing to HLS |
| Existing: `KEY_SYNTH_ADAPTIVE` | on | Multi-rep synthetic DASH |
| Existing: `KEY_MPD_PREFETCH` | on | DASH MPD prefetch |
| Existing: `KEY_IOS_FETCH` | on | iOS client for HLS |

---

## Files Affected

### Phase 1
- `android/app/build.gradle.kts` — version bumps
- `android/app/src/main/java/com/albunyaan/tube/player/YoutubeClientRotator.kt` — **new**
- `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt` — delegate to YoutubeClientRotator on 403
- `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt` — HLS probation + aggressive DASH preference
- `android/app/src/main/java/com/albunyaan/tube/player/SyntheticDashMediaSourceFactory.kt` — syntheticdash:// URI fix
- `android/app/src/main/java/com/albunyaan/tube/player/PlaybackFeatureFlags.kt` — new flags
- `android/app/src/main/java/com/albunyaan/tube/player/StreamRequestTelemetry.kt` — new matrix fields

### Phase 2
- `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt` — subtitle extraction
- `android/app/src/main/java/com/albunyaan/tube/player/MultiQualityMediaSourceFactory.kt` — SubtitleConfiguration wiring
- `android/app/src/main/java/com/albunyaan/tube/ui/shared/SubtitlePickerBottomSheet.kt` — **new**
- `android/app/src/main/java/com/albunyaan/tube/ui/player/PlayerFragment.kt` — CC button
- `android/app/src/main/res/layout/fragment_player.xml` (+ sw600dp, sw720dp variants) — CC button
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPageViewHolder.kt` — CC button
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/PlayerBinder.kt` — picker lifecycle

---

## What This Design Does NOT Include

- WebView-based poToken (deferred; client rotation addresses the same 403 class)
- LibreTube fork adoption (0.26.1 official release is sufficient)
- External analytics / Firebase telemetry (logcat only)
- Subtitle download/offline caching
- Custom subtitle styling UI
