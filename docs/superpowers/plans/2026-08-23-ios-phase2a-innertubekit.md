# InnerTubeKit (iOS Phase 2A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The pure-Swift extraction engine for the iOS app: `videoId → ResolvedStream` through the remote-config-ordered ladder, plus InnerTube `browse` for channel/playlist pages — as a local Swift package testable with `swift test`, no simulator.

**Architecture:** Local package `ios/Packages/InnerTubeKit` (spec D14). An `actor StreamResolver` walks `visionosHLS → androidItag18 → embed → openInYouTube` (spec §9); `SessionStore` keeps one `visitorData` per client family; `BrowseClient` (WEB context) serves channel tabs and playlist items; `RemoteConfig` is data-only JSON with a bundled default. All network I/O goes through one injected transport so every test runs against recorded fixtures.

**Tech Stack:** Swift 6 (`swift-tools-version: 6.0`), Foundation + Network only (no third-party). Swift Testing. Fixtures recorded by Task 1's live probes.

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` §9 (binding), which incorporates `docs/architecture/ios-app-plan.md` §6.1–6.4, §6.7, §6.13 verbatim. Behaviour corpus: `docs/superpowers/plans/2026-08-23-ios-phase2-research/extraction.md` (+ `RULINGS.md` 12–21). Conflicts resolve spec-first, then rulings, then the corpus.

## Global Constraints

- Package dir `ios/Packages/InnerTubeKit`; products `InnerTubeKit` (dynamic, like FitrahAPI); zero dependencies beyond Foundation/Network; no `@MainActor` defaults (this is a `nonisolated` engine per spec §5).
- No third-party libraries. No NewPipe port — a from-scratch innertube client (ruling 12).
- Client table (bundled `RemoteConfig` default, `docs/architecture/ios-app-plan.md:218-239` verbatim): VISIONOS `clientVersion 1.02` / `clientNameId 101` / `RealityDevice17,1` / visionOS `26.5.23O471`; ANDROID `21.26.364` / id 3 / SDK 30; WEB `2.20250925.01.00` / id 1. These are DATA — Task 1's probe may update the JSON values, never the code.
- Session hygiene (plan §6.3): ephemeral `URLSessionConfiguration`, `httpCookieAcceptPolicy = .never`, `httpShouldSetCookies = false`, `timeoutIntervalForRequest = 15`, `waitsForConnectivity = false`, fixed per-client headers; `visitorData` sent as both `context.client.visitorData` and `X-Goog-Visitor-Id`; contexts byte-identical per call; one visitorData per client family, never mixed.
- Resolver invariants (spec §9): one in-flight task per videoId; superseded requests cancelled; ≥500 ms spacing between `player` POSTs; 300 ms settle debounce; 8 s budget per rung; visitorData rotation ≤1 per 10 min and never for an age gate; cooldown 1 h→24 h persisted, 7-day clean reset (`CooldownState.kt:24-34,64`).
- Timeouts in tests: never real network; all clock-driven logic takes an injected `now: () -> Date` / `ContinuousClock`; `Task.sleep` only behind an injected `Sleeper` so tests run in milliseconds.
- Tests: Swift Testing, `@Suite(.perTest)` where the shared `TestLimits.swift` pattern applies (copy the FitrahAPI arrangement). `swift test` must pass from `ios/Packages/InnerTubeKit`.
- Every task ends with ONE commit, `[FEAT]`/`[TEST]`/`[CHORE]` prefixes, ≤50-char subject.
- No new `.md` files outside this plan's fixtures note (CLAUDE.md rule); fixture provenance goes in a Swift comment header, not a README.
- Localization for extraction: device `hl`/storefront `gl`, en-US fallback (ruling 19). US pin is NOT ported.
- NOT in this package: WKWebView embed wrapper, AVPlayer, CaptionsProvider overlay UI, pot minter (branch-only, plan §6.12), dub pipeline (ruling 13), scroll-prefetch (ruling 17), 4-lane limiter (ruling 16 — two lanes only).

---

### Task 1: Live probe + fixture harvest (the ruling-18 spike)

**Files:**
- Create: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/` (JSON files, sanitized)
- Create: `ios/scripts/innertube-probe.py` (adapted from `docs/architecture/ios-app-plan.md` Appendix A.1 `probe.py`)

This task is evidence-gathering; it writes no library code.