import AVFoundation
import Combine
import Foundation
import InnerTubeKit

/// Wraps InnerTubeKit's `StreamResolver` actor behind a protocol `PlayerViewModel` depends on --
/// depending on the concrete actor directly would leave tests unable to script resolve outcomes.
protocol StreamResolving: Sendable {
    /// The ONE requirement, and it carries `requiresMuxed` (owner ruling 2026-09-01): `true` demands
    /// a single-file muxed stream (itag 18) — the resolver skips the HLS rung, and neither reads nor
    /// writes the manifest cache nor touches the single-flight registry. `OfflineManager` and the
    /// cast's own walk (`PlayerViewModel.castMedia`) pass `true`; every other player call site takes
    /// the five-argument convenience below.
    ///
    /// R8-3: the direction used to be the other way round, with a defaulted six-argument overload
    /// forwarding to a five-argument requirement. A conformer that implemented only the short form
    /// then dropped the flag SILENTLY — every video save resolved `.hls`, hit `sourceURL`'s
    /// `(.hls, false)` nil arm and failed NO_STREAM, with a doc comment as the only guard. This way
    /// round, the compiler is the guard: a resolver that cannot answer the muxed question does not
    /// conform.
    ///
    /// `requiresHLS` is the same contract for the other save shape (Cubic C2): an audio-only save
    /// needs VISIONOS's itag 140, so the resolver skips the ANDROID rung, wherever a step-down put
    /// it, with the same cache and single-flight bypass, and it never records a bot-check trip (fix
    /// r5 N4-2). Only `OfflineManager` passes `true`: when its plain walk answered without itag 140
    /// (after a step-down, or with VISIONOS failing at walk time; fix r4 N-1), and for every walk the
    /// save must force (fix r5 N4-4).
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool,
                 requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved

    /// Recovery's step-down (owner directive 2026-10-14): `failed`'s stream for `videoId` resolved but
    /// would not play, so the next walks for this video resume the ladder at the rung after it
    /// (`StreamResolver.demote`). A requirement with NO default, on purpose (the R8-3 rule above): a
    /// decorator that forgot to forward it would silently send every step-down back to rung 1.
    /// Resolvers with no ladder (fixtures, the offline resolver, test doubles) implement it empty.
    func demote(_ videoId: String, failed: ClientFamily) async
}

extension StreamResolving {
    /// Every player call site: a plain walk, muxed not required.
    ///
    /// Do NOT implement this form in a conformer: it is extension-only, so it dispatches
    /// STATICALLY and a conformer's own five-argument method would be bypassed on every
    /// `any StreamResolving` call (r8 Minor 6 -- the opposite-direction hazard of the footgun R8-3
    /// removed, and much milder: this body always passes the player contract's `false`).
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        try await resolve(videoId, purpose: purpose, kind: kind, sourceChannelId: sourceChannelId,
                          forceRefresh: forceRefresh, requiresMuxed: false, requiresHLS: false)
    }
}

/// Production `StreamResolving` -- thin pass-through to the real actor (`AppContainer.resolver`).
/// `kind` is ignored here on purpose: `StreamResolver.resolve` takes no such argument, and its
/// `purpose:` is documented as "reserved for caller-side rate-limiter lane coordination" -- which is
/// exactly what `RateLimitedResolver` (the decorator wrapped around this) now performs.
struct LiveStreamResolver: StreamResolving {
    let resolver: StreamResolver

    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool,
                 requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
        try await resolver.resolve(videoId, purpose: purpose, sourceChannelId: sourceChannelId,
                                   forceRefresh: forceRefresh, requiresMuxed: requiresMuxed, requiresHLS: requiresHLS)
    }

    func demote(_ videoId: String, failed: ClientFamily) async {
        await resolver.demote(videoId, failed: failed)
    }
}

/// CF-B1-2. The limiter goes in a DECORATOR rather than inside `LiveStreamResolver`, so the gate is
/// testable over a fake without standing up a real `StreamResolver` with a stub transport,
/// remote-config store, session store, cache and availability gate.
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
                 sourceChannelId: String?, forceRefresh: Bool,
                 requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
        // Scoped exactly as Android scopes it (`ui/player/PlayerViewModel.kt:1243/1256/1265` are its
        // only three limiter call sites, all force-refreshes). A non-forced resolve may be served
        // straight from `ManifestCache` with no network at all -- gating it would make the 30 s
        // minimum interval refuse a replay of a video the user just watched. The `.prefetch` lane
        // is gated even though it is never forced (B5, reconciliation note 7): a prefetch is the
        // non-forced resolve most likely to hit the network, and it is the one lane the limiter
        // exists to keep behind the interactive one.
        // `.autoRecovery` is asked too and always allowed: `ExtractionRateLimiter.check` is the one
        // place that rule lives (owner directive 2026-10-14).
        if forceRefresh || kind == .prefetch {
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
                                                 sourceChannelId: sourceChannelId, forceRefresh: forceRefresh,
                                                 requiresMuxed: requiresMuxed, requiresHLS: requiresHLS)
        await rateLimiter.onSuccess(videoId)   // clears the .player exponential backoff (Android :1551)
        return resolved
    }

    func demote(_ videoId: String, failed: ClientFamily) async {
        await wrapped.demote(videoId, failed: failed)
    }

    private static func seconds(_ duration: Duration) -> TimeInterval {
        let components = duration.components
        return TimeInterval(components.seconds) + TimeInterval(components.attoseconds) / 1e18
    }
}

/// Playback Phase 2 (spec R43): the background HD upgrade of a 360p stream. Three closures rather than a protocol: the
/// live set forwards to `StreamResolver`; the simulator hook and the tests script their own.
nonisolated struct HDUpgrade: Sendable {
    var upgrade: @Sendable (_ videoId: String) async -> Resolved?
    /// R41: no HD for that video.
    var failed: @Sendable (_ videoId: String) async -> Void
    /// R42 (revision 4): a `sidx` 403 after its retry -- the 30-minute `tv_samsung` pause.
    var refused: @Sendable () async -> Void

    static func live(_ resolver: StreamResolver) -> HDUpgrade {
        HDUpgrade(upgrade: { await resolver.upgrade($0) }, failed: { await resolver.hdFailed($0) },
                  refused: { await resolver.hdRefused() })
    }
}

extension StreamState {
    /// The `Resolved` behind the two states that share `PlayerScreen`'s single playable `switch`
    /// branch, `nil` for every other state. M5 (fix round 1): this was three copies of the same
    /// switch -- `StreamState.isPlayable`, `PlayerViewModel.playable` and
    /// `PlayerHostView.resolvedStream` -- so a new playable state had to be remembered in three
    /// files or the player would half-recognise it.
    var resolved: Resolved? {
        switch self {
        case .ready(let resolved), .rung2Progressive(let resolved): return resolved
        default: return nil
        }
    }

    var isPlayable: Bool { resolved != nil }

    /// A live HLS manifest. Same M5 reasoning as `resolved` above -- the `case .hls(_, let isLive, …)`
    /// unwrap was written out in the host and again in `NowPlayingSnapshot`, so a rung that ever
    /// carries liveness has one place to say so.
    var isLive: Bool {
        guard let resolved, case .hls(_, let isLive, _, _, _) = resolved.stream else { return false }
        return isLive
    }

    /// A live HLS that is live NOW (spec §9.1, RV I11). `isLive` alone is also true for an ended broadcast (F28), which
    /// plays as a recording: no live refresh timer, and its replacement keeps its clock.
    var isLiveNow: Bool { isLive && resolved?.isLiveNow == true }
}

/// Android's `PlayerViewModel` resolve pipeline (`player.md` §2.2), the InnerTubeKit-backed slice
/// of it: `open()`/`retry()` walk `StreamResolver`'s ladder and map the outcome onto `StreamState`.
/// Same generation-guard discipline as `HomeViewModel`/`ContentListViewModel` (cancel the prior job,
/// a superseded completion is discarded) -- here there's only one job kind (resolve), not a
/// load/load-more pair, so one `resolveTask` + `generation` counter is enough.
@MainActor @Observable final class PlayerViewModel {
    private(set) var state: StreamState = .idle

    /// The quality menu's current pick (spec §10, `QualityCeiling.swift`). Session-only, like
    /// Android's -- `QualityTrackSelector` never persists it either (player.md §4.3). `PlayerHostView`
    /// re-applies this to every new `AVPlayerItem` it builds, so a pick made mid-play survives a
    /// re-resolve.
    var selectedQuality: QualityOption = .auto

    /// The audio-language menu's sticky pick (`AudioLanguageMenu.swift`, player.md §2.2/§8.6): an
    /// extended-language-tag (or, for a track the asset gives no tag, its display name -- see
    /// `AudioLanguageMenu`'s glue). Session-only, re-applied to every new/replaced `AVPlayerItem`
    /// the same way `selectedQuality` is -- set on pick, read back by `AudioLanguageMenu` on every
    /// prepare via the pure `AudioLanguageSelection.pickIndex`.
    var stickyAudioLanguage: String?

    /// Hand-off slot: `PlayerHostView` is the only place that owns the AVKit-managed `AVPlayerItem`
    /// (`AVPlayerViewController.player.currentItem`); `AudioLanguageMenu` is a SwiftUI overlay in
    /// `PlayerScreen` with no view-hierarchy access to that item, so this is how it reaches it to
    /// read/apply audible-track selections. Written on every (re)build/update -- same hook
    /// `PlayerHostView.applyQuality` uses for the quality ceiling.
    /// `didSet` loads the new item's audio options HERE, not from the menu: the menu renders
    /// nothing until there are >1 options, and a `.task` on that empty body never runs -- no globe,
    /// and AVPlayer keeps its own device-language dub pick. The previous item's
    /// options are cleared first, so nothing lists or selects into its group while the load runs.
    var currentItem: AVPlayerItem? {
        didSet {
            guard currentItem !== oldValue else { return }
            audioLanguageGroup = nil
            audioLanguageOptions = []
            audioLanguageLoad = Task { await AudioLanguageMenu.load(self) }
            legibleGroup = nil
            captionSelectionSync = nil
            captionsLoad = Task { await CaptionsMenu.load(self) }
        }
    }

    /// The load `currentItem`'s `didSet` started, kept so a caller can await it landing.
    private(set) var audioLanguageLoad: Task<Void, Never>?

    /// `AudioLanguageMenu.load`'s result for `currentItem`: what the menu lists and selects into.
    var audioLanguageGroup: AVMediaSelectionGroup?
    var audioLanguageOptions: [AudioLanguageOption] = []

    /// Same hand-off reasoning as `currentItem`, one level up: `CaptionOverlay` needs the live
    /// `AVPlayer` itself (`addPeriodicTimeObserver` is an `AVPlayer` method, not `AVPlayerItem`'s),
    /// and `PlayerHostView` is the only owner of the AVKit-managed player. Written alongside
    /// `currentItem` in `PlayerHostView`'s make/update pair.
    var currentPlayer: AVPlayer?

    /// The captions menu's pick (`CaptionChoice`, nil = Off). Session-only, like `selectedQuality`/
    /// `stickyAudioLanguage` -- survives a re-resolve and Up Next (`CaptionChoice.resolve`), never
    /// persisted; a freshly opened player screen starts Off.
    var captionChoice: CaptionChoice?

    /// `CaptionsMenu.load`'s result for `currentItem`: its legible (HLS SUBTITLES) group -- the
    /// uploader's tracks. Cleared with the audio group on every new item.
    var legibleGroup: AVMediaSelectionGroup?
    /// The load `currentItem`'s `didSet` started, kept so a caller can await it landing.
    private(set) var captionsLoad: Task<Void, Never>?
    /// Follows AVKit's own Subtitles menu on `currentItem` (`CaptionsMenu.load`); a new item drops it.
    @ObservationIgnored var captionSelectionSync: AnyCancellable?

    /// Ruling 34: the Settings "Audio only" value SEEDS this per-session toggle (Android's settings
    /// value is written and never read -- player.md §15, defect). Session-only after seeding:
    /// flipping it here never writes back to `SettingsStore`, matching Android's own non-persisted
    /// toggle (player.md §5, "Not persisted; resets with the ViewModel").
    var audioOnly: Bool

    private let resolver: any StreamResolving
    private let settings: any SettingsStore
    /// Task 4: `PlayerHostView` reads this for the Now Playing metadata (title / channel /
    /// thumbnail / seed duration). Readable rather than passed to the host as a second stored
    /// property -- the host already holds this VM, so a parallel `args` parameter would mean the
    /// same value arriving twice by two routes. Mutable since B5: an advance / Up Next tap swaps
    /// it, so `PlayerScreen` reads THIS, never its own initial `args`.
    private(set) var args: PlayerArgs

    /// The video `state` currently describes: written next to every `state = result` in
    /// `performResolve`, and nowhere else. `args` moves in `swapArgs` BEFORE the advance's resolve
    /// lands, so between the two `args.videoId` is the NEXT video while `state` still carries the
    /// OLD stream -- a host keyed on `args` read that as a video change, rebuilt the old url at 0,
    /// and then resumed the next video from that restarted clock (B5 final review, IMPORTANT-1).
    private(set) var resolvedVideoId: String?

    /// `PlayerHostView`'s position-carry key (`continuesCurrentVideo` / `lastVideoId`): the video
    /// the stream on screen belongs to. NOT the periodic observer's guard -- that one asks whether
    /// the VM still WANTS this video (`args`), so an outgoing item's ticking clock cannot land in
    /// the `currentTime` `swapArgs` just zeroed for the next one.
    var hostVideoId: String { resolvedVideoId ?? args.videoId }

    #if DEBUG
    /// CF-B5-h: an in-app signal for the "no background auto-advance on the simulator" item --
    /// read off `player.upNext.header`'s accessibilityValue. Debug builds only.
    private(set) var playToEndCalls = 0
    private(set) var advanceCalls = 0
    /// The CDN status the last recovery event carried, for the DEBUG route probe's `status=` -- the
    /// simulator step-down proof reads the real 401 its local server returned (plan Task 7).
    private(set) var debugLastRecoveryStatus: Int?
    #endif

    /// Ruling 33 / spec §10. Empty in single-video mode; populated from `args.playlistId`.
    private(set) var queue = PlayerQueue.start(items: [], targetVideoId: nil, startIndex: 0,
                                               shuffled: false, cursor: nil)

    /// CF-B1-8's hoist. Session-only (ruling 32 -- nothing persists this). Three consumers:
    /// `PlayerHostView` reads it to restore position after a state hop dismantled the host,
    /// Task 3's double-tap seek reads it as the seek origin, and `advance()` resets it to 0.
    /// Written by the host's existing 1 s periodic observer, so it is ~1 s coarse -- which is
    /// why the host still prefers a LIVE `AVPlayer.currentTime()` when it has one, and falls
    /// back to this only when there is no live player left to ask.
    var currentTime: TimeInterval = 0

    /// B5 Task 3. Set from `AVPlayerItem.presentationSize` by the host's periodic observer.
    /// Defaults FALSE: until an item is ready the size is `.zero`, and "unknown" must read as
    /// landscape or a 16:9 video would briefly fullscreen itself in portrait on open.
    var videoIsPortrait = false
    /// The centre-double-tap fill/fit override, sticky per stream (`PlayerFragment.kt:3525-3547`).
    /// Reset in `swapArgs` alongside `currentTime` -- "per stream", not per session.
    var videoZoomed = false
    /// Transient ±10 s feedback for the gesture overlay; cleared ~600 ms after it is set.
    var seekFeedback: PlayerGestures.Zone?
    /// True while AVKit owns the screen with its OWN fullscreen presentation (the iPad path,
    /// ruling 42). Our gesture recognizer is disabled while it is true, because AVKit's fullscreen
    /// already has its own double-tap gravity toggle -- two would double-fire.
    var avKitFullscreen = false
    /// The player's one transient banner slot (`PlayerScreen.transientBanner`): the fit/zoom
    /// toggle's "Fill screen"/"Fit to screen" (written by the host's double-tap handler, which has
    /// no SwiftUI binding) and the one-time fullscreen zoom hint. Same shape as `Router.pendingBanner`.
    var banner: BannerMessage?

    private let queueSource: (any PlaylistQueueSource)?
    /// Ids the `.prefetch` lane already warmed. A repeat resolve of one is a `ManifestCache` hit
    /// that still spends the per-video retry budget and the global prefetch lane, so the window
    /// only resolves what it has not resolved before. Success-only: a refusal leaves the id
    /// eligible for the next window (CF-B2-2 rule (a) says skip silently, not give up).
    private var prefetchedIds: Set<String> = []
    private var consecutiveSkips = 0
    /// The in-flight page fetch (Cubic P3). A single-flight boolean made a concurrent caller
    /// return EARLY: an end-of-item `advance()` landing while `open()`'s page was still inside
    /// `queueSource.page(...)` saw an empty `upcoming` and showed `.queueEnded` with a whole page
    /// still loading. Concurrent callers await this instead.
    private var pagingTask: Task<Void, Never>?

    private var generation = 0
    private var resolveTask: Task<Void, Never>?
    private var recoveryBudget = RecoveryBudget()
    /// The recovery in progress, if any. A token rather than a flag (stage 5 F4): a recovery that a
    /// user action superseded can stay suspended on the network for a long time (`Task.value` does
    /// not end when its awaiter is cancelled), and the next video's one-shot failure must not be
    /// dropped behind it. A fresh stream (`resolve(resetBudget: true)`) drops the claim, and the old
    /// call's exit clears only its own.
    private var recoveryToken: UUID?
    private var isRecovering: Bool { recoveryToken != nil }
    /// The videos this player has demoted a rung for: a step-down, or an exhaustion's demotion
    /// (stage 7 r2 N3; R2-1; fix r4 N-5 and Codex N1). Ids, not a per-stream flag: the resolver's
    /// demotion lasts the session per video, so Retry and a return to the video (a -> b -> a) must
    /// not forget it, and another video simply never matches.
    /// ponytail: per view model, so leaving the video and opening it again starts unguarded (R2-1
    /// scenario B, one extra Retry). Ask the resolver (`isDemoted`) if that ever matters.
    private var steppedDownVideoIds: Set<String> = []
    /// Task 8: this stream already mirrored once out of an AirPlay failure (`AirPlayFallback`).
    /// Per stream, reset in `swapArgs` alongside `videoZoomed`.
    /// ponytail: never re-armed within one video, so re-selecting an AirPlay route after a
    /// mirroring fallback gets no second fallback. Re-arm on an external-route change if that ever
    /// bites -- it needs a route observer this player does not otherwise want.
    ///
    /// Readable (R9-8): it is the TRUTH about the current video's route, and a host rebuilt inside
    /// that video -- a forced re-resolve that threw, then Retry -- builds a fresh `AVPlayer` whose
    /// `allowsExternalPlayback` write would otherwise turn the dead route back on.
    private(set) var airPlayFellBack = false

    /// Spec R43: the HD stream `PlayerHostView` should stage for the 360p on screen. Set when `hd.upgrade` answers;
    /// cleared by every new resolve, by the commit, by a failure and by the screen going away.
    private(set) var pendingUpgrade: Resolved?
    /// The 360p stream a committed HD replaced, kept for R43's drop-back while `.ready` shows that HD stream.
    private var standardBeforeUpgrade: Resolved?
    private let hd: HDUpgrade?
    private var upgradeTask: Task<Void, Never>?
    /// R41: the last `hd.failed` report. The next ask waits for it, so the resolver has denied the video before it is
    /// asked again (a 360p incident's own re-resolve starts that ask right away).
    private var hdFailedReport: Task<Void, Never>?

    /// The app's ONE cast seam (spec §10), or nil for a player that has no cast affordance: Shorts
    /// (the cast button is the main player's toolbar only) and every test that is not about
    /// casting. `reconcile(_:)` is then a no-op -- the same "no cast at all" posture a container
    /// whose `GCKCastContext` could not be created already has.
    private let cast: CastController?

    /// Spec §9.1 (Task 32): the backend's current lives for a live-category channel; nil = no live-channel mode.
    private let liveLookup: (@Sendable (String) async throws -> LiveChannelLives)?
    /// Injected so the live refresh timer, step 4's waits and the lookup's cap are testable without waiting.
    private let sleep: @Sendable (Duration) async throws -> Void
    private var liveRefreshTask: Task<Void, Never>?
    /// Spec §9.1: the live-channel incident in progress (one per video id) and its step 4 wait.
    private var liveIncident: LiveChannelRecovery?
    private var liveRetryTask: Task<Void, Never>?
    /// Set by `performResolve` when a live-channel resolve did not land live now, with the trigger it is; `resolve` acts on it.
    private var liveMiss: (generation: Int, trigger: LiveChannelRecovery.Trigger)?
    #if DEBUG
    private var debugForceEndTask: Task<Void, Never>?
    #endif
    /// Live-channel mode needs the lookup too: an offline player built from a live item's args has none.
    private var isLiveChannelMode: Bool { args.liveChannelId != nil && liveLookup != nil }
    /// The proactive refresh in flight (the live timer's or the foreground check's). The other one joins it: two forced
    /// walks for one expiry trip the limiter's 30 s interval, and the second's generation bump discards the first's
    /// answer. Superseded by a user action it can still be in flight; a join then only skips a refresh the fresh
    /// stream does not need.
    private var proactiveRefresh: Task<Void, Never>?

    /// M4 (B1 final review): `catalog` and `favorites` were stored and never read -- `PlayerToolbar`
    /// reaches favorites through the environment container on its own, and nothing in the player
    /// touches the catalog.
    init(resolver: any StreamResolving, settings: any SettingsStore, args: PlayerArgs,
         queueSource: (any PlaylistQueueSource)? = nil, cast: CastController? = nil, hd: HDUpgrade? = nil,
         liveLookup: (@Sendable (String) async throws -> LiveChannelLives)? = nil,
         sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.resolver = resolver
        self.settings = settings
        self.args = args
        self.queueSource = queueSource
        self.cast = cast
        self.hd = hd
        self.liveLookup = liveLookup
        self.sleep = sleep
        self.audioOnly = settings.audioOnly
    }

    /// The Settings "Background play" value, read live so a change made in Settings while the player
    /// is open takes effect on the next background transition (ruling 34).
    var backgroundPlay: Bool { settings.backgroundPlay }

    /// Phase 3 Task 7: the offline presentation flag. True while this player plays a saved file
    /// through `OfflineResolver` — `PlayerScreen`/`PlayerToolbar` hide the quality control, the
    /// cast affordance (Task 8 consumes this same flag) and the Save button, and the queue is
    /// disabled (the screen passes no `queueSource`). favorite/share/report stay: sharing the
    /// LINK is allowed; only media files never leave the sandbox.
    var isOfflinePlayback: Bool { args.offlineItemId != nil }

    /// The Settings "Safe Mode" value, read live so a change made while the player is open takes
    /// effect on the next resolve (same shape as `backgroundPlay`). Ruling 58 + spec §10: this is
    /// also B5's auto-advance hook -- Up Next reads THIS, not `SettingsStore` directly.
    var safeMode: Bool { settings.safeMode }

    /// Audio-only needs a real itag 140 URL. Rung 2 (a single muxed 360p progressive) has none, so
    /// the toggle is hidden there -- the same "hide the control that has no backing" rule spec §10
    /// applies to the quality control on rung 2.
    static func audioOnlyAvailable(for state: StreamState) -> Bool {
        // ponytail: no `isLive` check -- a live HLS has never been seen carrying an itag 140 url, so
        // the `audioOnlyURL != nil` test already excludes it. Add one here if a live stream ever
        // resolves with one (the swap would then hand AVPlayer a non-live audio rendition).
        guard case .ready(let resolved) = state,
              case .hls(_, _, let audioOnlyURL, _, _) = resolved.stream else { return false }
        return audioOnlyURL != nil
    }

    func open() async {
        seedQueue()                                   // issue 2: the screen's own rows, no network, before the resolve
        await resolve(forceRefresh: false, kind: .player)
        // ponytail: sequential, deliberately. Android defers its prefetch to the first `isPlaying`
        // (`PlayerFragment.kt:1167-1168`) to keep it off the critical path; awaiting the player's
        // own resolve first achieves the same with no observer and no timer. Ceiling: on the
        // fallback path (nothing seeded) the queue (and Up Next) appears only after the current
        // video resolved; a seeded queue is already in place before it.
        if queue.items.isEmpty { await loadQueue() }  // fallback: nothing was passed (deep link, Play All mid-reload)
        // A launch that already lands within `pageThreshold` of the page end (a row tap near the
        // bottom of a page, a `targetVideoId` there) needs its next page NOW, not on the first
        // advance -- otherwise Up Next lists a truncated tail, or nothing at all (ruling 33 hides
        // an empty section) while a whole page is still unfetched.
        await pageIfNeeded()
        await prefetchUpcoming()
    }

    // MARK: - Queue (B5 task 2)

    /// Owner issue 2: what the playlist screen already loaded. Same guards as `loadQueue`.
    private func seedQueue() {
        guard args.playlistId != nil, queueSource != nil, let items = args.queueItems, !items.isEmpty else { return }
        queue = PlayerQueue.start(items: items, targetVideoId: args.targetVideoId, startIndex: args.startIndex,
                                  shuffled: args.shuffled, cursor: args.queueContinuation)
    }

    /// Page 1, then the bounded deep scan for `targetVideoId` (`PlayerViewModel.kt:904-1031`,
    /// bounds `:1782-1785`: 250 items / 3 s). Any throw leaves the queue empty and the state
    /// untouched -- a failed queue load must never kill a playing video.
    private func loadQueue() async {
        guard let playlistId = args.playlistId, let queueSource else { return }
        let deadline = Date().addingTimeInterval(3)
        let target = args.targetVideoId
        var items: [ContentItem] = []
        var cursor: String? = nil
        do {
            repeat {
                let page = try await queueSource.page(playlistId: playlistId, continuation: cursor)
                items.append(contentsOf: page.items)
                cursor = page.continuation
            } while target != nil && !items.contains { $0.id == target }
                && cursor != nil && items.count < 250 && Date() < deadline
        } catch {
            return
        }
        queue = PlayerQueue.start(items: items, targetVideoId: args.targetVideoId,
                                  startIndex: args.startIndex, shuffled: args.shuffled, cursor: cursor)
    }

    /// `AVPlayerItemDidPlayToEndTime` (`PlayerFragment.kt:1247-1249` -> `PlayerViewModel.kt:389`).
    /// Ruling 58 / spec §10 / plan §6.10: Safe Mode disables AUTO-advance only. `safeMode` is the
    /// VM property, never `SettingsStore` (CF-B3-2) -- one Safe Mode reader in the player.
    /// Single-video mode (no queue) does nothing: AVKit sits on the last frame with its own replay.
    func playToEnd() async {
        #if DEBUG
        playToEndCalls += 1
        #endif
        if args.liveChannelId != nil {          // §9.1: never advance the queue in live-channel mode
            await liveChannelTrigger(.ended)
            return
        }
        // `isAdvancing`: one advance per end -- a second end posted for the same ended item (a
        // `.resume` playing it while the advance is out) must not skip a lecture.
        guard !safeMode, !queue.items.isEmpty, !isAdvancing else { return }
        isAdvancing = true
        advanceSerial += 1
        let mine = advanceSerial
        // Not a later advance's flag: the landing clears this one before the prefetch (round 5, N1),
        // so the next lecture's own end can start another advance while this one still prefetches.
        defer { if advanceSerial == mine { isAdvancing = false } }
        await advance()
    }
    private var advanceSerial = 0

    /// 1.0.4 round 3 (review m1): an auto-advance is in flight, from the end until the next lecture
    /// has landed (round 5, N1). The ended item leaves the player paused, so an interruption that
    /// begins now would read "not playing" and its `.shouldResume` would leave the next lecture
    /// paused; the host counts this as playing (`resumeIntentPending`).
    private(set) var isAdvancing = false

    private func advance() async {
        #if DEBUG
        advanceCalls += 1
        #endif
        // Cubic #4: `pageIfNeeded()` can suspend (its own fetch, or joining an in-flight one), and a
        // user action landing in that window -- `play(at:)`, `retry()` -- owns the queue now. Every
        // user action routes through `resolve()`, which bumps `generation`, so a stale advance
        // aborts here instead of walking the queue out from under the user's pick and superseding
        // their resolve with its own.
        let myGeneration = generation
        await pageIfNeeded()
        guard myGeneration == generation else { return }
        guard let next = queue.advance() else {
            // `PlayerViewModel.kt:1920-1923`: no next item and no more pages -- playback stops.
            // NOT `.idle` (reconciliation note 9): that is the pre-open value and renders as a
            // Retry-less "Loading..." spinner forever. A finished playlist is a real terminal
            // state with real copy.
            state = .queueEnded
            return
        }
        swapArgs(to: next)
        // `showLoading: false` is LOAD-BEARING (reconciliation note 2): a `.loading` hop
        // dismantles PlayerHostView, detaches the audio session and kills background
        // auto-advance. NOT `silent:` -- a failed advance MUST surface, because that is what
        // drives auto-skip and, past the cap, the terminal state the user sees.
        // `forceRefresh: false` is CF-B2-2 rule (b): land on the warmed ManifestCache entry.
        let stillCurrent = await resolve(forceRefresh: false, kind: .player, showLoading: false)
        // Review F1: the same supersession check as after `pageIfNeeded()` above, for the OTHER
        // suspension. A `play(at:)` landing during THIS resolve owns the queue now -- it cancelled
        // this resolve and set `.loading`; without this guard the resumed advance read that
        // `.loading` as a failed advance, burned a skip walking `queue.advance()` off the user's
        // pick, and its recursive resolve superseded the tap's.
        guard stillCurrent else { return }
        if state.isPlayable {
            consecutiveSkips = 0             // `PlayerViewModel.kt:1916`
            // Round 5 (re-review N1): landed, so the lecture is the user's from here. Kept through the
            // prefetch, the flag resumed a lecture the user paused after an interruption, and
            // swallowed the end of a short next lecture.
            isAdvancing = false
            await prefetchUpcoming()
            await pageIfNeeded()
        } else if queue.hasNext, AutoSkipPolicy.decide(consecutive: consecutiveSkips, limit: 3) {
            consecutiveSkips += 1
            await advance()                  // bounded by the policy above; max depth 3
        }
        // else: leave the non-playable state on screen. Ruling 14's one terminal surface, with
        // PlayerStateView's Retry -- exactly what Android shows past MAX_CONSECUTIVE_SKIPS.
    }

    /// The Up Next tap (`PlayerViewModel.kt:355-387`). A deliberate user action, so this one DOES
    /// show the loading card with the new thumbnail. Index-addressed (Cubic P2): a duplicate id in
    /// the playlist must play the tapped occurrence, not the first one.
    func play(at index: Int) async {
        guard let item = queue.select(at: index) else { return }
        swapArgs(to: item)
        consecutiveSkips = 0
        await resolve(forceRefresh: false, kind: .player)
        await prefetchUpcoming()
        await pageIfNeeded()
    }

    /// The queue context rides along: playlistId/shuffled keep the queue alive across the hop;
    /// targetVideoId/startIndex are consumed and must NOT be re-applied to the next video.
    private func swapArgs(to item: ContentItem) {
        // The claim and the pause belong to the video they were taken for. Leaving them set meant
        // the phone played B while every cast field still described A: the session's end seeked B
        // to A's receiver position and played it, and nothing ever cast B -- phone and TV on
        // different videos for the rest of the session. Released with the id actually claimed,
        // before `args` moves off it.
        if let claimedVideoId { cast?.releaseClaim(claimedVideoId, owner: castOwner) }
        claimedVideoId = nil
        pausedForCast = false
        var next = PlayerArgs(item: item)
        next.playlistId = args.playlistId
        next.shuffled = args.shuffled
        args = next
        currentTime = 0                      // a new video starts at the beginning
        videoZoomed = false                  // B5 Task 3: the fit/zoom override is per stream
        videoIsPortrait = false              // unknown reads as landscape until the new item is ready
        airPlayFellBack = false              // Task 8: the mirroring fallback is per stream too
        // Resetting the flag alone left the fallback in force. The flag is this VM's bookkeeping;
        // `allowsExternalPlayback = false` is what the fallback actually DID, and it lives on the
        // `AVPlayer` -- which `PlayerHostView.player(for:replacing:)` reuses across advances and
        // never re-enables: it writes the value in full only on a freshly built player, and only the
        // OFF direction on an update pass, so a fallback that is still wanted survives.
        // Undo it through the same `currentPlayer` seam the fallback used, or every later queue item
        // silently mirrors. Never for an offline player: a saved file is in-app only, whatever route
        // the stock picker offers.
        //
        // ponytail: `isOfflinePlayback` is read off the NEW args, which never carry an
        // `offlineItemId` (`PlayerArgs(item:)` has none) -- so this line would enable the route while
        // the `file://` item is still in the player. Unreachable only because `PlayerScreen` gives an
        // offline VM no `queueSource` (`PlayerScreen.swift:113-121`), so no advance can run here at
        // all; the reuse branch's own OFF write covers the item swap, not this one. Close it with a
        // `guard !isOfflinePlayback` here if an offline player ever gets a queue.
        currentPlayer?.allowsExternalPlayback = !isOfflinePlayback
        // The same start path a mount runs (spec §10): a video opened during a live session casts.
        reconcile(.videoStarted)
    }

    /// Ruling 16's prefetch lane, first and only call site in the app. Six lines because
    /// InnerTubeKit's ManifestCache already IS the prefetch cache (`StreamResolver.swift:73-74`):
    /// a non-forced resolve populates it, and the advance's own non-forced resolve reads it back.
    /// Android needs 70 lines here only because its extractor has no shared cache
    /// (`PlayerViewModel.kt:1703-1770`) -- do not port that dictionary, its TTL or its eviction.
    /// `sourceChannelId: nil`: the availability gate's channel hint belongs to the LAUNCHED video,
    /// not to a playlist member that may come from another channel.
    func prefetchUpcoming() async {
        for item in queue.streamPrefetchTargets where !prefetchedIds.contains(item.id) {   // <=2, note 8
            // CF-B2-2 rule (a): a refusal is skipped SILENTLY. `try?` is that rule -- never a
            // state write, never a retry, never a log line the user can reach.
            if (try? await resolver.resolve(item.id, purpose: .prefetch, kind: .prefetch,
                                            sourceChannelId: nil, forceRefresh: false)) != nil {
                prefetchedIds.insert(item.id)
            }
        }
    }

    /// Playlist paging at <=5 remaining (`PlayerViewModel.kt:1786,1911`); single-flight -- but a
    /// concurrent caller AWAITS the in-flight fetch (Cubic P3, `pagingTask`'s doc comment) and
    /// then proceeds with the refreshed queue. A throw latches `pagingFailed` (`:1946-1978`).
    /// Browse paging is not rate-limited.
    private func pageIfNeeded() async {
        if let pagingTask {
            await pagingTask.value
            return
        }
        guard queue.needsPage, let queueSource, let playlistId = args.playlistId else { return }
        let task = Task {
            do {
                let page = try await queueSource.page(playlistId: playlistId, continuation: queue.cursor)
                queue.append(page.items, cursor: page.continuation)
            } catch {
                queue.markPagingFailed()
            }
        }
        pagingTask = task
        await task.value
        pagingTask = nil
    }

    /// CF-B1: `.cooldown` is terminal -- a manual retry while still inside the window must not
    /// re-hit the network (that's exactly the traffic the cooldown exists to suppress). Once
    /// `until` has passed this is a normal forced re-resolve.
    func retry() async {
        if case .cooldown(let until) = state, until > Date() { return }
        await resolve(forceRefresh: true, kind: .player)
        // R9-6: a retry that lands playable IS a video starting on this screen, and the mount path
        // says so with the same one call. Without it a cast whose failure was the resolve's ("no
        // media" -> `.reportFailure` -> the claim dropped) left the TV idle with the session
        // connected until the next advance or reconnect. Gated on playable so a retry that fails
        // again does not walk `startCast` into a second "Couldn't play on {TV}" of its own.
        if state.isPlayable { reconcile(.videoStarted) }
    }

    /// Task 7: one recovery incident (`PlayerHostView`'s KVO/notification/stall observers feed this).
    /// Runs the pure budget machine, then re-resolves in place, steps down, or gives up.
    ///
    /// **Step-down** (owner directive 2026-10-14): `.stepDownRung` tells the resolver the rung on
    /// screen failed for this video (`StreamResolving.demote`) BEFORE re-resolving, so the walk
    /// resumes at the next rung -- and stays there for this video for the session. `.reResolveSameRung`
    /// demotes nothing: a fresh URL from the same rung is the fix for an expired or revoked one.
    /// `httpStatus` is the CDN's answer from the failed item's error log, when AVFoundation gave one.
    /// Recovery is never refused by the extraction limiter (`RateLimitedResolver`): `RecoveryBudget`
    /// is its budget.
    ///
    /// Position is preserved by NOT passing through `.loading`: `PlayerHostView.player(for:replacing:)`
    /// keeps the live `AVPlayer` and seeks the replacement item back to its `currentTime()`, but only
    /// while the state stays playable -- a `.loading` hop would drop the player and restart at 0.
    func handleRecoveryEvent(_ event: RecoveryEvent, httpStatus: Int? = nil) async {
        #if DEBUG
        if let httpStatus { debugLastRecoveryStatus = httpStatus }
        #endif
        // One failure raises several observers at once -- `AVPlayerItemFailedToPlayToEndTime` and
        // `status == .failed` land together on a dead stream. Without this the same incident spends
        // two budget slots and fires two resolves, one of which the generation guard then discards.
        // First event in wins; the rest are the same incident.
        // `resolvedVideoId == args.videoId` (final review M1): the failure is the stream on screen's,
        // and while an advance's resolve is out `args` already names the NEXT video -- recovering
        // then would force-resolve that one on this lane and supersede the advance.
        guard !isRecovering, resolvedVideoId == args.videoId, let resolved = state.resolved else { return }
        let token = UUID()
        recoveryToken = token
        defer { if recoveryToken == token { recoveryToken = nil } }
        // §9.1 step 1: in live-channel mode an incident first asks the backend; the same id falls through to the
        // existing recovery below (step 3).
        if args.liveChannelId != nil, liveIncident?.currentId != args.videoId, await liveChannelTrigger(.failure) { return }
        // Spec R43: any failure or stall of the HD stream drops back to the 360p it replaced, at the live position (the
        // host's replace-and-seek; `fire` already recorded the resume intent), before the budget machine runs and spending
        // nothing. HD is off for this video (R41). The 360p's own later incidents get Phase 1's full ladder.
        if resolved.client == .tvSamsung, let standard = standardBeforeUpgrade {
            standardBeforeUpgrade = nil
            state = .rung2Progressive(standard)
            if let hd { await hd.failed(hostVideoId) }
            return
        }
        // Plan review I4 (spec R43 revision 4): an incident on the 360p while HD stages means the link cannot carry
        // both. No HD for this video; the 360p then gets Phase 1's recovery exactly as in 1.0.3.
        if pendingUpgrade != nil { upgradeFailed() }
        // Task 8 (spec §10 AirPlay), BEFORE the budget machine and never as a `RecoveryAction`:
        // an item that failed while an external route was playing it is the plan's IP-binding
        // risk, not a broken stream. Drop external playback so the same re-resolve mirrors from
        // the phone instead -- and spend nothing, because the budgets belong to real failures.
        // The re-resolve is the SAME call the recovery actions issue, with the same
        // `showLoading: false` (a `.loading` hop would dismantle the host and lose the position).
        if AirPlayFallback.shouldMirror(event: event,
                                        externalPlaybackActive: currentPlayer?.isExternalPlaybackActive == true,
                                        alreadyFellBack: airPlayFellBack) {
            airPlayFellBack = true
            currentPlayer?.allowsExternalPlayback = false
            await resolve(forceRefresh: true, kind: .autoRecovery, resetBudget: false, showLoading: false)
            return
        }
        let action = PlaybackRecovery.decide(event: event, httpStatus: httpStatus, state: recoveryBudget)
        recoveryBudget.apply(action, for: event)
        switch action {
        case .exhausted:
            // §9.1 step 3: in live-channel mode a spent budget goes to step 4 (false: not live-channel mode).
            if await liveChannelTrigger(.ended) { return }
            // Stage 3b F1: with no CDN status, progress between failures keeps re-resolving the same
            // rung, so the budget can run out with the rung on screen never demoted. Demote it then,
            // so Retry starts at the next rung instead of walking the one that kept failing. Not
            // once this video demoted a rung (r2 N3), Retries since included (R2-1), this demotion
            // too (Codex N1): the resolver keeps ONE demoted rung per video, the stream on screen
            // is already the fallback, and demoting it would point Retry back at the rung that was
            // proven bad.
            if steppedDownVideoIds.insert(hostVideoId).inserted {
                let myGeneration = generation
                await resolver.demote(hostVideoId, failed: resolved.client)
                guard myGeneration == generation else { return }
            }
            // Codex N2: a proactive refresh still in flight (`reResolveIfExpiring` holds no recovery
            // token) would land over the Retry card with the rung just demoted. Superseded here,
            // its result is discarded like any other.
            generation += 1
            resolveTask?.cancel()
            upgradeTask?.cancel()                // spec R43: nothing can take its answer now
            state = .recoveryExhausted(resolved)
        case .stepDownRung:
            steppedDownVideoIds.insert(hostVideoId)
            // ponytail: two stalls without progress (>= 12 s) pin this video to 360p for the session;
            // make `.stall` step-downs non-sticky if congestion makes that common (R15).
            // `hostVideoId`, not `args.videoId`: the failed stream is the one on screen, and an
            // advance moves `args` before its own resolve lands.
            let myGeneration = generation
            await resolver.demote(hostVideoId, failed: resolved.client)
            // `demote` suspends: an advance or Up Next tap that landed meanwhile owns the screen now.
            guard myGeneration == generation else { return }
            await resolve(forceRefresh: true, kind: .autoRecovery, resetBudget: false, showLoading: false)
        case .reResolveSameRung:
            // `showLoading: false` but NOT `silent:` -- by the time this runs the stream has
            // genuinely stopped working, so a failed re-resolve SHOULD surface as `.error` /
            // `.contentUnavailable` / `.cooldown` rather than leaving a dead player on screen.
            await resolve(forceRefresh: true, kind: .autoRecovery, resetBudget: false, showLoading: false)
        }
    }

    /// The stream is genuinely playing again: refund what Android refunds (`RecoveryBudget`). In live-channel mode,
    /// sustained playback also refunds the lifetime retries (§9.1, Task 32 fix round 1).
    func recordPlaybackProgress(now: Date = Date()) {
        recoveryBudget.recordPlaybackProgress(now: args.liveChannelId != nil ? now : nil)
    }

    // MARK: - Live-channel mode (spec §9.1)

    /// Spec §9.1: ask the backend for the channel's current lives, then switch, recover the same id (returns false:
    /// the caller runs the same-id recovery), wait in the existing loading state, or give up into the error state.
    /// Returns false outside live-channel mode too.
    @discardableResult
    func liveChannelTrigger(_ trigger: LiveChannelRecovery.Trigger) async -> Bool {
        guard let channel = args.liveChannelId, let liveLookup else { return false }
        if liveIncident?.currentId != args.videoId {     // an incident belongs to one video id
            liveIncident = LiveChannelRecovery(currentId: args.videoId, currentTitle: args.title, startedAt: Date())
        }
        let incidentId = liveIncident?.id
        var lives: [LiveChannelLives.Live]?
        var notFound = false
        do {
            lives = try await withThrowingTaskGroup(of: LiveChannelLives.self) { [sleep] group in
                group.addTask { try await liveLookup(channel) }
                group.addTask {                 // a backend that never answers is no answer, not a frozen screen
                    try await sleep(.seconds(10))
                    throw URLError(.timedOut)
                }
                defer { group.cancelAll() }
                return try await group.next()!
            }.lives
        } catch is LiveChannelNotFound {
            notFound = true                     // not a servable live channel: stop, never ten minutes of waits
        } catch let error where error is CancellationError || Task.isCancelled {
            return true                         // cancelled (a newer wait, a closed screen): not an answer, spend nothing
        } catch {}                              // anything else is no answer: retry
        // The incident ended while the lookup was out (a resolve landed live, a switch, a give-up).
        guard var incident = liveIncident, incident.id == incidentId, incident.currentId == args.videoId else { return true }
        let step: LiveChannelRecovery.Step = notFound ? .giveUp : incident.next(trigger, lives: lives, now: Date())
        liveIncident = incident
        switch step {
        case .sameId:
            return false
        case .switchTo(let id, let title):
            await switchLive(to: id, title: title, channel: channel)
        case .wait(let seconds):
            generation += 1                     // supersedes any resolve still in flight
            resolveTask?.cancel()
            state = .loading                    // the existing loading/reconnecting state (§9)
            liveRetryTask?.cancel()
            let videoId = args.videoId
            liveRetryTask = Task { [weak self, sleep = self.sleep] in
                try? await sleep(.seconds(seconds))
                // A wait stops with its incident and its stream, or it would wake over a healthy one.
                guard !Task.isCancelled, let self, self.liveIncident?.id == incidentId, self.args.videoId == videoId else { return }
                // The same trigger again: a failure that waited still gets its same-id recovery once the id is listed.
                if await !self.liveChannelTrigger(trigger) {
                    await self.resolve(forceRefresh: true, kind: .autoRecovery, resetBudget: false)
                }
            }
        case .giveUp:
            liveIncident = nil
            generation += 1
            resolveTask?.cancel()
            state = .error(messageKey: "player_error_message")
        }
        return true
    }

    /// §9.1 step 2: a new broadcast is a new timeline (a 1-3 s transition in the existing loading state).
    private func switchLive(to id: String, title: String, channel: String) async {
        liveIncident = nil
        var next = PlayerArgs(videoId: id, title: title, channelName: args.channelName,
                              thumbnailURL: URL(string: "https://i.ytimg.com/vi/\(id)/hqdefault_live.jpg"), channelId: channel)
        next.liveChannelId = channel
        args = next
        currentTime = 0
        await resolve(forceRefresh: false, kind: .player)
    }

    // MARK: - HD upgrade (spec R43)

    /// R8: only the stream on screen, never a saved file. The answer is dropped if anything moved on meanwhile.
    private func startUpgrade(videoId: String, generation: Int) {
        guard let hd, !isOfflinePlayback else { return }
        let report = hdFailedReport
        upgradeTask = Task {
            await report?.value
            guard !Task.isCancelled else { return }
            let upgrade = await hd.upgrade(videoId)
            guard let upgrade, !Task.isCancelled, generation == self.generation, resolvedVideoId == videoId else { return }
            pendingUpgrade = upgrade
        }
    }

    /// The host's hand-over, asked synchronously right before it swaps the item: true = the HD stream is still wanted for
    /// the 360p on screen, and `state` is now `.ready` with it (the update pass then reuses the swapped item, whose URL
    /// matches). False for the pending upgrade = it is dropped and denied.
    func commitUpgrade(_ masterURL: URL) -> Bool {
        guard let pending = pendingUpgrade, case .hls(let url, _, _, _, _) = pending.stream, url == masterURL else { return false }
        // Pre-flight scan: an upgrade this view model can no longer take (an error card, recovery exhausted) is dropped
        // and HD is denied for that video (R41), or the host would stage it again every second.
        guard case .rung2Progressive(let standard) = state, resolvedVideoId == args.videoId else {
            upgradeFailed()
            return false
        }
        pendingUpgrade = nil
        standardBeforeUpgrade = standard
        state = .ready(pending)
        return true
    }

    /// The staged HD item failed, never buffered, or the 360p had an incident while it staged: stay on 360p; no HD for
    /// this video (R41).
    func upgradeFailed() {
        guard pendingUpgrade != nil else { return }
        pendingUpgrade = nil
        let videoId = hostVideoId
        if let hd { hdFailedReport = Task { await hd.failed(videoId) } }
    }

    /// R42 (revision 4): the staged item's `sidx` was still refused after its retry.
    func upgradeRefused() {
        if let hd { Task { await hd.refused() } }
    }

    /// Plan review M11: a screen that went away stops asking for HD.
    /// ponytail: a push-over and a return keeps that play on 360p; ask again on appear if that ever matters.
    func stopUpgrade() {
        upgradeTask?.cancel()
        pendingUpgrade = nil
    }

    // MARK: - Cast (Task 8, spec §10)

    /// How close to `expiresAt` a stream has to be before a cast re-resolves it rather than handing
    /// the receiver the URL the phone is already playing.
    static let castExpiryMargin: TimeInterval = 600

    /// The slack on top of the remaining playback in `castStreamCoversPlayback`: a receiver's clock,
    /// its buffering and the seconds between this decision and the load all have to fit inside it.
    static let castLifetimeFloor: TimeInterval = 60

    /// May a NEAR-EXPIRY stream be handed to a receiver at all? Nothing re-resolves for the
    /// receiver -- `reResolveIfExpiring` drives the LOCAL player, and `CastController` publishes
    /// rather than drives -- so a URL that dies mid-playback dies there with no recovery and no
    /// banner. It is usable only while its remaining lifetime plausibly covers what is left of the
    /// video, with `castLifetimeFloor` to spare. An unknown duration is never covered: guessing in
    /// the receiver's favour is guessing at exactly the failure this bound exists to prevent.
    ///
    /// Only the fallback below calls this, and only for a stream `currentCastStream` already
    /// refused as near-expiry -- a stream that is NOT near expiry never reaches it.
    static func castStreamCoversPlayback(expiresAt: Date?, now: Date, durationSeconds: Int?,
                                         position: TimeInterval) -> Bool {
        guard let expiresAt else { return true }      // no expiry to outlive
        guard let durationSeconds else { return false }
        return expiresAt.timeIntervalSince(now)
            >= max(0, Double(durationSeconds) - position) + castLifetimeFloor
    }

    /// The stream to cast. Side-band on purpose -- NOT through `resolve()`, whose `state` write
    /// would dismantle `PlayerHostView` and drop the very local player this screen is about to
    /// pause and hand back to on session end.
    ///
    /// It re-uses the stream this player is ALREADY playing whenever it has one for this video and
    /// that one is not about to expire. Forcing a fresh walk per cast put every cast on the
    /// `.player` rate-limit lane, whose 30 s minimum interval turns any second attempt inside the
    /// window -- reopening a video mid-cast, a receiver reconnecting, a manual Retry then Cast --
    /// into a `.cooldown` and a false "Couldn't play on {device}" for a URL that works. The
    /// resolved URL is bound to the phone's IP either way, so a fresh walk buys the receiver
    /// nothing; only genuine expiry does.
    ///
    /// `nil` = nothing castable (a refusal with no usable stream behind it) -- the caller surfaces
    /// that as the same "Couldn't play on {device}" a receiver's own refusal produces.
    ///
    /// Only the muxed itag-18 MP4 is castable (`CastMedia.make`: a receiver cannot play the rung-1
    /// manifest). So a manifest on screen is never reused: the cast walks the muxed rung itself.
    /// NOT forced -- a muxed walk bypasses the manifest cache anyway, and `RateLimitedResolver`
    /// gates forced walks, so a forced one would hit the `.player` lane's 30 s interval right after
    /// the phone's own resolve and refuse the very first cast.
    /// ponytail: one ungated android POST per cast start of a manifest; give casts their own limiter
    /// lane if that ever trips the bot check.
    ///
    /// `.cast` purpose: a bot check on this walk fails the cast only (the existing banner) and never
    /// records InnerTubeKit's GLOBAL cooldown -- from the usual `.hls` the phone never tried the
    /// android rung, so a trip here would lock out its WORKING rung-1 playback for up to 5 minutes. The
    /// offline save's identical walk (`.prefetch`) still records it. NOT skipped for
    /// `state.isLive`: that flag reads `isLiveContent` for an ENDED
    /// broadcast -- nhaGO__rxHQ resolves `.hls(isLive: true)`, yet its itag-18 walk played on a real
    /// Chromecast (2026-09-27).
    func castMedia(now: Date = Date()) async -> CastMediaInfo? {
        if let media = currentCastStream(now: now).flatMap({ CastMedia.make(resolved: $0, args: args) }) { return media }
        // An advance reconciles BEFORE its own resolve lands, so at this point `state` still carries
        // the outgoing video's stream. Joining the walk this player is already making is free.
        await resolveTask?.value
        if let media = currentCastStream(now: now).flatMap({ CastMedia.make(resolved: $0, args: args) }) { return media }
        do {
            let resolved = try await resolver.resolve(args.videoId, purpose: .cast, kind: .player,
                                                      sourceChannelId: args.channelId, forceRefresh: false,
                                                      requiresMuxed: true, requiresHLS: false)
            return CastMedia.make(resolved: resolved, args: args)
        } catch {
            // The user-visible collapse to one banner is deliberate (a cooldown and a receiver
            // refusal look the same to them), but swallowing the reason entirely made a cast that
            // can never work indistinguishable from one the TV refused.
            #if DEBUG
            print("PlayerViewModel: cast resolve failed for \(args.videoId): \(error)")
            #endif
            // A refusal is not a dead stream: a cooldown, a bot check or a transient failure all
            // leave a PROGRESSIVE stream the phone is playing right now perfectly castable, and
            // near-expiry is a reason to prefer a fresher one, never a reason to cast nothing. A
            // manifest on screen is no fallback (`CastMedia.make` refuses it), and with no stream of
            // ours at all there is nothing either: nil, and the banner is the honest answer.
            // ponytail: a refusal with no stream for THIS video yet still banners -- reachable when
            // a cast is refused for a video whose own resolve also failed. Closing it would mean
            // giving the cast its own limiter lane, which is a rate-limit decision, not this one.
            //
            // BOUNDED. "Refused AND about to die on the receiver" is not a fallback, it is
            // a doomed load with no recovery and no banner -- so it takes the honest failure path
            // (`startCast`'s `reportLoadFailure` -> the existing `cast_error_format`) instead.
            guard let playing = currentCastStream(now: now, allowNearExpiry: true),
                  Self.castStreamCoversPlayback(expiresAt: playing.expiresAt, now: now,
                                                durationSeconds: args.durationSeconds,
                                                position: currentTime)
            else { return nil }
            return CastMedia.make(resolved: playing, args: args)
        }
    }

    /// The stream on screen, but only when it is THIS video's. `state` keeps the previous video's
    /// stream until an advance's own resolve lands (the advance deliberately never hops through
    /// `.loading`), so `resolvedVideoId` is what stops a cast handing the receiver the outgoing
    /// video's URL under the incoming video's title.
    private func currentCastStream(now: Date, allowNearExpiry: Bool = false) -> Resolved? {
        guard resolvedVideoId == args.videoId, let resolved = state.resolved else { return nil }
        guard allowNearExpiry
                || !Self.shouldPreemptivelyReResolve(state, now: now, margin: Self.castExpiryMargin)
        else { return nil }
        return resolved
    }

    /// True while THIS view model's player is paused because THIS screen started a cast. The
    /// session flag is app-wide and several `PlayerScreen`s can be mounted at once, so the
    /// hand-back has to know it is talking to the player it actually paused -- otherwise an
    /// unrelated (or offline) video gets seeked and force-played.
    private(set) var pausedForCast = false

    /// The videoId THIS view model claimed the cast session for -- never a re-read `args.videoId`,
    /// which `swapArgs` replaces on every auto-advance, Up Next tap and auto-skip while the
    /// controller's stamp keeps the id that was claimed. It lives HERE, next to `pausedForCast`,
    /// because the two are halves of one fact: split across the view, this model and the
    /// controller and recombined by five reaction sites, any one of the three can be reset without
    /// the others -- which is the shape of every hand-back bug in this feature.
    private(set) var claimedVideoId: String?

    /// Mirrors `CastOwnershipState.droppedWhilePaused` -- written by the `.dropClaim` arm below,
    /// read back into `decide` on the next `reconcile` call, consumed by `.resume`. Lives here for
    /// the same reason `claimedVideoId` does: it is this screen's own fact, not the controller's.
    private var droppedWhilePaused = false

    /// Mirrors `CastOwnershipState.isVisible` -- maintained by `reconcile`'s own `.appear`/
    /// `.disappear` triggers, which are the only two that say anything about visibility, and read
    /// back into `decide` by whichever trigger discovers a drop.
    private var isVisible = false

    /// THIS screen's identity in the cast stamp, minted per view model -- which is exactly per
    /// `PlayerScreen`, since the screen owns its model. `MainShellView` keeps every visited tab's
    /// stack mounted, so two screens can be up on the same video; without this the stamp reads as
    /// theirs to both and both pause, load and resume (double audio). Readable so
    /// `CastSessionTests` can record a load AS this screen -- `load()` itself needs a
    /// `GCKCastContext` no test can create, same reason `recordLoad` exists.
    let castOwner = UUID()

    /// Which cast start is the current one. Same last-writer-wins shape as `resolve`'s
    /// `generation`, and for the same reason: `startCast` awaits `castMedia()`, and a mount landing
    /// with a session transition -- or an off-screen release and the return leg's re-claim -- opens
    /// a second walk inside that window. A guard keyed on the VIDEO cannot arbitrate them: the
    /// return leg's start bails on the id it shares with the walk already in flight, and that walk
    /// then fails its own `stillCasting` check on the claim it no longer holds -- claim set, stamp
    /// free, receiver idle until the next trigger. Two walks for the same video are
    /// indistinguishable to the claim (same id, same owner); only an attempt number says which one
    /// still counts.
    private var castAttempts = 0

    /// The mount path: everything `PlayerScreen`'s `.task` arm owes this model the moment it exists.
    ///
    /// `.appear` FIRST, and not only because `.onAppear` fires before this model is built -- it is
    /// the appearance that already happened, replayed against the model that missed it. Without it
    /// a screen was never recorded visible for the whole of its first presentation, so any
    /// `.dropClaim(resume:)` decided during it left the phone paused on a screen the user is
    /// looking at, waiting for an `.appear` that only comes after navigating away and back.
    ///
    /// With no claim yet `.appear` decides `.none` and only sets the flag — unless
    /// `droppedWhilePaused` is armed, where it decides `.resume` and plays the phone (r8 Minor 2).
    /// That is exactly the drop this replay exists to answer, and `PlayerScreen` may already have
    /// spent it: the screen reconciles `.appear` the moment the model exists, so this replay is
    /// second and idempotent.
    func didMount() {
        reconcile(.appear)
        reconcile(.videoStarted)
    }

    /// The ONE cast reaction. Every site that can change who owns the session -- `onAppear`,
    /// `onDisappear`, `.onChange(isSessionActive)`, `.onChange(lastLoadFailure)`, the `.task`
    /// mount arm and `swapArgs` -- calls this with what happened and nothing else decides anything:
    /// `CastOwnership.decide` is a pure table over the three owners' state, and this executes its
    /// one answer.
    func reconcile(_ trigger: CastTrigger) {
        guard let cast else { return }
        // Before the decision, not after: whichever trigger discovers a drop reads this, and
        // `.appear` is itself one of them.
        if trigger == .appear { isVisible = true } else if trigger == .disappear { isVisible = false }
        let action = CastOwnership.decide(
            state: CastOwnershipState(claimedVideoId: claimedVideoId, videoId: args.videoId,
                                      isOfflinePlayback: isOfflinePlayback,
                                      sessionActive: cast.isSessionActive, owner: castOwner,
                                      stamp: cast.castingClaim, loaded: cast.loadedClaim,
                                      failure: cast.lastLoadFailure?.claim,
                                      droppedWhilePaused: droppedWhilePaused, isVisible: isVisible),
            trigger: trigger)
        switch action {
        case .none:
            break
        case .startCast(let videoId):
            Task { await startCast(videoId) }
        case .adopt(let videoId):
            // No re-resolve and no second `load()`: the receiver is already playing this. The
            // loaded claim is re-stamped with OUR owner because the screen that put it there may be
            // gone -- and `receiverPosition(for:owner:)`, which the hand-back spends, answers only
            // for the screen the receiver played FOR.
            //
            // `recordLoad` re-stamps it WITHOUT issuing a fresh `load()`, so the claim we adopt
            // carries the PREDECESSOR's issue time -- if that still-in-flight request then fails, the
            // rejection lands on our claim, not its own. Self-correcting (`.reportFailure` still
            // resumes and drops it, just under our name); narrow enough that nothing further is owed.
            cast.claimCastSource(videoId: videoId, owner: castOwner)
            cast.recordLoad(videoId, owner: castOwner)
            claimedVideoId = videoId
            pauseForCast()
        case .dropClaim(let videoId, let resume):
            // Never a seek: this arm runs precisely when the receiver was never playing ours, so
            // there is no position to take. The PAUSE still has to go either way, or the video is
            // stuck paused forever -- and it goes here rather than through `resumeAfterCast`, which
            // returns early (leaving the flag set) when this screen holds no player at all.
            //
            // Whether the phone may keep PLAYING is the table's call, off this screen's own tracked
            // visibility: on screen it plays now, off screen it owes itself the next `.appear`'s
            // `.resume` instead -- playing there is what put a hidden tab's audio under another
            // screen's cast. Only a phone THIS drop is leaving genuinely paused is owed either, and
            // the flag is recomputed on every drop, so a stale one from an earlier episode never
            // lingers past this one.
            let wasPausedForCast = pausedForCast
            droppedWhilePaused = wasPausedForCast && !resume
            pausedForCast = false
            cast.finishClaim(videoId, owner: castOwner)
            claimedVideoId = nil
            if resume, wasPausedForCast { currentPlayer?.play() }
        case .resume:
            // The resume `.dropClaim` couldn't give while this screen was off screen -- no seek
            // (nothing of ours ever reached the receiver, so there is no position to take), just let
            // the phone keep going now that this screen is the one actually on screen.
            droppedWhilePaused = false
            currentPlayer?.play()
        case .release(let videoId):
            cast.releaseClaim(videoId, owner: castOwner)
        case .handBack(let videoId, let resume):
            // The receiver's position only if the receiver actually played OUR video -- it is
            // sampled off the session, so after another screen cast and popped it belongs to that
            // video and seeking to it is a silent jump to a stranger's timestamp. `nil` resumes in
            // place. This arm also runs for a claim that has simply gone stale, where its whole job
            // is to let the claim go: one that outlives its session is what stops this screen ever
            // casting this video again.
            //
            // R9-7: the same visibility rule (and the same consumed-once flag) as `.dropClaim`
            // above. The seek happens either way -- it is where this video IS now, not sound.
            droppedWhilePaused = pausedForCast && !resume
            resumeAfterCast(at: cast.receiverPosition(for: videoId, owner: castOwner), resume: resume)
            cast.finishClaim(videoId, owner: castOwner)
            claimedVideoId = nil
        case .reportFailure(let videoId, let resume):
            // Spec §10: "observe the load result and surface 'Couldn't play on {device}'" (Android
            // swallows it). Consumed and cleared here; a nameless failure (no session left to ask)
            // still resumes and drops the claim below -- there is just nothing honest to say.
            //
            // Raised even when this screen is hidden (I1): the banner is state, not sound, and it
            // surfaces on the next visible presentation -- `banner` lives on the view model, and a
            // failure left unconsumed instead would never fire `.onChange` again once cleared.
            if let device = cast.lastLoadFailure?.device {
                banner = BannerMessage(text: String(format: String(localized: "cast_error_format"), device))
            }
            // A PLAYBACK failure carries where the TV got to; a load failure resumes in place.
            let position = cast.lastLoadFailure?.position
            cast.lastLoadFailure = nil
            // `startCast` pauses the local player before the load, so without this the user taps
            // Cast, gets a toast, and their video has silently stopped on the phone too. A no-op
            // for the "nothing castable" path, which never paused.
            //
            // I1: and only where somebody is looking. A claimless hidden screen still answers a
            // receiver reconnect (`.sessionChanged` fires on an opacity-0 rail tab), so this arm is
            // reachable off screen -- where it played audio under whatever tab the user is actually
            // on. Same consumed-once flag as its two siblings.
            droppedWhilePaused = pausedForCast && !resume
            resumeAfterCast(at: position, resume: resume)
            // Nothing of ours reached the receiver, so this screen owns nothing -- and holding a
            // spent claim would both block its own next cast and keep every other screen out.
            cast.finishClaim(videoId, owner: castOwner)
            claimedVideoId = nil
        }
    }

    /// Session start/resume (spec §10): the stream, then the load with the local position, then the
    /// pause. Order matters -- the pause happens only once there is something to load, so a video
    /// that turns out to be uncastable keeps playing on the phone under its banner.
    private func startCast(_ videoId: String) async {
        // R9-1: the video check comes FIRST, ahead of any claim. `reconcile` enqueues this in a
        // `Task`, and an Up Next tap's `swapArgs` landing before it runs moves the screen to
        // another video -- stamping the session for the one that is gone left the phone playing B
        // with the TV idle for the rest of the session, because every later reconcile compares that
        // stamp against B and decides `.none`. Claiming nothing for a video this screen no longer
        // plays is what makes the bail path have nothing to undo.
        //
        // R9-12: `CastOwnership.canStart` already decided the preconditions (a live session, not an
        // offline player, a stamp free-or-ours) -- the table is the ONE start decision. What is
        // left here is the stamp CAS itself, which only the controller can perform, plus a
        // re-read of the session, because the decision was taken before this task got the actor.
        guard let cast, args.videoId == videoId, cast.isSessionActive,
              cast.claimCastSource(videoId: videoId, owner: castOwner) else { return }
        castAttempts += 1
        let attempt = castAttempts
        claimedVideoId = videoId
        let media = await castMedia()
        // `castMedia()` can walk the network, and both the session and this screen's video can be
        // gone by the time it lands: a session that ended inside the window already ran its
        // hand-back, and an advance means the stamp names a video this screen no longer plays. A
        // newer walk supersedes this one outright -- it is the one holding the claim now,
        // and two loads for one video restart the receiver.
        // Ahead of the no-media branch too -- a cancelled walk must not raise "Couldn't play on
        // {TV}" for a cast that was never attempted.
        //
        // None of these three release the claim on the way out, deliberately (R9-1): a superseding
        // walk holds the very claim this one took (same video, same owner -- R7-16), `swapArgs`
        // has already released it when the video moved, and a session that ended is the hand-back
        // arm's to spend. A blanket release here rips the stamp out from under the walk that is
        // still casting.
        guard attempt == castAttempts, args.videoId == videoId,
              cast.stillCasting(videoId, owner: castOwner) else { return }
        guard let media else {
            // Nothing castable: a resolve that did not come back, or a near-expiry URL that could
            // not outlive what is left to play. Same outcome for the user as a receiver refusing
            // the load, so it gets the same banner rather than copy of its own.
            cast.reportLoadFailure(claim: CastClaim(videoId: videoId, owner: castOwner))
            return
        }
        pauseForCast()
        cast.load(media, videoId: videoId, owner: castOwner, at: currentTime)
    }

    /// The receiver owns playback now. Goes through the host's `currentPlayer` hand-off slot --
    /// never `AVAudioSession` (single-audio-owner rule), never a second player.
    func pauseForCast() {
        currentPlayer?.pause()
        pausedForCast = true
    }

    /// Session end (spec §10): seek local to the receiver's `approximateStreamPosition` and
    /// resume. Also the failure hand-back: a receiver that REJECTS the load leaves the phone paused
    /// otherwise, against `startCast`'s own promise -- that path calls this with a nil position, so
    /// playback resumes exactly where it stopped.
    ///
    /// A no-op unless this player is the one `pauseForCast()` paused: with no player at all (the
    /// mini controller outlives the player route, so a session can end with no `PlayerScreen`
    /// mounted) there is nothing to seek and resurrecting the popped route would be worse; with a
    /// player that never cast, seeking it to some other video's receiver position is the bug.
    ///
    /// `resume: false` (R9-7) performs the seek and spends the pause but leaves the player stopped
    /// -- the caller has decided nobody is looking at this screen and armed `droppedWhilePaused`
    /// for the `.appear` that will be.
    func resumeAfterCast(at position: TimeInterval?, resume: Bool = true) {
        // R9-5: the flag goes BEFORE the player guard. `.adopt` is reachable on a screen that has
        // no player yet (`start` never checks `state.resolved`), so `pauseForCast()` can record the
        // intent with `currentPlayer == nil` -- and returning below with the flag still set left
        // nothing that could ever clear it: the next Retry built a player that never autoplayed.
        let wasPausedForCast = pausedForCast
        pausedForCast = false
        guard let player = currentPlayer else { return }
        // RULING: the SEEK is unconditional for the claimant. Spec §10's
        // hand-back is "seek local to the receiver's position and resume", and a screen reopened
        // on the same video mid-cast must still land where the TV got to even though its fresh
        // view model never paused anything. Only the RESUME stays gated on having paused: playing
        // a player the user deliberately left paused is the unrequested-playback bug.
        // WHO hands back at all, and WHICH position, are `CastOwnership.decide` and
        // `CastController.receiverPosition(for:)`; this only performs it.
        //
        // The receiver only ever plays the muxed VOD MP4 (`CastMedia.make`), so its position is
        // always on this video's own timeline.
        if let position, position > 0, position.isFinite {
            player.seek(to: CMTime(seconds: position, preferredTimescale: 600))
            currentTime = position
        }
        guard wasPausedForCast, resume else { return }
        player.play()
    }

    /// §6.2 step 5: "On `willEnterForeground`, re-resolve pre-emptively if past
    /// `resolvedAt + expires - margin`". Note this is NOT ruling 20's rejected 50-minute live timer --
    /// that was a periodic timer against a self-refreshing HLS manifest; this fires once, only on
    /// return to the foreground, only when the URL is genuinely near expiry.
    static func shouldPreemptivelyReResolve(_ state: StreamState, now: Date, margin: TimeInterval = 60) -> Bool {
        guard let resolved = state.resolved, let expiresAt = resolved.expiresAt else { return false }
        return now >= expiresAt.addingTimeInterval(-margin)
    }

    /// CF-B1-3. Re-resolves in place if the above says so. Never hops out of the playable branch --
    /// on ANY outcome, success or failure.
    func reResolveIfExpiring(now: Date = Date()) async {
        // `!isRecovering` (fix round 1, C1): a recovery resolve deliberately runs with
        // `showLoading: false`, so while it is in flight `state` is still the OLD `.ready` -- which
        // can be past its TTL, which is exactly what makes this fire. Starting here would bump
        // `generation` and discard the recovery's completion, and then the silent rule below would
        // swallow this refresh's own failure too: a `.failed` `AVPlayerItem` frozen inside `.ready`
        // with no error surface and no retry. The recovery owns the stream until it finishes; the
        // refresh gets its next chance on the following foreground.
        guard !isRecovering, Self.shouldPreemptivelyReResolve(state, now: now) else { return }
        await refreshInPlace()
    }

    /// Spec §9.3(a), R13: re-resolve 10 min before a live stream's URLs expire (~6 h, F11) and replace the item at
    /// the live edge: one ~1-3 s rebuffer per ~5 h 50 m instead of a failure at ~6 h. This revisits RULINGS ruling
    /// 20; its cost note ("rare long-session stall -> re-add the timer") anticipated it.
    static let liveRefreshLead: TimeInterval = 600

    static func liveRefreshDelay(expiresAt: Date, now: Date) -> TimeInterval {
        max(0, expiresAt.timeIntervalSince(now) - liveRefreshLead)
    }

    /// DEBUG only (L3, L5): `-fitrah-live-refresh-seconds N`. `LaunchArguments.debug` is empty in Release.
    static var debugLiveRefreshSeconds: TimeInterval? {
        let arguments = LaunchArguments.debug
        guard let i = arguments.firstIndex(of: "-fitrah-live-refresh-seconds"), arguments.indices.contains(i + 1) else { return nil }
        return TimeInterval(arguments[i + 1])
    }

    /// DEBUG only (L5): `-fitrah-live-force-end-seconds N` forces an end N s after a live-channel item plays.
    static var debugLiveForceEndSeconds: TimeInterval? {
        let arguments = LaunchArguments.debug
        guard let i = arguments.firstIndex(of: "-fitrah-live-force-end-seconds"), arguments.indices.contains(i + 1) else { return nil }
        return TimeInterval(arguments[i + 1])
    }

    /// Fix round 1: a refresh that does not land (a blip, a cooldown, not live now) is retried this often inside the
    /// lead (Android parity), so one blip at T − 10 min does not bring back the 6 h cut.
    static let liveRefreshRetry: TimeInterval = 120

    /// Re-armed by every landed resolve; anything but a stream that is live now disarms it (a recording, a VOD, HD).
    /// A landed refresh re-arms, which cancels this task and so ends its retries.
    private func scheduleLiveRefresh() {
        liveRefreshTask?.cancel()
        liveRefreshTask = nil
        guard state.isLiveNow, let expiresAt = state.resolved?.expiresAt else { return }
        let delay = Self.debugLiveRefreshSeconds ?? Self.liveRefreshDelay(expiresAt: expiresAt, now: Date())
        liveRefreshTask = Task { [weak self, sleep = self.sleep] in
            try? await sleep(.seconds(delay))
            let stop = min(expiresAt, Date().addingTimeInterval(Self.liveRefreshLead)).addingTimeInterval(-Self.liveRefreshRetry)
            while !Task.isCancelled {
                await self?.refreshLiveStream()
                guard !Task.isCancelled, self != nil, Date() < stop else { return }
                try? await sleep(.seconds(Self.liveRefreshRetry))
            }
        }
    }

    /// The timer's re-resolve: `reResolveIfExpiring`'s in-place path without its "within 60 s of expiry" condition.
    /// A timer does not fire while the app is suspended and paused, so the foreground check stays (R13).
    func refreshLiveStream() async {
        guard !isRecovering, state.isLiveNow else { return }
        await refreshInPlace()
    }

    /// The one proactive refresh path (`proactiveRefresh` above): a second caller joins the walk in flight.
    /// Never during an advance (fix round 1): its forced walk would supersede the advance's resolve and swallow it.
    private func refreshInPlace() async {
        guard !isAdvancing, resolvedVideoId == args.videoId else { return }
        if let proactiveRefresh { return await proactiveRefresh.value }
        // `showLoading: false` + `silent: true` are both load-bearing and are NOT the same flag:
        // `showLoading: false` holds the playable branch on the way IN (a `.loading` hop dismantles
        // PlayerHostView and drops the AVPlayer whose `currentTime()` carries the position);
        // `silent: true` holds it on the way OUT (see `performResolve`). `handleRecoveryEvent` passes
        // the first and not the second, because a genuine playback failure SHOULD surface.
        // `resetBudget: false` so a pre-emptive refresh cannot refill the recovery budget.
        let refresh = Task {
            _ = await resolve(forceRefresh: true, kind: .proactiveTTLRefresh, resetBudget: false, showLoading: false, silent: true)
        }
        proactiveRefresh = refresh
        await refresh.value
        proactiveRefresh = nil
    }

    /// Returns whether this resolve is still the current one when it finishes (review F1): `false`
    /// means a later user action bumped `generation` while it was in flight, so the caller's
    /// context -- queue position included -- belongs to that action now. Only `advance()` acts on
    /// it; every other caller is either itself the superseder or has nothing left to do.
    @discardableResult
    private func resolve(forceRefresh: Bool, kind: RequestKind, resetBudget: Bool = true,
                         showLoading: Bool = true, silent: Bool = false) async -> Bool {
        generation += 1
        let myGeneration = generation
        resolveTask?.cancel()
        // Spec R43: an upgrade belongs to the stream that asked for it; whatever this resolve lands, it is not that one.
        upgradeTask?.cancel()
        pendingUpgrade = nil
        // A user-initiated open/retry is a fresh stream (or a deliberate fresh start on the same
        // one): hand it a full budget, and end any recovery's claim on it (stage 5 F4). Recovery's
        // own re-resolves must not refill their own budget.
        if resetBudget {
            recoveryBudget = RecoveryBudget()
            recoveryToken = nil
        }
        if showLoading { state = .loading }
        let task = Task {
            await self.performResolve(generation: myGeneration, forceRefresh: forceRefresh,
                                      kind: kind, silent: silent)
        }
        resolveTask = task
        await task.value
        // §9.1: not live now, or failed (F3): step 4, never a recording or an error. A failed open, Retry or switch is a
        // FAILURE, so a listed id gets its same-id re-resolve first; that re-resolve's own miss is an END, so nothing loops.
        if myGeneration == generation, let miss = liveMiss, miss.generation == generation {
            liveMiss = nil
            if await !liveChannelTrigger(miss.trigger) {
                await resolve(forceRefresh: true, kind: .autoRecovery, resetBudget: false)
            }
        }
        return myGeneration == generation
    }

    private func performResolve(generation: Int, forceRefresh: Bool, kind: RequestKind, silent: Bool) async {
        let videoId = args.videoId
        let result: StreamState
        do {
            let resolved = try await resolver.resolve(
                videoId, purpose: .player, kind: kind,
                sourceChannelId: args.channelId, forceRefresh: forceRefresh)
            result = Self.map(resolved)
        } catch {
            result = Self.map(error)
        }
        // A superseding `open()`/`retry()` both cancels this job and bumps `generation` before its
        // own resolve starts -- either signal alone is enough to discard a late/stale completion,
        // matching `HomeViewModel.fetchFirstPage`'s belt-and-suspenders check.
        guard !Task.isCancelled, generation == self.generation else { return }
        // §9.1 / RV I11: live-channel mode only lands a stream that is live now. A recording (from any resolve, a
        // proactive refresh included) and a failed resolve (F3: an ended 24/7 id fails to resolve) are step 4's, never
        // played or shown as an error; `resolve` runs the trigger. A failed silent refresh keeps the stream (below).
        if isLiveChannelMode, !result.isLiveNow, result.isPlayable || !silent {
            liveMiss = (generation, result.isPlayable || kind == .autoRecovery ? .ended : .failure)
            return
        }
        // A silent (proactive TTL) refresh runs WHILE a healthy stream is playing -- that is why it
        // skips the `.loading` hop. It must therefore never apply a non-playable result: a
        // `RateLimitedResolver` cooldown or a network blip on foreground would otherwise knock a
        // working player out of `PlayerScreen`'s `.ready`/`.rung2Progressive` branch, dismantle
        // `PlayerHostView` and drop the `AVPlayer` carrying the position -- for a stream that is
        // still perfectly playable. Dropping the result leaves the unexpired stream playing;
        // reactive recovery (`handleRecoveryEvent`, which never passes `silent`) owns real failures,
        // because by then the stream has actually stopped working.
        if silent, !result.isPlayable { return }
        // §9.3(a): a proactive refresh of a live stream only ever replaces it with a stream that is still live now.
        if silent, state.isLiveNow, !result.isLiveNow { return }
        // Together, always: `.error`/`.contentUnavailable` results move it too --
        // the state on screen is now THIS video's, whatever it says.
        resolvedVideoId = videoId
        // Owner issue 1: the source's own title (videoDetails.title), never the list row's hl=en translation.
        // Before `state`: the host republishes Now Playing from `args` on the pass the state change triggers.
        // Replace-only: a titleless resolve (offline, TV) keeps what the row gave. Not in live-channel mode: D12's title
        // match compares against the backend's list, so the item keeps the backend's title (Android 4d3951bf).
        if args.liveChannelId == nil, let title = result.resolved?.title, !title.isEmpty { args.title = title }
        // Spec §4.9: the kept 360p goes only when a resolve replaces the stream on screen. A silent refresh that never
        // lands (it failed, or is still out) keeps it, so the HD stream still drops back and never reaches `decide`,
        // whose step-down would demote `.tvSamsung`.
        standardBeforeUpgrade = nil
        state = result
        scheduleLiveRefresh()
        if isLiveChannelMode, result.isPlayable {
            liveIncident = nil                  // live again: the incident is over, and its wait stops
            #if DEBUG
            if let seconds = Self.debugLiveForceEndSeconds {
                debugForceEndTask?.cancel()     // one armed force-end: a re-arm replaces it
                debugForceEndTask = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(seconds))
                    guard !Task.isCancelled, self?.args.videoId == videoId else { return }
                    await self?.liveChannelTrigger(.ended)
                }
            }
            #endif
        }
        // Spec R43: 360p is on screen; ask for HD in the background.
        if case .rung2Progressive = result { startUpgrade(videoId: videoId, generation: generation) }
    }

    /// No Safe Mode branch: owner directive 2026-08-27 removed the YouTube hand-off outright, so
    /// there is no longer an outcome for Safe Mode to filter here. `PlayerViewModel.safeMode` stays
    /// as the reader B5's auto-advance gate uses (ruling 58, CF-B3-2).
    private static func map(_ resolved: Resolved) -> StreamState {
        switch resolved.stream {
        case .hls:
            return .ready(resolved)
        case .progressive:
            return .rung2Progressive(resolved)
        }
    }

    #if DEBUG
    /// Task 9 screenshot rig (`PlayerScreen`'s `-fitrah-fake-player-recovery-exhausted` hook): jumps
    /// straight to `.recoveryExhausted` for whatever `Resolved` the fixture already resolved to,
    /// skipping the real budget machine -- which is exhaustively unit-tested in
    /// `PlaybackRecoveryTests` and would otherwise need a genuinely failing `AVPlayerItem` to drive
    /// for real. Same technique as `NetworkMonitor`'s `-fitrah-offline` hook.
    func debugForceRecoveryExhausted() {
        guard let resolved = state.resolved else { return }
        state = .recoveryExhausted(resolved)
    }
    #endif

    private static func map(_ error: Error) -> StreamState {
        guard let extractionError = error as? ExtractionError else {
            return .error(messageKey: "player_error_message")
        }
        switch extractionError {
        case .cooldown(let until):
            return .cooldown(until: until)
        case .unavailable, .liveOffline, .ageRestricted, .geoBlocked, .private, .removed:
            // Ruling 14: age-restricted/geo-blocked/private/removed are distinct terminal states
            // upstream, but the player has one non-retryable "not playable" surface for all of them.
            return .contentUnavailable
        case .invalidVideoId, .botCheck, .allRungsFailed, .cancelled, .transport:
            return .error(messageKey: "player_error_message")
        }
    }
}
