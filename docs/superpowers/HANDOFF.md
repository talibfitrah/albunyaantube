# iOS Effort — Session Handoff (paused 2026-08-24 ~16:10)

Session paused by the user mid–Phase 2. Everything is committed on `feature/ios-app` at **`f102206b`**; the working tree is clean except one untracked file that is NOT ours (see "Do not touch"). Resume ONLY on the user's explicit "continue", then follow **Resume checklist** below in order.

---

## 1. Exact pause point

**Plan B1 (player core) — Tasks 1–8 of 10 complete and committed; paused between Task 8's commit and its review.**

Left deliberately unfinished at the pause (first items of the resume checklist):
1. **Task 8's screenshot was never captured** — the `b1-task8` case is wired into `ScreenshotTests.testPlayerMetadataAndToolbar` + `ios/scripts/screenshots.sh`, but the script wasn't run after the commit. Run it and eyeball the PNG.
2. **Task 8's review was never dispatched** — diff range `0fe8d81b..f102206b`. Review focus: link allow-list correctness, favorite optimistic/revert, `Format` usage (ruling 37), channel-not-category (ruling 39), the always-rendered "Show more" (ponytail-noted), and judge the implementer's choice of an **inert no-op Report button** (vs a "coming soon" banner) — an open judgment call.
3. **Tasks 9–10 not started** (details in §4).

---

## 2. What is DONE (verified, reviewed, committed)

### Phase 0 — scaffold (complete, gated)
XcodeGen project (iPhone+iPad, iOS 18 floor, Swift 6 `SWIFT_DEFAULT_ACTOR_ISOLATION=MainActor` app target), generated `FitrahAPI` package, `@MainActor AppContainer` DI with `@Environment(\.container)` + DEBUG fakes, design tokens, string catalog generated from Android (`ios/scripts/convert-strings.py`, 755+ keys), test runner `ios/scripts/test.sh` (300 s watchdog, both simulators + package + Release build). Passed the full 9-stage pipeline.

### Phase 1 — catalog UI (complete, gated at `070cc129`)
All 11 screens: splash (animated), onboarding, shell (bottom tabs compact / custom Android-parity rail ≥600 pt — NO `.sidebarAdaptable`), Home, Channels/Playlists/Videos, Search, Categories/Subcategories, Featured, Favorites (SwiftData tombstones), Settings (real settings, Android section order), About + 7-tap DeveloperDialog. en/ar/nl, RTL, Dynamic Type (single column at `.accessibility1+`), XCUITest screenshot rig (`ios/scripts/screenshots.sh`, NOT in the 300 s gate). Gate: 5 fix waves, Cubic r1–r3 (r2+r3 P0/P1-clean), gstack `/review` + `/cso`, Codex. CHANGELOG entry committed.

### Phase 2 Plan A — InnerTubeKit (complete, SHIPPED)
`ios/Packages/InnerTubeKit` (range `7440d186..af2cd2b5`): pure-Swift engine — `StreamResolver` ladder (visionosHLS → androidItag18 → embed → openInYouTube), `SessionStore` (visitorData per family + persisted escalating cooldown, ENFORCED), `ManifestCache` (TTL clamped to real URL expiry, live never cached), `PlayerRequestBuilder`/`PlayerResponseParser` (real fixtures), `RemoteConfig` (bundled default + last-known-good + sanitize), `ExtractionRateLimiter` (faithful Android port), `BrowseClient` (**lockupViewModel** — YouTube's current shape; docs' richItemRenderer is stale), `AtomFeedFetcher`, `URLSessionTransport` + `InnerTube` composition root. 86 tests; **live test passes** (`INNERTUBE_LIVE=1 swift test --filter LiveResolveTests` → real HLS).
Key discovery (docs corrected in `docs/architecture/ios-app-plan.md` §6.2/§6.3, commit `729aca54`): **the first tokenless call is always bot-checked and THAT response carries the visitorData to adopt** — "capture on first success" was the bug. Final whole-branch review → SHIP after Wave D (concurrency-safe POST spacing; cooldown enforcement).
Consumer contract: `docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md` (CF-B1..6, CF-C1..3).

### Phase 2 Plan B1 — player core, Tasks 1–8 (complete at `f102206b`)
| Task | Commit | What |
|---|---|---|
| 1 | `42376df1` | `BackendAvailabilityGate` (HEAD videos/channels; 404 fail-open, 410 block) + `InnerTube` in the container + remote-config refresh on launch/foreground (≥15 min). Also fixed InnerTubeKit `Package.swift` resource packaging (codesign-invalid nested bundle — swift-test-invisible). |
| 2 | `9f03e983` | `PlayerViewModel` + `StreamState` (idle/loading/ready/rung2Progressive/error/contentUnavailable/cooldown/recoveryExhausted); exhaustive resolver→state mapping; generation guard; `StreamResolving` protocol seam. |
| 3 | `145dda2a` | `PlayerHostView` (AVPlayerViewController) + `PlayerScreen` + route wiring; **real decoded frame proven** in the rig via a bundled AVAssetWriter fixture. |
| 4 | `1299842e`+fix `07b4fa16` | Quality ceiling (Auto/1080/720/480/DataSaver → `preferredMaximumResolution`+`preferredPeakBitRate`); fix round: **cap in PIXELS not points** (×displayScale) + native `…ForExpensiveNetworks` backstop. |
| 5 | `43aec06c` | Audio-language menu (`mediaSelectionGroup(.audible)`, "Original: X", sticky per session, hidden ≤1 option). |
| 6 | `2299a26e`+fix `05d0e3f8` | Captions: WebVTT parser + overlay + toggle; fix round: **`.filter(\.isAutoGenerated)`** (manual tracks ride AVKit's stock menu) + track-switch currentness guard. |
| 7 | `dc4562c8`+fix `0fe8d81b` | Recovery: pure `PlaybackRecovery` budgets (retries 3 / re-resolves 2, Android-cited resets) + pure `StallWatchdog` (fires ONLY while `timeControlStatus == .waitingToPlayAtSpecifiedRate`; 6 s VOD / 45 s live); rung-2 pill; event serialisation (`isRecovering`); KVO via publisher+main; `wasPlaying` preserved. **LOAD-BEARING: `.ready`/`.rung2Progressive` share ONE switch branch in PlayerScreen (host identity — splitting them rebuilds the host mid-demotion and drops position).** |
| 8 | `f102206b` | `PlayerMetadataView` (title/channel/views via `Format`, description expand + http/https-only link allow-list) + `PlayerToolbar` (Favorite optimistic+revert, ShareLink watch URL, Report inert placeholder, Download hidden). Review PENDING (§1). |

---

## 3. Where everything lives

- **Spec (binding):** `docs/superpowers/specs/2026-08-23-ios-app-design.md` (§9 InnerTube, §10 Player, §15 phases). Detail: `docs/architecture/ios-app-plan.md` §6.
- **Plans:** phase 