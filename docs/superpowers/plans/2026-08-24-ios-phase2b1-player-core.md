# Player Core Implementation Plan (iOS Phase 2, Plan B1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** A working VOD player: open a catalog video, resolve it through InnerTubeKit, play the HLS stream in `AVPlayerViewController` with quality/audio/caption controls, recover on failure, and show metadata + toolbar actions — the core the later player sub-plans (B2 background/PiP, B3 embed+SafeMode, B4 Shorts, B5 fullscreen/gestures/UpNext) build on.

**Architecture:** `PlayerViewModel` (`@Observable @MainActor`) owns a `StreamState` machine and drives an `AVPlayer` hosted in a `UIViewControllerRepresentable(AVPlayerViewController)`. Resolution comes from the shipped `InnerTubeKit` package (`StreamResolver`) via an app-provided `AvailabilityGate`; the FitrahTube toolbar and metadata are SwiftUI outside the VC. Quality is an AVFoundation *ceiling* (`preferredMaximumResolution`/`preferredPeakBitRate`), not a track pick. Recovery is a KVO/stall-driven re-resolve state machine with fixed budgets.

**Tech Stack:** Swift 6, SwiftUI, AVFoundation/AVKit, `@Observable`, Swift Testing; the app target (`ios/FitrahTube`) + `ios/Packages/{InnerTubeKit,FitrahAPI}`. XcodeGen. UI acceptance via the existing `ios/scripts/screenshots.sh` XCUITest rig (Phase-1 style).

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` §10. Detail: `docs/architecture/ios-app-plan.md` §6.5–6.6. Behavioural values: `docs/superpowers/plans/2026-08-23-ios-phase2-research/player.md` §2–§4, §13–§14. Rulings: `docs/superpowers/plans/2026-08-23-ios-phase2-research/RULINGS.md` 32–45. Carry-forwards from InnerTubeKit: `docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md` (CF-B1..6).

## Global Constraints

- **Everything `@MainActor`.** `AVPlayer`/`AVPlayerItem`/`AVPlayerViewController` are not Sendable — confine to the main actor. `StreamResolver` is the nonisolated InnerTubeKit actor; `await` into it.
- **DI shape identical to Phase 1**: `PlayerViewModel(resolver:catalog:favorites:settings:args:)` created by the view from `@Environment(\.container)` in a `.task`, held as `@State private var model: PlayerViewModel?`. No singletons. The container gains an `InnerTube` composition root and exposes `resolver`.
- **Quality = ceiling, never a track pick** (spec §10): `AVPlayerItem.preferredMaximumResolution` (CGSize) + `preferredPeakBitRate`; default cap = the player layer's pixel size; cellular ≤720p via `…ForExpensiveNetworks`; Low Data via `NWPath.isConstrained`. Rung-2 (progressive 360p) hides the quality control entirely.
- **Never silently swap a playing native stream into the embed** (spec §10, §6.6): every rung transition is a visible state change with a VoiceOver announcement.
- **`ExtractionError.cooldown(until:)` → a "try again in X" state** (CF-B1), never a generic error; read the remaining time via `InnerTube.cooldownRemaining()`.
- **No watch-progress persistence** (ruling 32): resume is session-only, across re-resolution within one player lifetime.
- **Download button is Phase 3**: render a disabled/hidden state driven by a `nil` download store; no DownloadKit reference (ruling 28).
- **No Cast/AirPlay-specific code** (Phase 3): AVPlayerViewController's stock AirPlay route button is accepted as-is (ruling 29), but no `GCK*`/session logic.
- Strings come from the Phase-1 `Localizable.xcstrings` (the converter already ported the player keys). New iOS-only keys go through `ios/scripts/convert-strings.py` `EXTRA_KEYS`.
- Pure logic (state machine, quality math, recovery budgets, default-selection) is `swift test`-able in the app test target with a fake resolver; AVPlayer glue + UI need the simulator screenshot rig. Split tasks accordingly.

---

### Task 1: `AvailabilityGate` impl + `InnerTube` in the container

**Files:**
- Create: `ios/FitrahTube/Catalog/BackendAvailabilityGate.swift`
- Modify: `ios/FitrahTube/App/AppContainer.swift` (add `innerTube`/`resolver`), `ios/FitrahTube/App/FitrahTubeApp.swift` (wire on launch), `ios/project.yml` (add InnerTubeKit dependency to the app target)
- Test: `ios/FitrahTubeTests/BackendAvailabilityGateTests.swift`

**Interfaces:**
- Consumes: InnerTubeKit `AvailabilityGate` protocol, `InnerTube` composition root; FitrahAPI HEAD `api/v1/videos/{id}` (and channels/playlists for `sourceChannelId`).
- Produces: `struct BackendAvailabilityGate: AvailabilityGate` (verify → 2xx/404 true, 410 false, per §5.2 / CF-B3); `AppContainer.innerTube: InnerTube` (lazy), `AppContainer.resolver: StreamResolver`.

- [ ] **Step 1: Failing test** — a `BackendAvailabilityGate` over a stub transport returns true on 2xx and 404, false on 410, true (fail-open) on a thrown transport error; uses `sourceChannelId` → channels endpoint when present, else videos.
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement.** Add InnerTubeKit to `project.yml` app-target dependencies (regenerate). Container builds `InnerTube(keyValueStore: UserDefaults-backed, availabilityGate: BackendAvailabilityGate(api:), locale: InnerTubeLocale(hl:gl:) from the app locale, remoteConfigURL: the raw.githubusercontent config URL)`; `FitrahTubeApp` calls `container.innerTube.remoteConfig.refresh()` on launch and `willEnterForeground` (≥15 min spacing) per CF-B4.
- [ ] **Step 4: Run, green.** Full `ios/scripts/test.sh` stays green (container still builds; no UI change).
- [ ] **Step 5: Commit** `[FEAT]: iOS availability gate and InnerTube container wiring`.

---

### Task 2: `PlayerViewModel` state machine (pure)

**Files:**
- Create: `ios/FitrahTube/Features/Player/PlayerViewModel.swift`, `ios/FitrahTube/Features/Player/PlayerState.swift`
- Test: `ios/FitrahTubeTests/PlayerViewModelTests.swift`

**Interfaces:**
- Consumes: `StreamResolver` (via a `protocol StreamResolving` the VM depends on, with a fake in tests — do NOT depend on the concrete actor for testability; `LiveStreamResolver: StreamResolving` wraps InnerTube's resolver), `PlayerArgs` (Phase-1 `Route.PlayerArgs`, 12 fields), `SettingsStore`, `FavoritesStore`.
- Produces: `@Observable @MainActor final class PlayerViewModel`; `enum StreamState: Equatable { case idle, loading, ready(Resolved), rung2Progressive(Resolved), error(messageKey: String), contentUnavailable, cooldown(until: Date), recoveryExhausted(Resolved) }`; `func open() async`, `func retry() async`.

Behaviour (player.md §2.2, mapped to InnerTubeKit): `open()` → `.loading`, cancel prior job, `resolver.resolve(args.videoId, purpose: .player, sourceChannelId: args.sourceChannelId?, forceRefresh: false)`; map the `Resolved`/`ExtractionError`: `.hls` → `.ready`; `.progressive` → `.rung2Progressive`; `.embed` is B3 (for B1, treat as `.error` with a "not yet available" key — a `// ponytail:` note that B3 replaces this; *Owner directive 2026-08-27*: there is no `.openInYouTube` case to map — the ladder ends at `.embed`); `ExtractionError.unavailable/liveOffline` → `.contentUnavailable`; `.cooldown(until:)` → `.cooldown(until:)`; other → `.error`. `retry()` re-resolves with `forceRefresh: true`.

- [ ] **Step 1: Failing tests** with a `FakeResolver`: each resolver outcome maps to the right state; `.cooldown` never retries into itself; `retry()` passes `forceRefresh: true`; a superseded `open()` (called twice) keeps only the latest result (generation guard, the Phase-1 discipline).
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement** the VM + state enum + `StreamResolving` protocol + `LiveStreamResolver`.
- [ ] **Step 4: Run, green** (`ios/scripts/test.sh`).
- [ ] **Step 5: Commit** `[FEAT]: iOS PlayerViewModel state machine`.

---

### Task 3: `AVPlayerViewController` host + rung-1 playback

**Files:**
- Create: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (UIViewControllerRepresentable), `ios/FitrahTube/Features/Player/PlayerScreen.swift` (the SwiftUI screen)
- Modify: `ios/FitrahTube/Features/Shell/MainShellView.swift` (route `.player(args)` → `PlayerScreen`)
- Test: `ios/FitrahTubeTests/PlayerHostTests.swift` + a screenshot under `screenshots/b1-task3/`

**Interfaces:**
- Consumes: `PlayerViewModel`, `Resolved`.
- Produces: `PlayerHostView` wrapping `AVPlayerViewController` (`showsPlaybackControls = true`, `allowsPictureInPicturePlayback = false` for B1 — B2 enables), binding an `AVPlayer` created from `resolved.stream`'s `.hls` URL with the mandatory `userAgent` set on the asset's `AVURLAsset(url:options:["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": resolved.userAgent]])`; `PlayerScreen` shows the host when `.ready`, the state UI otherwise (Task 9).

- [ ] **Step 1: Write the host + a test** that `.ready(resolved)` builds an `AVPlayer` whose current item's asset URL == the resolved HLS URL and carries the resolved User-Agent header (inspect `AVURLAsset` options). A UI test launches the player route against a fake container resolving to a bundled local HLS sample (so no network) and screenshots the playing frame.
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement.** Route wiring in the shell; the representable creates/updates the `AVPlayer` on state change; tears it down on disappear. Session-only resume: keep `currentTime` across `replaceCurrentItem`.
- [ ] **Step 4: Run** `ios/scripts/test.sh` + `ios/scripts/screenshots.sh` for the new case; verify the frame renders.
- [ ] **Step 5: Commit** `[FEAT]: iOS AVPlayer host and rung-1 playback`.

---

### Task 4: Quality ceiling control

**Files:** Create `ios/FitrahTube/Features/Player/QualityCeiling.swift`; Modify `PlayerViewModel.swift`, `PlayerScreen.swift`; Test `ios/FitrahTubeTests/QualityCeilingTests.swift`.

**Interfaces:** Produces `enum QualityOption { case auto, p1080, p720, p480, dataSaver }` with `func apply(to item: AVPlayerItem, layerSize: CGSize, network: NWPath)` → sets `preferredMaximumResolution`/`preferredPeakBitRate` (+ the `…ForExpensiveNetworks` cellular variants and Low Data honouring per player.md §3 ceilings: LTE/5G ≤720p/2.5 Mbps, 3G/metered ≤480p/1.2 Mbps, WiFi none). Default cap = layer pixel size. Rung-2 hides the control.

- [ ] **Step 1: Failing tests** (pure): each option maps to the right resolution/bitrate pair; the cellular ceiling clamps a 1080p pick to 720p on expensive networks; Low Data forces data-saver; default AUTO uses the layer size. (Assert the values on a real `AVPlayerItem` — it's constructible without playback.)
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement** + a menu in `PlayerScreen` (labels "720p (1280×720)" from `asset.variants` when available, else the option name; sorted high→low; title from `player_quality_dialog_title`). Hidden on `.rung2Progressive`.
- [ ] **Step 4: Run** tests + a screenshot of the quality menu.
- [ ] **Step 5: Commit** `[FEAT]: iOS player quality ceiling`.

---

### Task 5: Audio-language menu (sticky per session)

**Files:** Modify `PlayerViewModel.swift`, `PlayerScreen.swift`; Create `ios/FitrahTube/Features/Player/AudioLanguageMenu.swift`; Test `ios/FitrahTubeTests/AudioLanguageTests.swift`.

**Interfaces:** Reads `asset.mediaSelectionGroup(forMediaCharacteristic: .audible)`; lists options labelling the first "Original: X" (`shorts_audio_track_original_prefix`); selecting sets the item's audible selection; the choice is sticky for the session (a VM `stickyAudioLanguage: String?` re-applied on every prepare/re-resolve, player.md §2.2/§8.6). Hidden when ≤1 audible option. (Dub enumeration is Phase 3 — CF: only languages already in the resolved stream.)

- [ ] **Step 1–2: TDD** the sticky logic (pure): once set, a subsequent prepare re-selects the same language tag if present; falls back to original if absent.
- [ ] **Step 3: Implement** the menu (only shown when the group has ≥2 options).
- [ ] **Step 4: Run** tests + screenshot (use a bundled multi-audio HLS sample, or assert menu presence logic if no such sample — note which).
- [ ] **Step 5: Commit** `[FEAT]: iOS player audio-language menu`.

---

### Task 6: Captions (stock menu + auto-generated overlay)

**Files:** Create `ios/FitrahTube/Features/Player/CaptionsProvider.swift`, `ios/FitrahTube/Features/Player/CaptionOverlay.swift`; Modify `PlayerScreen.swift`; Test `ios/FitrahTubeTests/CaptionsProviderTests.swift`.

**Interfaces:** Manual subtitle tracks appear in AVPlayerViewController's stock menu (HLS `SUBTITLES` renditions — nothing to build). Auto-generated tracks (`isAutoGenerated`, from `Resolved.hls`'s `captionTracks`) need `CaptionsProvider`: fetch `captionTrack.url` (already `&fmt=vtt`), parse WebVTT cues, drive a `CaptionOverlay` off `addPeriodicTimeObserver`; label "(Auto-generated)"; auto-enable when `UIAccessibility.isClosedCaptioningEnabled`.

- [ ] **Step 1–2: TDD** a minimal WebVTT parser (cue start/end/text) against a small fixture; the overlay shows the cue whose interval contains the current time, nothing otherwise.
- [ ] **Step 3: Implement** provider + overlay + the toggle in `PlayerScreen`.
- [ ] **Step 4: Run** tests + a screenshot with a caption visible.
- [ ] **Step 5: Commit** `[FEAT]: iOS player captions (auto-generated overlay)`.

---

### Task 7: Recovery state machine + rung-2 pill

**Files:** Create `ios/FitrahTube/Features/Player/PlaybackRecovery.swift`; Modify `PlayerViewModel.swift`, `PlayerHostView.swift`, `PlayerScreen.swift`; Test `ios/FitrahTubeTests/PlaybackRecoveryTests.swift`.

**Interfaces:** Observe `AVPlayerItem.status == .failed` (before first frame → next rung), `AVPlayerItemFailedToPlayToEndTime`, a stall watchdog (VOD 6 s / live 45 s, armed after first READY, fires only if buffered position hasn't advanced — player.md §3.2). On a recoverable error: re-resolve the SAME rung once (`resolver.resolve(forceRefresh: true)`), `replaceCurrentItem` + seek to saved position; budgets **retries 3 / re-resolves 2** (spec §10); then step down a rung. `.rung2Progressive` shows the persistent pill **"Standard quality (360p)"** (`player_standard_quality`/existing key) and hides the quality control; carries `currentTime` over when demoted mid-play. Exhausted → `.recoveryExhausted` (manual-retry escape hatch). Never swap a playing native stream into embed silently.

- [ ] **Step 1–2: TDD** the budget machine (pure, with a fake resolver + injected clock): a failed prepare before first frame advances the rung; a 403-class error re-resolves the same rung once then steps down; 3 retries / 2 re-resolves exhaust to `.recoveryExhausted`; the stall watchdog fires only when position hasn't advanced.
- [ ] **Step 3: Implement** the observers in the host, feeding events to the VM's recovery machine; the rung-2 pill UI.
- [ ] **Step 4: Run** tests + screenshots (rung-2 pill; error/exhausted state).
- [ ] **Step 5: Commit** `[FEAT]: iOS player recovery and rung-2 pill`.

---

### Task 8: Metadata panel + toolbar actions

**Files:** Create `ios/FitrahTube/Features/Player/PlayerMetadataView.swift`, `ios/FitrahTube/Features/Player/PlayerToolbar.swift`; Modify `PlayerScreen.swift`; Test `ios/FitrahTubeTests/PlayerToolbarTests.swift`.

**Interfaces:** Metadata panel below the player: title (`lineLimit(2)`), channel line (real channel title, fall back to nil — NOT category, ruling 39), view count via Phase-1 `Format` (ruling 37), description expand/collapse with tappable http/https links only (`PlayerDescriptions.kt` allow-list, Phase-1 has the pattern). Toolbar: Favorite (SwiftData `FavoritesStore`, optimistic toast pre-state, revert on failure — Phase-1 pattern), Share (`ShareLink` → `https://app.fitrahtube.com/api/watch/{id}`, no "ad-free"; OG publish is Phase 4 — CF), Report (opens the report flow — VIDEO with parent PLAYLIST/CHANNEL + subtype; the report UI itself is Plan C, so B1 wires the button to a placeholder + `// ponytail:` note), Download button = disabled state (Phase 3).

- [ ] **Step 1–2: TDD** favorite toggle (optimistic + revert on store failure), the description link allow-list (http/https kept, others stripped, text preserved), the channel-name-not-category rule.
- [ ] **Step 3: Implement** panel + toolbar.
- [ ] **Step 4: Run** tests + screenshots (metadata + toolbar, en/ar).
- [ ] **Step 5: Commit** `[FEAT]: iOS player metadata and toolbar`.

---

### Task 9: Player state UI (per-rung) + VoiceOver

**Files:** Create `ios/FitrahTube/Features/Player/PlayerStateView.swift`; Modify `PlayerScreen.swift`; Test `ios/FitrahTubeTests/PlayerStateViewTests.swift` + screenshots.

**Interfaces:** Render each `StreamState` per spec §6.6: `.loading` = thumbnail + spinner + "Loading…" (offline gated by `NetworkMonitor` so offline is one state); `.rung2Progressive` = the pill (Task 7); `.error` = message + Retry; `.contentUnavailable` = "This video isn't available" (no retry); `.cooldown(until:)` = "Try again in {relative}" with the countdown from `InnerTube.cooldownRemaining()` (CF-B1); `.recoveryExhausted` = manual Retry. Each rung transition posts an `AccessibilityNotification.Announcement` ("Playing in standard quality", etc.); cross-dissolve, static under Reduce Motion.

- [ ] **Step 1–2: TDD** the state→copy/announcement mapping (pure); the cooldown countdown text from a fixed `until`.
- [ ] **Step 3: Implement** the state view.
- [ ] **Step 4: Run** tests + screenshots of every state (loading/rung-2/error/unavailable/cooldown), en/ar, light/dark.
- [ ] **Step 5: Commit** `[FEAT]: iOS player state UI and announcements`.

---

### Task 10: iPad / RTL / accessibility / Dynamic Type pass

**Files:** touch only what the checks require; screenshots under `screenshots/b1-task10/`.

- [ ] Run the player on iPhone 17 + iPad Pro (portrait + landscape), en + ar (RTL — transport mirrors, `play.fill` doesn't; leading-aligned metadata), Dynamic Type `.accessibility3` (no clipped metadata/toolbar), VoiceOver (every control labelled + valued: "Quality, Auto"; rung announcements fire). Player 16:9 top-anchored up to a max width on iPad (spec §11, the one place `content_max_width` applies); compact-height landscape hides metadata. Fix per spec §6.11. Use the XCUITest rig (`ios/scripts/screenshots.sh`).
- [ ] Commit `[FIX]: iOS player iPad, RTL and accessibility`.

---

## Out of scope for B1 (later sub-plans)
Background audio / Now Playing / PiP / audio-only swap (B2); the embed WKWebView rung + Safe Mode (B3); Shorts (B4); fullscreen, gesture overlay, Up Next / playlist queue / auto-advance / auto-skip (B5); the real Report flow UI (Plan C); Chromecast / AirPlay session logic / downloads / dub audio (Phase 3). The `.embed` resolver outcome renders a placeholder `.error` in B1 with a `// ponytail:` note — B3 replaces it. *(Owner directive 2026-08-27: `.openInYouTube` is not a resolver outcome — it does not exist. The ladder ends at `.embed`; anything past it is a terminal "not available" state, never a YouTube hand-off.)*
