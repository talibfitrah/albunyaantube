# Fullscreen, Gestures and Up Next Implementation Plan (iOS Phase 2, Plan B5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** After B1–B4 the player plays one video, in one layout, forever. B5 is the last Plan-B sub-plan and it closes three gaps: the player has no fullscreen (iPhone landscape today puts the toolbar below the fold and floats the tab bar over the video — CF-B2-10), no gestures (double-tap ±10 s, centre fit/zoom), and no queue (`PlayerArgs.playlistId` is carried and ignored, so a video opened from a playlist ends and stops). When B5 lands, a video opened from a playlist plays the playlist: Up Next below the metadata, auto-advance on end unless Safe Mode, ≤3 auto-skips over unplayable items, the next items' manifests warmed on the `.prefetch` lane — and rotating an iPhone to landscape gives full-bleed video with no tab bar and no chrome below the fold.

**Architecture:** Three seams already exist and B5 is the plan that writes them, which is why it is small. (1) `Router.isFullscreen` is declared, read by `MainShellView` for both the tab bar and the iPad rail, and written by nothing — its doc comment literally says "Set by the player screen on entering/exiting fullscreen (phase 2)" (`Router.swift:33-34`, `MainShellView.swift:58,62`). (2) `PlayerViewModel.safeMode` is declared with the comment "this is also B5's auto-advance hook -- Up Next reads THIS" (CF-B3-2). (3) `RequestKind.prefetch` and `Purpose.prefetch` exist in InnerTubeKit, unused, reserved for this plan (CF-B2-2). The queue itself is a pure `struct PlayerQueue` over `[ContentItem]` — `ContentItem` because that is what `VideoRow`/`VideoGridCell` already render and what `PlayerArgs(item:)` already converts, so the Up Next list is zero new UI and the advance is one initializer. Prefetch is six lines because `ManifestCache` already caches non-forced resolves (`StreamResolver.swift:73-74`): warming the cache *is* the prefetch, and no second cache, TTL or eviction policy is written. Fullscreen is a **layout** change inside the existing `ScrollView`, never a second `PlayerHostView` — the host stays at one structural position in one view tree so SwiftUI keeps one identity and the live `AVPlayer` survives entering and leaving fullscreen.

**Tech Stack:** Swift 6, SwiftUI, AVKit, UIKit (one `UITapGestureRecognizer`), `@Observable`, Swift Testing; app target `ios/FitrahTube` only (`ios/Packages/InnerTubeKit` is read-only in this plan — B5 changes no package source). XcodeGen. Gate: `ios/scripts/test.sh` (300 s wall). Acceptance screenshots via `ios/scripts/screenshots.sh`.

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` §10 — the four bullets this plan implements, verbatim: "Controls auto-hide 5 s; prev/next; **double-tap left/right thirds ±10 s, centre double-tap fit/zoom in fullscreen** (`PlayerGestureDetector.kt:10-79`) — implemented as an overlay on the content view"; "**Up Next**: the playlist the video was opened from (grid of 2 on wide layouts); auto-advance on end unless Safe Mode; playlist paging prefetch when the queue ≤5; auto-skip unplayable max 3 consecutive (`PlayerViewModel.kt:1866-1925`)"; "**Fullscreen**: 9:16 sources fullscreen in portrait, else landscape; auto-enter when opened in landscape on iPhone; zoom hint once (`PlayerFragment.kt:3382-3484`). Compact-height landscape hides metadata"; "**Safe Mode** (default on): … playlist auto-advance off". Also §6 (`PlayerArgs` carries Android's 12 arguments — "videoId, playlistId, startIndex, shuffled, targetVideoId, title, channelName, thumbnailUrl, description, durationSeconds, viewCount, channelId"; "tab bar hidden during fullscreen playback (`MainShellFragment.kt:141-170`)"), §14 (RTL, ≥44 pt, Dynamic Type, "single column at `.accessibility1+`", iPad "player 16:9 top-anchored up to a max width"). Detail: `docs/architecture/ios-app-plan.md` §6.5 ("Android's double-tap seek/zoom gesture layer is dropped in v1 (AVPlayerViewController has its own)" — see reconciliation note 1; "Up Next = the playlist the video was opened from; Android has no recommendation source either"), §6.9/§6.10 (Safe Mode "disables playlist auto-advance (the PRD's 'no autoplay to next video' persona promise)"), §6.11 (iPad, RTL, accessibility). Behavioural source: `docs/superpowers/plans/2026-08-23-ios-phase2-research/player.md` §2.3 (playlist queue), §2.4 (prefetch), §7 (fullscreen + landscape), §8.1 (gestures), §12 (queue / Up Next / autoplay), §19 items 6, 7, 8, 10, §23 (system back while fullscreen — "iOS ruling required", answered by ruling 45); `.../playlist-detail-shorts.md` §3.4 (Play All / Shuffle, the caller side of this plan's contract). Predecessors: `.../2026-08-24-ios-phase2b1-player-core.md`, `.../2026-08-27-ios-phase2b2-background-audio.md`, `.../2026-08-27-ios-phase2b3-embed-safemode.md`, `.../2026-08-27-ios-phase2b4-shorts.md`.

**Dependency on B4 (state it before starting).** B5 reuses two things B4 creates: `PlayerPresentation` (the four-property AVKit knob table on `PlayerHostView`, including `PlayToEndAction`) and `OrientationLock` + the `AppDelegate` seam (B4 fork C, CF-B4-2). **Check `git log`/the working tree before Task 3.** If B4 has landed: extend `PlayToEndAction` with a `.advance` case and make `.standard` return it (Task 2), and leave `OrientationLock` alone — **B5 does not write `OrientationLock.mask`** (see reconciliation note 3: this plan forces no orientation, so CF-B4-2's "must write it rather than adding a second mechanism" is satisfied by writing nothing). If B4 has **not** landed, Task 2 declares `PlayToEndAction { case advance, none }` and the end-of-item observer in `PlayerHostView.Coordinator` itself, in the exact shape B4's Task 2 Step 3 specifies (field + arm inside `observe(…)` + teardown inside `stopObserving()`), so B4 can later add `.restart` to the same enum rather than a second observer. Nothing else in B5 depends on B4.

**Android parity, and where this plan deliberately leaves it.** The record is `player.md` §7, §8.1, §12 and `playlist-detail-shorts.md` §3.4, cited file:line throughout. Behaviours mirrored: the ±10 s double-tap on **actual view width** thirds, split-screen-safe, with the centre zone returning unconsumed outside fullscreen so there is no dead zone (`PlayerGestureDetector.kt:43-47,49-83`); centre double-tap toggling fill/fit in fullscreen only, sticky per stream, with the `player_resize_mode_zoom` / `player_resize_mode_fit` copy (`PlayerFragment.kt:3525-3547`); the one-time `player_fullscreen_zoom_hint` snackbar on first fullscreen (`:3473-3484`); auto-enter fullscreen on rotation to landscape on **phones only**, suppressed once after a deliberate exit (`:639-657,691-777`); portrait (9:16) sources fullscreening in **portrait** (`:1290-1303`); the tab bar hidden while fullscreen (`MainShellFragment.kt:141-170`); Up Next as a 1-column list on phones and a **2-column grid** on tablets (`PlayerFragment.kt:895-908`); auto-advance on end (`PlayerFragment.kt:1247-1249` → `PlayerViewModel.kt:389` `markCurrentComplete()` → `:1866-1924` `advanceToNext`); the empty-queue terminus being `StreamState.Idle`, i.e. playback simply stops (`:1920-1923`); auto-skip of unplayable items capped at **3 consecutive**, counter reset on any successful advance (`:1349-1366`, `:1786-1787` `MAX_CONSECUTIVE_SKIPS = 3`, reset `:1916`); a background page fetch when the remaining queue is **≤5** (`:1911`, `:1786` `QUEUE_PREFETCH_THRESHOLD = 5`) with the single-flight mutex and the `pagingFailed` latch that stops paging for good (`:1946-1978`); stream prefetch of the next **2** items on the PREFETCH rate-limiter lane, refusals skipped and never waited on (`:198` `maxPrefetchItems = 2`, `:1703-1770`); deep-start scan for `targetVideoId` bounded at 250 items / 3 s falling back to `startIndex` (`:904-1031`, `:1782-1785`); shuffle pinning the tapped video first and disabling paging (`:1044-1093`); Up Next tap → `playItem`, id-matched against the queue (`:355-387`); history capped at 100 (`:182,2011-2016`). Behaviours **not** mirrored, each with its reason: Android's single-step back out of fullscreen (**ruling 45** — recorded defect 25, iOS does the two-step); Android's orientation-forcing exit with its 500 ms/3 000 ms unlock timers and deferred-exit safety timeout (`:3448-3470,4272-4276` — reconciliation note 3: iOS forces no orientation, so none of that machinery has anything to do); Android's `AspectPolicy` automatic crop-budget heuristic with its Samsung S25-Ultra build flag (`AspectPolicy.kt:24-68`, `PlaybackFeatureFlags.kt:163-173` — reconciliation note 4: AVKit's `videoGravity` default is already `.resizeAspect` and the manual centre-double-tap override is what the user actually reaches for); the up-next cell's `m:ss` duration that never renders hours (a 90-minute video reads "90:00") and its non-locale-aware `%.1fM` view count (`UpNextAdapter.kt:60-79` — **ruling 37**, one formatter: `DurationChip` and `Format` already do this correctly); the hidden 0 dp `upNextEmpty` view and the header rendered over nothing (**ruling 33**); the excluded-items stub queue (**ruling 41**); the playlist channel-name approval gate (`:889-902,1201-1206` — out of scope, see the Out-of-scope list); Media3's always-visible-but-alpha-0.3 prev/next buttons and the `player_up_next_empty` toast (`:449-458,3998-4032` — AVKit's stock transport has no prev/next to disable, and the Up Next list is the affordance).

**Rulings this plan implements** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/RULINGS.md`):

| # | Ruling | Where it lands |
|---|---|---|
| **42** | **Fullscreen: iPhone auto-fullscreens on landscape, iPad by button only (parity on both form factors)** | Task 3 — `PlayerFullscreen.isActive` is a pure four-row truth table driven by size classes; iPad never satisfies it (`widthClass != .compact`), so on iPad the *only* fullscreen is `AVPlayerViewController`'s own stock fullscreen button, which is already in the transport. **iPad costs zero lines** — that is the whole implementation of "by button only". |
| **45** | **Two-step back: back/swipe exits fullscreen first, then leaves the player** (Android's single-step exit is recorded defect 25) | Task 3 — fullscreen hides the navigation bar, which disables the interactive pop gesture, so the only back affordance while fullscreen is the overlay control we draw, and it exits fullscreen. The system back/edge-swipe returns with the bar when the layout leaves fullscreen, and then pops. Step 3 of Task 4 verifies the edge-swipe really is disabled — if it is not, `.navigationBarBackButtonHidden(true)` is the belt. |
| **58** | **Safe Mode ON disables player auto-advance** (spec §10: "auto-advance on end **unless Safe Mode**"; plan §6.10: the PRD's "no autoplay to next video" persona promise) | Task 2 — the end-of-item hook reads `model.safeMode` (CF-B3-2: **that property, never `SettingsStore`**) and returns without advancing. Nothing else about the queue changes: the Up Next list still renders, and a **tap** still plays, because Safe Mode disables *auto*-advance, not the playlist. |
| **33** | **"Up next" header hidden when the queue is empty**; shown for playlist queues | Task 2 — the whole section is inside `if !model.queue.upcoming.isEmpty`. `player_up_next_empty` ("Queue is empty.") is a **live catalog key with no iOS caller** — it is Android's toast on a dead Next button we do not have. Do not reference it. |
| **32** | **No watch-progress / resume persistence** | Task 2 — `PlayerViewModel.currentTime` (CF-B1-8's hoist) is a session-only property on a `@State` view model. It is reset to 0 on every advance and nothing writes it to `UserDefaults`, SwiftData or anywhere else. If you find yourself adding `@AppStorage` here, stop. |
| **16** | **Two lanes only (interactive, prefetch) + the cooldown/backoff ladder**; the shorts priority leak is impossible by construction | Task 2 — the queue's stream prefetch is the app's **first and only** `purpose: .prefetch` / `kind: .prefetch` call site. Everything else stays `.player` / `.autoRecovery` / `.proactiveTTLRefresh`. |
| **17** | **Tap-prefetch yes; the scroll-attach prefetch controller is NOT ported** | Task 2 — prefetch fires after a resolve settles (open, advance, tap), never off a scroll position or an `onAppear` in the Up Next list. |
| **21** | `forceRefresh` stays sticky across retries | Task 2 — untouched. The advance and the prefetch both pass `forceRefresh: false` and neither is a retry (CF-B2-2 forbids `forceRefresh` on auto-advance outright). |
| **37** | **ONE formatter: Phase 1 `Format` (ICU compact + plurals) everywhere, including player stats and up-next rows** (fixes defect 3 family) | Task 2 — the Up Next rows are `VideoRow` / `VideoGridCell` verbatim, which already route through `Format` and `DurationChip`. No second duration or view-count formatter is written, and the two Android defects (`UpNextAdapter.kt:60-79`) cannot be reproduced because their code is not ported. |
| **48** | `video_views` plural everywhere | Task 2 — inherited from `VideoRow`'s `videoMeta`. See reconciliation note 5 for why the queue rows carry no view count at all. |
| **39** | `channelName ← category` leak fixed: pass the real channel title, fall back only when nil | Task 1 — `ContentItem(video:)` maps `VideoItem.channelName` to `channelTitle` and leaves `category` nil. A queue row never displays a category in the channel slot. |
| **14** | Age-restricted / geo-blocked / private / removed are terminal, no retries | Task 2 — inherited: an advance whose resolve throws lands on `.contentUnavailable` via the existing `PlayerViewModel.map(_:)`. Auto-skip then moves past it **without retrying it** (it advances; it never re-resolves the dead item). |
| **34** | The settings are REAL on iOS | Task 2 — Safe Mode's first *behavioural* consequence in the player after B3's rung-4 filter. |
| **40** | Acceptance bar = "plays reliably, position-preserving refresh on failure", not Android's ladder-for-ladder parity | Task 4 |
| **35 / 36 / 43 / 44** | Speed, quality, PiP, audio session | **Not here.** All shipped in B1/B2 and inherited unchanged. B5 adds no AVKit configuration beyond `videoGravity` (Task 3) and the end-of-item observer (Task 2). |
| **50 / 51 / 57** | Shorts paging, feed, chrome | **Not here.** B4's. A Short is already full-bleed and portrait-locked; `PlayerFullscreen` is never consulted on that screen. |
| **11 / 46 / 47 / 52 / 55** | Playlist-detail and channel-tab list behaviour | **Not here.** Plan C's. B5 owns the queue *inside the player*; Plan C owns the playlist screen that launches it (see the contract in Task 1). |

**Carry-forwards this plan absorbs** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md`):

| Item | What it demands | Task |
|---|---|---|
| **CF-B1-6** | "`PlayerArgs` has 9 of spec §120's 12 fields; B5 adds `playlistId`/`startIndex`/`shuffled`/`targetVideoId` with the PlaylistDetail play/shuffle caller" | 1 — **three fields, not four**, and the twelve are in **spec §6** ("Routes"), not a §120: the spec has 19 sections and the carry-forward's "§120" is a stale cite — do not go looking for it, and do not edit `PHASE2-CARRYFORWARDS.md` to fix it (it is another agent's file). `playlistId` already exists on `PlayerArgs` (`Route.swift:70-80` lists videoId, playlistId, title, channelName, thumbnailURL, description, durationSeconds, viewCount, channelId = the 9). B5 adds `startIndex`, `shuffled`, `targetVideoId`. Reconciliation note 6. |
| **CF-B1-8** | "`.recoveryExhausted` and every non-playable state dismantle `PlayerHostView` so session-only resume position is lost on manual Retry; **if B5 makes that visible, hoist `currentTime` into the VM**" | 2 — **it does**, and it is hoisted. Auto-skip and auto-advance both cross non-playable states routinely now, and the same hoisted `currentTime` is what the double-tap seek (Task 3) reads and what tells the host whether a replacement item is the *same* video (resume) or the *next* one (start at 0). One property, three consumers. |
| **CF-B2-1** | "PiP survives BACKGROUNDING, not a back-navigation … Making PiP outlive the route needs an **app-scoped player holder** — one owner above the navigation stack that the host borrows from. → Plan B5 (it is the same holder Up Next / queue playback needs)" | **Deferred, deliberately — fork A.** It is *not* the holder queue playback needs: reconciliation note 2 shows why auto-advance works inside one route, and enumerates exactly what stays broken. |
| **CF-B2-2** | "`.prefetch` is unused and is B5's lane … a `.prefetch` refusal must be **skipped silently** (never surfaced as `.cooldown`, never retried into), and auto-advance must **never pass `forceRefresh: true`** — the next video's manifest cache entry is the whole point of prefetching it" | 2 — both rules are in Global Constraints and both are pinned by tests. The prefetch call is `_ = try? await resolver.resolve(…, purpose: .prefetch, kind: .prefetch, forceRefresh: false)`: the `try?` **is** the silent skip, and the advance's own resolve is `forceRefresh: false` so it lands on the warmed `ManifestCache` entry. Task 2 also closes the gap that makes the lane real — see reconciliation note 7. |
| **CF-B2-10** | "iPhone **landscape**: the player toolbar sits below the fold and the tab bar floats over the video … Landscape playback needs its own overlay treatment — full-bleed video, chrome that auto-hides, no tab bar" | 3 — this **is** ruling 42's fullscreen, not a separate fix. Full-bleed video, no tab bar (`Router.isFullscreen`), no metadata, no toolbar, no status bar, no navigation bar. |
| **CF-B2-11** | "The iPad `playerMaxWidth` screenshot assertion is **vacuous** — it passes without constraining anything" | 3 — `testPlayerB1Task10IPad` gets a real bound: the video box carries `player.videoBox` and the test asserts its frame width `<= 1600` while the window is ~1032 pt wide *and* that it is strictly narrower than the window in landscape. Fixed where the constraint is applied, per the carry-forward's own "or delete it and assert the constraint where it is applied". |
| **CF-B2-14** | "The periodic time observer is removed when the host is dismantled while PiP is deferred … the **lock-screen scrubber freezes** … Fixing it properly needs the observer to outlive the host, i.e. the same app-scoped player holder" | **Deferred with CF-B2-1 — fork A.** Not made worse by B5: B5 adds *one* consumer to that same observer (`model.currentTime`), which freezes under exactly the same conditions and has no user-visible effect in the frozen case (the host is gone; there is nothing reading the position). |
| **CF-B2-15** | "Background play ON + a stream with **no itag 140** keeps downloading video … an open behaviour question, not a known-good" | **Still open, and B5 is asked to answer it.** Fork B: the default is to leave the behaviour and **document it in Settings copy**, not to change it. Task 4 step 2 measures it so the controller decides against a number instead of a guess. |
| CF-B3-2 | "`PlayerViewModel.safeMode` is B5's auto-advance gate … B5 must read that property, not `SettingsStore` directly, so the player has one Safe Mode reader" | 2 — the end-of-item hook's `guard !safeMode` is that read, and it is the only Safe Mode read B5 adds. |
| CF-B4-2 | "`OrientationLock.mask` is the single writable seam for supported orientations. B5's fullscreen must write it rather than adding a second mechanism, and must restore it on exit" | 3 — satisfied by **writing nothing**: B5 forces no orientation (reconciliation note 3), so there is no second mechanism and nothing to restore. |
| CF-B4-6 | "the periodic time observer driving the scrub bar is per-screen … If B5's holder makes the player outlive the view, that observer becomes CF-B2-14's problem shape — check both together" | Fork A — with the holder deferred, nothing changes shape and the two stay checkable together whenever the holder lands. |
| CF-B1-2 / CF-B1-3 / CF-B2-3 / CF-B2-4 / CF-B2-7 / CF-B3-1 / CF-B3-3 / CF-B3-5 | Rate limiter wiring, foreground re-resolve, `silent:`, the live-state policy closure, the foreground deadline, the embed frame, audio ownership, the WebView test | **Inherited, not touched.** B5 adds no background-policy action, no new audio owner and no new `AVPlayer`. The one interaction worth naming: `PlayerHostView.applyPolicyAction` only acts on `StreamState.resolved` states, and an auto-advance never produces a new state case, so CF-B2-4's live-state read keeps working unchanged. |
| CF-B1-9 / CF-B1-10 / CF-C1 / CF-C2 / CF-C3 | Report flow, description truncation probe, channel-tab modelling, browse cooldown, degraded mode | **Plan C.** The one Plan C item B5 constrains is the launch contract in Task 1. |

---

## Global Constraints

Implementers inherit nothing from earlier plans. All of the following are binding:

- **Swift 6, `SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor`** on the app and unit-test targets (`ios/project.yml`). `AVPlayer` / `AVPlayerItem` / `AVPlayerViewController` are not `Sendable` and are main-actor-confined by default. Do not add `nonisolated` to silence a warning. `NotificationCenter` observer blocks and UIKit gesture-recognizer targets are nonisolated: decode inside the block and hop with `MainActor.assumeIsolated`, exactly as `BackgroundPlaybackController.attach` and `PlayerHostView.Coordinator.observe` already do (`PlayerHostView.swift`, the `failedToEndObserver` / `timeObserverToken` blocks).
- **One implementer at a time.** `ios/DerivedData` is shared; two concurrent `xcodebuild` runs corrupt it. Never dispatch two B5 tasks in parallel.
- **Gate:** `ios/scripts/test.sh` from the repo root, 300 s wall-clock watchdog, 60 s per test. It runs `convert-strings.py --check` → `xcodegen generate` → `xcodebuild test` (iPhone 17 + iPad Pro 13-inch (M5)) → `swift test` (packages — InnerTubeKit's suite is ~90 tests and is the bulk of the budget) → a Release build. A task is not done until this is green.
- **`.ready` and `.rung2Progressive` share ONE `switch` branch, and there is ONE `PlayerHostView` in the whole tree.** This is the single most important structural rule in B5. Two `case`s, or two placements of `PlayerHostView` in two layout branches, give SwiftUI two view identities, so the transition dismantles the host and drops the `AVPlayer` — which is the only thing carrying the position, the audio session attachment and a live PiP window (`PlayerScreen.swift`, Task 7's identity note; `PlayerHostView.player(for:replacing:audioOnly:)`). **Fullscreen must therefore be a modifier change on one host in one tree, never an `if fullscreen { PlayerHostView(...) } else { PlayerHostView(...) }`.** The same rule kills the tempting `.fullScreenCover` implementation: a cover is a second view tree.
- **AVKit chrome is not XCUITest-accessible** on this toolchain (Xcode 26.3 / iOS 26.2). AVKit's own "Video" label is system-localized and must never be asserted against. Every UI assertion anchors on FitrahTube's own accessibility identifiers. **New identifiers keep the `player.` prefix** (`player.videoBox`, `player.fullscreenExit`, `player.seekFeedback`, `player.upNext.header`, `player.upNext.row.<videoId>`); reused components keep the identifiers they already emit — **do not rename them**, existing B1/B2/B3 tests assert on them.
- **`ios/scripts/screenshots.sh`'s device argument does not scope the trailing player blocks.** Passing a device name filters only the `DEVICES` loop; every `-only-testing:` block appended after it (B1 tasks 3–10, B2, B3, B4) still runs on its own hard-coded destination. To capture one B5 screenshot during development, **invoke a single `xcodebuild -only-testing:FitrahTubeUITests/ScreenshotTests/<case> -destination …` line by hand**; add the permanent block to `screenshots.sh` only in Task 4.
- **Stale install symptom.** If a UI test launches into a screen that no longer exists in the source (no Up Next section where one is clearly implemented, a missing identifier that is present in the code), the simulator is running a stale install: `xcrun simctl uninstall <device-udid> com.albunyaan.tube` and re-run. Do not "fix" source that is already correct.
- **The `.prefetch` lane has exactly two rules and both are absolute** (CF-B2-2). (a) **A refusal is skipped silently.** It must never reach `state`, never become `.cooldown`, never be retried, never be logged as an error and never block the advance. (b) **Auto-advance never passes `forceRefresh: true`.** The whole point of warming `ManifestCache` is that the advance's resolve is free; a forced advance discards the warm entry and doubles the traffic the prefetch was supposed to remove. A test pins each rule.
- **No second cache.** Android carries a `prefetchCache` dictionary with a 30 s TTL and hand-rolled eviction (`PlayerViewModel.kt:198,1747,1799`) because its extractor has no shared cache. InnerTubeKit's `StreamResolver` returns from `ManifestCache` on any non-forced resolve (`StreamResolver.swift:73-74`), and D3 already clamps that cache's TTL to `resolved.expiresAt`. **Warming it is the entire prefetch.** Do not add a dictionary, a TTL, an eviction policy or a `CachedPrefetch` type.
- **`PlayerViewModel.args` becomes mutable and `PlayerScreen` must read `model.args`, never its own `let args`.** After Task 2 the screen's `args` is only the *initial* value; the toolbar, the metadata panel, the state view's thumbnail and the Open-in-YouTube handler must all read `model.args` or they will show the previous video after an advance. Grep `PlayerScreen.swift` for `args` and convert every use inside `stateView` — this is the most likely silent bug in the plan.
- **All user-visible strings go through `ios/scripts/convert-strings.py`.** **B5 needs exactly ONE new key** — `player_queue_ended` (Task 2, reconciliation note 9), authored in that script's `EXTRA_KEYS` dict because Android has no source string for it (its empty-queue terminus is `StreamState.Idle`, a silent stop with no copy at all — `PlayerViewModel.kt:1920-1923`). Every *other* string this plan uses already exists in the catalog (verified against `Localizable.xcstrings` at HEAD, 2026-08-27): `player_up_next_header` ("Up next"), `player_resize_mode_zoom` ("Fill screen"), `player_resize_mode_fit` ("Fit to screen"), `player_fullscreen_zoom_hint` ("Double-tap to toggle fit/zoom"), `player_action_fullscreen` ("Toggle fullscreen"), `back` ("Back"), `video_views`, `player_duration_minutes_seconds`. **Never hand-edit `Localizable.xcstrings`** — regenerate it. If you believe a *second* string is missing, you are about to build something that is not in this plan.
- **Do not use `player_up_next_empty`.** It is a live Android key the converter keeps in the catalog, and it is the toast on Media3's always-visible-but-disabled Next button (`PlayerFragment.kt:449-458`) — an affordance ruling 33 and AVKit's transport both remove. Leave the key in the catalog; do not reference it from Swift.
- **Copy rules (spec §10, plan §6.6, §9 checklist).** Never the words "ad-free" anywhere in-app. Never a kids-vs-lecture explanation: say *what* is playing, never *why*.
- **Accessibility floor (plan §6.11, spec §14).** The fullscreen exit control is ≥44×44 pt measured on its tap target, on a scrim, never on bare video. Up Next rows inherit `VideoRow`/`VideoGridCell`'s existing labels — do not add a competing `.accessibilityLabel`. The Up Next grid collapses to **one column at `.accessibility1+`** via the existing `Size.columns(_:dynamicTypeSize:)`. The seek-feedback layer is `.accessibilityHidden(true)` and `.allowsHitTesting(false)`: it is chrome for a gesture VoiceOver users do not perform (they use AVKit's own ±10 s buttons, which is why B5 adds no seek strings). Reduce Motion turns the seek flash into an instant show/hide, not a fade.
- **RTL.** The gesture zones are **spatial, not directional**: the left third always seeks backward in time, in Arabic as in English, because the user is pointing at the part of the video timeline AVKit draws there and AVKit mirrors its own scrubber. Do **not** flip the zones under `layoutDirection` — flip nothing, and verify against AVKit's mirrored scrubber in Task 4. The seek-feedback symbols (`gobackward.10` / `goforward.10`) are SF Symbols and mirror themselves.
- **No new `.md` files.** This plan is the only document B5 creates. `docs/superpowers/HANDOFF.md`, `docs/superpowers/plans/2026-08-23-ios-phase2a-innertubekit.md`, the B3/B4 plans and any `ios/` peer docs are work owned by other agents — **never `git add` them**; stage only the exact files each task's commit step names.
- **The simulator can prove the layout and the machine, not the stream.** With the `#if DEBUG` fixture resolvers plus this plan's fixture queue source, the whole feature — fullscreen layout, gestures, Up Next, advance, skip, the empty terminus — renders and is assertable with no network. What it cannot prove: real playlist paging against YouTube, a real prefetch cache hit, a genuinely unplayable playlist member, real rotation hardware and everything on the device list. Task 4 separates the three tiers and marks the device tier USER-BLOCKED (the repo has no signing identity — `DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset). **B5 also inherits B2's undone 27-item device checklist and B4's 10-item one.**

---

## Reconciliation — read all nine before writing code

**1. Plan §6.5 drops the gesture layer; spec §10 requires it. Spec §10 wins, and the two are reconcilable.** `ios-app-plan.md` §6.5 says "Android's double-tap seek/zoom gesture layer is dropped in v1 (AVPlayerViewController has its own)". Spec §10 — written after, and the authority per the decomposition — lists "double-tap left/right thirds ±10 s, centre double-tap fit/zoom in fullscreen … implemented as an overlay on the content view", and `.superpowers/sdd/scratch/plan-B-decomposition.md` assigns it to B5 explicitly ("spec §6.5 drops Android's double-tap in v1; **B5 adds a minimal one**"). What §6.5 is actually right about is that **AVKit owns the single tap** (show/hide controls) and must keep owning it. So the reconciliation is mechanical, not editorial:

- The **gesture recognizer is UIKit**, a `UITapGestureRecognizer(numberOfTapsRequired: 2)` added to `controller.contentOverlayView` — the view AVKit documents as "for displaying custom content above the video", which is literally spec §10's "overlay on the content view". Its delegate returns `true` from `gestureRecognizer(_:shouldRecognizeSimultaneouslyWith:)`, so **AVKit's own recognizers are neither required to fail nor blocked**: single taps still toggle the controls, and a double tap additionally fires AVKit's single-tap once (the controls flash). That flash is accepted — Android's behaves the same way, and suppressing it would mean reaching into `controller.view.gestureRecognizers` to `require(toFail:)` AVKit's private recognizers, which this plan does not do.
- A **SwiftUI overlay would break this.** A transparent SwiftUI layer above the host is hit-testable or it is not: if it is, it swallows single taps and AVKit's controls stop toggling; if it is not, it receives no taps at all. There is no middle setting. So the SwiftUI half of the overlay is the **feedback only** — the ±10 s flash and the fit/zoom banner — and it is `.allowsHitTesting(false)`.
- **Verify `contentOverlayView` receives touches** in Task 3 Step 4 (a `print` in the handler is enough). If it does not on this SDK, the fallback is `controller.view` with the same delegate and the same simultaneous-recognition rule; nothing else changes. Do not fall back to a SwiftUI gesture.

**2. CF-B2-1 says the app-scoped player holder "is the same holder Up Next / queue playback needs". It is not, and this plan defers it — fork A.** Auto-advance never leaves the route, so the holder buys it nothing:

- `PlayerScreen` holds the `PlayerViewModel` in `@State`. An advance mutates `model.args` and re-resolves; the state stays in the playable branch (the advance passes `showLoading: false`), so `PlayerHostView` is never dismantled and the same `AVPlayer` gets one `replaceCurrentItem`. The audio session, the Now Playing surface, the remote commands and a live PiP window all survive because their owner — the `Coordinator` — was never released.
- That is also what makes **background auto-advance** work: with the screen locked and the itag 140 rendition playing, the end-of-item notification fires, the advance resolves from the warm `ManifestCache` and swaps the item on the live player, with no SwiftUI teardown in a suspended UI. A `.loading` hop would dismantle the host, `detach()` the audio session mid-background, and end playback. **This is the reason `showLoading: false` on the advance is load-bearing, not a preference.**
- **What stays broken by deferring:** CF-B2-1 (a back-navigation out of the player still closes a live PiP window — the deferred teardown in `finishTeardown` never runs because the coordinator is released, which is exactly why `Coordinator.deinit`'s unconditional `background.detach()` backstop exists) and CF-B2-14 (with a deferred-PiP dismantle, the periodic observer is gone, so the lock-screen scrubber freezes for the life of that window — and, added by this plan, `model.currentTime` stops updating too, with no visible effect since the model is being released anyway).
- **Why deferring is right rather than lazy:** the holder is not a refactor, it is a new ownership model. It moves the `PlayerViewModel`, the `AVPlayer` and the `BackgroundPlaybackController` above the `NavigationStack`; it needs an identity policy (what happens when a *second* player route opens while the first is in PiP — replace, refuse, or queue?); it invalidates `PiPDismantlePolicy`'s entire premise (that policy exists *because* the host can die while PiP lives — with a holder, the host dying is routine and the teardown decision moves to the holder, so `PiPTeardownActions` and its tests are rewritten, not adjusted); it forces `ShortsScreen` onto it too (CF-B4-1) or leaves one route holding its player differently from every other; and it must answer what happens to the holder on `Route.player` with a *different* videoId. None of that is needed to ship fullscreen, gestures or Up Next, and doing it inside B5 would make the queue work impossible to review separately from an ownership rewrite. It is a standalone plan.

**3. iOS forces no orientation, and that is what makes the two-step back cheap.** Android's fullscreen exit locks `SENSOR_LANDSCAPE` on entry, forces `PORTRAIT` on exit, unlocks after 500 ms/3 000 ms, and defers the exit until the portrait config change arrives with a 3 500 ms safety timeout (`PlayerFragment.kt:3382-3471,3448-3470,4272-4276`) — plus a `ponytail:` note in its own source admitting one swallowed rotation as an accepted cost. **None of that is ported.** iOS fullscreen here is a pure function of the size classes plus one `userExitedFullscreen` latch:

- Rotate to landscape on iPhone → compact height → fullscreen. Rotate back → not fullscreen. No API call.
- The fullscreen exit control sets `userExitedFullscreen = true`, which drops the layout back to the normal column **while still in landscape** — a perfectly good landscape layout (16:9 video, toolbar, Up Next, metadata already hidden by B1's existing `verticalSizeClass != .compact` rule). It does **not** force portrait.
- The latch clears on `.onChange(of: verticalSizeClass)` when the new value is not `.compact` — i.e. rotating to portrait re-arms auto-fullscreen, so rotating back to landscape enters it again. That is Android's "suppressed exactly one auto-enter", achieved with one line and no timers.
- Consequence for CF-B4-2: **B5 writes nothing to `OrientationLock.mask`**, so there is no second orientation mechanism and nothing to restore on exit. The seam stays B4's alone.

**4. There is no `AspectPolicy`.** Android computes fill-vs-fit automatically from a crop budget (5 % default, 20 % on Samsung S25 Ultra via a build flag — `AspectPolicy.kt:24-68`, `PlaybackFeatureFlags.kt:163-173`) and *also* offers the centre-double-tap override. iOS ships **only the override**: `AVPlayerViewController.videoGravity` defaults to `.resizeAspect` (fit), which is the correct default for every source, and the centre double-tap toggles it to `.resizeAspectFill` (fill) in fullscreen, sticky per stream, with the `player_resize_mode_zoom` / `player_resize_mode_fit` banner. Porting a device-specific crop heuristic to guess what one gesture says explicitly is exactly the kind of unenforceable second model this plan avoids. Cost if wrong: a user with a 21:9 source has to double-tap once. **Note:** `videoGravity` is B4's `PlayerPresentation.videoGravity` if B4 has landed — the zoom flag *overrides* the presentation's value; it does not replace the property.

**5. The Up Next rows carry no view count, deliberately, and that satisfies ruling 37 rather than violating it.** `InnerTubeKit.VideoItem` exposes `viewCountText: String?` — YouTube's already-formatted, already-localized *display text* ("1.2M views"), not a number (`BrowseClient.swift:21-44`). Ruling 37 requires everything rendered to go through Phase 1's `Format` (ICU compact + plurals). The only way to feed `Format` here is to parse "1.2M" back into an Int64, which is locale-fragile, lossy and a new parser. So `ContentItem(video:)` sets `viewCount: nil` and `VideoRow`'s existing `videoMeta` drops the blank part, leaving title + channel + duration chip. **This also fixes both recorded Android defects for free** (`UpNextAdapter.kt:60-79`: a duration formatter that renders a 90-minute video as "90:00", and a `String.format("%.1fM")` view count that ignores the locale) — by not porting the code that has them. If a real view count is ever wanted in the queue, it arrives from the backend's `ContentItem`, not from a parsed string.

**Consequence for the iPad grid, which is why `VideoGridCell` grows one parameter.** `videoMeta` builds its line from `viewCount` and `uploadedDaysAgo` only (`Components.swift:11-24`); with both nil it returns `""`. `VideoRow` already has a `subtitle: String?` override for exactly this case (Favorites passes the channel name through it — `favorites-settings-about.md:90,94`), so the phone list is fine. **`VideoGridCell` has no such override** (`Components.swift:361-375`), and its meta line sits inside a `@ScaledMetric` fixed-height block (`.frame(height: contentHeight)`), so a queue cell would render a reserved, empty grey band where the channel name belongs. Task 2 therefore adds the **same** `subtitle: String?` parameter to `VideoGridCell`, defaulted `nil` and mirroring `VideoRow`'s exact semantics (when non-nil it replaces the computed meta line, `lineLimit(1)`), and the Up Next grid passes `item.channelTitle`. One parameter on an existing component, not a new cell type — and every existing `VideoGridCell(item:onTap:)` call site is untouched because it is defaulted.

**6. CF-B1-6 says four fields; it is three.** `PlayerArgs` at HEAD already carries `playlistId` (`Route.swift:70-80`: videoId, playlistId, title, channelName, thumbnailURL, description, durationSeconds, viewCount, channelId — the nine CF-B1-6 counts). Spec §6's twelve are those nine plus `startIndex`, `shuffled`, `targetVideoId`. B5 adds those three. (B4, if it lands, adds `channelAvatarURL` as a thirteenth, iOS-only field; the two additions do not collide — both are defaulted, so no existing initializer call site changes either way.)

**7. `RateLimitedResolver` does not gate the prefetch lane today, and B5 must fix that or ruling 16's lane is decorative.** `RateLimitedResolver.resolve` checks `ExtractionRateLimiter` **only when `forceRefresh == true`** (`PlayerViewModel.swift`, the `if forceRefresh {` guard), with a correct comment: a non-forced resolve may be served from `ManifestCache` with no network at all, so gating it would refuse a free replay. But a prefetch is *precisely* the non-forced resolve that is most likely to hit the network — a cold cache entry for a video the user has never opened — and it is the one lane the limiter exists to keep behind the interactive one. The fix is two words: `if forceRefresh || kind == .prefetch`. A prefetch of an already-warm entry then gets refused by the 30 s minimum interval, which costs nothing (the entry is already there when the advance asks for it) and is skipped silently by rule. **Without this change every prefetch is an ungated `youtubei/v1/player` POST and CF-B2-2's "lane" is a parameter nobody reads.**

**8. "Prefetch ≤5" and "prefetch ≤2" are two different mechanisms and spec §10 means the first.** Spec §10 says "**playlist paging** prefetch when the queue ≤5" — that is Android's `QUEUE_PREFETCH_THRESHOLD = 5` (`PlayerViewModel.kt:1786,1911`), which triggers fetching the next **page of playlist items** (a `browse` call, no stream resolution). Android's *stream* prefetch is a separate thing: the next **2** items resolved on the PREFETCH lane (`:198` `maxPrefetchItems = 2`, `:1703-1770`). B5 implements both, under distinct names — `PlayerQueue.needsPage` (≤5 remaining → one `PlaylistQueueSource.page` call) and `PlayerQueue.streamPrefetchTargets` (first 2 upcoming → `.prefetch` resolves). **Do not resolve five streams ahead**: the rate limiter's global lane is 10 attempts per 60 s (`ExtractionRateLimiter.maxGlobalAttempts`), so five resolves per advance would spend half a minute's budget on speculation and starve the interactive lane — which is exactly why Android chose 2.

**9. The empty-queue terminus is a NEW `StreamState.queueEnded`, not `.idle` — `.idle` would leave a permanent spinner.** Android's `advanceToNext` ends a finished playlist on `StreamState.Idle` and playback simply stops (`PlayerViewModel.kt:1920-1923`), and the naive port is `state = .idle`. **That port is a bug on iOS.** `.idle` is this app's *pre-open* value, and `PlayerStateCopy.map` folds it in with `.loading` (`PlayerStateView.swift:28-32`): online, it renders `String(localized: "loading")` with `showsRetry: false` and `announces: false`. So finishing a playlist would put the user on an indefinite "Loading…" spinner with no Retry, no explanation and no announcement — the exact "bare `ProgressView` dead end" that T7-M3 already fixed once for `.recoveryExhausted`. It is worse than Android's, which at least leaves the last frame on screen.

So B5 adds one case:

```swift
    /// Ruling 33's terminus, and the reason it is NOT `.idle`: `.idle` is the pre-open value and
    /// `PlayerStateCopy` renders it as "Loading..." with no Retry (`PlayerStateView.swift:28-32`),
    /// so ending a playlist on it is a permanent spinner. This is a real terminal state with real
    /// copy. NO Retry -- there is nothing to retry; the queue is genuinely finished and Back (or an
    /// Up Next tap, if any item remains) is the exit. Announced, because a playlist ending while
    /// the user is not looking at the screen is exactly the kind of transition spec 6.6's
    /// "Transitions" row exists for.
    case queueEnded
```

**Every exhaustive `StreamState` switch it touches, enumerated** (the same audit B3 ran when it added `.embed`) — check each one, do not assume. *(Owner directive 2026-08-27: `StreamState.openInYouTube` does not exist — the ladder ends at `.embed`; do not add a switch arm for it.)*

| Site | Exhaustive? | What B5 must do |
|---|---|---|
| `PlayerStateCopy.map` (`PlayerStateView.swift:23-66`) | **Yes** — no `default:` | **Add an arm.** `Copy(message: String(localized: "player_queue_ended"), showsRetry: false, announces: true)`. It is *not* inside the offline gate: a finished queue is finished whether or not there is a network. |
| `StreamState: Equatable` `==` (`PlayerState.swift:29-46`) | Has `default: return false` | **Add `case (.queueEnded, .queueEnded): return true`** alongside the existing `.idle`/`.loading`/`.contentUnavailable` tuple arm, or `#expect(vm.state == .queueEnded)` silently fails forever. |
| `StreamState.resolved` (`PlayerViewModel.swift:74-80`) | Has `default:` | Nothing — falls through to `nil`, which is correct: `.queueEnded` carries no `Resolved`, so `isPlayable` is false, `PlayerHostView` is dismantled and `PlayerViewModel.audioOnlyAvailable(for:)` (a `guard case .ready`) is false. |
| `NowPlayingSnapshot.make` (`NowPlayingSnapshot.swift:27-33`) | Has `default: return nil` | Nothing — and `nil` is the **wanted** behaviour: a finished playlist must retract the lock-screen surface rather than leave the last video's metadata standing (the same rule B2 wrote for `.embed`, CF-B2-9). Confirm it in Task 4. |
| `PlayerScreen.stateView` (`PlayerScreen.swift:63`) | Has `default:` | Nothing — `.queueEnded` lands on the shared `PlayerStateView` mount, which is what makes the cross-dissolve and the announcement work (Task 9's one-identity note). |
| `PlayerHostView.applyPolicyAction` / `isLive` | Switch on `PlaybackPolicyAction` / go through `state.resolved` | Nothing. |
| `AudioSessionPolicy.decide` (`AudioSessionPolicy.swift:46`) | Switches on the **event**, not the state | Nothing — its state-derived input is `audioOnlyAvailable`, already false via `state.resolved`. |
| `PlayerViewModel.map(_:safeMode:)` / `map(_:)` | Switch on `ResolvedStream` / `ExtractionError` | Nothing — `.queueEnded` is never a *resolve* outcome. It is only ever written by `advance()`. |

Cost if the enumeration is wrong: a missed `default:`-less switch is a compile error, which is the point of listing them. The one that compiles and is still wrong is `Equatable`.

---

### Task 1: The launch contract — `PlayerArgs`' last three fields, the pure queue, and the playlist source Plan C implements

**Why this is first:** everything downstream needs a queue type and a way to fill it, and the *shape* of both is a contract with Plan C that must be settled before either plan writes a call site. Nothing in this task touches the player, so it is a clean, fully-pure deliverable: a struct, a protocol, one mapping function and one thin network wrapper.

**Files:**
- Modify: `ios/FitrahTube/App/Route.swift` (`PlayerArgs.startIndex` / `.shuffled` / `.targetVideoId`)
- Create: `ios/FitrahTube/Features/Player/PlayerQueue.swift` (`PlayerQueue`, `PlaylistQueueSource`, `LivePlaylistQueueSource`, `ContentItem.init(video:)`)
- Test: `ios/FitrahTubeTests/PlayerQueueTests.swift` (create)

**Interfaces:**
- Consumes: `ContentItem` (`ios/FitrahTube/Catalog/Models.swift`), `InnerTubeKit.VideoItem` / `BrowsePage` / `BrowseClient.playlistItems(_:continuation:)` (`BrowseClient.swift:21-44,190-194`).
- Produces: `PlayerQueue`, `PlaylistQueueSource`, `LivePlaylistQueueSource`, `ContentItem.init(video: VideoItem)`, three `PlayerArgs` fields.

**The Plan C contract (this is the deliverable other plans read).** Two seams, one owned by each side:

```swift
// ── Side A: Plan C CALLS this. B5 owns the type; Plan C owns the call sites. ──────────────
//
// PlaylistDetail's "Play all"  -> Route.player(PlayerArgs(
//                                     videoId: firstItem.id, playlistId: playlistId,
//                                     startIndex: 0, shuffled: false, targetVideoId: nil, …))
// PlaylistDetail's "Shuffle"   -> ... startIndex: 0, shuffled: true,  targetVideoId: nil
// PlaylistDetail's row tap     -> ... startIndex: rowIndex, shuffled: false,
//                                     targetVideoId: item.id        // AUTHORITATIVE
//
// Android's contract, verbatim (`PlaylistDetailViewModel.kt:404-417`,
// `PlaylistDetailFragment.kt:745-754`): "targetVideoId is the authoritative identifier,
// startIndex an optimization hint". B5 honours exactly that: it deep-scans for targetVideoId
// and falls back to startIndex only when the scan is exhausted. Emission is UNCONDITIONAL --
// Plan C must NOT wait for its own items to load before navigating; the player resolves the
// playlist itself. Plan C also does not prefetch the first stream (Android does,
// `PlaylistDetailFragment.kt:294-298`); B5's own open() resolve is that call.
//
// `videoId` must still be a real, playable id -- it is what plays while the queue loads. For
// "Play all" / "Shuffle" before items are loaded, pass the playlist's first known item; if
// Plan C genuinely has none, that is a Plan C empty-state, not a player launch.

// ── Side B: B5 DEFINES this; Plan C MAY provide a different implementation. ───────────────
protocol PlaylistQueueSource: Sendable {
    /// One page of a playlist, oldest cursor first. `continuation == nil` means "first page".
    /// Returns the page's items and the cursor for the next page, or nil when exhausted.
    func page(playlistId: String, continuation: String?) async throws
        -> (items: [ContentItem], continuation: String?)
}
```

**Who owns what, explicitly.** B5 owns the queue **inside the player**: the protocol, the pure machine, the Up Next UI, advance/skip/prefetch, and one shipped implementation (`LivePlaylistQueueSource`) over `InnerTubeKit.BrowseClient.playlistItems`. Plan C owns the **PlaylistDetail screen**: its own header, its own pagination state, its own search/save/download affordances, and the `PlayerArgs` construction above. Both read `BrowseClient.playlistItems`, which is InnerTubeKit's and already shipped and already works (CF-C1 confirms `channelVideos`/`playlistItems` do; only `channelTab(.shorts/.playlists)` are unmodelled) — so this is two consumers of one client with independent paging cursors, **not** duplicated extraction. Plan C must not try to hand its own loaded page array into the player: the player's cursor advances independently (it pages past what the screen has shown), and a shared array would need a shared paging owner, which is a coupling neither plan wants. If Plan C later wants to save a round trip on "Play all", the cheap version is passing the first page through `PlayerArgs` — that is a future field, not this contract.

- [ ] **Step 1: Write the failing tests**

Create `ios/FitrahTubeTests/PlayerQueueTests.swift`. Everything here is pure — no AVFoundation, no network, no `@MainActor` needed beyond the target default.

```swift
import Testing
import InnerTubeKit
@testable import FitrahTube

private func items(_ ids: [String]) -> [ContentItem] {
    ids.map { ContentItem(video: VideoItem(id: $0, title: "T-\($0)", channelName: "Ch",
                                           durationSeconds: 120, thumbnailURL: nil)) }
}

@Test func videoItemMapsOntoContentItemWithoutInventingAViewCount() {
    // Reconciliation note 5 + ruling 39: the real channel title lands in `channelTitle`,
    // `category` stays nil (no `channelName <- category` leak), and `viewCount` stays nil
    // because VideoItem only has PRE-FORMATTED display text, which `Format` cannot consume.
    let mapped = ContentItem(video: VideoItem(id: "abc", title: "Lecture",
                                              channelName: "Sheikh", channelId: "UC1",
                                              durationSeconds: 610,
                                              viewCountText: "1.2M views", thumbnailURL: nil))
    #expect(mapped.id == "abc")
    #expect(mapped.type == .video)
    #expect(mapped.channelTitle == "Sheikh")
    #expect(mapped.category == nil)
    #expect(mapped.viewCount == nil)
    #expect(mapped.durationSeconds == 610)
}

@Test func startPositionsOnTargetVideoIdNotStartIndex() {
    // `playlist-detail-shorts.md` §3.4 / `PlaylistDetailFragment.kt:747`: targetVideoId is
    // authoritative, startIndex is a hint. A stale hint must lose.
    let q = PlayerQueue.start(items: items(["a", "b", "c", "d"]), targetVideoId: "c",
                              startIndex: 0, shuffled: false, cursor: nil)
    #expect(q.current?.id == "c")
    #expect(q.upcoming.map(\.id) == ["d"])
}

@Test func startFallsBackToStartIndexWhenTheTargetIsNotPresent() {
    let q = PlayerQueue.start(items: items(["a", "b", "c"]), targetVideoId: "zz",
                              startIndex: 1, shuffled: false, cursor: nil)
    #expect(q.current?.id == "b")
}

@Test func startClampsAnOutOfRangeStartIndex() {
    let q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil,
                              startIndex: 99, shuffled: false, cursor: nil)
    #expect(q.current?.id == "b")
}

@Test func shufflePinsTheTappedVideoFirstAndDisablesPaging() {
    // `PlayerViewModel.kt:1044-1093`: randomize, pin the tapped video first, paging OFF.
    var generator = SeededGenerator(seed: 7)
    let q = PlayerQueue.start(items: items(["a", "b", "c", "d", "e"]), targetVideoId: "d",
                              startIndex: 0, shuffled: true, cursor: "PAGE2",
                              using: &generator)
    #expect(q.current?.id == "d")
    #expect(Set(q.upcoming.map(\.id)) == Set(["a", "b", "c", "e"]))
    #expect(q.hasMorePages == false)          // paging disabled when shuffled
    #expect(q.needsPage == false)
}

@Test func advanceWalksForwardAndStopsAtTheEnd() {
    var q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: nil)
    #expect(q.advance()?.id == "b")
    #expect(q.hasNext == false)
    #expect(q.advance() == nil)               // `PlayerViewModel.kt:1920-1923`: playback stops
}

@Test func selectPlaysAnArbitraryQueuedItemAndRepositionsTheQueue() {
    // `PlayerViewModel.kt:355-387`: the Up Next tap is id-matched against the queue.
    var q = PlayerQueue.start(items: items(["a", "b", "c"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: nil)
    #expect(q.select(id: "c")?.id == "c")
    #expect(q.upcoming.isEmpty)
    #expect(q.select(id: "nope") == nil)      // desync is a no-op, never a crash
}

@Test func needsPageFiresAtFiveRemainingAndNotAboveIt() {
    // Spec §10 "playlist paging prefetch when the queue <=5"; `PlayerViewModel.kt:1786,1911`.
    var q = PlayerQueue.start(items: items(["0","1","2","3","4","5","6"]), targetVideoId: nil,
                              startIndex: 0, shuffled: false, cursor: "P2")
    #expect(q.upcoming.count == 6)
    #expect(q.needsPage == false)
    _ = q.advance()
    #expect(q.upcoming.count == 5)
    #expect(q.needsPage)
}

@Test func needsPageIsFalseOnceThereAreNoMorePages() {
    var q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: "P2")
    #expect(q.needsPage)
    q.append([], cursor: nil)                 // exhausted
    #expect(q.needsPage == false)
    #expect(q.hasMorePages == false)
}

@Test func aFailedPageStopsPagingForGood() {
    // `PlayerViewModel.kt:1946-1978`: pagingFailed is a latch, not a retry counter.
    var q = PlayerQueue.start(items: items(["a"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: "P2")
    q.markPagingFailed()
    #expect(q.hasMorePages == false)
    #expect(q.needsPage == false)
    q.append(items(["b"]), cursor: "P3")      // a late success must not un-latch it
    #expect(q.hasMorePages == false)
}

@Test func streamPrefetchTargetsAreTheNextTwoOnly() {
    // Reconciliation note 8 + `PlayerViewModel.kt:198`: two, never five.
    let q = PlayerQueue.start(items: items(["a","b","c","d","e"]), targetVideoId: nil,
                              startIndex: 0, shuffled: false, cursor: nil)
    #expect(q.streamPrefetchTargets.map(\.id) == ["b", "c"])
}

@Test func appendDoesNotDisturbTheCurrentIndex() {
    var q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil, startIndex: 1,
                              shuffled: false, cursor: "P2")
    q.append(items(["c", "d"]), cursor: nil)
    #expect(q.current?.id == "b")
    #expect(q.upcoming.map(\.id) == ["c", "d"])
}
```

`SeededGenerator` is a four-line deterministic `RandomNumberGenerator` — put it at the bottom of this test file, not in the app target.

- [ ] **Step 2: Run the tests, watch them fail**

```bash
ios/scripts/test.sh
```
Expected: compile failure — `PlayerQueue`, `ContentItem.init(video:)` and the three `PlayerArgs` fields do not exist.

- [ ] **Step 3: Implement**

**`PlayerArgs`** (`Route.swift`), three defaulted fields so no existing call site changes:

```swift
    /// Index hint from the caller's list (`PlayerFragment.kt:402-417`). NOT authoritative --
    /// `targetVideoId` wins; this is the fallback when the deep scan is exhausted.
    var startIndex: Int = 0
    /// Randomize the queue, pinning the launched video first; paging is disabled while shuffled
    /// ("can't prefetch shuffle since we don't know the order", `PlaylistDetailFragment.kt:300-303`).
    var shuffled: Bool = false
    /// The authoritative start video when the caller knows it (`PlaylistDetailFragment.kt:747`).
    var targetVideoId: String? = nil
```

**`PlayerQueue.swift`** — one file, four declarations:

1. `nonisolated struct PlayerQueue: Equatable, Sendable` holding `items: [ContentItem]`, `index: Int`, `cursor: String?`, `pagingFailed: Bool`, all `private(set)`. Constants `pageThreshold = 5` and `streamPrefetchCount = 2` as `static let`, each with the reconciliation-note-8 comment. Computed: `current`, `upcoming` (`items.dropFirst(index + 1)`), `hasMorePages` (`cursor != nil && !pagingFailed`), `hasNext` (`!upcoming.isEmpty || hasMorePages`), `needsPage` (`upcoming.count <= pageThreshold && hasMorePages`), `streamPrefetchTargets` (`Array(upcoming.prefix(streamPrefetchCount))`). Mutating: `advance() -> ContentItem?`, `select(id:) -> ContentItem?`, `append(_:cursor:)` (a no-op on the cursor when `pagingFailed`), `markPagingFailed()`. Static `start(items:targetVideoId:startIndex:shuffled:cursor:using:)` with a `inout some RandomNumberGenerator` defaulted to `SystemRandomNumberGenerator` — shuffle pins the resolved start item at index 0, shuffles the rest, and sets `cursor = nil`.
2. `protocol PlaylistQueueSource: Sendable` exactly as in the contract block above.
3. `extension ContentItem { init(video: VideoItem) }` — `type: .video`, `category: nil`, `description: nil`, `uploadedDaysAgo: nil`, `viewCount: nil` (reconciliation note 5, with that comment), `channelTitle: video.channelName`, `subscribers`/`videoCount`/`itemCount` nil.
4. `struct LivePlaylistQueueSource: PlaylistQueueSource` wrapping `BrowseClient` — ten lines of pass-through and `map(ContentItem.init(video:))`. **Untested by design**, same rule B1 applies to `LiveStreamResolver`: everything decidable is in `PlayerQueue`, everything here is a network client's own behaviour. Say so in its doc comment.

- [ ] **Step 4: Run the tests, watch them pass**

```bash
ios/scripts/test.sh
```

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/App/Route.swift \
        ios/FitrahTube/Features/Player/PlayerQueue.swift \
        ios/FitrahTubeTests/PlayerQueueTests.swift
git commit -m "[FEAT]: iOS player queue machine and launch args"
```

---

### Task 2: The queue in the player — Up Next, auto-advance, auto-skip, prefetch

**Why this is second:** it is the feature. Everything is decidable without AVFoundation except two wiring points (the end-of-item notification and the item swap), and both are one-liners on machinery `PlayerHostView.Coordinator` already owns. The UI is `VideoRow` / `VideoGridCell`, unmodified.

**Files:**
- Modify: `ios/FitrahTube/Features/Player/PlayerViewModel.swift` (`args` mutable, the queue, `loadQueue`, `playToEnd`, `advance`, `play(id:)`, `prefetchUpcoming`, `pageIfNeeded`, `currentTime`; and the two-word `RateLimitedResolver` fix)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (Up Next section; **every `args` inside `stateView` becomes `model.args`**; the queue source and fixture hook)
- Modify: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (the end-of-item observer / `PlayToEndAction.advance`; `continuesCurrentVideo` + `resumeFallback` on `player(for:replacing:)`; `model.currentTime` written from the existing periodic observer)
- Modify: `ios/FitrahTube/Features/Player/PlayerState.swift` (`case queueEnded` + its `Equatable` arm — reconciliation note 9)
- Modify: `ios/FitrahTube/Features/Player/PlayerStateView.swift` (`PlayerStateCopy.map`'s `.queueEnded` arm)
- Modify: `ios/FitrahTube/DesignSystem/Components.swift` (`VideoGridCell.subtitle` — reconciliation note 5's consequence paragraph)
- Modify: `ios/scripts/convert-strings.py` (`EXTRA_KEYS` — one addition, `player_queue_ended`)
- Modify: `ios/FitrahTube/Resources/Localizable.xcstrings` (**regenerated by the script, never hand-edited**)
- Modify: `ios/FitrahTube/App/AppContainer.swift` (nothing new to construct — `container.innerTube.browse` already exists; add only what the screen needs to reach it, if anything)
- Test: `ios/FitrahTubeTests/PlayerQueueTests.swift` (extend — the pure skip policy), `ios/FitrahTubeTests/PlayerViewModelQueueTests.swift` (create), `ios/FitrahTubeTests/PlayerStateViewTests.swift` (extend — the `.queueEnded` copy), `ios/FitrahTubeTests/PlayerHostTests.swift` (extend), `ios/FitrahTubeTests/Support/PlayerTestDoubles.swift` (extend — a recording fake resolver and a fake queue source)

**Interfaces:**
- Consumes: `PlayerQueue`, `PlaylistQueueSource` (Task 1); `PlayerViewModel.safeMode` (B3, CF-B3-2); `StreamResolving.resolve(_:purpose:kind:sourceChannelId:forceRefresh:)`; `ExtractionRateLimiter` via `RateLimitedResolver`.
- Produces:
  - `PlayerViewModel.queue: PlayerQueue`, `.currentTime: TimeInterval`, `.args` as `private(set) var`.
  - `PlayerViewModel.playToEnd()`, `.advance()`, `.play(id:)`, `.prefetchUpcoming()`.
  - `StreamState.queueEnded` + its `PlayerStateCopy.map` arm + its `Equatable` arm (reconciliation note 9).
  - One new localized key: `player_queue_ended`.
  - `VideoGridCell(item:subtitle:onTap:)` — `subtitle` defaulted `nil`, so no existing call site changes.
  - `AutoSkipPolicy.decide(consecutive:limit:) -> Bool` (pure).
  - `PlayerHostView.player(for:replacing:audioOnly:continuesCurrentVideo:resumeFallback:)` — both new parameters defaulted, so `applyPolicyAction`'s call site is unchanged.
  - `PlayerHostView.shouldPreservePosition(previous:next:) -> Bool` (pure, `static`, testable).

- [ ] **Step 1: Write the failing tests**

Extend `PlayerQueueTests.swift` with the skip policy:

```swift
@Test func autoSkipStopsAfterThreeConsecutiveFailures() {
    // `PlayerViewModel.kt:1352-1357`, MAX_CONSECUTIVE_SKIPS = 3 (`:1787`). The 4th failure
    // must NOT skip -- it shows the real error state (spec §10 "auto-skip unplayable max 3").
    #expect(AutoSkipPolicy.decide(consecutive: 0, limit: 3))
    #expect(AutoSkipPolicy.decide(consecutive: 2, limit: 3))
    #expect(AutoSkipPolicy.decide(consecutive: 3, limit: 3) == false)
}
```

Create `ios/FitrahTubeTests/PlayerViewModelQueueTests.swift`. The doubles: a `RecordingResolver` that scripts an outcome per videoId and records every `(videoId, purpose, kind, forceRefresh)` tuple, and a `FakeQueueSource` that serves scripted pages and can be told to throw.

```swift
@Test @MainActor func endOfItemAdvancesToTheNextQueuedVideo() async {
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"],
                       safeMode: false)
    await vm.open()
    await vm.playToEnd()
    #expect(vm.args.videoId == "b")
    #expect(vm.state.isPlayable)
    #expect(vm.args.playlistId == "PL")   // the queue context survives the advance
}

@Test @MainActor func safeModeDisablesAutoAdvanceButNotTheQueue() async {
    // Ruling 58 + spec §10 "auto-advance on end UNLESS Safe Mode" + plan §6.10. The queue is
    // still populated and a TAP still plays -- only the automatic hop is gone.
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"],
                       safeMode: true)
    await vm.open()
    await vm.playToEnd()
    #expect(vm.args.videoId == "a")                    // did not advance
    #expect(vm.queue.upcoming.map(\.id) == ["b"])      // queue intact -- ruling 33 shows it
    await vm.play(id: "b")
    #expect(vm.args.videoId == "b")                    // manual tap is unaffected
}

@Test @MainActor func safeModeReadsTheViewModelPropertyNotTheStoreDirectly() async {
    // CF-B3-2: one Safe Mode reader in the player. Flipping the store mid-session must take
    // effect on the NEXT end-of-item, because `safeMode` is a live computed read.
    let settings = FakeSettingsStore(safeMode: true)
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"],
                       settings: settings)
    await vm.open()
    await vm.playToEnd()
    #expect(vm.args.videoId == "a")
    settings.safeMode = false
    await vm.playToEnd()
    #expect(vm.args.videoId == "b")
}

@Test @MainActor func endOfItemWithAnEmptyQueueStopsInsteadOfLooping() async {
    // `PlayerViewModel.kt:1920-1923`: no queue, no more pages -> playback stops. On iOS that must
    // NOT be `.idle` (reconciliation note 9: `.idle` renders as an eternal "Loading..." spinner
    // with no Retry -- `PlayerStateView.swift:28-32`), so the terminus is its own state with its
    // own copy. This test fails on the naive Android port, which is the point of it.
    let vm = makeModel(args: .init(videoId: "a"), queue: ["a"], safeMode: false)
    await vm.open()
    await vm.playToEnd()
    #expect(vm.args.videoId == "a")
    #expect(vm.state == .queueEnded)
    #expect(vm.state != .idle)
}

@Test func theQueueEndedStateHasRealCopyAndNoRetry() {
    // The half a state-machine test cannot see. No Retry: there is nothing to retry -- the queue
    // is finished, and Back (or a remaining Up Next row) is the exit. Announced, because a
    // playlist ending is exactly spec 6.6's "Transitions" case. Offline must not hijack it:
    // a finished queue is finished with or without a network.
    for online in [true, false] {
        let copy = PlayerStateCopy.map(.queueEnded, isOnline: online)
        #expect(copy.message == String(localized: "player_queue_ended"))
        #expect(copy.showsRetry == false)
        #expect(copy.announces)
    }
}

@Test func queueEndedIsEquatableToItself() {
    // `StreamState.==` has a `default: return false` arm -- without an explicit tuple case the
    // assertion above would silently fail forever (reconciliation note 9's enumeration table).
    #expect(StreamState.queueEnded == StreamState.queueEnded)
    #expect(StreamState.queueEnded != StreamState.idle)
}

@Test @MainActor func autoSkipWalksPastUnplayableItemsAndStopsAfterThree() async {
    // `PlayerViewModel.kt:1349-1366`. Four dead items in a row: skip 1, 2, 3, then STOP on the
    // 4th with the real terminal state so the user sees something instead of a silent walk.
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                       queue: ["a", "x1", "x2", "x3", "x4", "z"],
                       unplayable: ["x1", "x2", "x3", "x4"], safeMode: false)
    await vm.open()
    await vm.playToEnd()
    #expect(vm.args.videoId == "x4")
    #expect(vm.state == .contentUnavailable)   // ruling 14's one terminal surface
}

@Test @MainActor func aSuccessfulAdvanceResetsTheSkipCounter() async {
    // `PlayerViewModel.kt:1916`: consecutive, not cumulative.
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                       queue: ["a", "x1", "b", "x2", "x3", "x4", "c"],
                       unplayable: ["x1", "x2", "x3", "x4"], safeMode: false)
    await vm.open()
    await vm.playToEnd()
    #expect(vm.args.videoId == "b")            // one skip, counter back to 0
    await vm.playToEnd()
    #expect(vm.args.videoId == "x4")           // three more skips, then stop
}

@Test @MainActor func autoAdvanceNeverForcesARefresh() async {
    // CF-B2-2, rule (b), verbatim: "auto-advance must NEVER pass forceRefresh: true -- the next
    // video's manifest cache entry is the whole point of prefetching it."
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"],
                       safeMode: false)
    await vm.open()
    await vm.prefetchUpcoming()
    await vm.playToEnd()
    #expect(vm.resolver.calls.filter { $0.forceRefresh }.isEmpty)
}

@Test @MainActor func prefetchResolvesTheNextTwoOnThePrefetchLane() async {
    // Ruling 16 + CF-B2-2 + reconciliation note 8.
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                       queue: ["a", "b", "c", "d", "e"], safeMode: false)
    await vm.open()
    await vm.prefetchUpcoming()
    let pre = vm.resolver.calls.filter { $0.kind == .prefetch }
    #expect(pre.map(\.videoId) == ["b", "c"])
    #expect(pre.allSatisfy { $0.purpose == .prefetch && $0.forceRefresh == false })
}

@Test @MainActor func aPrefetchRefusalIsSkippedSilently() async {
    // CF-B2-2, rule (a): never surfaced as .cooldown, never retried into, never blocks.
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b", "c"],
                       cooldown: ["b"], safeMode: false)
    await vm.open()
    let before = vm.state
    await vm.prefetchUpcoming()
    #expect(vm.state == before)                                  // state untouched
    #expect(vm.resolver.calls.filter { $0.videoId == "b" }.count == 1)   // not retried
    await vm.playToEnd()
    #expect(vm.args.videoId == "b")                              // and it did not block
}

@Test @MainActor func pagingFetchesTheNextPageAtFiveRemainingAndLatchesOnFailure() async {
    let source = FakeQueueSource(pages: [(ids: ["a","b","c","d","e","f"], next: "P2"),
                                         (ids: ["g","h"], next: nil)])
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"), source: source,
                       safeMode: false)
    await vm.open()
    #expect(vm.queue.upcoming.count == 5)
    await vm.playToEnd()                     // now 4 remaining -> needsPage fired at 5
    #expect(vm.queue.items.count == 8)
    #expect(source.pageCalls == 2)
}

@Test @MainActor func aFailedPageStopsPagingAndPlaybackEndsCleanly() async {
    let source = FakeQueueSource(pages: [(ids: ["a", "b"], next: "P2")], failFrom: 1)
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"), source: source,
                       safeMode: false)
    await vm.open()
    await vm.playToEnd()                     // -> b
    await vm.playToEnd()                     // queue empty, page failed -> stop
    #expect(vm.state == .idle)
    #expect(vm.queue.hasMorePages == false)
}

@Test @MainActor func theDeepStartScanIsBoundedAndFallsBackToTheIndexHint() async {
    // `PlayerViewModel.kt:904-1031`, bounds `:1782-1785` (250 items / 3 s).
    let source = FakeQueueSource(pages: (0..<40).map {
        (ids: (0..<10).map { i in "v\($0 * 10 + i)" }, next: $0 == 39 ? nil : "P\($0)")
    })
    let vm = makeModel(args: .init(videoId: "v0", playlistId: "PL", startIndex: 3,
                                   targetVideoId: "v390"),   // past the 250-item bound
                       source: source, safeMode: false)
    await vm.open()
    #expect(vm.queue.items.count <= 250)
    #expect(vm.queue.current?.id == "v3")     // the startIndex hint
}

@Test @MainActor func advanceResetsTheHoistedPositionAndKeepsItAcrossASameVideoRetry() async {
    // CF-B1-8: currentTime is session-only (ruling 32), hoisted so a host rebuild can restore it.
    let vm = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"],
                       safeMode: false)
    await vm.open()
    vm.currentTime = 42
    await vm.retry()
    #expect(vm.currentTime == 42)             // same video: position survives
    await vm.playToEnd()
    #expect(vm.currentTime == 0)              // new video: starts at the beginning
}
```

Extend `PlayerHostTests.swift`:

```swift
@Test func positionIsPreservedOnlyWhileTheVideoIsTheSameOne() {
    // The host seeks a replacement item back to the outgoing item's time. That is right for a
    // re-resolve or an audio-only swap and WRONG for an auto-advance -- it would start the next
    // video at the previous one's position.
    #expect(PlayerHostView.shouldPreservePosition(previous: "a", next: "a"))
    #expect(PlayerHostView.shouldPreservePosition(previous: nil, next: "a"))  // first build
    #expect(PlayerHostView.shouldPreservePosition(previous: "a", next: "b") == false)
}
```

Extend the rate-limiter coverage (wherever `RateLimitedResolver` is already tested):

```swift
@Test func theLimiterGatesThePrefetchLaneEvenWithoutForceRefresh() async throws {
    // Reconciliation note 7: a prefetch is the non-forced resolve most likely to hit the network,
    // and it is the one lane the limiter exists to keep behind the interactive one.
    let limited = RateLimitedResolver(wrapping: AlwaysOKResolver(), rateLimiter: limiter,
                                      clock: clock)
    _ = try await limited.resolve("v", purpose: .prefetch, kind: .prefetch,
                                  sourceChannelId: nil, forceRefresh: false)
    await #expect(throws: ExtractionError.self) {
        _ = try await limited.resolve("v", purpose: .prefetch, kind: .prefetch,
                                      sourceChannelId: nil, forceRefresh: false)
    }
}
```

- [ ] **Step 2: Run the tests, watch them fail**

```bash
ios/scripts/test.sh
```
Expected: compile failure — `PlayerViewModel.queue`, `playToEnd`, `advance`, `play(id:)`, `prefetchUpcoming`, `currentTime`, `AutoSkipPolicy` and `PlayerHostView.shouldPreservePosition` do not exist, and `args` is a `let`.

- [ ] **Step 3: Implement**

**`RateLimitedResolver`** — the two-word fix (reconciliation note 7). In `resolve`, change the guard to `if forceRefresh || kind == .prefetch {` and extend the existing comment to say why the prefetch lane is gated despite being unforced.

**`PlayerViewModel`** — the queue:

```swift
    /// Ruling 33 / spec §10. Empty in single-video mode; populated from `args.playlistId`.
    private(set) var queue = PlayerQueue()

    /// CF-B1-8's hoist. Session-only (ruling 32 -- nothing persists this). Three consumers:
    /// `PlayerHostView` reads it to restore position after a state hop dismantled the host,
    /// Task 3's double-tap seek reads it as the seek origin, and `advance()` resets it to 0.
    /// Written by the host's existing 1 s periodic observer, so it is ~1 s coarse -- which is
    /// why the host still prefers a LIVE `AVPlayer.currentTime()` when it has one, and falls
    /// back to this only when there is no live player left to ask.
    var currentTime: TimeInterval = 0

    private let queueSource: (any PlaylistQueueSource)?
    private var consecutiveSkips = 0
```

`init` gains `queueSource: (any PlaylistQueueSource)? = nil`. `args` becomes `private(set) var args: PlayerArgs`.

`open()` becomes: resolve the current video first (unchanged), **then** `await loadQueue()`, **then** `await prefetchUpcoming()`. Sequential on purpose — Android defers its prefetch to the first `isPlaying` (`PlayerFragment.kt:1167-1168`) to keep it off the critical path; awaiting the player's own resolve first achieves the same thing with no observer and no timer. `ponytail:` note that ceiling in the code.

`loadQueue()` — guard `args.playlistId` non-nil and `queueSource` non-nil, else return with an empty queue. Fetch page 1; then, while `args.targetVideoId` is set and not yet found and `items.count < 250` and `Date() < deadline` (start + 3 s), fetch the next page. Build with `PlayerQueue.start(items:targetVideoId:startIndex:shuffled:cursor:)`. Any throw → empty queue, no state change (a failed queue load must never kill a playing video).

`playToEnd()` — the end-of-item hook:

```swift
    /// `AVPlayerItemDidPlayToEndTime` (`PlayerFragment.kt:1247-1249` -> `PlayerViewModel.kt:389`).
    /// Ruling 58 / spec §10 / plan §6.10: Safe Mode disables AUTO-advance only. `safeMode` is the
    /// VM property, never `SettingsStore` (CF-B3-2) -- one Safe Mode reader in the player.
    func playToEnd() async {
        guard !safeMode, queue.hasNext else { return }
        await advance()
    }
```

`advance()`:

```swift
    private func advance() async {
        await pageIfNeeded()
        guard let next = queue.advance() else {
            // `PlayerViewModel.kt:1920-1923`: no next item and no more pages -- playback stops.
            // NOT `.idle` (reconciliation note 9): that is the pre-open value and renders as a
            // Retry-less "Loading..." spinner forever. A finished playlist is a real terminal
            // state with real copy.
            state = .queueEnded
            return
        }
        // The queue context rides along: playlistId/shuffled keep the queue alive across the hop,
        // targetVideoId/startIndex are consumed and must NOT be re-applied to the next video.
        var next = PlayerArgs(item: next)
        next.playlistId = args.playlistId
        next.shuffled = args.shuffled
        args = next
        currentTime = 0                      // a new video starts at the beginning
        // `showLoading: false` is LOAD-BEARING (reconciliation note 2): a `.loading` hop
        // dismantles PlayerHostView, detaches the audio session and kills background
        // auto-advance. NOT `silent:` -- a failed advance MUST surface, because that is what
        // drives auto-skip and, past the cap, the terminal state the user sees.
        // `forceRefresh: false` is CF-B2-2 rule (b): land on the warmed ManifestCache entry.
        await resolve(forceRefresh: false, kind: .player, showLoading: false)
        if state.isPlayable {
            consecutiveSkips = 0             // `PlayerViewModel.kt:1916`
            await prefetchUpcoming()
            await pageIfNeeded()
        } else if queue.hasNext, AutoSkipPolicy.decide(consecutive: consecutiveSkips, limit: 3) {
            consecutiveSkips += 1
            await advance()                  // bounded by the policy above; max depth 3
        }
        // else: leave the non-playable state on screen. Ruling 14's one terminal surface, with
        // PlayerStateView's Retry -- exactly what Android shows past MAX_CONSECUTIVE_SKIPS.
    }
```

`play(id:)` — the Up Next tap: `guard let item = queue.select(id: id)`, same `args` swap, `currentTime = 0`, `resolve(forceRefresh: false, kind: .player)` **with** the loading state (a tap is a deliberate user action; the loading card with the new thumbnail is the right feedback), then `prefetchUpcoming()` + `pageIfNeeded()`, and `consecutiveSkips = 0`.

`prefetchUpcoming()` — the whole thing:

```swift
    /// Ruling 16's prefetch lane, first and only call site in the app. Six lines because
    /// InnerTubeKit's ManifestCache already IS the prefetch cache (`StreamResolver.swift:73-74`):
    /// a non-forced resolve populates it, and the advance's own non-forced resolve reads it back.
    /// Android needs 70 lines here only because its extractor has no shared cache
    /// (`PlayerViewModel.kt:1703-1770`) -- do not port that dictionary, its TTL or its eviction.
    func prefetchUpcoming() async {
        for item in queue.streamPrefetchTargets {          // <=2, reconciliation note 8
            // CF-B2-2 rule (a): a refusal is skipped SILENTLY. `try?` is that rule -- never a
            // state write, never a retry, never a log line the user can reach.
            _ = try? await resolver.resolve(item.id, purpose: .prefetch, kind: .prefetch,
                                            sourceChannelId: item.channelTitle == nil ? nil : args.channelId,
                                            forceRefresh: false)
        }
    }
```

(Pass `sourceChannelId: nil` for queue items — the availability gate's channel hint belongs to the *launched* video, not to a playlist member from another channel. Keep it simple: `sourceChannelId: nil`.)

`pageIfNeeded()` — `guard queue.needsPage, let queueSource`; single-flight via an `isPaging` flag; on success `queue.append(page.items.map(...), cursor: page.continuation)`, on throw `queue.markPagingFailed()` (the latch, `PlayerViewModel.kt:1946-1978`).

**`StreamState.queueEnded`, `PlayerStateCopy` and the one new key.** Add the case to `PlayerState.swift` with the doc comment from reconciliation note 9, add `case (.queueEnded, .queueEnded): return true` to the existing `.idle`/`.loading`/`.contentUnavailable` tuple arm of `==`, and add the `PlayerStateCopy.map` arm:

```swift
        case .queueEnded:
            // Ruling 33's terminus. No Retry (nothing to retry -- the queue is finished; Back or a
            // remaining Up Next row is the exit), and it is deliberately OUTSIDE the offline gate
            // above: a finished playlist is finished whether or not there is a network.
            return Copy(message: String(localized: "player_queue_ended"), showsRetry: false, announces: true)
```

Then add exactly one entry to `EXTRA_KEYS` in `ios/scripts/convert-strings.py`, in the same commented shape the existing player entries use, and **regenerate** `Localizable.xcstrings` by running the script (never hand-edit it):

```python
    # player_queue_ended (B5 task 2): the terminal card when a playlist runs out. iOS-only --
    # Android's empty-queue terminus is `StreamState.Idle`, a silent stop with no copy at all
    # (`PlayerViewModel.kt:1920-1923`), so there is no source string to port. Deliberately says
    # what happened, never why, and never offers a next step we do not have.
    "player_queue_ended": {
        "en": "You've reached the end of the playlist",
        "ar": "لقد وصلت إلى نهاية قائمة التشغيل",
        "nl": "Je hebt het einde van de afspeellijst bereikt",
    },
```

Then walk reconciliation note 9's enumeration table and confirm each site. The only one that compiles while still being wrong is `Equatable`.

**`VideoGridCell.subtitle`** (reconciliation note 5's consequence paragraph). Mirror `VideoRow` exactly (`Components.swift:310-322,341-348`): a `let subtitle: String?` stored property, an `init(item:subtitle:onTap:)` with `subtitle` defaulted `nil`, and in the body `if let subtitle { Text(subtitle).font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(1) } else { Text(videoMeta(...)) }`. Do **not** touch `videoAccessibilityLabel` — it already handles nil counts, and `VideoRow` does not override it either. Every existing `VideoGridCell(item:onTap:)` call site is untouched because the parameter is defaulted.

`AutoSkipPolicy` — a four-line `nonisolated enum` next to `PlayerQueue` (Task 1's file) or beside `PlaybackRecovery`; one static `decide(consecutive:limit:) -> Bool { consecutive < limit }`. It is a named, tested constant, not an inline `< 3`.

**`PlayerHostView`**:

1. `player(for:replacing:audioOnly:continuesCurrentVideo:resumeFallback:)`. Inside, replace the unconditional `let resumeTime = existing.currentTime()` with:
   ```swift
   // A live player's own clock is authoritative when we have one; `resumeFallback` (the VM's
   // hoisted currentTime, CF-B1-8) covers the case where a non-playable state dismantled the
   // host and a fresh AVPlayer is being built. `continuesCurrentVideo == false` (an
   // auto-advance or an Up Next tap) means neither applies -- the next video starts at 0.
   let resume = continuesCurrentVideo ? (existing?.currentTime().seconds ?? resumeFallback) : 0
   ```
   and seek to `CMTime(seconds: resume, preferredTimescale: 600)` when `resume > 0`, in **both** the fresh-`AVPlayer` branch and the `replaceCurrentItem` branch. `wasPlaying` stays as-is for the replace branch; a freshly built player still `play()`s.
2. `static func shouldPreservePosition(previous: String?, next: String) -> Bool { previous == nil || previous == next }` — pure, tested.
3. `Coordinator` gains `private var lastVideoId: String?`. `makeUIViewController` / `updateUIViewController` compute `let continues = Self.shouldPreservePosition(previous: context.coordinator.lastVideoId, next: model.args.videoId)`, pass it plus `resumeFallback: model.currentTime`, then set `context.coordinator.lastVideoId = model.args.videoId`.
4. In the existing periodic time observer block (which already calls `self?.sample(time:)` and `self?.background.refreshNowPlaying()`), add `self?.model?.currentTime = time.seconds` — one line, the same observer, no second one.
5. The end-of-item observer. **If B4 has landed**, add `case advance` to `PlayToEndAction`, make `PlayerPresentation.standard.actionOnPlayToEnd` return `.advance`, and extend the existing `endObserver` arm:
   ```swift
   if playToEnd == .restart { … existing shorts loop … }
   else if playToEnd == .advance {
       endObserver = NotificationCenter.default.addObserver(
           forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak model] _ in
               MainActor.assumeIsolated { Task { await model?.playToEnd() } }
           }
   }
   ```
   **If B4 has not landed**, declare `enum PlayToEndAction: Sendable, Equatable { case advance, none }` in `PlayerHostView.swift`, add the `endObserver` field, this arm, and its teardown in `stopObserving()` (`if let endObserver { NotificationCenter.default.removeObserver(endObserver) }; endObserver = nil`) — per-item, like every other observer here, so a recovery `replaceCurrentItem` re-arms against the new item rather than firing for a dead one. **Missing the teardown is the classic bug**: every recovery swap adds another observer and one end-of-item fires N advances.

**`PlayerScreen`**:

1. **Every `args` inside `stateView` becomes `model.args`** — `PlayerToolbar(args:)`, `PlayerMetadataView(args:)`, `PlayerStateView(thumbnailURL:)`. *(Owner directive 2026-08-27: there is no `Self.openInYouTube(videoId:)` — that case is removed; drop it from this list, do not port it.)* The screen's own `let args` is now only the initial value passed into the view model.
2. The Up Next section, after `PlayerMetadataView`, inside the same `if verticalSizeClass != .compact` group is **wrong** — Up Next belongs below the toolbar and should be visible in the non-fullscreen landscape column too. Put it after the metadata guard, at the same level:
   ```swift
   // Ruling 33: the whole section, header included, is absent when there is nothing queued.
   // Android renders the header over nothing (`fragment_player.xml:608-625`, defect-adjacent).
   if !model.queue.upcoming.isEmpty {
       Text(String(localized: "player_up_next_header"))
           .font(TypeScale.sectionTitle).fontWeight(.bold)
           .foregroundStyle(Color.textPrimary)
           .padding(.horizontal, Spacing.md(widthClass)).padding(.top, Spacing.md(widthClass))
           .accessibilityIdentifier("player.upNext.header")
       upNextList(model)
   }
   ```
3. `upNextList` — one column on compact, **2 on regular/large** (`PlayerFragment.kt:895-908`), collapsing to one at `.accessibility1+` via the existing `Size.columns(2, dynamicTypeSize:)`:
   ```swift
   @ViewBuilder
   private func upNextList(_ model: PlayerViewModel) -> some View {
       let columns = Size.columns(widthClass == .compact ? 1 : 2, dynamicTypeSize: dynamicTypeSize)
       if columns == 1 {
           ForEach(Array(model.queue.upcoming), id: \.id) { item in
               VideoRow(item: item, subtitle: item.channelTitle) { Task { await model.play(id: item.id) } }
                   .accessibilityIdentifier("player.upNext.row.\(item.id)")
           }
       } else {
           LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Size.cardGap(widthClass)),
                                    count: columns), spacing: Size.cardGap(widthClass)) {
               ForEach(Array(model.queue.upcoming), id: \.id) { item in
                   VideoGridCell(item: item, subtitle: item.channelTitle) { Task { await model.play(id: item.id) } }
                       .accessibilityIdentifier("player.upNext.row.\(item.id)")
               }
           }
           .padding(.horizontal, Spacing.md(widthClass))
       }
   }
   ```
   **No new row component, no new formatter, no new accessibility label** — `VideoRow`/`VideoGridCell` already carry `videoAccessibilityLabel` and route duration and views through `Format`/`DurationChip` (ruling 37/48). `subtitle: item.channelTitle` is `VideoRow`'s existing override, the same one Favorites uses; `VideoGridCell` gains the identical parameter in this task (reconciliation note 5) because without it the queue cell renders a reserved, empty meta band.
4. Wire the queue source into the view model construction in `.task`: `queueSource: Self.queueSource(container: container)`, a `#if DEBUG` ladder in the same shape as `resolver(container:)`. Production: `LivePlaylistQueueSource(browse: container.innerTube.browse)`. Debug hooks: `-fitrah-fake-player-queue` → a `FixtureQueueSource` returning ~8 items (for the Up Next screenshots), and `-fitrah-fake-player-queue-dead` → the same list with three unplayable ids (for the auto-skip capture). Put the fixtures at the bottom of `PlayerScreen.swift` beside the existing `Fixture*Resolver` types, inside the same `#if DEBUG`.

- [ ] **Step 4: Run the tests, watch them pass**

```bash
ios/scripts/test.sh
```

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/PlayerViewModel.swift \
        ios/FitrahTube/Features/Player/PlayerScreen.swift \
        ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTube/Features/Player/PlayerQueue.swift \
        ios/FitrahTube/Features/Player/PlayerState.swift \
        ios/FitrahTube/Features/Player/PlayerStateView.swift \
        ios/FitrahTube/DesignSystem/Components.swift \
        ios/scripts/convert-strings.py \
        ios/FitrahTube/Resources/Localizable.xcstrings \
        ios/FitrahTubeTests/PlayerQueueTests.swift \
        ios/FitrahTubeTests/PlayerViewModelQueueTests.swift \
        ios/FitrahTubeTests/PlayerStateViewTests.swift \
        ios/FitrahTubeTests/PlayerHostTests.swift \
        ios/FitrahTubeTests/Support/PlayerTestDoubles.swift
git commit -m "[FEAT]: iOS player up next queue and auto advance"
```

---

### Task 3: Fullscreen and the gesture overlay

**Why this is third:** it is independent of the queue and touches the same two files, so doing it after Task 2 avoids two implementers colliding in `PlayerScreen.swift`. It is also where CF-B2-10 and CF-B2-11 close.

**Files:**
- Create: `ios/FitrahTube/Features/Player/PlayerFullscreen.swift` (`PlayerFullscreen`, `PlayerGestures`)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (the one-tree layout switch, the exit control, the seek feedback, the zoom hint, `Router.isFullscreen`)
- Modify: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (the double-tap recognizer on `contentOverlayView`, the `videoGravity` override, the AVKit-fullscreen delegate pair, `model.videoIsPortrait`)
- Modify: `ios/FitrahTube/Features/Player/PlayerViewModel.swift` (`videoIsPortrait`, `videoZoomed`, `seekFeedback`, `avKitFullscreen`)
- Modify: `ios/FitrahTubeUITests/ScreenshotTests.swift` (CF-B2-11's real bound in `testPlayerB1Task10IPad`)
- Test: `ios/FitrahTubeTests/PlayerFullscreenTests.swift` (create)

**Interfaces:**
- Consumes: `Router.isFullscreen` (`Router.swift:33-34`, declared and unwritten), `WidthClass`, `Size.playerMaxWidth`, `BannerMessage`/`.transientBanner` (`Components.swift`), `model.currentTime` (Task 2).
- Produces:
  - `PlayerFullscreen.isActive(widthClass:deviceIsLandscape:videoIsPortrait:userExited:) -> Bool` (pure).
  - `PlayerGestures.Zone` (`.back`, `.centre`, `.forward`) + `PlayerGestures.zone(x:width:layoutDirection:) -> Zone` + `PlayerGestures.seek(from:zone:duration:step:) -> TimeInterval?` (pure).
  - `PlayerViewModel.videoIsPortrait`, `.videoZoomed`, `.seekFeedback`, `.avKitFullscreen`.

- [ ] **Step 1: Write the failing tests**

Create `ios/FitrahTubeTests/PlayerFullscreenTests.swift`:

```swift
@Test func iPhoneAutoFullscreensWhenTheDeviceAndTheVideoAgreeOnOrientation() {
    // Ruling 42 + spec §10: "9:16 sources fullscreen in portrait, else landscape; auto-enter
    // when opened in landscape on iPhone". The rule is `deviceIsLandscape != videoIsPortrait`.
    func active(_ landscape: Bool, _ portraitVideo: Bool) -> Bool {
        PlayerFullscreen.isActive(widthClass: .compact, deviceIsLandscape: landscape,
                                  videoIsPortrait: portraitVideo, userExited: false)
    }
    #expect(active(true,  false))        // landscape device, landscape video -> yes
    #expect(active(false, true))         // portrait device,  9:16 video      -> yes
    #expect(active(true,  true) == false)   // landscape device, 9:16 video   -> no
    #expect(active(false, false) == false)  // portrait device, 16:9 video    -> no
}

@Test func iPadNeverAutoFullscreens() {
    // Ruling 42: "iPad by button only". The only iPad fullscreen is AVKit's own stock button,
    // which costs this app nothing -- so the pure rule must simply never fire on a regular width.
    for landscape in [true, false] {
        #expect(PlayerFullscreen.isActive(widthClass: .regular, deviceIsLandscape: landscape,
                                          videoIsPortrait: false, userExited: false) == false)
        #expect(PlayerFullscreen.isActive(widthClass: .large, deviceIsLandscape: landscape,
                                          videoIsPortrait: false, userExited: false) == false)
    }
}

@Test func aDeliberateExitSuppressesTheAutoEnter() {
    // `PlayerFragment.kt:719-731` (`userDismissedFullscreen`, consumed after exactly one
    // suppressed auto-enter). Reconciliation note 3: the latch is cleared by rotating back,
    // which is one `.onChange`, not Android's two orientation-unlock timers.
    #expect(PlayerFullscreen.isActive(widthClass: .compact, deviceIsLandscape: true,
                                      videoIsPortrait: false, userExited: true) == false)
}

@Test func gestureZonesAreThirdsOfTheActualViewWidthNotTheScreen() {
    // `PlayerGestureDetector.kt:43-47`: split-screen / multi-window safe.
    #expect(PlayerGestures.zone(x: 10,  width: 300) == .back)
    #expect(PlayerGestures.zone(x: 150, width: 300) == .centre)
    #expect(PlayerGestures.zone(x: 290, width: 300) == .forward)
    #expect(PlayerGestures.zone(x: 100, width: 300) == .back)     // boundary: < width/3
    #expect(PlayerGestures.zone(x: 200, width: 300) == .centre)   // boundary: <= 2*width/3
}

@Test func gestureZonesDoNotMirrorUnderRTL() {
    // Global Constraints: the zones are SPATIAL. AVKit mirrors its own scrubber under RTL, so
    // the leading third of the screen is still the earlier part of the timeline. Flipping the
    // zones would make the gesture disagree with the scrubber the user is looking at.
    #expect(PlayerGestures.zone(x: 10, width: 300, layoutDirection: .rightToLeft) == .back)
}

@Test func seekClampsAtZeroAndAtTheDuration() {
    // `PlayerGestureDetector.kt:66-79`: floor 0, cap duration, no-op when duration is unknown.
    #expect(PlayerGestures.seek(from: 3,  zone: .back,    duration: 100, step: 10) == 0)
    #expect(PlayerGestures.seek(from: 40, zone: .back,    duration: 100, step: 10) == 30)
    #expect(PlayerGestures.seek(from: 95, zone: .forward, duration: 100, step: 10) == 100)
    #expect(PlayerGestures.seek(from: 40, zone: .forward, duration: 0,   step: 10) == nil)
    #expect(PlayerGestures.seek(from: 40, zone: .centre,  duration: 100, step: 10) == nil)
}
```

- [ ] **Step 2: Run the tests, watch them fail**

```bash
ios/scripts/test.sh
```
Expected: compile failure — `PlayerFullscreen` and `PlayerGestures` do not exist.

- [ ] **Step 3: Implement**

**`PlayerFullscreen.swift`** — two pure `nonisolated enum`s. `isActive` is `widthClass == .compact && !userExited && (deviceIsLandscape != videoIsPortrait)`, with ruling 42 and the four-row table in its doc comment. `PlayerGestures.zone` computes `width / 3` / `width * 2 / 3` from the **passed** width (never `UIScreen`), takes `layoutDirection` only to document that it is deliberately ignored, and `seek` returns `nil` for `.centre` and for `duration <= 0`.

**`PlayerViewModel`** — four session-only properties, all reset appropriately:

```swift
    /// Set from `AVPlayerItem.presentationSize` (host, below). Defaults FALSE: until an item is
    /// ready the size is `.zero`, and "unknown" must read as landscape or a 16:9 video would
    /// briefly fullscreen itself in portrait on open.
    var videoIsPortrait = false
    /// The centre-double-tap fill/fit override, sticky per stream (`PlayerFragment.kt:3525-3547`).
    /// Reset in `advance()`/`play(id:)` alongside `currentTime` -- "per stream", not per session.
    var videoZoomed = false
    /// Transient ±10 s feedback for the gesture overlay; cleared ~600 ms after it is set.
    var seekFeedback: SeekFeedback?
    /// True while AVKit owns the screen with its OWN fullscreen presentation (the iPad path,
    /// ruling 42). Our gesture recognizer is disabled while it is true, because AVKit's fullscreen
    /// already has its own double-tap gravity toggle -- two would double-fire.
    var avKitFullscreen = false
```

`SeekFeedback` is a two-field `Equatable` struct (`zone`, `seconds`).

**`PlayerScreen`** — the layout, in **one tree**:

```swift
    @Environment(\.router) private var router
    @State private var userExitedFullscreen = false
    @State private var zoomHint: BannerMessage?
```

Compute once inside `stateView`:
```swift
    let fullscreen = PlayerFullscreen.isActive(
        widthClass: widthClass, deviceIsLandscape: verticalSizeClass == .compact,
        videoIsPortrait: model.videoIsPortrait, userExited: userExitedFullscreen)
```

Then, inside the existing `ScrollView { VStack { ZStack(alignment: .topTrailing) { PlayerHostView(...) ... } ... } }`, change **modifiers only**:

- the video `ZStack`: `.aspectRatio(fullscreen ? nil : 16.0 / 9.0, contentMode: .fit)`, `.frame(maxWidth: .infinity, maxHeight: fullscreen ? .infinity : nil)`, `.ignoresSafeArea(edges: fullscreen ? .all : [])`, and `.accessibilityIdentifier("player.videoBox")` (CF-B2-11's anchor).
- the content column's `.frame(maxWidth: Size.playerMaxWidth(widthClass))` becomes `.frame(maxWidth: fullscreen ? nil : Size.playerMaxWidth(widthClass))`.
- `PlayerToolbar`, `PlayerMetadataView` and the Up Next section are wrapped in `if !fullscreen { … }` (the metadata keeps its existing `verticalSizeClass != .compact` guard inside that).
- the `ScrollView` gets `.scrollDisabled(fullscreen)`.
- the outermost `Group` gets `.statusBarHidden(fullscreen)`, `.toolbar(fullscreen ? .hidden : .visible, for: .navigationBar)`, and:
  ```swift
  // Ruling 42/CF-B2-10: the shell already reads this and hides the tab bar (compact) and the
  // navigation rail (regular) -- `Router.swift:33-34`, `MainShellView.swift:58,62`. B5 is the
  // plan its doc comment was waiting for. `avKitFullscreen` is folded in so the iPad's stock
  // AVKit fullscreen also clears the rail.
  .onChange(of: fullscreen || model.avKitFullscreen, initial: true) { _, isFS in
      router.isFullscreen = isFS
  }
  .onDisappear { router.isFullscreen = false }   // never leak fullscreen past the screen
  // Reconciliation note 3: rotating out of the fullscreen orientation re-arms the auto-enter.
  .onChange(of: verticalSizeClass) { _, new in if new != .compact { userExitedFullscreen = false } }
  ```
  **The `onDisappear` reset is not optional.** Android restores system UI unconditionally in `onDestroyView` for exactly this reason (`PlayerFragment.kt:822-829`); without it, popping the player while fullscreen leaves the app with no tab bar.

**The fullscreen overlay.** The video `ZStack` is already `alignment: .topTrailing` and already has a `VStack(alignment: .trailing, spacing: 8)` in that corner holding the quality menu / rung-2 pill / audio-language / captions / audio-only controls (`PlayerScreen.swift:79-119`), and **that VStack is NOT hidden in fullscreen** — those controls stay available, which is correct (a landscape viewer still needs the quality cap and the captions toggle). So the exit control must not be dropped into the same corner as a sibling overlay or the two will overlap.

- **the exit control is the FIRST item inside that existing trailing `VStack`**, gated `if fullscreen`, above `qualityMenu`/`rung2Pill`. It stacks in the same 8 pt rhythm, inherits the same `.padding()`, and never collides — do **not** add a second `.overlay` or a second aligned child to the `ZStack` for it. A `Button` with `Image(systemName: "arrow.down.right.and.arrow.up.left")`, `.frame(minWidth: 44, minHeight: 44)`, on the same `.black.opacity(0.55)` circle chrome `statusPill`/`qualityMenu` already use, `.accessibilityIdentifier("player.fullscreenExit")`, `.accessibilityLabel(String(localized: "player_action_fullscreen"))`. Action: `userExitedFullscreen = true`. **Ruling 45's first step.** It does not force an orientation (reconciliation note 3).
- the seek feedback, which does **not** go in that VStack: it is a **separate** overlay on the video `ZStack`, `.allowsHitTesting(false)`, `.accessibilityHidden(true)`, `.accessibilityIdentifier("player.seekFeedback")`: `Image(systemName: model.seekFeedback?.zone == .back ? "gobackward.10" : "goforward.10")` in the matching third, `.transition(.opacity)` — and **no animation under Reduce Motion** (`@Environment(\.accessibilityReduceMotion)`; an instant show/hide, not a fade). No new string: the SF Symbols carry the "10" and mirror themselves under RTL.
- the one-time zoom hint: on the first transition into fullscreen, if `@AppStorage("fullscreen_zoom_hint_shown")` is false, set it true and `zoomHint = BannerMessage(...)` with `String(localized: "player_fullscreen_zoom_hint")` through the existing `.transientBanner(_:)` modifier (`PlayerFragment.kt:3473-3484`; same flag name Android uses). Reuse the banner — do not write a snackbar.

**`PlayerHostView`** — three additions:

1. `videoGravity`. In `makeUIViewController`/`updateUIViewController`, set `controller.videoGravity = model.videoZoomed ? .resizeAspectFill : .resizeAspect`. If B4 has landed, that reads `presentation.videoGravity` when `videoZoomed` is false, so the Shorts surface is unaffected: `controller.videoGravity = model.videoZoomed ? .resizeAspectFill : presentation.videoGravity`.
2. `model.videoIsPortrait`, from the existing periodic observer block: `if let size = self?.observedItem?.presentationSize, size.width > 0, size.height > 0 { self?.model?.videoIsPortrait = size.height > size.width }`. One line beside the `currentTime` write Task 2 added — the same observer, still no second one.
3. The double-tap recognizer. In `makeUIViewController`, after the controller exists:
   ```swift
   // Reconciliation note 1 / spec §10 "implemented as an overlay on the content view".
   // `contentOverlayView` is AVKit's own documented surface for custom content above the video.
   // The delegate's `shouldRecognizeSimultaneouslyWith` returning true is what keeps plan §6.5's
   // rule true: AVKit's single tap still toggles its controls, because our recognizer neither
   // requires its failure nor blocks it. A double tap therefore ALSO flashes the controls once;
   // that is accepted (Android does the same) and is NOT worth reaching into
   // `controller.view.gestureRecognizers` to suppress.
   let doubleTap = UITapGestureRecognizer(target: context.coordinator,
                                          action: #selector(Coordinator.handleDoubleTap(_:)))
   doubleTap.numberOfTapsRequired = 2
   doubleTap.delegate = context.coordinator
   controller.contentOverlayView?.addGestureRecognizer(doubleTap)
   context.coordinator.doubleTap = doubleTap
   ```
   The handler (`@objc`, on the `Coordinator`, already `@MainActor`): read `recognizer.location(in: view)` and `view.bounds.width`, call `PlayerGestures.zone`, then:
   - `.centre` — only act when the caller is fullscreen; otherwise **return without consuming** (Android's "no dead zone", `PlayerGestureDetector.kt:58-62`). Toggle `model.videoZoomed` and show the `player_resize_mode_zoom` / `player_resize_mode_fit` banner.
   - `.back` / `.forward` — `PlayerGestures.seek(from: player.currentTime().seconds, zone:, duration: item.duration.seconds, step: 10)`; `nil` → return; else `player.seek(to:)`, write `model.currentTime`, and set `model.seekFeedback` (cleared by a 600 ms `Task`).
   Whether the screen is fullscreen reaches the coordinator through one stored `var isFullscreen` written from `updateUIViewController` — do not re-derive size classes inside the coordinator.
   `updateUIViewController` sets `doubleTap.isEnabled = !model.avKitFullscreen`.
   Tear the recognizer down in `dismantleUIViewController` alongside `stopObserving()`.
4. The AVKit-fullscreen delegate pair on the `Coordinator`, writing `model.avKitFullscreen`:
   ```swift
   func playerViewController(_ c: AVPlayerViewController,
       willBeginFullScreenPresentationWithAnimationCoordinator co: UIViewControllerTransitionCoordinator) {
       model?.avKitFullscreen = true      // ruling 42's iPad path; also disables our recognizer
   }
   func playerViewController(_ c: AVPlayerViewController,
       willEndFullScreenPresentationWithAnimationCoordinator co: UIViewControllerTransitionCoordinator) {
       model?.avKitFullscreen = false
   }
   ```

**`ScreenshotTests.testPlayerB1Task10IPad`** — CF-B2-11. Replace the vacuous existence check with a real bound:

```swift
    // CF-B2-11: the old assertion proved nothing -- it only checked the title existed, which it
    // does at any width. The constraint under test is Size.playerMaxWidth (1600 pt on `.large`),
    // so assert the video box's own frame against it AND against the window, which is what a
    // regression (an unconstrained full-width column) would actually break.
    let box = app.otherElements["player.videoBox"]
    XCTAssertTrue(box.waitForExistence(timeout: 10), "ipad: player.videoBox never appeared")
    XCTAssertLessThanOrEqual(box.frame.width, 1600, "ipad: player column exceeds playerMaxWidth")
    XCTAssertLessThan(box.frame.width, app.windows.firstMatch.frame.width,
                      "ipad landscape: the player column must be narrower than the window")
```

- [ ] **Step 4: Run the tests, watch them pass, then verify the one thing tests cannot**

```bash
ios/scripts/test.sh
```

Then, by hand, on the iPhone 17 simulator with `-fitrah-fake-player-hls`: **confirm `contentOverlayView` receives the double tap** (a temporary `print` in the handler, or the seek feedback appearing) **and that a single tap still toggles AVKit's controls.** If `contentOverlayView` is inert on this SDK, move the recognizer to `controller.view` with the same delegate — nothing else changes. Do **not** fall back to a SwiftUI gesture (reconciliation note 1). Remove the `print` before committing.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/PlayerFullscreen.swift \
        ios/FitrahTube/Features/Player/PlayerScreen.swift \
        ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTube/Features/Player/PlayerViewModel.swift \
        ios/FitrahTubeUITests/ScreenshotTests.swift \
        ios/FitrahTubeTests/PlayerFullscreenTests.swift
git commit -m "[FEAT]: iOS player fullscreen and gesture overlay"
```

---

### Task 4: Acceptance pass — simulator matrix, then live YouTube, then the device checklist

**Files:** `ios/scripts/screenshots.sh` (append the permanent B5 block); otherwise touch only what a finding requires. Screenshots under `.superpowers/sdd/2026-08-27-ios-phase2b5-fullscreen-queue/screenshots/b5-task4/`.

- [ ] **Step 1: Add the permanent screenshot block, then run the simulator matrix (do this yourself)**

Append a block to `screenshots.sh` in the same shape as the B1/B2/B3/B4 blocks (its own `OUT` variable, its own single `-only-testing:` line per case, `simctl shutdown` after). **Remember the device argument does not scope it** — that is why each block names its own destination.

Then check, on iPhone 17 and iPad Pro 13-inch (M5), en and ar:

  - **Fullscreen, iPhone.** Portrait → normal column. Rotate to landscape → video is full-bleed, **no tab bar**, no status bar, no navigation bar, no toolbar, no metadata, no Up Next. Rotate back → everything returns, and the tab bar is really back (not just off-screen). Pop the player *while fullscreen* → the tab bar returns (the `onDisappear` reset).
  - **Ruling 45, two-step back.** In fullscreen, attempt an edge-swipe back: it must **not** pop. Tap `player.fullscreenExit` → normal landscape column, navigation bar back. Then back → pops. **If the edge-swipe does pop while the bar is hidden, add `.navigationBarBackButtonHidden(true)` and re-check** — this is the one behaviour in the plan that depends on an SDK detail rather than our own code.
  - **The latch.** Exit fullscreen in landscape; it stays exited while landscape. Rotate to portrait and back to landscape → fullscreen again (reconciliation note 3).
  - **Fullscreen, iPad.** Rotating an iPad **never** auto-fullscreens (ruling 42). AVKit's own stock fullscreen button does, and while it is up the navigation rail is gone (`router.isFullscreen` via `avKitFullscreen`); leaving it restores the rail.
  - **9:16 in portrait.** With a 9:16 fixture, the *portrait* iPhone layout goes fullscreen and landscape does not — the inverse of the 16:9 case.
  - **Gestures.** Double-tap the left third → −10 s with the `gobackward.10` flash; right third → +10 s; centre in fullscreen → fill/fit toggles with the "Fill screen"/"Fit to screen" banner; centre **outside** fullscreen → nothing happens and the single-tap controls still toggle. Single tap anywhere still shows/hides AVKit's chrome. With **Reduce Motion** on, the flash is instant.
  - **Zoom hint.** First ever fullscreen shows "Double-tap to toggle fit/zoom" once; the second fullscreen does not. Delete the app and confirm it shows again.
  - **Up Next.** With `-fitrah-fake-player-queue`: header + rows below the metadata; **1 column on iPhone, 2 on iPad** (`PlayerFragment.kt:895-908`); tapping a row plays it and the row leaves the list; the row that just played does not reappear. With a single-video launch (no `playlistId`), **no header and no section at all** (ruling 33).
  - **Safe Mode.** Default (ON): reach the end of the fixture clip — playback stops, the queue is still listed, and tapping a row still plays. With `-safe_mode NO`: the same end-of-clip auto-advances (ruling 58).
  - **Auto-skip.** With `-fitrah-fake-player-queue-dead`: the player walks past exactly three dead items and then stops on `PlayerStateView` with Retry — not four, not silently forever.
  - **Queue ended** (reconciliation note 9, the state this plan added). Play a two-item fixture queue to the end: the screen shows "You've reached the end of the playlist", **no Retry button**, no spinner, and Back works. VoiceOver announces it. Capture it in en and ar (the ar copy is right-to-left and must not clip). Then background the app from that state and confirm the **lock screen is empty** — a finished playlist must not leave the last video's Now Playing entry standing (`NowPlayingSnapshot.make` returns nil).
  - **Short → player rotation.** Navigate into a portrait-locked Short (B4), come back, then open a regular video and rotate to landscape: fullscreen must still engage. This is the one place B4's `OrientationLock` and B5's fullscreen can interact — if `ShortsScreen.onDisappear`'s `OrientationLock.release()` did not run, the mask is still `.portrait` and the device never reaches landscape at all, so the bug looks like "B5 fullscreen is broken" when it is B4's lock leaking. **Skip this check only if B4 has not landed**, and say so in the commit message.
  - **RTL (ar).** The Up Next rows mirror; the fullscreen exit control sits on the **trailing** edge as designed; the gesture zones do **not** mirror (left third still seeks backward — check it against AVKit's own mirrored scrubber, which is the reference per plan §6.11).
  - **Dynamic Type `.accessibility3`.** The Up Next grid collapses to one column on iPad; the header does not truncate; rows do not clip.
  - **VoiceOver.** The Up Next header, each row (title + duration + channel via the existing `videoAccessibilityLabel`), and the fullscreen exit control ("Toggle fullscreen") are reachable and correctly ordered. The seek-feedback layer is **absent** from the accessibility tree.
  - **Tap targets** measured ≥44×44 pt with Accessibility Inspector, not eyeballed.

- [ ] **Step 2: Live-YouTube checks (do these yourself, on the simulator, with network)**

Use a real approved playlist id. Record each result in the commit message; do not "fix" behaviour that is correct.

  1. Open a video from a real playlist → Up Next lists real siblings with real titles, channels and durations, and **no view count** (reconciliation note 5 — confirm this reads acceptably rather than broken).
  2. Let a real video end → auto-advance, and the next video starts **at 0**, not at the previous video's position (the `continuesCurrentVideo` half of Task 2). This is the single most likely regression in the plan.
  3. Deep start: launch with `targetVideoId` set to an item on page 3 → it starts there, within the 250-item / 3 s bound.
  4. Shuffle: launch with `shuffled: true` → the launched video plays first, the order differs across two launches, and **no paging occurs**.
  5. Paging: advance until 5 items remain → a new page arrives without a visible stall.
  6. **Prefetch, measured.** Advance and note the delay before the next video's first frame; then relaunch with prefetch disabled (comment out the call temporarily) and compare. If there is no measurable difference, say so in the commit message — it is a finding for the controller, not a reason to delete the lane.
  7. A playlist containing a genuinely unavailable video → one auto-skip, no error card, and the skip is invisible apart from the video changing.
  8. Background auto-advance: start a playlist, lock the screen, wait for the current video to end → the next one keeps playing and the lock screen updates (this is reconciliation note 2's claim, and the reason for `showLoading: false`).
  9. Rotate to landscape mid-playback on a real HLS stream → fullscreen with **no re-buffer** (the host was not rebuilt). Rotate back → still playing, still at position.
  10. **CF-B2-15**, the open question B5 is asked to answer: play a rung-2 / itag-18 stream (no itag 140) with **Background play ON**, background the app for 3 minutes, and measure the data pulled. Report the number. Fork B's default is to leave the behaviour and fix the Settings copy, but the controller should decide against a measurement.

- [ ] **Step 3: Fix anything steps 1–2 surface**, re-run `ios/scripts/test.sh`, commit `[FIX]: iOS B5 fullscreen and queue acceptance pass`.

- [ ] **Step 4: Record the device checklist — USER-BLOCKED, do not attempt**

The repo has no signing identity (`DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset; `CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]: NO` is the only reason simulator builds work). **B5 inherits B2's undone 27-item checklist and B4's 10-item one** — nothing below supersedes them. Report these to the controller as blocked, verbatim:

  1. **Real rotation** — the simulator's rotation is instantaneous and synthetic. On device, confirm entering/leaving fullscreen does not stutter, drop a frame, or re-buffer, and that a rotation *during* an auto-advance does not produce two item swaps.
  2. **Rotation lock ON** — with the device's rotation lock engaged, landscape fullscreen is unreachable. Confirm the AVKit stock fullscreen button still works so the feature is not lost entirely (and if it is, that is a finding, not an improvisation).
  3. **Double-tap on glass** — the ±10 s gesture against a real finger and a real 44 pt target, including near the notch/Dynamic Island and near the home indicator, where system edge gestures compete.
  4. **The single-tap flash** — confirm the accepted controls flash on double-tap (reconciliation note 1) is genuinely unobjectionable on hardware rather than only on a 60 Hz simulator.
  5. **Background auto-advance on device** — step 2 item 8 with a real lock screen, over 3+ consecutive videos, confirming the lock-screen metadata updates each time and audio does not gap.
  6. **Auto-advance while PiP is up** — the floating window must follow the queue, not freeze on the finished video.
  7. **Auto-advance during a phone call interruption** — a video that ends while interrupted must not advance into silence and then resume two videos later.
  8. **CF-B2-15 measured on device** (step 2 item 10 with real cellular).
  9. **Thermals** — ten minutes of continuous playlist playback with prefetch active produces no thermal notice.
  10. **VoiceOver on device** — the Up Next reading order is stable across an auto-advance (the list mutates under the cursor).

---

## Out of scope for B5 (later sub-plans, or deliberate deferrals)

- **The app-scoped player holder** (CF-B2-1, CF-B2-14, CF-B4-1, CF-B4-6) — fork A and reconciliation note 2. It is a standalone plan with its own ownership model, not a task inside this one.
- **The PlaylistDetail screen** — Plan C. B5 defines the launch contract and the `PlaylistQueueSource` protocol (Task 1); Plan C builds the screen and the call sites.
- **The playlist channel-name approval gate** (`PlayerViewModel.kt:889-902,1201-1206`: blank the channel name on every queue row until the playlist's parent channel is confirmed APPROVED, fail-closed, 10 s timeout). It needs a backend registry call B5 has no other reason to make, it is not in any ruling, and its failure mode is a queue full of nameless rows. **Plan C**, which already owns the registry surfaces (report parents, channel detail). Cost if it never lands: a playlist's rows may name a channel the catalog has not approved — the same exposure the playlist screen itself has.
- **Previous / history** (`previousItems`, capped at 100 — `PlayerViewModel.kt:182,393-411,2011-2016`). AVKit's stock transport has no Previous button to bind it to (spec §10's "prev/next" is Media3's chrome, not AVKit's), and the Up Next list only shows what is *ahead*. Building a history nobody can reach is scaffolding. Add it with a "Previously played" section if one is ever asked for.
- **A related-videos source.** There is none, on either platform: "**There is no related-videos source anywhere in the player**" (`player.md` §12.1), "Android has no recommendation source either" (plan §6.5). Up Next is the playlist or it is nothing.
- **Watch progress / resume across launches** — ruling 32. `currentTime` is session-only and nothing persists it.
- **Auto-advance out of the embed rung.** Rung 3's ENDED is B3's Replay/Back card, inside a navigation-locked `WKWebView`; the queue does not advance from it. If that is ever wanted, it is a message-bridge change in B3's code, not a queue change here.
- **Prev/next on the lock screen and remote commands.** `MPRemoteCommandCenter`'s next/previous track commands are wired in B2 as no-ops; binding them to the queue is a two-line follow-up but needs its own device verification (item 6 above) and is not on the B5 line.
- **Android's `AspectPolicy` crop heuristic and its per-device flag** — reconciliation note 4.
- **Any orientation forcing, orientation locking, or `OrientationLock.mask` write** — reconciliation note 3.
- **New localized strings** — Global Constraints. B5 adds none.

## Carry-forward for Plan C / Phase 3

- **CF-B5-1:** `PlaylistQueueSource` and the `PlayerArgs` launch contract (Task 1) are Plan C's integration surface. Plan C must pass `targetVideoId` on a row tap (authoritative) and `startIndex` as a hint, unconditionally, without waiting for its own items to load.
- **CF-B5-2:** `LivePlaylistQueueSource` and Plan C's PlaylistDetail both page `BrowseClient.playlistItems` with **independent cursors**. That is deliberate (see Task 1's ownership paragraph), but it means opening a long playlist can cost two paging walks. If that shows up as real traffic, the fix is passing the first page through `PlayerArgs` — a new field, not a shared paging owner.
- **CF-B5-3:** the queue rows carry **no view count** by construction (reconciliation note 5). If the backend's `ContentItem` ever backs the queue instead of `BrowseClient.VideoItem`, the count arrives for free and `VideoRow` will start rendering it — which is a visual change to verify, not a regression.
- **CF-B5-4:** `RateLimitedResolver` now gates `kind == .prefetch` even without `forceRefresh` (reconciliation note 7). Any future non-forced lane that is *not* speculative must not be added to that condition, or it will be refused by the 30 s per-video minimum interval.
- **CF-B5-5:** `PlayerViewModel.args` is mutable. Every consumer must read `model.args`, never a captured copy — the same class of bug CF-B2-4 records for `model.state` in the background policy closure. Any new player sub-view must follow this rule.
- **CF-B5-6:** the end-of-item, stall, status and `currentTime`/`presentationSize` observers all hang off the same per-item `Coordinator.observe(…)` and are torn down in `stopObserving()`. Anything added there must be torn down there, or a recovery swap accumulates observers and a single end-of-item fires N advances.
- **CF-B5-7:** ruling 45's two-step back rests on hiding the navigation bar disabling the interactive pop gesture. If a future SDK re-enables the gesture under a hidden bar, fullscreen becomes single-step again — Task 4 step 1 is the check, and `.navigationBarBackButtonHidden(true)` is the belt.
- **CF-B5-8:** the double-tap recognizer lives on `AVPlayerViewController.contentOverlayView`. If a later plan puts a hit-testable SwiftUI overlay above the host, single taps stop reaching AVKit and the stock controls stop toggling. Any new player overlay must be `.allowsHitTesting(false)` unless it deliberately owns its region.
- **CF-B5-9:** CF-B2-15 is answered by measurement in Task 4 step 2 item 10, not by this plan's code. Whatever the number is, it is fork B's input.

---

## Forks for the controller (defaults are chosen; work proceeds unless overridden)

**A. The app-scoped player holder is deferred, not built.** Reconciliation note 2 in full: auto-advance, Up Next and background advance all work inside one route, so the holder buys B5 nothing, while costing an ownership rewrite that invalidates `PiPDismantlePolicy` and drags `ShortsScreen` with it. **Default: defer.** What stays broken, precisely: a back-navigation out of the player still closes a live PiP window (CF-B2-1), and the lock-screen scrubber still freezes for the life of a PiP window opened from a dismantled host (CF-B2-14). Both are pre-existing and neither is worsened by B5. Override = a separate plan, sequenced before or after B5 but never inside it — if it is ordered *before*, B5's Task 2 and Task 3 are unaffected except that `PlayerScreen`'s `@State` model becomes a borrowed one.

**B. CF-B2-15 (Background play ON + no itag 140 keeps pulling video) is documented, not changed.** The alternatives are muting the video track (AVKit still fetches the segments — no saving), pausing (silently contradicts a setting the user turned on), or accepting it. **Default: accept, measure it in Task 4 step 2 item 10, and fix the Settings row's description copy** so "Background play" says what it costs on streams with no separate audio track. Override = pause instead, which makes the Background-play setting a lie on rung 2 / itag 18 / live, and would need its own string.

**C. Fullscreen is a layout change on the same host, not `AVPlayerViewController`'s own fullscreen.** AVKit exposes **no public API to enter fullscreen** — only the delegate callbacks for when the user does it via the stock button — so ruling 42's "iPhone auto-fullscreens on landscape" is unreachable through AVKit and must be a layout change. Ruling 42's "iPad by button only" is then satisfied by AVKit's stock button at zero cost, which is why the two halves of the ruling land on two different mechanisms. **Default: implement as written** — one host, one tree, modifiers only. Override = a `.fullScreenCover`, which is a second view tree, a second `PlayerHostView` identity and a dropped `AVPlayer`; the Global Constraints forbid it and nothing in this plan works if it is chosen.

**D. B5 ships a real `PlaylistQueueSource` (over `BrowseClient.playlistItems`), rather than only the protocol.** Shipping only the protocol would mean the queue is always empty until Plan C lands, nothing in B5 is acceptance-testable against live YouTube, and Plan C would end up writing the player's paging as well as its own. **Default: ship `LivePlaylistQueueSource`** — ten lines over a client that already works (CF-C1 confirms `playlistItems` does). Override = B5 ships the protocol plus fixtures only, Task 4 step 2's items 1–7 all become blocked, and the whole feature stays unproven until Plan C.

**E. The double-tap gesture ships at all.** Plan §6.5 dropped it in v1 on the grounds that AVKit has its own transport, and AVKit's stock ±10 s buttons genuinely already exist. Spec §10 and the B5 decomposition line both name it, which is why it is here. **Default: ship it** — it is ~40 lines including the pure zone/seek functions, and the centre-double-tap fill/fit half has no AVKit equivalent in our inline (non-AVKit-fullscreen) layout, which is where the app spends its landscape time. Override = drop `PlayerGestures` and the recognizer entirely and keep only the fullscreen half of Task 3, which is a clean subtraction (the pure tests go with it).
