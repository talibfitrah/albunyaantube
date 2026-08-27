# Background Audio, Audio-Only, Now Playing and PiP Implementation Plan (iOS Phase 2, Plan B2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the B1 player leave the screen correctly — audio keeps playing in the background when the user asked for it, an audio-only mode that stops pulling video, a real lock-screen/Control-Centre Now Playing surface with working transport commands, PiP, and correct behaviour when a call or a headphone unplug interrupts — plus the four B1 carry-forwards that had to land in the same code paths.

**Architecture:** One pure decision table (`AudioSessionPolicy`) turns (setting, lifecycle event, PiP state) into an action; one `@MainActor` controller (`BackgroundPlaybackController`) owns the `AVAudioSession`, the notification observers, the `MPNowPlayingInfoCenter` dictionary and the `MPRemoteCommandCenter` handlers, and is instantiated and torn down by the existing `PlayerHostView.Coordinator` (the seam B1 already built for KVO/stall observers — no new lifecycle owner). Backgrounding pause is delegated to AVFoundation's own `audiovisualBackgroundPlaybackPolicy` rather than a hand-rolled pause, so PiP and AirPlay keep the exemptions the platform gives them. Audio-only is a URL swap through B1's existing `PlayerHostView.player(for:replacing:)` builder — a different URL means `replaceCurrentItem` + seek, which is already how B1 preserves position.

**Tech Stack:** Swift 6, SwiftUI, AVFoundation/AVKit, MediaPlayer, `@Observable`, Swift Testing; app target `ios/FitrahTube` + `ios/Packages/InnerTubeKit`. XcodeGen. Gate: `ios/scripts/test.sh` (300 s wall). Acceptance screenshots via `ios/scripts/screenshots.sh`.

**Spec:** `docs/superpowers/specs/2026-08-23-ios-app-design.md` §10 (Audio-only / Background play / PiP / Now Playing / AirPlay bullets). Detail: `docs/architecture/ios-app-plan.md` §6.5 ("Background audio", "PiP", "Now Playing / lock screen", "AirPlay"), §6.2 step 5 (pre-emptive foreground re-resolve), §6.6 (per-rung UI table). Behavioural source: `docs/superpowers/plans/2026-08-23-ios-phase2-research/player.md` §5 (audio-only), §6 (background playback / MediaSession / metadata), §7.4 (PiP), §15 (the dead-settings defects), §24 (task-removed). Predecessor: `docs/superpowers/plans/2026-08-24-ios-phase2b1-player-core.md`.

**Rulings this plan implements** (`docs/superpowers/plans/2026-08-23-ios-phase2-research/RULINGS.md`):

| # | Ruling | Where it lands |
|---|---|---|
| 29 | AirPlay via the stock AVKit transport is accepted in Phase 2; no session logic | Task 5 (note only — the route picker is already in B1's stock chrome; `allowsExternalPlayback` stays at its `true` default) |
| 32 | No watch-progress persistence; resume is session-only | Every swap in this plan carries `currentTime()` in memory and persists nothing |
| 34 | **The settings are REAL on iOS**: background-play OFF pauses on background; the settings audio-only value seeds the player toggle | Tasks 2 and 3 — this is the ruling the whole plan turns on |
| 35 | Playback speed lives in the stock overflow, per-session | Untouched (stock AVKit); the remote-command centre does not add a rate command |
| 43 | Platform-standard auto-PiP via AVKit, not Android's manual-only menu item | Task 5 (`canStartPictureInPictureAutomaticallyFromInline`) |
| 44 | Audio session `.playback`, **no** `mixWithOthers`; standard interruption handling — pause on interruption, resume after transient ones | Tasks 1 and 2 |
| 13 / 28 / 56 | Dub audio and every download affordance stay Phase 3 | Nothing in this plan references them |

**Carry-forwards this plan absorbs** (from the B1 final review, 2026-08-27, and `docs/superpowers/plans/2026-08-23-ios-phase2-research/PHASE2-CARRYFORWARDS.md`):

| Item | What it demands | Task |
|---|---|---|
| CF-B1-1 | B1's fix round adds `AVAudioSession.setCategory(.playback, mode: .moviePlayback)` and nothing else. B2 owns `setActive`, `UIBackgroundModes: audio`, interruption handling (resume on `.shouldResume`), route change (pause on `.oldDeviceUnavailable`) | 2 |
| CF-B1-2 | Wire `ExtractionRateLimiter.check(videoId:kind:)` into the resolve path; `.blocked`/`.delayed` map onto the existing `.cooldown(until:)` state | 6 |
| CF-B1-3 | Pre-emptive re-resolve on foreground when past `resolvedAt + expires − margin` (plan §6.2 step 5), position preserved, no state hop out of the playable branch | 6 |
| CF-B1-11 / CF-B2 | `InnerTube.init` pins `ManifestCache`'s TTL to the bundled default; give the cache a live TTL source | 1 |
| CF-B1-13 | Extract `FitrahTubeApp`'s refresh-spacing decision as a testable free function | 1 |
| M4 | `PlayerViewModel` stores `catalog`/`favorites`/`settings` and reads none. Read `settings` (audioOnly / backgroundPlay); delete the other two | 3 |
| CF-B6 | `Resolved` carries no `durationSeconds`. Now Playing therefore takes duration from `args.durationSeconds`, overridden by the `AVPlayerItem`'s own duration once it is finite — no InnerTubeKit change needed | 4 |

## Global Constraints

Implementers inherit nothing from earlier plans. All of the following are binding:

- **Swift 6, `SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor`** on the app and unit-test targets (`ios/project.yml`). `AVPlayer` / `AVPlayerItem` / `AVPlayerViewController` / `MPNowPlayingInfoCenter` are not `Sendable` — everything in the app target is main-actor-confined by default; do not add `nonisolated` to work around a warning. `StreamResolver` / `ExtractionRateLimiter` are nonisolated InnerTubeKit actors; `await` into them.
- **One implementer at a time.** `ios/DerivedData` is shared; two concurrent `xcodebuild` runs corrupt it. Never dispatch two B2 tasks in parallel.
- **Gate:** `ios/scripts/test.sh` from the repo root, 300 s wall-clock watchdog, 60 s per test. It runs `convert-strings.py --check` → `xcodegen generate` → `xcodebuild test` (iPhone 17 + iPad Pro 13-inch (M5)) → `swift test` (packages) → a Release build. A task is not done until this is green.
- **AVKit chrome is not XCUITest-accessible** on this toolchain (Xcode 26.3 / iOS 26.2): `AVPlayerViewController`'s stock transport exposes no separate accessibility elements. Every UI assertion must anchor on the app's own `player.*` accessibility identifiers (`player.qualityMenu.button`, `player.rung2Pill`, …). B2 adds `player.audioOnly.button` and `player.audioOnlyPill`.
- **`AVURLAssetHTTPUserAgentKey`, never `AVURLAssetHTTPHeaderFieldsKey`.** The generic header-dictionary key is gone from this SDK. Every asset built in this plan goes through the existing `PlayerHostView.asset(url:userAgent:)` / `assetOptions(userAgent:)` pair — including the audio-only asset, which must carry the same `Resolved.userAgent` or googlevideo 403s.
- **All user-visible strings go through `ios/scripts/convert-strings.py`.** Android-sourced keys are ported automatically; iOS-only keys go in that script's `EXTRA_KEYS` dict and the catalog is regenerated. **B2 needs no new keys** — `player_audio_only_label` ("Audio only"), `player_action_audio` ("Audio"), `player_status_audio_only` ("Playing audio-only stream.") and `player_default_title` are already in `ios/FitrahTube/Resources/Localizable.xcstrings` (verified). If a task finds it needs one anyway, add it to `EXTRA_KEYS` with en/ar/nl text and re-run the converter; never hand-edit the `.xcstrings`.
- **No new `.md` files.** This plan is the only document B2 creates. `docs/superpowers/HANDOFF.md`, `docs/superpowers/plans/2026-08-23-ios-phase2a-innertubekit.md` and `ios/` peer docs are untracked work owned by other agents — **never `git add` them**; stage only the exact files each task's commit step names.
- **`.ready` and `.rung2Progressive` share ONE `switch` branch in `PlayerScreen.stateView`** and this is load-bearing: two branches gave SwiftUI two view identities and dropped the live `AVPlayer` (and its `currentTime()`) on a mid-play demotion. Nothing in B2 may add a state case that leaves that branch mid-play — overlays and modifiers only.
- **The simulator cannot prove background audio.** It has no lock screen, no phone call, no route change and no real audio hardware. Each task states exactly what the simulator *can* assert; Task 7 collects everything that needs real hardware and marks it USER-BLOCKED (the repo has no signing identity / Team ID — `DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset, so no device install is possible until the user supplies one).

---

### Task 1: InnerTubeKit — real itag 140 URLs, a live manifest-cache TTL, and a testable refresh gate

**Why this is first:** `Resolved.hls` already carries an `audioOnlyURL`, but it is **always `nil` today**. `PlayerResponseParser.streamingData(from:)` reads itag 140 out of `streamingData.formats`, and itag 140 is an *adaptive* (audio-only m4a) format that YouTube returns in `streamingData.adaptiveFormats` — a container `Wire.StreamingData` does not even decode. Building the app-side audio-only mode on top of a permanently-nil field would ship a toggle that silently does nothing. This task also absorbs two carry-forwards that need no other B2 machinery: **CF-B1-11** (same package, same test suite) and **CF-B1-13** (a three-line extraction in `FitrahTubeApp`).

**Files:**
- Modify: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/PlayerResponseParser.swift` (the `Wire.StreamingData` struct and `streamingData(from:)`)
- Modify: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/ManifestCache.swift` (`init`, `ttlSeconds()`, `put`)
- Modify: `ios/Packages/InnerTubeKit/Sources/InnerTubeKit/InnerTube.swift` (drop the pinned-TTL `ponytail:` wiring)
- Modify: `ios/FitrahTube/App/FitrahTubeApp.swift` (CF-B1-13 — extract the refresh-spacing decision)
- Test: `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/PlayerResponseParserTests.swift`, `ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/ManifestCacheTests.swift` (both exist — add cases, do not create new files), `ios/FitrahTubeTests/AppContainerTests.swift` (extend)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `StreamingData.itag140URL` becomes genuinely populated: itag 140 is looked up in `adaptiveFormats` first, then `formats` (defensive — a client family that muxes it there still works). Unchanged type: `URL?`.
  - `ManifestCache.init(remoteConfig: RemoteConfigStore)` replaces `init(configTTLSeconds: Int)`. `public func put(_:videoId:now:)` becomes `async` (call sites already `await` across the actor boundary, so no caller changes).
  - `static func FitrahTubeApp.isRemoteConfigRefreshDue(now: Date, last: Date?, spacing: TimeInterval) -> Bool`.

- [ ] **Step 1: Write the failing tests**

In `PlayerResponseParserTests.swift`:

```swift
@Test func parsesItag140FromAdaptiveFormats() throws {
    let json = """
    {"playabilityStatus":{"status":"OK"},
     "streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/x.m3u8",
       "formats":[{"itag":18,"url":"https://r1.googlevideo.com/v18"}],
       "adaptiveFormats":[{"itag":140,"url":"https://r1.googlevideo.com/a140"},
                          {"itag":137,"url":"https://r1.googlevideo.com/v137"}]}}
    """.data(using: .utf8)!
    let parsed = try PlayerResponseParser().parse(json)
    guard case .ok(let streaming) = parsed.playability else { return #expect(Bool(false), "expected .ok") }
    #expect(streaming.itag140URL?.absoluteString == "https://r1.googlevideo.com/a140")
    #expect(streaming.itag18URL?.absoluteString == "https://r1.googlevideo.com/v18")
}

@Test func itag140IsNilWhenNoAudioOnlyFormatExists() throws {
    let json = """
    {"playabilityStatus":{"status":"OK"},
     "streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/x.m3u8",
       "adaptiveFormats":[{"itag":137,"url":"https://r1.googlevideo.com/v137"}]}}
    """.data(using: .utf8)!
    let parsed = try PlayerResponseParser().parse(json)
    guard case .ok(let streaming) = parsed.playability else { return #expect(Bool(false), "expected .ok") }
    #expect(streaming.itag140URL == nil)
}

@Test func itag140MustBeHTTPS() throws {
    let json = """
    {"playabilityStatus":{"status":"OK"},
     "streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/x.m3u8",
       "adaptiveFormats":[{"itag":140,"url":"http://r1.googlevideo.com/a140"}]}}
    """.data(using: .utf8)!
    let parsed = try PlayerResponseParser().parse(json)
    guard case .ok(let streaming) = parsed.playability else { return #expect(Bool(false), "expected .ok") }
    #expect(streaming.itag140URL == nil)  // httpsURL() drops non-https at the trust boundary
}
```

In `ManifestCacheTests.swift`:

```swift
@Test func ttlFollowsThePublishedRemoteConfigNotTheBundledDefault() async throws {
    // A RemoteConfigStore whose fetched config carries manifestCacheSeconds: 5.
    let store = RemoteConfigStore(transport: StubTransport(json: remoteConfigJSON(manifestCacheSeconds: 5)),
                                  keyValueStore: InMemoryKeyValueStore(),
                                  url: URL(string: "https://example.com/config.json")!)
    await store.refresh()
    let cache = ManifestCache(remoteConfig: store)
    let now = Date(timeIntervalSince1970: 1_000_000)
    let resolved = Resolved(stream: .hls(url: URL(string: "https://x/y.m3u8")!, isLive: false,
                                          audioOnlyURL: nil, captionTracks: []),
                            client: .visionos, userAgent: "UA", resolvedAt: now, expiresAt: nil)
    await cache.put(resolved, videoId: "abc", now: now)
    #expect(await cache.get("abc", now: now.addingTimeInterval(4)) != nil)
    #expect(await cache.get("abc", now: now.addingTimeInterval(6)) == nil)  // fails today: pinned to the bundled default
}
```

Reuse the stub transport / in-memory key-value store already used elsewhere in `InnerTubeKitTests`; if the helper names differ, use whatever those tests already use rather than adding new ones.

In `ios/FitrahTubeTests/AppContainerTests.swift` (CF-B1-13):

```swift
@Test func remoteConfigRefreshIsDueOnFirstCallAndThenOnlyAfterTheSpacing() {
    let now = Date(timeIntervalSince1970: 1_000_000)
    #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: nil, spacing: 900))
    #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: now.addingTimeInterval(-60), spacing: 900) == false)
    #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: now.addingTimeInterval(-901), spacing: 900))
}
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `cd ios/Packages/InnerTubeKit && swift test --filter 'PlayerResponseParserTests|ManifestCacheTests'`
Expected: `parsesItag140FromAdaptiveFormats` FAILS (`itag140URL` is nil — `adaptiveFormats` is not decoded) and `ttlFollowsThePublishedRemoteConfigNotTheBundledDefault` FAILS to compile (`init(remoteConfig:)` does not exist).

Then run: `ios/scripts/test.sh` — expected: compile failure, `isRemoteConfigRefreshDue` does not exist.

- [ ] **Step 3: Implement**

In `PlayerResponseParser.swift`, add `adaptiveFormats` to the wire struct and look there first:

```swift
struct StreamingData: Decodable {
    var expiresInSeconds: String?
    var hlsManifestUrl: String?
    var formats: [Format]?
    var adaptiveFormats: [Format]?
}
```

```swift
private func streamingData(from wire: Wire) -> StreamingData {
    let formats = wire.streamingData?.formats ?? []
    // itag 140 (audio-only m4a) is an ADAPTIVE format -- it is never in `formats`, which carries
    // only the muxed renditions (itag 18/22). Reading it from `formats` made `audioOnlyURL`
    // permanently nil. `formats` stays as a defensive second lookup.
    let adaptive = wire.streamingData?.adaptiveFormats ?? []
    return StreamingData(
        hlsManifestURL: Self.httpsURL(wire.streamingData?.hlsManifestUrl),
        itag18URL: url(forItag: 18, in: formats),
        itag140URL: url(forItag: 140, in: adaptive) ?? url(forItag: 140, in: formats),
        expiresInSeconds: wire.streamingData?.expiresInSeconds.flatMap(Int.init),
        isLive: wire.videoDetails?.isLive ?? wire.videoDetails?.isLiveContent ?? false,
        captionTracks: captionTracks(from: wire.captions)
    )
}
```

In `ManifestCache.swift`, swap the pinned `Int` for the live store:

```swift
private let remoteConfig: RemoteConfigStore

public init(remoteConfig: RemoteConfigStore) {
    self.remoteConfig = remoteConfig
}

public func put(_ resolved: Resolved, videoId: String, now: Date) async {
    guard !isLive(resolved) else { return }
    let expiresAt = min(now.addingTimeInterval(await ttlSeconds()), resolved.expiresAt ?? .distantFuture)
    ...unchanged...
}

private func ttlSeconds() async -> TimeInterval {
    // ponytail: `Resolved` carries no video duration yet, so spec §9's
    // expiresAt-minus-duration term is still not computable here.
    min(TimeInterval(await remoteConfig.current().manifestCacheSeconds), Self.maxTTLSeconds)
}
```

In `InnerTube.swift`, delete the `ponytail:` TTL comment and the local `manifestCache` binding's argument:

```swift
let manifestCache = ManifestCache(remoteConfig: remoteConfig)
```

In `FitrahTubeApp.swift` (CF-B1-13), pull the spacing decision out of the side-effecting method so it is testable without a running scene:

```swift
/// CF-B1-13: the spacing decision, extracted so it is testable without a running scene.
static func isRemoteConfigRefreshDue(now: Date, last: Date?, spacing: TimeInterval) -> Bool {
    guard let last else { return true }
    return now.timeIntervalSince(last) >= spacing
}

private func refreshRemoteConfigIfDue() {
    let now = Date()
    guard Self.isRemoteConfigRefreshDue(now: now, last: lastRemoteConfigRefresh,
                                        spacing: Self.remoteConfigRefreshSpacing) else { return }
    lastRemoteConfigRefresh = now
    Task { await container.innerTube.remoteConfig.refresh() }
}
```

- [ ] **Step 4: Run the tests, watch them pass**

Run: `cd ios/Packages/InnerTubeKit && swift test`
Expected: PASS, whole package suite green (the `StreamResolver` tests call `cache.put` across the actor boundary and already `await`, so they need no edit — if any test constructs `ManifestCache(configTTLSeconds:)` directly, update it to the new init).

Then run the full gate: `ios/scripts/test.sh` — expected green.

- [ ] **Step 5: Commit**

```bash
git add ios/Packages/InnerTubeKit/Sources/InnerTubeKit/PlayerResponseParser.swift \
        ios/Packages/InnerTubeKit/Sources/InnerTubeKit/ManifestCache.swift \
        ios/Packages/InnerTubeKit/Sources/InnerTubeKit/InnerTube.swift \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/PlayerResponseParserTests.swift \
        ios/Packages/InnerTubeKit/Tests/InnerTubeKitTests/ManifestCacheTests.swift \
        ios/FitrahTube/App/FitrahTubeApp.swift \
        ios/FitrahTubeTests/AppContainerTests.swift
git commit -m "[FIX]: iOS itag 140 from adaptiveFormats, live cache TTL"
```

---

### Task 2: `AudioSessionPolicy` (pure) + `AVAudioSession` lifecycle, interruptions and route changes

**This is carry-forward CF-B1-1 in full.** B1's fix round (finding C1) landed exactly one line — `try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)` at the top of `PlayerHostView.makeUIViewController` (**`PlayerHostView.swift:35`**, under a comment that says B2 owns the rest). B2 owns `setActive`, the `UIBackgroundModes: audio` entitlement, the interruption handler and the route-change handler — and **moves** that line rather than adding a second caller. Step 3 below deletes it explicitly. Grep `ios/FitrahTube` for `AVAudioSession` after implementing: the only remaining hit must be inside `BackgroundPlaybackController`.

**Files:**
- Create: `ios/FitrahTube/Features/Player/AudioSessionPolicy.swift`
- Create: `ios/FitrahTube/Features/Player/BackgroundPlaybackController.swift`
- Modify: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (the existing `Coordinator` creates/attaches/detaches the controller)
- Modify: `ios/project.yml` (`UIBackgroundModes`)
- Test: `ios/FitrahTubeTests/AudioSessionPolicyTests.swift` (create)

**Interfaces:**
- Consumes: `PlayerViewModel` (for `settings`-derived flags, added in Task 3 — for this task the controller takes `backgroundPlay: Bool` directly), `AVPlayer` (owned by `PlayerHostView`).
- Produces:

```swift
enum PlaybackLifecycleEvent: Equatable, Sendable {
    case enteredBackground
    case willEnterForeground
    case interruptionBegan
    case interruptionEnded(shouldResume: Bool)
    case routeChanged(oldDeviceUnavailable: Bool)
}

enum PlaybackPolicyAction: Equatable, Sendable {
    case none
    case pause
    case resume
    case swapToAudioOnly     // Task 3 acts on this; Task 2 only decides it
    case restoreVideo        // Task 3 acts on this
}

/// Whether AVFoundation should keep the player running when the app backgrounds
/// (`AVPlayer.audiovisualBackgroundPlaybackPolicy`, mapped by the controller).
enum BackgroundPolicy: Equatable, Sendable { case continues, pauses }

struct PlaybackPolicyContext: Equatable, Sendable {
    var backgroundPlay: Bool
    var userAudioOnly: Bool
    var pictureInPictureActive: Bool
    var wasPlayingBeforeInterruption: Bool
    var autoSwappedToAudioOnly: Bool
}

enum AudioSessionPolicy {
    static func decide(_ event: PlaybackLifecycleEvent, _ context: PlaybackPolicyContext) -> PlaybackPolicyAction
    static func backgroundPolicy(backgroundPlay: Bool, pictureInPictureActive: Bool) -> BackgroundPolicy
}

@MainActor final class BackgroundPlaybackController {
    init(backgroundPlay: Bool)
    var pictureInPictureActive: Bool { get set }   // Task 5 sets this from the AVKit delegate
    func attach(player: AVPlayer)
    func detach()
    static func configureAudioSession()
}
```

**The decision table `decide` implements** (this is the whole contract — the test is this table):

| Event | Condition | Action | Source |
|---|---|---|---|
| `.enteredBackground` | `pictureInPictureActive` | `.none` | PiP is video-in-background by definition; swapping the item would blank the PiP window (Android disables the audio-only toggle in PiP, `PlayerFragment.kt:1993-1996`) |
| `.enteredBackground` | `!backgroundPlay` | `.none` | The pause is AVFoundation's job via `backgroundPolicy`, not a second hand-rolled pause (ruling 34) |
| `.enteredBackground` | `userAudioOnly` | `.none` | Already audio-only; nothing to swap |
| `.enteredBackground` | otherwise | `.swapToAudioOnly` | spec §10 "itag 140 swap on background"; plan §6.5 |
| `.willEnterForeground` | `autoSwappedToAudioOnly` | `.restoreVideo` | The mirror of the line above |
| `.willEnterForeground` | otherwise | `.none` | **Never auto-resume on foreground** — a user pause must survive backgrounding (player.md §6.1: "playWhenReady preserved across stop/start") |
| `.interruptionBegan` | always | `.pause` | Ruling 44 |
| `.interruptionEnded(shouldResume: true)` | `wasPlayingBeforeInterruption` | `.resume` | Ruling 44 "resume after transient ones" |
| `.interruptionEnded(shouldResume: true)` | `!wasPlayingBeforeInterruption` | `.none` | Do not start playing something the user had paused |
| `.interruptionEnded(shouldResume: false)` | always | `.none` | The system says do not resume; obey it |
| `.routeChanged(oldDeviceUnavailable: true)` | always | `.pause` | Headphones unplugged / Bluetooth dropped — plan §6.5; Android's `setHandleAudioBecomingNoisy(true)` (`PlayerFragment.kt:979`) is the same behaviour |
| `.routeChanged(oldDeviceUnavailable: false)` | always | `.none` | A new device appearing is not a reason to touch playback |

`backgroundPolicy(backgroundPlay:pictureInPictureActive:)` → `.continues` when `pictureInPictureActive || backgroundPlay`, else `.pauses`.

- [ ] **Step 1: Write the failing tests**

Create `ios/FitrahTubeTests/AudioSessionPolicyTests.swift`:

```swift
import Testing
@testable import FitrahTube

@MainActor struct AudioSessionPolicyTests {
    private func context(backgroundPlay: Bool = true, userAudioOnly: Bool = false,
                         pip: Bool = false, wasPlaying: Bool = true,
                         autoSwapped: Bool = false) -> PlaybackPolicyContext {
        PlaybackPolicyContext(backgroundPlay: backgroundPlay, userAudioOnly: userAudioOnly,
                              pictureInPictureActive: pip, wasPlayingBeforeInterruption: wasPlaying,
                              autoSwappedToAudioOnly: autoSwapped)
    }

    @Test func backgroundingSwapsToAudioOnlyWhenBackgroundPlayIsOn() {
        #expect(AudioSessionPolicy.decide(.enteredBackground, context()) == .swapToAudioOnly)
    }

    @Test func backgroundingDoesNothingExtraWhenBackgroundPlayIsOff() {
        // Ruling 34's pause is delivered by `backgroundPolicy`, not by this action.
        #expect(AudioSessionPolicy.decide(.enteredBackground, context(backgroundPlay: false)) == .none)
    }

    @Test func backgroundingNeverSwapsWhileInPictureInPicture() {
        #expect(AudioSessionPolicy.decide(.enteredBackground, context(pip: true)) == .none)
    }

    @Test func backgroundingDoesNotSwapWhenTheUserAlreadyChoseAudioOnly() {
        #expect(AudioSessionPolicy.decide(.enteredBackground, context(userAudioOnly: true)) == .none)
    }

    @Test func foregroundRestoresVideoOnlyAfterAnAutomaticSwap() {
        #expect(AudioSessionPolicy.decide(.willEnterForeground, context(autoSwapped: true)) == .restoreVideo)
        #expect(AudioSessionPolicy.decide(.willEnterForeground, context(autoSwapped: false)) == .none)
    }

    @Test func interruptionPausesAndResumesOnlyWhenItWasPlaying() {
        #expect(AudioSessionPolicy.decide(.interruptionBegan, context()) == .pause)
        #expect(AudioSessionPolicy.decide(.interruptionEnded(shouldResume: true), context(wasPlaying: true)) == .resume)
        #expect(AudioSessionPolicy.decide(.interruptionEnded(shouldResume: true), context(wasPlaying: false)) == .none)
        #expect(AudioSessionPolicy.decide(.interruptionEnded(shouldResume: false), context(wasPlaying: true)) == .none)
    }

    @Test func headphoneUnplugPauses() {
        #expect(AudioSessionPolicy.decide(.routeChanged(oldDeviceUnavailable: true), context()) == .pause)
        #expect(AudioSessionPolicy.decide(.routeChanged(oldDeviceUnavailable: false), context()) == .none)
    }

    @Test func backgroundPolicyFollowsTheSettingUnlessPiPIsActive() {
        #expect(AudioSessionPolicy.backgroundPolicy(backgroundPlay: true, pictureInPictureActive: false) == .continues)
        #expect(AudioSessionPolicy.backgroundPolicy(backgroundPlay: false, pictureInPictureActive: false) == .pauses)
        #expect(AudioSessionPolicy.backgroundPolicy(backgroundPlay: false, pictureInPictureActive: true) == .continues)
    }

    @Test func audioSessionIsPlaybackWithoutMixing() {
        BackgroundPlaybackController.configureAudioSession()
        let session = AVAudioSession.sharedInstance()
        #expect(session.category == .playback)
        #expect(session.mode == .moviePlayback)
        // Ruling 44: no mixWithOthers.
        #expect(session.categoryOptions.contains(.mixWithOthers) == false)
    }
}
```

(Add `import AVFoundation` for the last test.)

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `AudioSessionPolicy`, `PlaybackPolicyContext`, `BackgroundPlaybackController` do not exist.

- [ ] **Step 3: Implement**

`AudioSessionPolicy.swift` — pure, no AVFoundation import, exactly the table above:

```swift
import Foundation

enum PlaybackLifecycleEvent: Equatable, Sendable {
    case enteredBackground
    case willEnterForeground
    case interruptionBegan
    case interruptionEnded(shouldResume: Bool)
    case routeChanged(oldDeviceUnavailable: Bool)
}

enum PlaybackPolicyAction: Equatable, Sendable { case none, pause, resume, swapToAudioOnly, restoreVideo }

/// Whether AVFoundation should keep the player running when the app backgrounds
/// (`AVPlayer.audiovisualBackgroundPlaybackPolicy`, mapped by the controller).
enum BackgroundPolicy: Equatable, Sendable { case continues, pauses }

struct PlaybackPolicyContext: Equatable, Sendable {
    var backgroundPlay: Bool
    var userAudioOnly: Bool
    var pictureInPictureActive: Bool
    var wasPlayingBeforeInterruption: Bool
    var autoSwappedToAudioOnly: Bool
}

/// The single decision table for "what should playback do when the world changes around it"
/// (rulings 34 and 44). Pure and AVFoundation-free so the whole contract is a truth table in
/// `AudioSessionPolicyTests`; `BackgroundPlaybackController` is the only thing that turns these
/// actions into calls on a real `AVPlayer`.
enum AudioSessionPolicy {
    static func decide(_ event: PlaybackLifecycleEvent, _ context: PlaybackPolicyContext) -> PlaybackPolicyAction {
        switch event {
        case .enteredBackground:
            // Ruling 34's "background-play OFF pauses" is delivered by `backgroundPolicy` below --
            // AVFoundation's own policy fires before app suspension and already exempts PiP and
            // AirPlay, which a hand-rolled `player.pause()` racing suspension would not.
            guard context.backgroundPlay, !context.pictureInPictureActive, !context.userAudioOnly else { return .none }
            return .swapToAudioOnly
        case .willEnterForeground:
            // Never `.resume`: a pause the user made before backgrounding must survive
            // (player.md §6.1, Android's preserved `playWhenReady`).
            return context.autoSwappedToAudioOnly ? .restoreVideo : .none
        case .interruptionBegan:
            return .pause
        case .interruptionEnded(let shouldResume):
            return shouldResume && context.wasPlayingBeforeInterruption ? .resume : .none
        case .routeChanged(let oldDeviceUnavailable):
            return oldDeviceUnavailable ? .pause : .none
        }
    }

    static func backgroundPolicy(backgroundPlay: Bool, pictureInPictureActive: Bool) -> BackgroundPolicy {
        backgroundPlay || pictureInPictureActive ? .continues : .pauses
    }
}
```

`BackgroundPlaybackController.swift`:

```swift
import AVFoundation
import UIKit

/// Owns everything that happens to playback while the player view is not the thing on screen:
/// the audio session, interruptions, route changes, and (Task 4) the Now Playing surface.
/// Created and torn down by `PlayerHostView.Coordinator`, which already owns the KVO/stall
/// observers -- one lifecycle owner, not two.
@MainActor final class BackgroundPlaybackController {
    var backgroundPlay: Bool { didSet { applyBackgroundPolicy() } }
    var pictureInPictureActive = false { didSet { applyBackgroundPolicy() } }
    /// Task 3 sets this; Task 2 only needs it to build the policy context.
    var userAudioOnly = false
    /// Task 3 owns the swap itself. Left nil here, the swap actions are no-ops.
    var onPolicyAction: ((PlaybackPolicyAction) -> Void)?

    private weak var player: AVPlayer?
    private var observers: [NSObjectProtocol] = []
    private var wasPlayingBeforeInterruption = false
    private(set) var autoSwappedToAudioOnly = false

    init(backgroundPlay: Bool) {
        self.backgroundPlay = backgroundPlay
    }

    /// `.playback` + `.moviePlayback`, no `mixWithOthers` (ruling 44). Idempotent: called on every
    /// attach; AVAudioSession tolerates a repeat set/activate. Errors are logged and swallowed --
    /// a failed activation must degrade to "no background audio", never to a crash on open.
    static func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .moviePlayback)
            try session.setActive(true)
        } catch {
            #if DEBUG
            print("[BackgroundPlaybackController] audio session activation failed: \(error)")
            #endif
        }
    }

    func attach(player: AVPlayer) {
        detach()
        self.player = player
        Self.configureAudioSession()
        applyBackgroundPolicy()
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.handle(.enteredBackground) }
            },
            center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.handle(.willEnterForeground) }
            },
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
                MainActor.assumeIsolated { self?.handleInterruption(note) }
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
                MainActor.assumeIsolated { self?.handleRouteChange(note) }
            }
        ]
    }

    func detach() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        player = nil
        // `.notifyOthersOnDeactivation` hands the session back politely so a paused music app
        // resumes instead of staying silent.
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func applyBackgroundPolicy() {
        player?.audiovisualBackgroundPlaybackPolicy =
            AudioSessionPolicy.backgroundPolicy(backgroundPlay: backgroundPlay,
                                                pictureInPictureActive: pictureInPictureActive) == .continues
            ? .continuesIfPossible : .pauses
    }

    private var context: PlaybackPolicyContext {
        PlaybackPolicyContext(backgroundPlay: backgroundPlay, userAudioOnly: userAudioOnly,
                              pictureInPictureActive: pictureInPictureActive,
                              wasPlayingBeforeInterruption: wasPlayingBeforeInterruption,
                              autoSwappedToAudioOnly: autoSwappedToAudioOnly)
    }

    private func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            wasPlayingBeforeInterruption = player?.timeControlStatus != .paused
            handle(.interruptionBegan)
        case .ended:
            let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume)
            if shouldResume { Self.configureAudioSession() }  // the session was deactivated by the interruption
            handle(.interruptionEnded(shouldResume: shouldResume))
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
        handle(.routeChanged(oldDeviceUnavailable: reason == .oldDeviceUnavailable))
    }

    private func handle(_ event: PlaybackLifecycleEvent) {
        let action = AudioSessionPolicy.decide(event, context)
        switch action {
        case .none: break
        case .pause: player?.pause()
        case .resume: player?.play()
        case .swapToAudioOnly: autoSwappedToAudioOnly = true
        case .restoreVideo: autoSwappedToAudioOnly = false
        }
        onPolicyAction?(action)   // Task 3 performs the actual URL swap
    }
}
```

**Delete the B1 category line first.** In `PlayerHostView.makeUIViewController`, remove `PlayerHostView.swift:35`'s `try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)` **and its five-line `// C1 (B1 final review):` comment above it**. `BackgroundPlaybackController.configureAudioSession()` now sets the same category *and* activates the session, and `attach` calls it — two callers would mean two places to keep the category contract in, and the B1 comment already anticipates this move ("B2 can move it without a migration"). If the line is not there (the fix round has not landed on this branch), skip the deletion and carry on; do not re-add it anywhere.

In `PlayerHostView.swift`, the `Coordinator` gains the controller and drives it from the existing hooks:

```swift
// inside Coordinator
let background = BackgroundPlaybackController(backgroundPlay: true)   // Task 3 feeds the real setting
```

In `makeUIViewController` / `updateUIViewController`, after `controller.player` is set, call `context.coordinator.background.attach(player: player)` when the player object identity changed; in `dismantleUIViewController`, call `coordinator.background.detach()` alongside the existing `stopObserving()`.

In `ios/project.yml`, under the `FitrahTube` target's `info.properties`, add:

```yaml
        UIBackgroundModes:
          - audio
```

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh`
Expected: PASS. Then confirm the entitlement actually made it into the built plist:
`cd ios && xcodegen generate && plutil -p build/*/Build/Products/Debug-iphonesimulator/FitrahTube.app/Info.plist | grep -A2 UIBackgroundModes` — expected `"audio"`. (If the DerivedData path differs on this machine, use `xcodebuild -showBuildSettings -scheme FitrahTube | grep BUILT_PRODUCTS_DIR` to find it.)

**What the simulator cannot prove here:** that audio actually keeps playing with the screen locked, that a phone call fires `.began`/`.ended`, and that a headphone unplug fires `.oldDeviceUnavailable`. The decision table is proven by the unit tests; the notification→event mapping is proven only on device (Task 7).

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/AudioSessionPolicy.swift \
        ios/FitrahTube/Features/Player/BackgroundPlaybackController.swift \
        ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTubeTests/AudioSessionPolicyTests.swift \
        ios/project.yml
git commit -m "[FEAT]: iOS audio session, background mode, interruptions"
```

---

### Task 3: Background-play setting + audio-only mode

Ruling 34 in full: the **Background play** setting really controls background behaviour, and the **Audio only** setting really seeds the player's per-session toggle. This task also closes review finding **M4** — `PlayerViewModel` holds `catalog`, `favorites` and `settings` and reads none of them; after this task it reads `settings` and the other two are deleted (`PlayerToolbar` gets favorites from the environment container on its own, and nothing in the player uses `catalog`).

**Files:**
- Modify: `ios/FitrahTube/Features/Player/PlayerViewModel.swift` (add `audioOnly`, seed from `settings`, drop the dead `catalog`/`favorites` deps)
- Modify: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (`player(for:replacing:audioOnly:)`, `streamURL(_:audioOnly:)`, feed the real setting into `BackgroundPlaybackController`, act on `.swapToAudioOnly`/`.restoreVideo`)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (audio-only toggle button, audio-only pill, hide quality/captions/audio-language while audio-only, drop the removed init args)
- Test: `ios/FitrahTubeTests/PlayerHostTests.swift` (extend), `ios/FitrahTubeTests/PlayerViewModelTests.swift` (extend)

**Interfaces:**
- Consumes: `Resolved.stream == .hls(url:isLive:audioOnlyURL:captionTracks:)` — `audioOnlyURL` is now really populated (Task 1). `SettingsStore.audioOnly: Bool`, `SettingsStore.backgroundPlay: Bool`.
- Produces:
  - `PlayerViewModel.init(resolver:settings:args:)` — **`catalog:` and `favorites:` are removed**. Every call site (`PlayerScreen.task`, `PlayerViewModelTests`, `PlaybackRecoveryTests` if it constructs one) must be updated.
  - `PlayerViewModel.audioOnly: Bool` — seeded from `settings.audioOnly` in `init`, session-only thereafter (never written back to `SettingsStore`; Android's is not persisted either, player.md §5).
  - `PlayerViewModel.audioOnlyAvailable(for state: StreamState) -> Bool` (static) — true only when `state` is `.ready` with a non-nil `audioOnlyURL`.
  - `PlayerHostView.player(for state: StreamState, replacing existing: AVPlayer?, audioOnly: Bool) -> AVPlayer?` and `static func streamURL(_ stream: ResolvedStream, audioOnly: Bool) -> URL?` (the latter becomes non-private so the tests can pin it).
  - Accessibility ids `player.audioOnly.button` and `player.audioOnlyPill`.

- [ ] **Step 1: Write the failing tests**

In `PlayerHostTests.swift`:

```swift
@Test func audioOnlySelectsTheItag140URL() {
    let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
    let audio = URL(string: "https://r1.googlevideo.com/a140")!
    let stream = ResolvedStream.hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: [])
    #expect(PlayerHostView.streamURL(stream, audioOnly: false) == video)
    #expect(PlayerHostView.streamURL(stream, audioOnly: true) == audio)
}

@Test func audioOnlyFallsBackToVideoWhenNoAudioTrackExists() {
    let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
    let stream = ResolvedStream.hls(url: video, isLive: false, audioOnlyURL: nil, captionTracks: [])
    #expect(PlayerHostView.streamURL(stream, audioOnly: true) == video)
    let progressive = ResolvedStream.progressive(url: video, label: "360p")
    #expect(PlayerHostView.streamURL(progressive, audioOnly: true) == video)
}

@Test func togglingAudioOnlyReplacesTheItemAndKeepsThePlayer() {
    let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
    let audio = URL(string: "https://r1.googlevideo.com/a140")!
    let resolved = Resolved(stream: .hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: []),
                            client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
    let state = StreamState.ready(resolved)
    let first = PlayerHostView.player(for: state, replacing: nil, audioOnly: false)
    let second = PlayerHostView.player(for: state, replacing: first, audioOnly: true)
    #expect(second === first)  // same AVPlayer -- position carries via replaceCurrentItem + seek
    #expect((second?.currentItem?.asset as? AVURLAsset)?.url == audio)
}

@Test func audioOnlyAssetCarriesTheResolvedUserAgent() {
    // The itag 140 URL is IP+UA-bound exactly like the manifest; a bare asset 403s.
    #expect(PlayerHostView.assetOptions(userAgent: "UA")[AVURLAssetHTTPUserAgentKey] as? String == "UA")
}
```

In `PlayerViewModelTests.swift`:

```swift
@Test func audioOnlyIsSeededFromTheSettingAndIsSessionOnly() async {
    let settings = FakeSettingsStore()          // whatever the existing tests already use
    settings.audioOnly = true
    let model = PlayerViewModel(resolver: FakeResolver(.hls), settings: settings,
                                args: PlayerArgs(videoId: "abc"))
    #expect(model.audioOnly == true)
    model.audioOnly = false
    #expect(settings.audioOnly == true)   // ruling 34 seeds; it never writes back (player.md §5)
}

@Test func audioOnlyIsOfferedOnlyWhenTheResolvedStreamHasAnAudioTrack() {
    let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
    let withAudio = Resolved(stream: .hls(url: video, isLive: false,
                                          audioOnlyURL: URL(string: "https://r1/a140")!, captionTracks: []),
                             client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
    let withoutAudio = Resolved(stream: .progressive(url: video, label: "360p"),
                                client: .android, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
    #expect(PlayerViewModel.audioOnlyAvailable(for: .ready(withAudio)) == true)
    #expect(PlayerViewModel.audioOnlyAvailable(for: .rung2Progressive(withoutAudio)) == false)
    #expect(PlayerViewModel.audioOnlyAvailable(for: .loading) == false)
}
```

If the test target has no `FakeSettingsStore`, use `UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: #function)!)` the way `SettingsStoreTests` already does — do not invent a new fake.

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `streamURL(_:audioOnly:)`, `player(for:replacing:audioOnly:)`, `PlayerViewModel.audioOnly` and the 3-argument `init` do not exist.

- [ ] **Step 3: Implement**

`PlayerViewModel.swift`:

```swift
/// Ruling 34: the Settings "Audio only" value SEEDS this per-session toggle (Android's settings
/// value is written and never read -- player.md §15, defect). Session-only after seeding: flipping
/// it here never writes back to `SettingsStore`, matching Android's own non-persisted toggle
/// (player.md §5, "Not persisted; resets with the ViewModel").
var audioOnly: Bool

private let settings: any SettingsStore

init(resolver: any StreamResolving, settings: any SettingsStore, args: PlayerArgs) {
    self.resolver = resolver
    self.settings = settings
    self.args = args
    self.audioOnly = settings.audioOnly
}

/// The Settings "Background play" value, read live so a change made in Settings while the player is
/// open takes effect on the next background transition (ruling 34).
var backgroundPlay: Bool { settings.backgroundPlay }

/// Audio-only needs a real itag 140 URL. Rung 2 (a single muxed 360p progressive) has none, so the
/// toggle is hidden there -- the same "hide the control that has no backing" rule spec §10 applies
/// to the quality control on rung 2.
static func audioOnlyAvailable(for state: StreamState) -> Bool {
    guard case .ready(let resolved) = state,
          case .hls(_, _, let audioOnlyURL, _) = resolved.stream else { return false }
    return audioOnlyURL != nil
}
```

Delete the `catalog` and `favorites` stored properties and their init parameters.

`PlayerHostView.swift`:

```swift
let audioOnly: Bool   // new stored property, passed by PlayerScreen as `model.audioOnly`

static func player(for state: StreamState, replacing existing: AVPlayer?, audioOnly: Bool) -> AVPlayer? {
    guard let resolved = resolvedStream(for: state),
          let url = streamURL(resolved.stream, audioOnly: audioOnly) else {
        existing?.pause()
        return nil
    }
    // ...body unchanged from B1: identity check on the URL, then replaceCurrentItem + seek.
    // A toggle flip yields a DIFFERENT url, so the existing identity check already produces
    // exactly Android's behaviour: "audio-mode change counts as a quality switch, position
    // preserved" (PlayerFragment.kt:2841-2842).
}

/// Not private: `PlayerHostTests` pins the audio-only selection directly.
static func streamURL(_ stream: ResolvedStream, audioOnly: Bool) -> URL? {
    switch stream {
    case .hls(let url, _, let audioOnlyURL, _):
        return audioOnly ? (audioOnlyURL ?? url) : url
    case .progressive(let url, _):
        return url   // rung 2 has no separate audio rendition; the toggle is hidden there anyway
    case .embed, .openInYouTube:
        return nil   // B3
    }
}
```

In the `Coordinator`, feed the controller the real setting and act on the swap actions:

```swift
// in makeUIViewController / updateUIViewController, alongside the existing handoffs:
context.coordinator.background.backgroundPlay = model.backgroundPlay
context.coordinator.background.userAudioOnly = model.audioOnly
context.coordinator.background.onPolicyAction = { [weak model] action in
    // ponytail: an automatic background swap costs one re-buffer going in and one coming out
    // (both are local URL swaps, no network). Accepted: spec §10 asks for the itag 140 swap on
    // background so the phone stops pulling video segments off-screen. Skipped: the "or when
    // backgrounded on cellular" variant from plan §6.5 -- the setting already carries user intent.
    switch action {
    case .swapToAudioOnly: model?.audioOnly = true
    case .restoreVideo: model?.audioOnly = false
    default: break
    }
}
```

`PlayerScreen.swift` — inside the existing `.ready`/`.rung2Progressive` branch (**one branch, do not split it**):

```swift
PlayerHostView(state: state, quality: model.selectedQuality, audioOnly: model.audioOnly, model: model)
if model.audioOnly {
    // Android shows `player_status_audio_only` as its status line (PlayerFragment.kt:2092); on iOS
    // the video surface is a black rectangle while audio-only, so the same string fills it.
    Text(String(localized: "player_status_audio_only"))
        .font(.subheadline).foregroundStyle(.white)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
        .accessibilityIdentifier("player.audioOnlyPill")
}
```

and in the overlay control stack, replacing the current unconditional quality/captions/audio-language block:

```swift
// While audio-only there is no video rendition to cap, no subtitle track and no alternate
// audible group on an m4a item -- every one of these controls would be inert, so none of them
// is shown. The audio-only button itself stays, so the user can get back out.
if !model.audioOnly {
    if isRung1 { qualityMenu(model) } else { rung2Pill }
    AudioLanguageMenu(model: model)
    captionsMenu(model, tracks: Self.captionTracks(state))
}
if PlayerViewModel.audioOnlyAvailable(for: state) {
    Button { model.audioOnly.toggle() } label: {
        Image(systemName: model.audioOnly ? "headphones.circle.fill" : "headphones")
            .foregroundStyle(.white).padding(10).background(.black.opacity(0.55), in: Circle())
    }
    .accessibilityIdentifier("player.audioOnly.button")
    .accessibilityLabel(String(localized: "player_audio_only_label"))
    .accessibilityAddTraits(model.audioOnly ? [.isSelected] : [])
}
```

`.accessibilityAddTraits(.isSelected)`, not an `accessibilityValue`: no existing string key carries generic on/off wording (the favorite button uses two *distinct* labels, `player_action_favorited` / `player_action_not_favorited`, because it changes meaning; this button does not), and the constraint above forbids inventing a key that Android has no source for. The selected trait is what VoiceOver reads for a toggle button.

Update the **three** `PlayerViewModel(...)` call sites to the 3-argument init: `ios/FitrahTube/Features/Player/PlayerScreen.swift:30`, `ios/FitrahTubeTests/PlaybackRecoveryTests.swift:264` and `ios/FitrahTubeTests/PlayerViewModelTests.swift:32`. (The last two pass `catalog: FakeCatalogClient()` and a favorites fake purely to satisfy the old signature; those arguments — and any fake that becomes unreferenced as a result — go with them.)

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh` — expected green.
Then capture the acceptance screenshots: add a `-fitrah-fake-player-audio-only` launch argument to `PlayerScreen`'s existing `#if DEBUG` resolver switch returning a `FixtureAudioOnlyResolver` whose `Resolved` is `.hls(url: fixture, isLive: false, audioOnlyURL: fixture, captionTracks: [])`, and a `ScreenshotTests` case that launches it, taps `player.audioOnly.button`, and asserts `player.audioOnlyPill` exists before capturing.
Run: `ios/scripts/screenshots.sh "iPhone 17"` — expected: a frame showing the audio-only status surface with the quality/captions/language controls gone.

**What the simulator cannot prove here:** that the automatic background swap actually fires (`didEnterBackgroundNotification` reaches the app in the simulator, but there is no way to observe from XCUITest that the item URL changed). The `.swapToAudioOnly` decision is unit-tested; the swap mechanics are unit-tested; the *wiring between them* is verified on device in Task 7.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/PlayerViewModel.swift \
        ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTube/Features/Player/PlayerScreen.swift \
        ios/FitrahTubeTests/PlayerHostTests.swift \
        ios/FitrahTubeTests/PlayerViewModelTests.swift \
        ios/FitrahTubeTests/PlaybackRecoveryTests.swift \
        ios/FitrahTubeUITests/ScreenshotTests.swift
git commit -m "[FEAT]: iOS background-play setting and audio-only mode"
```

---

### Task 4: Now Playing info + remote command centre

**Files:**
- Create: `ios/FitrahTube/Features/Player/NowPlayingSnapshot.swift`
- Modify: `ios/FitrahTube/Features/Player/BackgroundPlaybackController.swift` (publish snapshots, install commands, load artwork)
- Modify: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (hand `args` + `state` to the controller)
- Test: `ios/FitrahTubeTests/NowPlayingSnapshotTests.swift` (create)

**Spec reconciliation — read this before implementing.** Spec §10 lists the remote command set as "play/pause/skip/seek/**next/prev**"; plan §6.5 narrows the same list to "play/pause/skip/`changePlaybackPosition`" with no next/prev. B2 follows **§6.5**: the queue that would give next/prev meaning is B5 (Up Next / playlist auto-advance), and a lock-screen button that does nothing is ruling 28's "dead buttons are worse than absent ones" applied to transport. `nextTrackCommand` and `previousTrackCommand` are therefore explicitly `isEnabled = false`, and B5 enables them when it lands the queue.

**Interfaces:**
- Consumes: `PlayerArgs` (`title`, `channelName`, `thumbnailURL`, `durationSeconds`), `StreamState`, the live `AVPlayer`.
- Produces:

```swift
struct NowPlayingSnapshot: Equatable, Sendable {
    var title: String
    var channel: String?
    var duration: TimeInterval?
    var elapsed: TimeInterval
    var rate: Float
    var isLive: Bool

    /// nil when there is nothing playable to advertise (`.loading`, `.error`, …) -- the caller
    /// clears `MPNowPlayingInfoCenter.default().nowPlayingInfo` in that case.
    static func make(args: PlayerArgs, state: StreamState, elapsed: TimeInterval,
                     duration: TimeInterval?, rate: Float) -> NowPlayingSnapshot?

    /// The `MPNowPlayingInfoCenter` dictionary. Artwork is added by the controller, not here --
    /// it needs a network fetch and this type stays pure.
    var info: [String: Any] { get }
}
```

Field rules (spec §10 "Now Playing", plan §6.5; Android's `MediaSessionMetadataManager.kt:96-120` is the parity source — title / artist = channel name / artwork):
- `title` = `args.title` when non-empty, else `String(localized: "player_default_title")`.
- `channel` = `args.channelName` — **ruling 39**: the real channel title, never the category, never a placeholder.
- `duration` = `args.durationSeconds.map(TimeInterval.init)`, overridden by the player item's real duration when finite. **Omitted entirely when `isLive`** (a live stream has no duration; publishing one gives the lock screen a scrubber that lies).
- `isLive` = true only for `.ready(resolved)` where `resolved.stream` is `.hls(_, isLive: true, _, _)`.

- [ ] **Step 1: Write the failing tests**

Create `ios/FitrahTubeTests/NowPlayingSnapshotTests.swift`:

```swift
import MediaPlayer
import Testing
@testable import FitrahTube
@testable import InnerTubeKit

@MainActor struct NowPlayingSnapshotTests {
    private func ready(isLive: Bool = false) -> StreamState {
        .ready(Resolved(stream: .hls(url: URL(string: "https://x/y.m3u8")!, isLive: isLive,
                                     audioOnlyURL: nil, captionTracks: []),
                        client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil))
    }

    @Test func mapsTitleChannelDurationAndElapsed() throws {
        let args = PlayerArgs(videoId: "abc", title: "Understanding Tawakkul",
                              channelName: "Sample Channel", durationSeconds: 754)
        let snapshot = try #require(NowPlayingSnapshot.make(args: args, state: ready(),
                                                            elapsed: 42, duration: 754, rate: 1))
        #expect(snapshot.info[MPMediaItemPropertyTitle] as? String == "Understanding Tawakkul")
        #expect(snapshot.info[MPMediaItemPropertyArtist] as? String == "Sample Channel")
        #expect(snapshot.info[MPMediaItemPropertyPlaybackDuration] as? TimeInterval == 754)
        #expect(snapshot.info[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? TimeInterval == 42)
        #expect(snapshot.info[MPNowPlayingInfoPropertyPlaybackRate] as? Float == 1)
    }

    @Test func liveStreamsPublishNoDuration() throws {
        let args = PlayerArgs(videoId: "abc", title: "Live khutbah", channelName: "Sample Channel")
        let snapshot = try #require(NowPlayingSnapshot.make(args: args, state: ready(isLive: true),
                                                            elapsed: 10, duration: nil, rate: 1))
        #expect(snapshot.isLive)
        #expect(snapshot.info[MPNowPlayingInfoPropertyIsLiveStream] as? Bool == true)
        #expect(snapshot.info[MPMediaItemPropertyPlaybackDuration] == nil)
    }

    @Test func fallsBackToTheDefaultTitleAndOmitsAMissingChannel() throws {
        let snapshot = try #require(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "abc"),
                                                            state: ready(), elapsed: 0,
                                                            duration: nil, rate: 0))
        #expect(snapshot.info[MPMediaItemPropertyTitle] as? String == String(localized: "player_default_title"))
        #expect(snapshot.info[MPMediaItemPropertyArtist] == nil)
    }

    @Test func nonPlayableStatesPublishNothing() {
        #expect(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "abc"), state: .loading,
                                        elapsed: 0, duration: nil, rate: 0) == nil)
        #expect(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "abc"), state: .contentUnavailable,
                                        elapsed: 0, duration: nil, rate: 0) == nil)
    }

    @Test func remoteCommandsExposePlayPauseAndSeekButNotNextOrPrevious() {
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.attach(player: AVPlayer())
        let center = MPRemoteCommandCenter.shared()
        #expect(center.playCommand.isEnabled)
        #expect(center.pauseCommand.isEnabled)
        #expect(center.changePlaybackPositionCommand.isEnabled)
        #expect(center.skipForwardCommand.isEnabled)
        #expect(center.skipBackwardCommand.isEnabled)
        // B5 owns the queue; a dead next/prev on the lock screen is worse than none (ruling 28's
        // "dead buttons are worse than absent ones", applied to transport).
        #expect(center.nextTrackCommand.isEnabled == false)
        #expect(center.previousTrackCommand.isEnabled == false)
        controller.detach()
    }
}
```

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `NowPlayingSnapshot` does not exist; `BackgroundPlaybackController` installs no commands.

- [ ] **Step 3: Implement**

`NowPlayingSnapshot.swift`:

```swift
import Foundation
import InnerTubeKit
import MediaPlayer

/// The pure mapper from "what the player is doing" to the `MPNowPlayingInfoCenter` dictionary
/// (spec §10, plan §6.5). Parity source: Android's `MediaSessionMetadataManager.kt:96-120`
/// (title / artist = channel name / artwork). Kept free of AVFoundation and of any network call so
/// the whole field contract is unit-testable.
struct NowPlayingSnapshot: Equatable, Sendable {
    var title: String
    var channel: String?
    var duration: TimeInterval?
    var elapsed: TimeInterval
    var rate: Float
    var isLive: Bool

    static func make(args: PlayerArgs, state: StreamState, elapsed: TimeInterval,
                     duration: TimeInterval?, rate: Float) -> NowPlayingSnapshot? {
        let isLive: Bool
        switch state {
        case .ready(let resolved):
            if case .hls(_, let live, _, _) = resolved.stream { isLive = live } else { isLive = false }
        case .rung2Progressive, .recoveryExhausted:
            isLive = false
        default:
            return nil   // nothing playable to advertise
        }
        let trimmed = args.title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let channel = args.channelName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return NowPlayingSnapshot(
            title: (trimmed?.isEmpty == false ? trimmed! : String(localized: "player_default_title")),
            channel: channel?.isEmpty == false ? channel : nil,
            // A live stream has no duration; publishing one gives the lock screen a lying scrubber.
            duration: isLive ? nil : (duration ?? args.durationSeconds.map(TimeInterval.init)),
            elapsed: elapsed, rate: rate, isLive: isLive)
    }

    var info: [String: Any] {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
            MPNowPlayingInfoPropertyPlaybackRate: rate,
            MPNowPlayingInfoPropertyIsLiveStream: isLive,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue
        ]
        if let channel { info[MPMediaItemPropertyArtist] = channel }
        if let duration { info[MPMediaItemPropertyPlaybackDuration] = duration }
        return info
    }
}
```

In `BackgroundPlaybackController`, add:

```swift
private var args: PlayerArgs?
private var state: StreamState = .idle
private var timeObserver: Any?
private var artwork: MPMediaItemArtwork?
private var artworkURL: URL?

/// Called from `PlayerHostView` whenever the state or args change.
func update(args: PlayerArgs, state: StreamState) {
    self.args = args
    self.state = state
    loadArtworkIfNeeded(args.thumbnailURL)
    publishNowPlaying()
}

private func publishNowPlaying() {
    guard let args, let player,
          let snapshot = NowPlayingSnapshot.make(args: args, state: state,
                                                 elapsed: player.currentTime().seconds.isFinite
                                                     ? player.currentTime().seconds : 0,
                                                 duration: player.currentItem?.duration.seconds.isFinite == true
                                                     ? player.currentItem?.duration.seconds : nil,
                                                 rate: player.rate) else {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        return
    }
    var info = snapshot.info
    if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
}

private func installRemoteCommands() {
    let center = MPRemoteCommandCenter.shared()
    center.playCommand.addTarget { [weak self] _ in self?.player?.play(); return .success }
    center.pauseCommand.addTarget { [weak self] _ in self?.player?.pause(); return .success }
    center.skipForwardCommand.preferredIntervals = [10]
    center.skipBackwardCommand.preferredIntervals = [10]
    center.skipForwardCommand.addTarget { [weak self] event in self?.skip(by: event); return .success }
    center.skipBackwardCommand.addTarget { [weak self] event in self?.skip(by: event, backwards: true); return .success }
    center.changePlaybackPositionCommand.addTarget { [weak self] event in
        guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
        self?.player?.seek(to: CMTime(seconds: event.positionTime, preferredTimescale: 600))
        return .success
    }
    for command in [center.playCommand, center.pauseCommand, center.skipForwardCommand,
                    center.skipBackwardCommand, center.changePlaybackPositionCommand] {
        command.isEnabled = true
    }
    // B5 owns Up Next / the playlist queue. Until then these must be visibly absent, not dead.
    center.nextTrackCommand.isEnabled = false
    center.previousTrackCommand.isEnabled = false
}

private func removeRemoteCommands() {
    let center = MPRemoteCommandCenter.shared()
    for command in [center.playCommand, center.pauseCommand, center.skipForwardCommand,
                    center.skipBackwardCommand, center.changePlaybackPositionCommand] {
        command.removeTarget(nil)
        command.isEnabled = false
    }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
}
```

`attach` additionally calls `installRemoteCommands()` and registers a 5 s periodic time observer that calls `publishNowPlaying()`; `detach` removes the observer and calls `removeRemoteCommands()`. (5 s, not 1 s: iOS extrapolates the elapsed time from `rate` between updates, so a tighter interval buys nothing and costs wakeups. A `.changePlaybackPositionCommand` or a play/pause also republishes immediately, which is what keeps the scrubber honest after a seek.)

Artwork: `loadArtworkIfNeeded(_ url: URL?)` skips when `url == artworkURL`, otherwise `Task { let (data, _) = try await URLSession.shared.data(from: url); guard let image = UIImage(data: data) else { return }; artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }; publishNowPlaying() }` — errors are swallowed (no artwork is a cosmetic degradation, never a failure).

`PlayerHostView` gains a stored `let args: PlayerArgs` — `PlayerScreen` already has `args` in scope and passes it alongside `state`/`quality`/`audioOnly`/`model`, which keeps `PlayerViewModel.args` private (one fewer VM surface than exposing it). In both `makeUIViewController` and `updateUIViewController`, call `context.coordinator.background.update(args: args, state: state)` after the player is set.

Update the `PlayerHostView(...)` call in `PlayerScreen.stateView` to `PlayerHostView(state: state, quality: model.selectedQuality, audioOnly: model.audioOnly, args: args, model: model)`.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh` — expected green.

**What the simulator cannot prove here:** the rendered lock screen and Control Centre, artwork appearance, and that a hardware/Bluetooth transport button routes to the installed commands. The dictionary contents and the command enablement are asserted in-process by the tests above (`MPNowPlayingInfoCenter.default().nowPlayingInfo` is readable in the simulator), which is the mutation check for this task; visual confirmation is Task 7.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/NowPlayingSnapshot.swift \
        ios/FitrahTube/Features/Player/BackgroundPlaybackController.swift \
        ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTube/Features/Player/PlayerViewModel.swift \
        ios/FitrahTubeTests/NowPlayingSnapshotTests.swift
git commit -m "[FEAT]: iOS Now Playing info and remote commands"
```

---

### Task 5: Picture in Picture

**Files:**
- Modify: `ios/FitrahTube/Features/Player/PlayerHostView.swift` (enable PiP, `AVPlayerViewControllerDelegate` on the existing `Coordinator`, guard teardown)
- Test: `ios/FitrahTubeTests/PlayerHostTests.swift` (extend)

**Interfaces:**
- Consumes: `PlayerViewModel.backgroundPlay`, `BackgroundPlaybackController.pictureInPictureActive`.
- Produces: `PlayerHostView.Coordinator: NSObject, AVPlayerViewControllerDelegate` (the Coordinator must become an `NSObject` subclass to be a delegate — check whether B1 already made it one; if not, change `@MainActor final class Coordinator` to `@MainActor final class Coordinator: NSObject` and add `override init() { super.init() }`).

**Ruling reconciliation — read this before implementing.** Spec §10 says "PiP: user-initiated only", plan §6.5 says "`allowsPictureInPicturePlayback`, `canStartPictureInPictureAutomaticallyFromInline`; only ever user-initiated (App Review rejects programmatic PiP)", and **ruling 43** picks "platform-standard auto-PiP via AVKit" over Android's manual menu item. These agree: `canStartPictureInPictureAutomaticallyFromInline` is not programmatic PiP — the transition is triggered by the *user* backgrounding the app, and AVKit performs it. What all three forbid is calling `AVPictureInPictureController.startPictureInPicture()` from code. **Never call it.**

- [ ] **Step 1: Write the failing test**

In `PlayerHostTests.swift`:

```swift
@Test func pictureInPictureIsEnabledAndAutoStartFollowsTheBackgroundPlaySetting() {
    // ruling 43 + plan §6.5: PiP is on; auto-start-from-inline is on only when the user has
    // allowed background playback, so backgrounding with the setting OFF cannot smuggle video
    // into a floating window the user asked not to have (ruling 34).
    let controller = AVPlayerViewController()
    PlayerHostView.configurePictureInPicture(controller, backgroundPlay: true)
    #expect(controller.allowsPictureInPicturePlayback)
    #expect(controller.canStartPictureInPictureAutomaticallyFromInline)

    PlayerHostView.configurePictureInPicture(controller, backgroundPlay: false)
    #expect(controller.allowsPictureInPicturePlayback)
    #expect(controller.canStartPictureInPictureAutomaticallyFromInline == false)
}
```

- [ ] **Step 2: Run the test, watch it fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — `PlayerHostView.configurePictureInPicture(_:backgroundPlay:)` does not exist. (B1 currently hard-sets `allowsPictureInPicturePlayback = false`.)

- [ ] **Step 3: Implement**

```swift
/// Ruling 43: platform-standard PiP via AVKit. `canStartPictureInPictureAutomaticallyFromInline`
/// is NOT programmatic PiP -- the user backgrounding the app is the trigger and AVKit performs the
/// transition; what spec §10 and plan §6.5 forbid (and App Review rejects) is calling
/// `startPictureInPicture()` from code, which this app never does. Gated on the Background play
/// setting so a user who turned background playback OFF cannot get a floating video window by
/// backgrounding (ruling 34); the stock PiP button in the transport stays available either way.
static func configurePictureInPicture(_ controller: AVPlayerViewController, backgroundPlay: Bool) {
    controller.allowsPictureInPicturePlayback = true
    controller.canStartPictureInPictureAutomaticallyFromInline = backgroundPlay
}
```

Call it from `makeUIViewController` (replacing the `allowsPictureInPicturePlayback = false` line) and from `updateUIViewController` (so a Settings change mid-session takes effect).

Delegate on the `Coordinator`:

```swift
func playerViewControllerWillStartPictureInPicture(_ controller: AVPlayerViewController) {
    background.pictureInPictureActive = true    // flips the background policy to .continuesIfPossible
}

func playerViewControllerDidStopPictureInPicture(_ controller: AVPlayerViewController) {
    background.pictureInPictureActive = false
}

/// The player screen is still mounted behind the PiP window, so there is nothing to restore --
/// answer `true` immediately or AVKit waits on a completion that never comes.
func playerViewController(_ controller: AVPlayerViewController,
                          restoreUserInterfaceForPictureInPictureStopWithCompletionHandler
                          completionHandler: @escaping (Bool) -> Void) {
    completionHandler(true)
}
```

Set `controller.delegate = context.coordinator` in `makeUIViewController`.

In `dismantleUIViewController`, do not tear the player down while PiP is active. **Placement matters:** the guard goes *immediately before* the two teardown lines at the end of the function, **not** as the function's first line — `stopObserving()`, `stopMonitoring()` and the `model.currentItem`/`currentPlayer` hand-off clearing must still run in every case (they are B1 review findings M6 and Task-7's family gap; skipping them leaks an `NWPathMonitor` callback and leaves `AudioLanguageMenu`/`CaptionOverlay` selecting into a dismantled item). The function becomes:

```swift
static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
    coordinator.stopObserving()
    coordinator.stopMonitoring()
    coordinator.model?.currentItem = nil
    coordinator.model?.currentPlayer = nil
    coordinator.background.detach()
    // "never detach the player while PiP is active" (plan §6.5): pausing and nil-ing the player
    // here would cut audio and video out from under a live PiP window.
    // ponytail: PiP survives BACKGROUNDING, not a back-navigation out of the player -- SwiftUI
    // pops PlayerScreen, which releases the @State PlayerViewModel and with it the AVPlayer.
    // Making PiP outlive the route needs an app-scoped player holder; deferred (see "Out of scope").
    guard !coordinator.background.pictureInPictureActive else { return }
    controller.player?.pause()
    controller.player = nil
}
```

- [ ] **Step 4: Run the test, watch it pass**

Run: `ios/scripts/test.sh` — expected green.
Manual simulator check: launch `-fitrah-fake-player-hls`, tap the stock PiP button in the transport, confirm the floating window appears and the app does not crash on returning. Capture one screenshot of the PiP window under `screenshots/b2-task5/`.

**What the simulator cannot prove here:** auto-PiP on a real home-swipe gesture, and PiP interacting with a real lock/unlock. Simulator PiP support is partial and version-dependent — if the button does nothing on this simulator, note it and defer to Task 7 rather than "fixing" working code.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTubeTests/PlayerHostTests.swift
git commit -m "[FEAT]: iOS picture in picture"
```

---

### Task 6: Rate-limiter gate + pre-emptive foreground re-resolve

Carry-forwards **CF-B1-2** and **CF-B1-3**. Both live on the resolve path, so they share one review gate. (CF-B1-13 landed in Task 1 — it touches neither.)

**Files:**
- Modify: `ios/FitrahTube/Features/Player/PlayerViewModel.swift` (`StreamResolving` gains `kind:`; add `RateLimitedResolver`; `StreamState.isPlayable`; `reResolveIfExpiring`)
- Modify: `ios/FitrahTube/App/AppContainer.swift` (a single app-lifetime `SystemClock`; expose it)
- Modify: `ios/FitrahTube/Features/Player/BackgroundPlaybackController.swift` (fire the pre-emptive re-resolve on foreground)
- Modify: `ios/FitrahTube/Features/Player/PlayerScreen.swift` (compose `RateLimitedResolver`; fixture resolvers gain the `kind:` parameter)
- Test: `ios/FitrahTubeTests/PlayerViewModelTests.swift` (extend), `ios/FitrahTubeTests/Support/PlayerTestDoubles.swift` (create)

**Interfaces:**
- Consumes: `InnerTubeKit.ExtractionRateLimiter` (`check(_:kind:now:) -> Decision`, `onSuccess(_:)`), `InnerTubeKit.RequestKind`, `InnerTubeKit.SystemClock` (`var now: Duration`), `InnerTube.rateLimiter`.
- Produces:

```swift
protocol StreamResolving: Sendable {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved
}

/// B1's `LiveStreamResolver` keeps its job (a thin pass-through to the concrete InnerTubeKit actor)
/// and only gains the ignored `kind:` parameter. The limiter goes in a DECORATOR instead of inside
/// it, so the gate is testable over a fake without standing up a real `StreamResolver` with a stub
/// transport, remote-config store, session store, cache and availability gate.
struct RateLimitedResolver: StreamResolving {
    init(wrapping wrapped: any StreamResolving, rateLimiter: ExtractionRateLimiter, clock: any MonotonicClock)
}

extension StreamState {
    /// True for the two states that share `PlayerScreen`'s single playable `switch` branch.
    var isPlayable: Bool { get }
}

extension PlayerViewModel {
    /// §6.2 step 5. True when the resolved stream is within `margin` of its `expiresAt`.
    static func shouldPreemptivelyReResolve(_ state: StreamState, now: Date, margin: TimeInterval = 60) -> Bool
    /// Re-resolves in place if the above says so. Never hops out of the playable branch --
    /// on ANY outcome, success or failure.
    func reResolveIfExpiring(now: Date = Date()) async
}
```

**The silent-resolve rule (do not skip this — it is the whole reason this task is delicate).** `reResolveIfExpiring` routes through the same private `resolve` → `performResolve` pair every other caller uses, and that pair ends with an unconditional `state = result` (`PlayerViewModel.swift:141-168`) — on the failure path too, because `map(_ error:)` turns a throw into `.error`/`.contentUnavailable`/`.cooldown`. A proactive refresh runs *while a healthy stream is playing* and deliberately skips the `.loading` hop for exactly that reason; if it then applied a failure result, a `RateLimitedResolver` cooldown or a two-second network blip on foreground would knock a perfectly good player out of the `.ready`/`.rung2Progressive` branch, dismantle `PlayerHostView`, and drop the `AVPlayer` carrying the position. **A silent resolve must never apply a non-playable result**: on a throw, or on a mapping to anything non-playable, drop the result and leave `state` untouched — the unexpired stream keeps playing and *reactive* recovery (`handleRecoveryEvent`, which is allowed to surface failures because the stream has actually stopped working) owns real failures. This is a guard in the shared `performResolve`, keyed off a new `silent:` flag, not a second resolve path.

`silent:` is deliberately NOT the same flag as `showLoading:`. `handleRecoveryEvent` also passes `showLoading: false` (to hold the playable branch during an in-place recovery), but it *must* be able to land `.error`/`.contentUnavailable` — the stream really has failed by then. Only `reResolveIfExpiring` passes `silent: true`.

**Which resolves are gated, and why not all of them.** Android's `PlayerViewModel` calls the limiter from exactly three sites, all of them *force refreshes*: `forceRefreshCurrentStream()` → `MANUAL` (`PlayerViewModel.kt:1243`), the auto-recovery refresh → `AUTO_RECOVERY` (`:1256`) and the TTL refresh → `PROACTIVE_TTL_REFRESH` (`:1265`); the initial open is not gated. B2 matches that boundary, and it is load-bearing on iOS for a second reason: `StreamResolver.resolve` serves a non-forced call from `ManifestCache` with **zero network traffic**, and `ExtractionRateLimiter`'s 30 s `minExtractionInterval` would otherwise refuse a cache-served re-open of a video the user just watched. Gate `forceRefresh: true` only. `onSuccess(videoId)` is called after every successful resolve (Android `:1551`) so the `.player` exponential backoff clears.

- [ ] **Step 1: Write the failing tests**

In `PlayerViewModelTests.swift`:

```swift
@Test func aBlockedForceRefreshBecomesACooldownState() async {
    let clock = FixedMonotonicClock(now: .seconds(0))
    let resolver = RateLimitedResolver(wrapping: RecordingResolver(.hls),
                                       rateLimiter: ExtractionRateLimiter(), clock: clock)
    // Burn the per-video .player budget: 3 attempts in the 5-minute window, spaced past the 30s
    // minimum interval so the earlier ones are `.allowed`, not `.delayed`.
    for offset in [0, 40, 80] {
        clock.now = .seconds(offset)
        _ = try? await resolver.resolve("abc", purpose: .player, kind: .player,
                                        sourceChannelId: nil, forceRefresh: true)
    }
    clock.now = .seconds(120)
    do {
        _ = try await resolver.resolve("abc", purpose: .player, kind: .player,
                                       sourceChannelId: nil, forceRefresh: true)
        #expect(Bool(false), "expected a cooldown")
    } catch let error as ExtractionError {
        guard case .cooldown(let until) = error else { return #expect(Bool(false), "expected .cooldown") }
        #expect(until > Date())
    } catch { #expect(Bool(false), "wrong error type") }
}

@Test func anUngatedOpenIsNeverRateLimited() async throws {
    // A non-forced resolve may be a pure ManifestCache hit -- gating it would refuse a replay of a
    // video the user watched 10 seconds ago (Android gates only its three force-refresh sites).
    let inner = RecordingResolver(.hls)
    let resolver = RateLimitedResolver(wrapping: inner, rateLimiter: ExtractionRateLimiter(),
                                       clock: FixedMonotonicClock(now: .seconds(0)))
    for _ in 0..<5 {
        _ = try await resolver.resolve("abc", purpose: .player, kind: .player,
                                       sourceChannelId: nil, forceRefresh: false)
    }
    #expect(inner.calls.count == 5)   // all five reached the resolver; none was refused
}

@Test func recoveryReResolvesUseTheAutoRecoveryLane() async {
    let resolver = RecordingResolver(.hls)   // records every (kind, forceRefresh) pair
    let model = PlayerViewModel(resolver: resolver, settings: settingsStore(), args: PlayerArgs(videoId: "abc"))
    await model.open()
    await model.handleRecoveryEvent(.playbackError)
    #expect(resolver.calls.first?.kind == .player)
    #expect(resolver.calls.last?.kind == .autoRecovery)
    #expect(resolver.calls.last?.forceRefresh == true)
}

@Test func foregroundReResolvesOnlyWhenTheStreamIsAboutToExpire() async {
    let now = Date(timeIntervalSince1970: 1_000_000)
    let fresh = Resolved(stream: .hls(url: URL(string: "https://x/y.m3u8")!, isLive: false,
                                      audioOnlyURL: nil, captionTracks: []),
                         client: .visionos, userAgent: "UA", resolvedAt: now,
                         expiresAt: now.addingTimeInterval(600))
    let stale = Resolved(stream: fresh.stream, client: .visionos, userAgent: "UA",
                         resolvedAt: now, expiresAt: now.addingTimeInterval(30))
    #expect(PlayerViewModel.shouldPreemptivelyReResolve(.ready(fresh), now: now) == false)
    #expect(PlayerViewModel.shouldPreemptivelyReResolve(.ready(stale), now: now) == true)
    #expect(PlayerViewModel.shouldPreemptivelyReResolve(.loading, now: now) == false)
}

@Test func aPreemptiveReResolveNeverLeavesThePlayableBranch() async {
    // LOAD-BEARING: `.ready` and `.rung2Progressive` share one SwiftUI branch; a `.loading` hop
    // would dismantle PlayerHostView and drop the AVPlayer carrying the position. `RecordingResolver`
    // holds its answer until released, so the state can be observed WHILE the re-resolve is in
    // flight -- the exact window a `.loading` hop would show in.
    let resolver = RecordingResolver(.hls, holdsUntilReleased: true)
    let model = PlayerViewModel(resolver: resolver, settings: settingsStore(), args: PlayerArgs(videoId: "abc"))
    resolver.release()
    await model.open()
    let task = Task { await model.reResolveIfExpiring(now: Date.distantFuture) }
    await resolver.waitUntilCalled(count: 2)
    #expect(model.state.isPlayable)          // mid-flight: still the playable branch
    resolver.release()
    await task.value
    #expect(model.state.isPlayable)          // and after
    #expect(resolver.calls.last?.kind == .proactiveTTLRefresh)
}

@Test func aFailedPreemptiveReResolveLeavesTheHealthyStreamPlaying() async {
    // THE FAILURE PATH, and the reason `silent:` exists. A proactive refresh that throws (a
    // RateLimitedResolver cooldown, or a network blip on foreground) must NOT demote a player
    // that is still happily playing an unexpired stream: `state` is left exactly as it was, so
    // PlayerScreen's playable branch keeps its view identity and PlayerHostView is not rebuilt.
    for failure in [ExtractionError.cooldown(until: Date().addingTimeInterval(600)),
                    ExtractionError.transport("blip"),
                    ExtractionError.unavailable(videoId: "abc")] {
        let resolver = RecordingResolver(.hls)
        let model = PlayerViewModel(resolver: resolver, settings: settingsStore(),
                                    args: PlayerArgs(videoId: "abc"))
        await model.open()
        guard case .ready(let opened) = model.state else { return #expect(Bool(false), "expected .ready") }

        resolver.outcome = .failure(failure)
        await model.reResolveIfExpiring(now: Date.distantFuture)

        // Same state, same associated `Resolved` -- not merely "still playable".
        guard case .ready(let after) = model.state else { return #expect(Bool(false), "state was demoted") }
        #expect(after.resolvedAt == opened.resolvedAt)
        #expect(resolver.calls.last?.kind == .proactiveTTLRefresh)
    }
}

@Test func reactiveRecoveryStillSurfacesFailures() async {
    // The other half of the rule: `silent:` must NOT leak into recovery. When the stream has
    // genuinely stopped working, a failed re-resolve is still allowed to land a failure state.
    let resolver = RecordingResolver(.hls)
    let model = PlayerViewModel(resolver: resolver, settings: settingsStore(), args: PlayerArgs(videoId: "abc"))
    await model.open()
    resolver.outcome = .failure(.unavailable(videoId: "abc"))
    await model.handleRecoveryEvent(.playbackError)
    #expect(model.state == .contentUnavailable)
}
```

`FixedMonotonicClock` and `RecordingResolver` are new test helpers. Put them in `ios/FitrahTubeTests/Support/PlayerTestDoubles.swift` next to whatever fakes already live in that directory, exactly as follows:

```swift
import Foundation
import InnerTubeKit
@testable import FitrahTube

/// `MonotonicClock` whose `now` the test sets. `@unchecked Sendable`: test-only, single-threaded
/// use; the protocol requires `Sendable` but a settable stored property cannot prove it.
final class FixedMonotonicClock: MonotonicClock, @unchecked Sendable {
    nonisolated(unsafe) var now: Duration
    init(now: Duration) { self.now = now }
}

/// Records every resolve and can hold its answer so a test can observe state mid-flight.
/// `outcome` is settable so one instance can succeed on `open()` and then fail on the refresh.
final class RecordingResolver: StreamResolving, @unchecked Sendable {
    struct Call: Equatable { var videoId: String; var kind: RequestKind; var forceRefresh: Bool }

    enum Outcome { case hls, progressive, failure(ExtractionError) }

    private let holdsUntilReleased: Bool
    private let lock = NSLock()
    private var _outcome: Outcome
    private var _calls: [Call] = []
    private var _permits = 0

    var outcome: Outcome {
        get { lock.withLock { _outcome } }
        set { lock.withLock { _outcome = newValue } }
    }
    var calls: [Call] { lock.withLock { _calls } }

    init(_ outcome: Outcome, holdsUntilReleased: Bool = false) {
        self._outcome = outcome
        self.holdsUntilReleased = holdsUntilReleased
    }

    /// Lets one held resolve proceed (no-op when `holdsUntilReleased` is false).
    func release() { lock.withLock { _permits += 1 } }

    /// Suspends until `calls.count >= count`.
    func waitUntilCalled(count: Int) async {
        while calls.count < count { try? await Task.sleep(for: .milliseconds(1)) }
    }

    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        lock.withLock { _calls.append(Call(videoId: videoId, kind: kind, forceRefresh: forceRefresh)) }
        // ponytail: a 1 ms poll, same as `waitUntilCalled` above -- a continuation registry would
        // be more code than the whole helper for a test-only gate. Ceiling: the test must call
        // `release()` or it spins until the 60 s per-test limit kills it.
        if holdsUntilReleased {
            while lock.withLock({ () -> Bool in
                if _permits > 0 { _permits -= 1; return false }
                return true
            }) { try? await Task.sleep(for: .milliseconds(1)) }
        }
        let url = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
        switch outcome {
        case .hls:
            return Resolved(stream: .hls(url: url, isLive: false, audioOnlyURL: URL(string: "https://r1/a140")!,
                                          captionTracks: []),
                            client: .visionos, userAgent: "UA", resolvedAt: Date(),
                            expiresAt: Date().addingTimeInterval(3600))
        case .progressive:
            return Resolved(stream: .progressive(url: url, label: "360p"), client: .android,
                            userAgent: "UA", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(3600))
        case .failure(let error):
            throw error
        }
    }
}
```

`settingsStore()` in these tests is `UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: #function)!)`, matching what `SettingsStoreTests` already does.

- [ ] **Step 2: Run the tests, watch them fail**

Run: `ios/scripts/test.sh`
Expected: compile failure — the `kind:` parameter, `RateLimitedResolver`, `StreamState.isPlayable`, `shouldPreemptivelyReResolve` and `reResolveIfExpiring` do not exist.

- [ ] **Step 3: Implement**

`StreamResolving` gains `kind: RequestKind`; update the fake resolvers in `PlayerScreen.swift`'s `#if DEBUG` block and every test fake accordingly.

```swift
extension StreamState {
    var isPlayable: Bool {
        switch self {
        case .ready, .rung2Progressive: return true
        default: return false
        }
    }
}

struct RateLimitedResolver: StreamResolving {
    let wrapped: any StreamResolving
    let rateLimiter: ExtractionRateLimiter
    let clock: any MonotonicClock

    init(wrapping wrapped: any StreamResolving, rateLimiter: ExtractionRateLimiter, clock: any MonotonicClock) {
        self.wrapped = wrapped
        self.rateLimiter = rateLimiter
        self.clock = clock
    }

    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        // CF-B1-2, scoped exactly as Android scopes it (PlayerViewModel.kt:1243/1256/1265 are its
        // only three limiter call sites, all force-refreshes). A non-forced resolve may be served
        // straight from ManifestCache with no network at all -- gating it would make the 30s
        // minimum interval refuse a replay of a video the user just watched.
        if forceRefresh {
            switch await rateLimiter.check(videoId, kind: kind, now: clock.now) {
            case .allowed:
                break
            case .delayed(let delay, _):
                throw ExtractionError.cooldown(until: Date().addingTimeInterval(Self.seconds(delay)))
            case .blocked(_, let retryAfter):
                throw ExtractionError.cooldown(until: Date().addingTimeInterval(Self.seconds(retryAfter)))
            }
        }
        let resolved = try await wrapped.resolve(videoId, purpose: purpose, kind: kind,
                                                 sourceChannelId: sourceChannelId, forceRefresh: forceRefresh)
        await rateLimiter.onSuccess(videoId)   // clears the .player exponential backoff (Android :1551)
        return resolved
    }

    private static func seconds(_ duration: Duration) -> TimeInterval {
        let components = duration.components
        return TimeInterval(components.seconds) + TimeInterval(components.attoseconds) / 1e18
    }
}
```

`PlayerViewModel`: thread **two** new parameters through the private `resolve(forceRefresh:resetBudget:showLoading:)` and `performResolve(generation:forceRefresh:)` — `kind: RequestKind` and `silent: Bool = false`. `open()`/`retry()` pass `.player`; `handleRecoveryEvent` passes `.autoRecovery` (and **not** `silent`); `reResolveIfExpiring` passes `.proactiveTTLRefresh` and `silent: true`.

The guard goes in the shared `performResolve`, right after the existing generation check — one guard where every caller already routes through, not a second resolve path:

```swift
private func performResolve(generation: Int, forceRefresh: Bool, kind: RequestKind, silent: Bool) async {
    let result: StreamState
    do {
        let resolved = try await resolver.resolve(
            args.videoId, purpose: .player, kind: kind,
            sourceChannelId: args.channelId, forceRefresh: forceRefresh)
        result = Self.map(resolved)
    } catch {
        result = Self.map(error)
    }
    guard !Task.isCancelled, generation == self.generation else { return }
    // A silent (proactive TTL) refresh runs WHILE a healthy stream is playing -- that is why it
    // skips the `.loading` hop. It must therefore never apply a non-playable result: a
    // RateLimitedResolver cooldown or a network blip on foreground would otherwise knock a working
    // player out of PlayerScreen's `.ready`/`.rung2Progressive` branch, dismantle PlayerHostView
    // and drop the AVPlayer carrying the position -- for a stream that is still perfectly playable.
    // Dropping the result leaves the unexpired stream playing; reactive recovery
    // (`handleRecoveryEvent`, which never passes `silent`) owns real failures, because by then the
    // stream has actually stopped working.
    if silent, !result.isPlayable { return }
    state = result
}

/// §6.2 step 5: "On `willEnterForeground`, re-resolve pre-emptively if past
/// `resolvedAt + expires - margin`". Note this is NOT ruling 20's rejected 50-minute live timer --
/// that was a periodic timer against a self-refreshing HLS manifest; this fires once, only on
/// return to the foreground, only when the URL is genuinely near expiry.
static func shouldPreemptivelyReResolve(_ state: StreamState, now: Date, margin: TimeInterval = 60) -> Bool {
    guard let resolved = playable(state), let expiresAt = resolved.expiresAt else { return false }
    return now >= expiresAt.addingTimeInterval(-margin)
}

func reResolveIfExpiring(now: Date = Date()) async {
    guard Self.shouldPreemptivelyReResolve(state, now: now) else { return }
    // `showLoading: false` + `silent: true` are both load-bearing and are NOT the same flag:
    // `showLoading: false` holds the playable branch on the way IN (a `.loading` hop dismantles
    // PlayerHostView and drops the AVPlayer whose `currentTime()` carries the position);
    // `silent: true` holds it on the way OUT (see `performResolve`). `handleRecoveryEvent` passes
    // the first and not the second, because a genuine playback failure SHOULD surface.
    // `resetBudget: false` so a pre-emptive refresh cannot refill the recovery budget.
    await resolve(forceRefresh: true, kind: .proactiveTTLRefresh,
                  resetBudget: false, showLoading: false, silent: true)
}
```

`BackgroundPlaybackController` gains `var onWillEnterForeground: (() -> Void)?`, invoked from the `.willEnterForeground` handler; `PlayerHostView` wires it to `Task { await model.reResolveIfExpiring() }`.

`LiveStreamResolver` keeps its B1 body and only gains the ignored `kind:` parameter (`StreamResolver.resolve` has no such argument; its `purpose:` is documented as "reserved for caller-side rate-limiter lane coordination", which is exactly what `RateLimitedResolver` now performs).

`AppContainer` gains `let monotonicClock = SystemClock()` (one instance for the app's lifetime — a fresh `SystemClock` per player screen would give the rate limiter a new zero baseline and break every interval), and `PlayerScreen.resolver(container:)` builds:

```swift
RateLimitedResolver(wrapping: LiveStreamResolver(resolver: container.resolver),
                    rateLimiter: container.innerTube.rateLimiter,
                    clock: container.monotonicClock)
```

The `#if DEBUG` fixture resolvers are returned **unwrapped** — the screenshot rig must never be rate limited.

- [ ] **Step 4: Run the tests, watch them pass**

Run: `ios/scripts/test.sh` — expected green. Then re-run `ios/scripts/screenshots.sh "iPhone 17"` to confirm the fixture resolvers still compile and the B1 state screenshots are unchanged.

- [ ] **Step 5: Commit**

```bash
git add ios/FitrahTube/Features/Player/PlayerViewModel.swift \
        ios/FitrahTube/Features/Player/PlayerScreen.swift \
        ios/FitrahTube/Features/Player/BackgroundPlaybackController.swift \
        ios/FitrahTube/Features/Player/PlayerHostView.swift \
        ios/FitrahTube/App/AppContainer.swift \
        ios/FitrahTubeTests/PlayerViewModelTests.swift \
        ios/FitrahTubeTests/Support/PlayerTestDoubles.swift
git commit -m "[FEAT]: iOS resolve rate limiting and TTL refresh"
```

---

### Task 7: Acceptance pass — simulator matrix, then the device checklist

**Files:** touch only what a finding requires; screenshots under `screenshots/b2-task7/`.

- [x] **Step 1: Simulator matrix (do this yourself)**

Run `ios/scripts/screenshots.sh` (full matrix) and check:
  - The audio-only control appears on rung 1 with an itag 140 URL and is absent on rung 2 (`-fitrah-fake-player` fixture) — the "hide the control that has no backing" rule.
  - With audio-only on, the quality / captions / audio-language controls are gone and `player.audioOnlyPill` shows `player_status_audio_only`.
  - iPhone 17 + iPad Pro 13-inch (M5), portrait and landscape, en and ar (RTL: the audio-only button sits with the other overlay controls and mirrors with them), Dynamic Type `.accessibility3` (the status string does not clip).
  - VoiceOver: the audio-only button reads its label and its on/off state.
  - Backgrounding the simulator (`xcrun simctl launch` then Cmd-Shift-H) and returning does not crash, does not restart playback from 0, and does not resume a stream the user had paused.

**Result (2026-08-27):** `ios/scripts/screenshots.sh "iPhone 17"` — every leg `** TEST SUCCEEDED **`, 33 PNGs regenerated and read: the Phase-1 catalog a11y3/offline leg, b1-task3…b1-task10 (incl. the iPad leg the script runs unconditionally) and b2-task3. Three checks had no capture before this pass and were added to `ScreenshotTests` rather than left as prose: the audio-only control's **absence on rung 2** (`testPlayerRung2Pill`), and the audio-only surface in **ar portrait** and at **Dynamic Type `.accessibility3`** (`testPlayerAudioOnly`, two extra passes writing into `b2-task3`). The `.accessibility3` pill wraps to two lines and stays inside the screen; the ar pill mirrors to the leading edge with the rest of the overlay column. VoiceOver state is pinned as `isSelected` (the control carries `.isSelected`, not an `accessibilityValue` — no catalog string for on/off exists).

**What the matrix could not show:** the fixture clip is 4:3, so the player host pillarboxes it and the overlay pills/buttons — whose backgrounds are a translucent scrim — sit partly over the black letterbox, where the scrim is invisible against black. The white text stays high-contrast, the frames are fully on-screen (asserted), and a real 16:9 stream fills the host, so this is a fixture artifact, not a layout defect. Now Playing / the lock screen cannot be captured in the simulator at all (see Step 3).

- [x] **Step 2: Fix anything the matrix surfaces**, re-run `ios/scripts/test.sh`, commit `[FIX]: iOS B2 accessibility and layout pass`.

**Result:** the matrix surfaced no defect, so no fix commit exists. The one real defect this task did fix came from the Task 5 review, not the matrix: `PlayerHostView.Coordinator` had no `deinit` backstop, so a back-navigation out of the player while PiP was live deallocated the coordinator with the deferred teardown still owed — leaking the audio session, the lock-screen dictionary and the remote-command handlers for the life of the process (`[FIX]: iOS PiP teardown backstop`, red→green against a test that asserts `nowPlayingInfo == nil` and the transport disabled after the last reference drops).

- [x] **Step 3: Record the device checklist — USER-BLOCKED, do not attempt**

**USER-BLOCKED: no Apple Team ID.** The repo has no signing identity (`DEVELOPMENT_TEAM: $(FITRAH_TEAM_ID)` is unset; `CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]: NO` is the only reason the simulator builds work). Nothing below can be executed until the user supplies an Apple Team ID and a provisioning profile. **B2 is not "verified" until this list has been run on real hardware** — the simulator has no lock screen, no phone call, no route change, no real audio hardware and only partial PiP, so every behaviour B2 exists to deliver is unproven.

This is the single consolidated list: the eight items this plan wrote up front, plus everything Tasks 2, 5 and 6 turned up while implementing (each of those tasks reported behaviour it could not prove in the simulator; those are folded in here rather than left in task reports). Each item names its pass criterion.

**A — Background audio and the setting (ruling 34, the ruling the whole plan turns on)**

1. **Lock-screen audio, Background play ON** — start a video, lock the screen. *Pass:* audio continues uninterrupted; the video surface coming back on unlock does not restart or re-buffer.
2. **Background play OFF** — same, with the setting off. *Pass:* audio stops on lock (`audiovisualBackgroundPlaybackPolicy = .pauses`), and resumes only when the user presses play again — it must NOT auto-resume on unlock.
3. **Home-swipe, not just lock** — swipe home mid-playback with Background play ON. *Pass:* audio continues (this is a different lifecycle path from lock; both must hold).
4. **Polite hand-back** — start music in another app, pause it, play a FitrahTube video, then leave the player. *Pass:* the other app resumes (`setActive(false, options: .notifyOthersOnDeactivation)` in `detach()`), rather than the device going silent.

**B — Interruptions and route changes (ruling 44)**

5. **Incoming call, playing** — take a call mid-playback. *Pass:* audio pauses at `.began` and resumes at `.ended` when the system sends `.shouldResume`.
6. **Incoming call, already paused** — pause first, then take a call. *Pass:* playback does NOT start on `.ended` (`wasPlayingBeforeInterruption` is the whole point of that flag).
7. **Wired headphone unplug** — unplug mid-playback. *Pass:* playback pauses (`.oldDeviceUnavailable`); audio does not blast out of the speaker.
8. **Bluetooth headphone power-off** — same test over Bluetooth. *Pass:* identical to 7 (the route-change reason is the same; the delivery timing is not).

**C — Audio-only (Task 3)**

9. **Automatic audio-only swap** — with Background play ON, background the app and watch data usage on a metered connection (Settings → Cellular data usage before/after, or a proxy). *Pass:* video segments stop being fetched; audio keeps playing.
10. **One swap, not two** — background, wait past the URL-expiry margin, foreground again. *Pass:* the item is swapped to audio-only exactly once going in and restored to video exactly once coming out, position preserved, no double re-buffer (Task 6 orders the pre-emptive re-resolve BEFORE `.restoreVideo` precisely so this holds).
11. **Manual audio-only toggle on a real stream** — open a real (non-fixture) video with an itag 140 rendition, toggle audio-only on and off. *Pass:* the swap is seamless and position-preserving; the quality / captions / audio-language controls disappear while audio-only and come back after; the m4a rendition does not 403 (it carries the resolved `User-Agent`).

**D — Now Playing and the remote command centre (Task 4)**

12. **Lock screen and Control Centre** — *Pass:* title, channel and artwork are correct, and the scrubber tracks real playback.
13. **Live stream** — *Pass:* no scrubber duration on a live stream (`MPNowPlayingInfoPropertyIsLiveStream`), and nothing shows a bogus 0:00 length.
14. **Transport commands** — play/pause, ±10 s and scrub from the lock screen. *Pass:* every one moves playback and the lock-screen elapsed/rate updates immediately, not on the next tick.
15. **Next / previous** — *Pass:* absent, not dead (B5 owns the queue; ruling 28).
16. **Main-thread delivery (Task 4 concern, unproven in the simulator)** — drive the commands hard from the lock screen and from a Bluetooth remote. *Pass:* no crash, no `_dispatch_assert_queue` trap — `MPRemoteCommand` handlers touch `@MainActor` state and their delivery queue is undocumented.

**E — Picture in Picture (Task 5, ruling 43)**

17. **Auto-PiP, Background play ON** — swipe home mid-playback. *Pass:* PiP starts (AVKit performs it; the app never calls `startPictureInPicture()`).
18. **Auto-PiP, Background play OFF** — same. *Pass:* PiP does NOT start and playback pauses (a user who turned background playback off must not get a floating video window).
19. **Stock PiP button** — tap the PiP button in the AVKit transport with the setting in each position. *Pass:* it works in both — the setting gates only the automatic-from-inline transition, never the explicit button.
20. **PiP restore** — tap the restore control on the PiP window. *Pass:* the player screen comes back with playback continuing (the completion handler answers `true` synchronously); no black frame, no restart from 0.
21. **Back-navigation during PiP** — start PiP, then navigate back out of the player. *Pass:* the audio session, the lock-screen entry and the remote commands are all handed back — no orphaned Now Playing entry and no dead transport on the lock screen (this is what `Coordinator.deinit`'s backstop exists for). **Known limitation, not a defect:** the PiP window itself closes with the route; making it survive needs an app-scoped player holder (CF-B2-1 → B5).
22. **Re-resolve suppressed under PiP** — leave a PiP window running long enough for the stream URL to approach expiry, then return to the app. *Pass:* the PiP window does not re-buffer or blank on the way back (the pre-emptive re-resolve is skipped while `pictureInPictureActive`).

**F — Foreground re-resolve and rate limiting (Task 6)**

23. **Pre-emptive re-resolve, no spinner** — background the app past `resolvedAt + expires − margin`, then return. *Pass:* playback continues from the same position with a fresh URL; the screen never hops to a loading spinner or an error state, and a failing refresh leaves the healthy stream playing (the `silent:` contract).
24. **Rate-limit cooldown self-clears** — tap Retry repeatedly until the cooldown state appears, then wait it out. *Pass:* the countdown runs down and Retry works again with no app relaunch.

**G — AirPlay and process lifecycle**

25. **AirPlay** (ruling 29, free from the stock transport) — route to an Apple TV. *Pass:* playback starts on the TV. Plan §6.5 flags an IP-binding risk: if the external device 403s the item, the documented remedy is `allowsExternalPlayback = false` so the video mirrors from the phone instead. Report the result; do not pre-emptively implement the remedy.
26. **Swipe-to-dismiss** (player.md §24, iOS's narrower version of Android's task-removed contract) — swipe the app out of the App Switcher while audio plays. *Pass:* iOS terminates the process and audio stops, leaving no orphaned lock-screen entry. Confirm this is acceptable, or raise it.

---

## Out of scope for B2 (later sub-plans, or deliberate deferrals)

- **The embed rung's control-hiding.** Plan §6.6's rung-3 row hides quality, audio-only, PiP and background for the embed — B2 has no embed player to hide them on, so **B3 owns that**. Note for the B3 implementer: `PlayerViewModel.audioOnlyAvailable(for:)` already returns `false` for `.embed`/`.openInYouTube`, and `PlayerHostView.streamURL` returns `nil`, so the native controls are already inert there; B3 must additionally suppress PiP on the `WKWebView` rung. **Checked and corrected**: §6.6 does *not* hide PiP or audio-only on rung 2 (progressive) — only the quality control. B2 hides audio-only on rung 2 for a different reason (a muxed 360p progressive has no separate audio rendition), and leaves PiP on.
- **PiP surviving a back-navigation out of the player.** Popping `PlayerScreen` releases the `@State PlayerViewModel` and the `AVPlayer` with it. Keeping PiP alive across the route change needs an app-scoped player holder; B2 only guarantees PiP survives backgrounding, and refuses to pause the player during dismantle while PiP is active.
- **Audio-only auto-swap on cellular** (plan §6.5's "or when backgrounded on cellular"). The Background play + Audio only settings already carry user intent; adding an `NWPath`-driven third trigger buys a data saving the user can already choose. Add it if metered-data complaints arrive.
- **Next / previous remote commands and lock-screen queue** — B5 (Up Next, playlist queue, auto-advance). B2 explicitly disables both commands.
- **Playback-rate remote command / sleep timer** — ruling 35 keeps speed in the stock overflow menu; neither exists on Android.
- **Chromecast, downloads, dub audio** — Phase 3 (rulings 13, 28, 29).
- **`MPNowPlayingSession` / a separate audio process** — unnecessary for a single-player app; `MPNowPlayingInfoCenter.default()` is the whole surface.

## Carry-forward for B3+

- **CF-B2-8:** `BackgroundPlaybackController.detach()` calls `AVAudioSession.setActive(false, options: .notifyOthersOnDeactivation)` unconditionally. Once the embed rung (B3) or Shorts (B4) also owns audio, exactly one owner must deactivate the session, or leaving one player will silence the other.
- **CF-B2-9:** `NowPlayingSnapshot.make` returns `nil` for `.embed` states, so backgrounding the embed rung leaves a stale Now Playing entry. B3 must clear it when the embed takes over (the embed is paused on background anyway — plan §6.4 row 3 — so "clear it" is the correct behaviour).
