# InnerTubeKit Implementation Plan (iOS Phase 2, Plan A)

> **Owner directive 2026-08-27 (superseding):** the `openInYouTube` rung described below was REMOVED — the app never offers any hand-off to YouTube (RULINGS.md Q75). Historical text left as written.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `InnerTubeKit`, the pure-Swift local package that turns a YouTube videoId into a playable stream (and channel/playlist data into browse results), so the Phase 2 player and detail screens have an engine to consume — testable with `swift test`, no simulator.

**Architecture:** A local Swift package (`ios/Packages/InnerTubeKit`) with no app dependency. `actor StreamResolver` walks a RemoteConfig-ordered strategy ladder (`visionosHLS → androidItag18 → embed → openInYouTube`), each strategy a `POST youtubei/v1/player` with a client-family context whose `visitorData` is owned by `SessionStore`. A `ManifestCache` (memory, TTL) short-circuits repeats. `BrowseClient` issues WEB-context `browse` calls for channel/playlist pages. `RemoteConfig` is data-only JSON with a bundled default. All network goes through an injected `HTTPTransport` protocol so every test runs against recorded fixtures.

**Tech Stack:** Swift 6 (strict concurrency), Swift Testing, Foundation `URLSession`, no third-party dependencies. Package builds for iOS 18 and macOS 14 (so `swift test` runs on the CLI). Mirrors the existing `FitrahAPI` package conventions (`ios/Packages/FitrahAPI/Package.swift`).

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` §9 (InnerTubeKit contract), which delegates detail to `docs/architecture/ios-app-plan.md` §6.1–6.4, §6.7, §6.13. Rulings: `docs/superpowers/plans/2026-08-23-ios-phase2-research/RULINGS.md` (12–21, 25). Android source-of-truth behaviours: `docs/superpowers/plans/2026-08-23-ios-phase2-research/extraction.md` §3–§6, §8.5. Consumers (out of scope here): the player and detail plans (B, C).

## Global Constraints

- **No network in tests.** Every type that fetches takes an injected `HTTPTransport` (protocol: `func send(_ request: HTTPRequest) async throws -> HTTPResponse`). Tests inject a `RecordingTransport`/`FixtureTransport`. The one integration test that hits live YouTube is tagged `.tags(.live)` and excluded from the default `swift test` run.
- **Swift 6 strict concurrency, zero warnings.** `StreamResolver`/`SessionStore`/`ManifestCache` are `actor`s; value models are `Sendable` structs/enums; `HTTPTransport` is `Sendable`. No `@unchecked` without a one-line justification comment.
- **No Foundation `Date()` / `Date.now` for elapsed-time logic.** Cache TTLs and cooldowns use an injected `Clock` (`protocol MonotonicClock: Sendable { var now: Duration { get } }`, default backed by `ContinuousClock`) so tests advance time deterministically. Wall-clock (`expiresAt`) is a separate injected `WallClock` returning `Date`.
- **videoId validation** is `^[a-zA-Z0-9_-]{11}` (`NewPipeExtractorClient.kt:1045`); reject others before any work.
- **US-pin removed** (ruling 19): contexts use the device locale for `hl` and storefront for `gl`, en/US fallback on parse anomaly. Locale is injected, not read from `Locale.current` inside the engine.
- **Not ported** (spec §9, rulings 13/16/17): `WebViewPoTokenProvider`, `NsigSolver`, dub enumeration/resolution, `AndroidVrStreamResolver`, `YoutubeClientRotator` (the IOS client is dead for this app — ruling 12 uses VISIONOS as primary), the 4-lane priority system (two lanes only), and the scroll-attach predictive prefetch.
- **UA is load-bearing**: the segment-fetch `User-Agent` MUST match the client that minted the URLs, or googlevideo 403s (`StreamModels.kt:110-119`). `ResolvedStream` carries `userAgent`; consumers set it on media requests.
- Package layout, naming, and `Package.swift` shape mirror `ios/Packages/FitrahAPI`. Add the package to `ios/project.yml` as a dependency of the app target in the task that first needs app wiring (none in this plan — it is engine-only; app wiring is Plan B).

---

### Task 1: Live re-probe spike (ruling 18) — freeze the client table

**Files:**
- Create: `ios/Packages/InnerTubeKit/probes/probe-2026-08-23.md` (findings, committed)
- Reference: `docs/architecture/ios-app-plan.md` Appendix A.1 (`probe.py`), §6.13 (client table)

This task produces a **decision record, not code**. The client table in spec §6.13 and the stream findings in memory `ios-youtube-client-findings-2026-08` are dated empirical claims (2026-08-18/22). Before freezing them into `RemoteConfig`'s bundled default (Task 4), re-probe live YouTube.

- [ ] **Step 1: Run the probe harness.** Use `docs/architecture/ios-app-plan.md` Appendix A.1 `probe.py` (copy it to the scratchpad, do not commit the copy) against 5 catalog videoIds spanning the cases: one normal lecture, one `madeForKids` (`UNPLAYABLE` path), one age-gated, one live, one embed-only. For each, record per client family (VISIONOS, ANDROID, WEB): `playabilityStatus.status`+`reason`, whether `hlsManifestUrl` is present, whether a pot/GVS token was demanded, and (VISIONOS + ANDROID itag18) whether the first media segment returns 200 with the client's UA. Run from a residential line (§6.13 note: never a datacenter IP).

- [ ] **Step 2: Write the record.** Fill `probe-2026-08-23.md`: a table of the results, then an explicit "client table for the bundled default" section giving the exact `clients` block to bake into Task 4 (clientName/clientVersion/clientNameId/UA per family), and a "deltas from spec §6.13" list. If VISIONOS now demands a GVS token on HLS (the §6.12 trigger), STOP and record it as a blocker for the controller — the ladder's primary rung would be dead and the plan needs a ruling before proceeding. Otherwise state "spec §6.13 table confirmed" or the corrected values.

- [ ] **Step 3: Commit.**

```bash
git add ios/Packages/InnerTubeKit/probes/probe-2026-08-23.md
git commit -m "[DOCS]: InnerTubeKit live client-table probe"
```

*No test — this is a probe. Its output is consumed by Task 4.*

---

### Task 2: Package skeleton + HTTP transport seam

**Files:**
- Create: `ios/Packages/InnerTubeKit/Package.swift`
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/HTTPTransport.swift`
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/Clock.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/HTTPTransportTests.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Support/FixtureTransport.swift`
- Reference: `ios/Packages/FitrahAPI/Package.swift` (shape to mirror)

**Interfaces:**
- Produces: `protocol HTTPTransport: Sendable`, `struct HTTPRequest`, `struct HTTPResponse`, `protocol MonotonicClock: Sendable`, `protocol WallClock: Sendable`, `struct SystemClock` (both, real), `final class ManualClock` (tests). `FixtureTransport` (tests) maps a request matcher → recorded `HTTPResponse`.

- [ ] **Step 1: Write the failing test.**

```swift
import Testing
@testable import InnerTubeKit

@Suite struct HTTPTransportTests {
    @Test func fixtureTransportReturnsMatchedResponse() async throws {
        let t = FixtureTransport(routes: [
            .init(match: { $0.url.path.hasSuffix("/player") }, response: .init(status: 200, headers: [:], body: Data("{\"ok\":true}".utf8)))
        ])
        let resp = try await t.send(HTTPRequest(method: "POST", url: URL(string: "https://youtubei.googleapis.com/youtubei/v1/player")!, headers: [:], body: nil))
        #expect(resp.status == 200)
        #expect(String(decoding: resp.body, as: UTF8.self) == "{\"ok\":true}")
    }

    @Test func manualClockAdvances() {
        let c = ManualClock()
        #expect(c.now == .zero)
        c.advance(by: .seconds(30))
        #expect(c.now == .seconds(30))
    }
}
```

- [ ] **Step 2: Run it, watch it fail** (`cd ios/Packages/InnerTubeKit && swift test`) — types not defined.

- [ ] **Step 3: Implement.** `Package.swift` mirroring FitrahAPI (library product `InnerTubeKit`, platforms `.iOS(.v18), .macOS(.v14)`, a test target with `resources: [.copy("Fixtures")]`). `HTTPTransport.swift`:

```swift
public struct HTTPRequest: Sendable {
    public var method: String
    public var url: URL
    public var headers: [String: String]
    public var body: Data?
    public init(method: String, url: URL, headers: [String: String], body: Data?) { … }
}
public struct HTTPResponse: Sendable {
    public var status: Int
    public var headers: [String: String]
    public var body: Data
}
public protocol HTTPTransport: Sendable {
    func send(_ request: HTTPRequest) async throws -> HTTPResponse
}
```

`Clock.swift`: `MonotonicClock` (`var now: Duration`), `WallClock` (`var wallNow: Date`), `SystemClock` conforming to both (monotonic backed by a stored `ContinuousClock.Instant` baseline diff; wall by `Date()`), and `ManualClock` in the test support file with `advance(by:)`. Put `FixtureTransport` under `Tests/.../Support/`.

- [ ] **Step 4: Run the tests, green.**

- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit package skeleton and transport seam`.

---

### Task 3: Value models — `ResolvedStream`, tracks, errors

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/Models.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/ModelsTests.swift`
- Reference: `extraction.md` §4 (`ResolvedStreams` fields), spec §9 (`ResolvedStream` cases)

**Interfaces:**
- Produces: `enum ResolvedStream: Sendable` with cases `.hls(url:isLive:audioOnlyURL:captionTracks:)`, `.progressive(url:label:)`, `.embed(videoId:)`, `.openInYouTube(url:)`; each wrapped in a `struct Resolved { var stream; var client: ClientFamily; var userAgent: String; var resolvedAt: Date; var expiresAt: Date? }`. `enum ClientFamily { case visionos, android, web }`. `struct CaptionTrack { url; languageCode; languageName; isAutoGenerated }`. `enum ExtractionError: Error, Sendable` with `.invalidVideoId, .unavailable(videoId), .ageRestricted, .geoBlocked, .private, .removed, .liveOffline(startsAt: Date?), .botCheck, .allRungsFailed, .cancelled, .transport(underlying)`. `enum Purpose { case player, prefetch }` (two lanes, ruling 16).

- [ ] **Step 1: Write the failing test** asserting: a `.hls` `Resolved` with an `expiresAt` in the past reports `isExpired(now:)==true`; `ExtractionError` is `Equatable` for the terminal cases; `ClientFamily.userAgentIsRequired` is true for `.visionos`/`.android`, false for `.web`.

- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement** `Models.swift` — the enums/structs above, `isExpired(now: Date) -> Bool` on `Resolved`, and a `terminal: Bool` computed property on `ExtractionError` (true for ageRestricted/geoBlocked/private/removed/unavailable/liveOffline — these do NOT retry, ruling 14).
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit stream and error models`.

---

### Task 4: `RemoteConfig` — schema, bundled default, last-known-good

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/RemoteConfig.swift`
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/Resources/remote-config-default.json` (from Task 1's record)
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/RemoteConfigTests.swift`
- Reference: `ios-app-plan.md` §6.13 (schema + rules), Task 1's `probe-2026-08-23.md`

**Interfaces:**
- Produces: `struct RemoteConfig: Sendable, Codable { schemaVersion; minAppVersion; resolverOrder: [String]; manifestCacheSeconds; clients: [String: ClientContext] }`, `struct ClientContext: Sendable, Codable` (clientName, clientVersion, clientNameId, optional device/os fields, userAgent). `actor RemoteConfigStore` with `func current() -> RemoteConfig` (bundled default → last-known-good → fetched), `func refresh() async` (fetch via injected transport, validate, persist last-known-good to an injected `KeyValueStore`). `resolverOrder` entries not in a known set are dropped (spec §9).

- [ ] **Step 1: Write failing tests:** (a) decoding the bundled default JSON yields `resolverOrder == ["visionosHLS","androidItag18","embed","openInYouTube"]`; (b) a fetched config with an unknown strategy name (`"magic"`) drops it; (c) a fetched body > 64 KiB is rejected and `current()` still returns the last good; (d) a malformed fetch leaves `current()` at the bundled default; (e) `minAppVersion` comparison: `"1.0.0" < "1.0.10"` and `"1.2.0" > "1.10.0"` is FALSE (numeric segment compare, not lexicographic).
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement.** Write `remote-config-default.json` from Task 1's frozen table (verbatim `clients` block). `RemoteConfigStore` validates: ≤64 KiB, decodes, filters `resolverOrder` against the known set, then persists. Include a small `SemVer` comparator (numeric segments) used by the `minAppVersion` gate. `KeyValueStore` is an injected protocol (`UserDefaults`-backed in the app; in-memory in tests).
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit remote config with bundled default`.

---

### Task 5: `SessionStore` — visitorData per client family, cooldown

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/SessionStore.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/SessionStoreTests.swift`
- Reference: `ios-app-plan.md` §6.3, `extraction.md` §6.3 (`CooldownState`), §3.5 (visitorData retry)

**Interfaces:**
- Produces: `actor SessionStore` with `func visitorData(for: ClientFamily) -> String?`, `func setVisitorData(_:for:)` (persists to the injected `KeyValueStore`), `func rotate(_ family:) -> Bool` (returns false if rotated within the last 10 min — ruling: ≤1 rotation/10 min, `ios-app-plan.md:122`), `func recordBotCheck()`/`func recordSuccess()` driving a persisted `CooldownState`, `func cooldownRemaining(now:) -> Duration?`. Cooldown escalation 1 h → 4 h → 12 h → 24 h by trip count in 24 h; 7 clean days reset (`extraction.md` §6.3).

- [ ] **Step 1: Write failing tests:** (a) `rotate` twice within 10 min → second returns false (uses `ManualClock`); after advancing 10 min → true; (b) three bot-checks in 24 h escalate the cooldown to 12 h; (c) a success recorded 7 days after the last trip resets the trip count so the next trip is 1 h again; (d) visitorData is per-family (setting VISIONOS doesn't change WEB).
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement** with the injected `MonotonicClock`/`WallClock` and `KeyValueStore`. Persist the cooldown trip list (timestamps) and per-family visitorData.
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit session store and cooldown`.

---

### Task 6: `PlayerRequestBuilder` — byte-identical innertube contexts

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/PlayerRequestBuilder.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/PlayerRequestBuilderTests.swift`
- Reference: `ios-app-plan.md` §6.2 step 2, §6.3 (byte-identical contexts, headers), Appendix A.1 (payload shape)

**Interfaces:**
- Consumes: `ClientContext` (Task 4), `SessionStore` visitorData (Task 5).
- Produces: `struct PlayerRequestBuilder { func build(videoId:, family:, context: ClientContext, visitorData: String?, locale: InnerTubeLocale) -> HTTPRequest }` targeting `POST https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false`, JSON body `{ context: { client: {...}, ... }, videoId, contentCheckOk: true, racyCheckOk: true }`, headers: `Content-Type: application/json`, `User-Agent: <context.userAgent>`, `X-Goog-Visitor-Id: <visitorData>` (omitted when nil), `X-YouTube-Client-Name: <clientNameId>`, `X-YouTube-Client-Version: <clientVersion>`. `struct InnerTubeLocale { hl; gl }`.

- [ ] **Step 1: Write failing test:** build a VISIONOS request with a known context + visitorData and assert (a) the body's `context.client.clientName == "VISIONOS"`, `visitorData` appears both in the body and the `X-Goog-Visitor-Id` header (§6.3 requires both); (b) `hl`/`gl` come from the injected locale, NOT "US"; (c) two builds with the same inputs are byte-identical (`Data` equality) — the "byte-identical contexts" rule; (d) a WEB build omits the device/os fields VISIONOS carries.
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement.** Use a deterministic JSON encoder (`.sortedKeys`) so byte-identity holds. Encode only the fields the family's context provides.
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit player request builder`.

---

### Task 7: `PlayerResponseParser` — playabilityStatus + streaming data → tracks

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/PlayerResponseParser.swift`
- Create: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/player-*.json` (recorded from Task 1's probe: ok-hls, unplayable-kids, age-gated, live, botcheck, embed-only)
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/PlayerResponseParserTests.swift`
- Reference: `extraction.md` §4 (mapping rules), `ios-app-plan.md` §6.2 step 4 (status/reason branching)

**Interfaces:**
- Produces: `enum Playability { case ok(StreamingData), unplayableKids, ageGate, botCheck, liveOffline(startsAt: Date?), unavailable(reason: String) }`; `struct StreamingData { hlsManifestURL: URL?; itag18URL: URL?; itag140URL: URL?; expiresInSeconds: Int?; isLive: Bool; captionTracks: [CaptionTrack] }`. `func parse(_ body: Data) throws -> Playability`.

Branching (`ios-app-plan.md` step 4): `status == OK` → `.ok`; `LOGIN_REQUIRED`/`AGE_CHECK_REQUIRED` + age reason → `.ageGate`; `LOGIN_REQUIRED` + bot-check reason → `.botCheck`; `UNPLAYABLE` (kids) → `.unplayableKids`; `LIVE_STREAM_OFFLINE` → `.liveOffline`; else `.unavailable(reason)`. `expiresInSeconds` from `streamingData.expiresInSeconds`. itag18/140 URLs from `streamingData.formats[]` by itag. Caption tracks from `captions.playerCaptionsTracklistRenderer.captionTracks[]` with `&fmt=vtt`.

- [ ] **Step 1: Write failing tests, one per fixture:** ok-hls → `.ok` with a non-nil `hlsManifestURL` and an `expiresInSeconds`; unplayable-kids → `.unplayableKids` with an itag18 URL present; age-gated → `.ageGate`; live → `.ok(isLive: true)`; botcheck → `.botCheck`; a response missing `streamingData` entirely → `.unavailable`.
- [ ] **Step 2: Run, fail.** (Record the 6 fixture JSONs from Task 1's probe output — redact nothing structural; these are public unauthenticated responses.)
- [ ] **Step 3: Implement** the parser with `Codable` structs matching the innertube shape (only the fields used), tolerant of missing keys.
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit player response parser`.

---

### Task 8: `ManifestCache` — memory TTL, live-never-cached

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/ManifestCache.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/ManifestCacheTests.swift`
- Reference: spec §9 (`ManifestCache` TTL), `extraction.md` §3.2 (never cache live; forceRefresh keeps the old entry as fallback)

**Interfaces:**
- Produces: `actor ManifestCache { func get(_ videoId:, now:) -> Resolved?; func put(_ resolved:, videoId:, now:); func invalidate(_ videoId:); func flushAll() }`. TTL = `min(configSeconds, expiresAt − duration − 600 s, 3600 s)`. Live results (`isLive`) are never stored (`put` is a no-op for them). Capacity 50, LRU eviction.

- [ ] **Step 1: Write failing tests:** (a) put then get within TTL → hit; after advancing past TTL → miss; (b) a `.hls(isLive: true)` is never stored (get returns nil right after put); (c) LRU: 51 puts evict the least-recently-got; (d) `flushAll` empties it (the app calls this on `NWPathMonitor` change — that wiring is Plan B).
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement** with `ManualClock` support and an ordered dictionary for LRU.
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit manifest cache`.

---

### Task 9: `StreamResolver` actor — the ladder, single-flight, spacing

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/StreamResolver.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/StreamResolverTests.swift`
- Reference: spec §9 (resolve contract), `ios-app-plan.md` §6.2 (ladder), `extraction.md` §5.1 (single-flight), §6.1 (spacing)

**Interfaces:**
- Consumes: `HTTPTransport`, `RemoteConfigStore`, `SessionStore`, `ManifestCache`, `PlayerRequestBuilder`, `PlayerResponseParser`, `MonotonicClock`, an injected availability gate `protocol AvailabilityGate: Sendable { func verify(videoId:, sourceChannelId: String?) async throws -> Bool }` (implemented against FitrahAPI in Plan B; a stub that returns true in tests).
- Produces: `actor StreamResolver { func resolve(_ videoId:, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved }`.

Behaviour: cache check first (unless forceRefresh); one in-flight task per videoId (`[String: Task<Resolved, Error>]`), a second caller for the same id joins it; ≥500 ms spacing between `player` POSTs (enforced with the monotonic clock); walk `resolverOrder`:
- `visionosHLS`: build+send VISIONOS player; `.ok` with HLS → `Resolved(.hls(...))`; `.botCheck` → `SessionStore.rotate` (if allowed) and retry the SAME rung once, else next rung + `recordBotCheck`; `.ageGate` → jump straight to `openInYouTube` (never rotate); `.unplayableKids` → next rung; `.liveOffline` → throw `.liveOffline`; terminal `.unavailable` reasons → throw the matching terminal error (ruling 14).
- `androidItag18`: ANDROID player; itag18 URL → `.progressive(label: "360p")`.
- `embed` → `.embed(videoId)` (no network).
- `openInYouTube` → `.openInYouTube(watchURL)`.
- 8 s budget per rung; on success cache (non-live) + `SessionStore.recordSuccess()`; availability gate runs once as the first step (404 fail-open, 410 → `.unavailable`, transport error fail-open).

- [ ] **Step 1: Write failing tests** using `FixtureTransport`: (a) VISIONOS ok → `.hls`, and the manifest is cached (second resolve makes zero transport calls — assert via a call-counting transport); (b) VISIONOS `.unplayableKids` then ANDROID itag18 → `.progressive("360p")`; (c) `.ageGate` skips straight to `.openInYouTube` and never rotates the session (assert `SessionStore.visitorData` unchanged); (d) `.botCheck` rotates once and retries the same rung, succeeding on the retry; (e) two concurrent `resolve` calls for the same id issue ONE player POST (call count == 1); (f) an invalid videoId throws `.invalidVideoId` before any transport call; (g) 410 from the availability gate throws `.unavailable`.
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement.** Keep the ladder a straight loop over `resolverOrder`; spacing via a stored "last POST instant". Single-flight via a task dictionary keyed by videoId with a `defer` cleanup (the Phase 1 generation-token discipline applies — a superseded forceRefresh cancels the prior task).
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit stream resolver ladder`.

---

### Task 10: `ExtractionRateLimiter` — permit ledger (two kinds)

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/ExtractionRateLimiter.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/ExtractionRateLimiterTests.swift`
- Reference: `extraction.md` §6.1 (constants), ruling 16 (two lanes)

**Interfaces:**
- Produces: `actor ExtractionRateLimiter { func check(_ videoId:, kind: RequestKind, now:) -> Decision }`, `enum RequestKind { case player, autoRecovery, prefetch, proactiveTTLRefresh }`, `enum Decision { case allowed, delayed(Duration, reason: String), blocked(reason: String, retryAfter: Duration) }`. Constants from `extraction.md` §6.1: min interval same-video-same-kind 30 s; per-video window 5 min, max 3 attempts; global window 60 s, max 10 (player+prefetch share); autoRecovery reserved 2/window, bypasses global; proactiveTTLRefresh reserved 2/video/window, own 10/min ceiling; player exponential backoff 2→4→8→16→32 cap 60 s on consecutive attempts; prefetch blocked once attempts ≥ max−1.

- [ ] **Step 1: Write failing tests** for each constant boundary: 3rd attempt in 5 min blocked; prefetch blocked at attempts==2 while autoRecovery still allowed; global 11th player/prefetch in 60 s blocked but autoRecovery bypasses; backoff schedule after consecutive player attempts; `onSuccess(videoId)` clears the player backoff.
- [ ] **Step 2: Run, fail.**
- [ ] **Step 3: Implement** with the `ManualClock`; record the attempt BEFORE returning `.allowed` (so failures can't storm — `extraction.md` §6.1).
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit extraction rate limiter`.

---

### Task 11: `BrowseClient` — channel tabs + playlist items via WEB browse

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/BrowseClient.swift`
- Create: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/browse-*.json` (recorded)
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/BrowseClientTests.swift`
- Reference: spec §9 (`BrowseClient`), `ios-app-plan.md` §6.7, `channel-detail.md` (tab params, `VLUU…` uploads trick, `VL<playlistId>`)

**Interfaces:**
- Produces: `actor BrowseClient { func channelHeader(_ id:) async throws -> ChannelHeader; func channelVideos(_ id:, continuation: String?) async throws -> BrowsePage<VideoItem>; func channelTab(_ id:, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<...>; func playlistItems(_ playlistId:, continuation: String?) async throws -> BrowsePage<VideoItem> }`. `struct BrowsePage<T> { items: [T]; nextContinuation: String? }`. `enum ChannelTab { case live, shorts, playlists }`. Videos use the `VLUU…` uploads-playlist continuation (stable); playlists use `VL<playlistId>`; tabs use channel `params`.

- [ ] **Step 1: Write failing tests** against recorded fixtures: channelVideos first page yields N `VideoItem`s + a `nextContinuation`; feeding that continuation yields page 2; `playlistItems` parses title/uploader/count; a bot-checked browse response surfaces `BrowseError.botCheck` (consumers fall back to the Atom/approved-playlist degraded mode — that wiring is Plan C). Parsing only — no live calls.
- [ ] **Step 2: Run, fail.** Record fixtures for one channel (videos page 1 + continuation) and one playlist.
- [ ] **Step 3: Implement** the WEB-context browse request (reusing `PlayerRequestBuilder`'s context assembly, generalised, or a sibling `BrowseRequestBuilder`) and a renderer parser (`richItemRenderer`/`playlistVideoRenderer` → `VideoItem`; `continuationItemRenderer` → token).
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit browse client`.

---

### Task 12: `AtomFeedFetcher` — degraded-mode channel feed (conditional GET)

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/AtomFeedFetcher.swift`
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/AtomFeedFetcherTests.swift`
- Reference: spec §9 (`AtomFeedFetcher` ports `AtomChannelFeedFetcher.kt:12-60`), `ios-app-plan.md` §6.7 (degraded mode)

**Interfaces:**
- Produces: `actor AtomFeedFetcher { func latest(_ channelId:) async throws -> [VideoItem] }` hitting `https://www.youtube.com/feeds/videos.xml?channel_id=<id>` with conditional GET (stores ETag/Last-Modified in an injected `KeyValueStore`; a 304 returns the cached list). 15 newest, no bot check.

- [ ] **Step 1: Write failing tests:** parse a recorded Atom XML fixture → 15 `VideoItem`s (id, title, published); a 304 response returns the previously-parsed list; the request carries `If-None-Match` when an ETag is stored.
- [ ] **Step 2: Run, fail.** Record one Atom fixture.
- [ ] **Step 3: Implement** with `XMLParser` (Foundation) — no third-party XML.
- [ ] **Step 4: Run, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit atom feed fetcher`.

---

### Task 13: `URLSessionTransport` + package integration

**Files:**
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/URLSessionTransport.swift`
- Create: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/InnerTube.swift` (composition root)
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/LiveResolveTests.swift` (tagged `.live`, excluded by default)
- Reference: `ios-app-plan.md` §6.3 (ephemeral session, cookies disabled, 15 s timeout)

**Interfaces:**
- Produces: `struct URLSessionTransport: HTTPTransport` wrapping one ephemeral `URLSession` per client family (cookies disabled, `httpShouldSetCookies=false`, `timeoutIntervalForRequest=15`, `waitsForConnectivity=false`, per-family `httpAdditionalHeaders`). `struct InnerTube` — the composition root the app uses: `init(config:, keyValueStore:, availabilityGate:, locale:)` wiring `RemoteConfigStore`, `SessionStore`, `ManifestCache`, `StreamResolver`, `BrowseClient`; exposes `resolver`, `browse`, `remoteConfig`.

- [ ] **Step 1: Write the failing live test** (tagged `.tags(.live)`): resolve one known-good catalog videoId end-to-end through `URLSessionTransport` and assert a `.hls` result. It is skipped in the default `swift test` run (the default filter excludes `.live`); it is the manual "does the real ladder still work" check for the runbook.
- [ ] **Step 2: Run the default suite** (`swift test`) — the live test does not run; everything else stays green.
- [ ] **Step 3: Implement** `URLSessionTransport` and `InnerTube`. Verify the live test passes when explicitly selected (`swift test --filter LiveResolveTests`) from a residential line; record the result in the commit message but do NOT make CI depend on it.
- [ ] **Step 4: Run the default suite, green.**
- [ ] **Step 5: Commit** `[FEAT]: InnerTubeKit URLSession transport and composition root`.
