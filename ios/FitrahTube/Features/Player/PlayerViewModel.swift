import AVFoundation
import Foundation
import InnerTubeKit

/// Wraps InnerTubeKit's `StreamResolver` actor behind a protocol `PlayerViewModel` depends on --
/// depending on the concrete actor directly would leave tests unable to script resolve outcomes.
protocol StreamResolving: Sendable {
    /// The ONE requirement, and it carries `requiresMuxed` (owner ruling 2026-09-01): `true` demands
    /// a single-file muxed stream (itag 18) — the resolver skips the HLS rung, and neither reads nor
    /// writes the manifest cache nor touches the single-flight registry. Only `OfflineManager`
    /// passes `true`; every player call site takes the five-argument convenience below.
    ///
    /// R8-3: the direction used to be the other way round, with a defaulted six-argument overload
    /// forwarding to a five-argument requirement. A conformer that implemented only the short form
    /// then dropped the flag SILENTLY — every video save resolved `.hls`, hit `sourceURL`'s
    /// `(.hls, false)` nil arm and failed NO_STREAM, with a doc comment as the only guard. This way
    /// round, the compiler is the guard: a resolver that cannot answer the muxed question does not
    /// conform.
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool, requiresMuxed: Bool) async throws -> Resolved
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
                          forceRefresh: forceRefresh, requiresMuxed: false)
    }
}

/// Production `StreamResolving` -- thin pass-through to the real actor (`AppContainer.resolver`).
/// `kind` is ignored here on purpose: `StreamResolver.resolve` takes no such argument, and its
/// `purpose:` is documented as "reserved for caller-side rate-limiter lane coordination" -- which is
/// exactly what `RateLimitedResolver` (the decorator wrapped around this) now performs.
struct LiveStreamResolver: StreamResolving {
    let resolver: StreamResolver

    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool, requiresMuxed: Bool) async throws -> Resolved {
        try await resolver.resolve(videoId, purpose: purpose, sourceChannelId: sourceChannelId,
                                   forceRefresh: forceRefresh, requiresMuxed: requiresMuxed)
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
                 sourceChannelId: String?, forceRefresh: Bool, requiresMuxed: Bool) async throws -> Resolved {
        // Scoped exactly as Android scopes it (`ui/player/PlayerViewModel.kt:1243/1256/1265` are its
        // only three limiter call sites, all force-refreshes). A non-forced resolve may be served
        // straight from `ManifestCache` with no network at all -- gating it would make the 30 s
        // minimum interval refuse a replay of a video the user just watched. The `.prefetch` lane
        // is gated even though it is never forced (B5, reconciliation note 7): a prefetch is the
        // non-forced resolve most likely to hit the network, and it is the one lane the limiter
        // exists to keep behind the interactive one.
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
                                                 requiresMuxed: requiresMuxed)
        await rateLimiter.onSuccess(videoId)   // clears the .player exponential backoff (Android :1551)
        return resolved
    }

    private static func seconds(_ duration: Duration) -> TimeInterval {
        let components = duration.components
        return TimeInterval(components.seconds) + TimeInterval(components.attoseconds) / 1e18
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
        guard let resolved, case .hls(_, let isLive, _, _) = resolved.stream else { return false }
        return isLive
    }

    /// Cubic #3 (= gstack R2): `.embed` is a successful playback surface for auto-advance purposes
    /// even though it is deliberately NOT `isPlayable` -- it has no `AVPlayer` to preserve, is never
    /// cached and never counts as a healthy fetch (`StreamResolver.succeed`), which is why it stays
    /// out of `resolved`/`isPlayable` rather than being folded in.
    var isEmbedRung: Bool {
        if case .embed = self { return true }
        return false
    }
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
    var currentItem: AVPlayerItem?

    /// Same hand-off reasoning as `currentItem`, one level up: `CaptionOverlay` needs the live
    /// `AVPlayer` itself (`addPeriodicTimeObserver` is an `AVPlayer` method, not `AVPlayerItem`'s),
    /// and `PlayerHostView` is the only owner of the AVKit-managed player. Written alongside
    /// `currentItem` in `PlayerHostView`'s make/update pair.
    var currentPlayer: AVPlayer?

    /// Task 6 (captions): the toggle's current pick (`nil` = Off). Session-only, like
    /// `selectedQuality`/`stickyAudioLanguage` -- survives a re-resolve within one player lifetime,
    /// never persisted.
    var selectedCaptionTrack: CaptionTrack?

    /// Ruling 34: the Settings "Audio only" value SEEDS this per-session toggle (Android's settings
    /// value is written and never read -- player.md §15, defect). Session-only after seeding:
    /// flipping it here never writes back to `SettingsStore`, matching Android's own non-persisted
    /// toggle (player.md §5, "Not persisted; resets with the ViewModel").
    var audioOnly: Bool

    /// Guards the one-time "auto-enable the first track when VoiceOver's closed-captioning
    /// setting is on" default (spec §10) so it never fights a later manual "Off" pick across
    /// re-renders of the same session.
    var captionsAutoEnableApplied = false

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
    private var isRecovering = false
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

    /// The app's ONE cast seam (spec §10), or nil for a player that has no cast affordance: Shorts
    /// (the cast button is the main player's toolbar only) and every test that is not about
    /// casting. `reconcile(_:)` is then a no-op -- the same "no cast at all" posture a container
    /// whose `GCKCastContext` could not be created already has.
    private let cast: CastController?

    /// M4 (B1 final review): `catalog` and `favorites` were stored and never read -- `PlayerToolbar`
    /// reaches favorites through the environment container on its own, and nothing in the player
    /// touches the catalog.
    init(resolver: any StreamResolving, settings: any SettingsStore, args: PlayerArgs,
         queueSource: (any PlaylistQueueSource)? = nil, cast: CastController? = nil) {
        self.resolver = resolver
        self.settings = settings
        self.args = args
        self.queueSource = queueSource
        self.cast = cast
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
              case .hls(_, _, let audioOnlyURL, _) = resolved.stream else { return false }
        return audioOnlyURL != nil
    }

    func open() async {
        await resolve(forceRefresh: false, kind: .player)
        // ponytail: sequential, deliberately. Android defers its prefetch to the first `isPlaying`
        // (`PlayerFragment.kt:1167-1168`) to keep it off the critical path; awaiting the player's
        // own resolve first achieves the same with no observer and no timer. Ceiling: the queue
        // (and Up Next) appears only after the current video resolved, never before.
        await loadQueue()
        // A launch that already lands within `pageThreshold` of the page end (a row tap near the
        // bottom of a page, a `targetVideoId` there) needs its next page NOW, not on the first
        // advance -- otherwise Up Next lists a truncated tail, or nothing at all (ruling 33 hides
        // an empty section) while a whole page is still unfetched.
        await pageIfNeeded()
        await prefetchUpcoming()
    }

    // MARK: - Queue (B5 task 2)

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
        guard !safeMode, !queue.items.isEmpty else { return }
        await advance()
    }

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
        // `.embed` counts as a successful advance (Cubic #3): the video shows in PlayerScreen's
        // embed branch and a direct tap would have played it -- skipping it burnt a
        // `consecutiveSkips` slot on a playable item. Backgrounded, the same hop stops the queue
        // instead (traced, not guessed): the state change dismantles `PlayerHostView`, whose
        // teardown runs `BackgroundPlaybackController.detach()` (audio session released, and
        // `NowPlayingSnapshot.make` already returns nil for `.embed` per CF-B2-9, clearing the lock
        // screen), and the embed's WKWebView cannot autoplay without the foreground -- so playback
        // ends silently at the embed item with the queue position preserved for the return to
        // foreground. That is the existing background pause semantics, not a skip.
        if state.isPlayable || state.isEmbedRung {
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
    /// Runs the pure budget machine, then either re-resolves in place or gives up.
    ///
    /// **Rung tracking**: there is none, deliberately. `StreamResolver` walks its whole
    /// `resolverOrder` ladder top-down on every forced resolve and exposes no "start at rung N"
    /// entry point, so `.reResolveSameRung` and `.stepDownRung` are the *same* network call -- the
    /// rung is whatever the resolver hands back, read off `Resolved.stream` by `map` (`.hls` = rung
    /// 1, `.progressive` = rung 2). A rung the resolver can still serve keeps playing; one that has
    /// started failing at resolve time demotes on its own. The two actions differ only in budget
    /// accounting (which is exactly what bounds the loop). A VM-side rung index would be a second,
    /// unenforceable model of the resolver's ladder.
    ///
    /// Position is preserved by NOT passing through `.loading`: `PlayerHostView.player(for:replacing:)`
    /// keeps the live `AVPlayer` and seeks the replacement item back to its `currentTime()`, but only
    /// while the state stays playable -- a `.loading` hop would drop the player and restart at 0.
    func handleRecoveryEvent(_ event: RecoveryEvent) async {
        // One failure raises several observers at once -- `AVPlayerItemFailedToPlayToEndTime` and
        // `status == .failed` land together on a dead stream. Without this the same incident spends
        // two budget slots and fires two resolves, one of which the generation guard then discards.
        // First event in wins; the rest are the same incident.
        guard !isRecovering, let resolved = state.resolved else { return }
        isRecovering = true
        defer { isRecovering = false }
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
        let action = PlaybackRecovery.decide(event: event, state: recoveryBudget)
        recoveryBudget.apply(action, for: event)
        switch action {
        case .exhausted:
            state = .recoveryExhausted(resolved)
        case .reResolveSameRung, .stepDownRung:
            // `showLoading: false` but NOT `silent:` -- by the time this runs the stream has
            // genuinely stopped working, so a failed re-resolve SHOULD surface as `.error` /
            // `.contentUnavailable` / `.cooldown` rather than leaving a dead player on screen.
            await resolve(forceRefresh: true, kind: .autoRecovery, resetBudget: false, showLoading: false)
        }
    }

    /// The stream is genuinely playing again: refund what Android refunds (`RecoveryBudget`).
    func recordPlaybackProgress() {
        recoveryBudget.recordPlaybackProgress()
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
    /// `nil` = nothing castable (the embed rung, or a refusal with no usable stream behind it) --
    /// the caller surfaces that as the same "Couldn't play on {device}" a receiver's own refusal
    /// produces.
    func castMedia(now: Date = Date()) async -> CastMediaInfo? {
        if let fresh = currentCastStream(now: now) { return CastMedia.make(resolved: fresh, args: args) }
        // An advance reconciles BEFORE its own resolve lands, so at this point `state` still carries
        // the outgoing video's stream. Joining the walk this player is already making is free;
        // opening a second, FORCED one would race it onto the manual-retry lane and hand the 30 s
        // minimum interval a reason to refuse the cast of a video that is resolving fine.
        await resolveTask?.value
        if let fresh = currentCastStream(now: now) { return CastMedia.make(resolved: fresh, args: args) }
        do {
            let resolved = try await resolver.resolve(args.videoId, purpose: .player, kind: .player,
                                                      sourceChannelId: args.channelId, forceRefresh: true)
            return CastMedia.make(resolved: resolved, args: args)
        } catch {
            // The user-visible collapse to one banner is deliberate (a cooldown and a receiver
            // refusal look the same to them), but swallowing the reason entirely made a cast that
            // can never work indistinguishable from one the TV refused.
            #if DEBUG
            print("PlayerViewModel: cast resolve failed for \(args.videoId): \(error)")
            #endif
            // A refusal is not a dead stream: a limiter cooldown, a bot check or a transient
            // failure all leave the URL the phone is playing right now perfectly castable, and
            // near-expiry is a reason to prefer a fresher one, never a reason to cast nothing.
            // With no stream of ours at all (the embed rung, a resolve that never landed) there is
            // nothing to fall back to and the banner is the honest answer.
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
            resumeAfterCast(at: nil, resume: resume)
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
            // Nothing castable: the embed rung (never castable -- the no-hand-off directive), a
            // resolve that did not come back, or a near-expiry URL that could not outlive
            // what is left to play. Same outcome for the user as a receiver refusing the load, so
            // it gets the same banner rather than copy of its own.
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
        // Never for a LIVE stream. `CastController.load` deliberately leaves `startTime` at
        // the live edge for live media, so the receiver's sampled `approximateStreamPosition` is a
        // number from its own timeline that means nothing on ours -- seeking to it lands the local
        // player at an unrelated point or the DVR edge. Live resumes where live always resumes.
        if let position, position > 0, position.isFinite, !state.isLive {
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
        // `showLoading: false` + `silent: true` are both load-bearing and are NOT the same flag:
        // `showLoading: false` holds the playable branch on the way IN (a `.loading` hop dismantles
        // PlayerHostView and drops the AVPlayer whose `currentTime()` carries the position);
        // `silent: true` holds it on the way OUT (see `performResolve`). `handleRecoveryEvent` passes
        // the first and not the second, because a genuine playback failure SHOULD surface.
        // `resetBudget: false` so a pre-emptive refresh cannot refill the recovery budget.
        await resolve(forceRefresh: true, kind: .proactiveTTLRefresh,
                      resetBudget: false, showLoading: false, silent: true)
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
        // A user-initiated open/retry is a fresh stream (or a deliberate fresh start on the same
        // one): hand it a full budget. Recovery's own re-resolves must not refill their own budget.
        if resetBudget { recoveryBudget = RecoveryBudget() }
        if showLoading { state = .loading }
        let task = Task {
            await self.performResolve(generation: myGeneration, forceRefresh: forceRefresh,
                                      kind: kind, silent: silent)
        }
        resolveTask = task
        await task.value
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
        // A silent (proactive TTL) refresh runs WHILE a healthy stream is playing -- that is why it
        // skips the `.loading` hop. It must therefore never apply a non-playable result: a
        // `RateLimitedResolver` cooldown or a network blip on foreground would otherwise knock a
        // working player out of `PlayerScreen`'s `.ready`/`.rung2Progressive` branch, dismantle
        // `PlayerHostView` and drop the `AVPlayer` carrying the position -- for a stream that is
        // still perfectly playable. Dropping the result leaves the unexpired stream playing;
        // reactive recovery (`handleRecoveryEvent`, which never passes `silent`) owns real failures,
        // because by then the stream has actually stopped working.
        if silent, !result.isPlayable { return }
        // Together, always: `.unplayable`/`.error`/`.contentUnavailable` results move it too --
        // the state on screen is now THIS video's, whatever it says.
        resolvedVideoId = videoId
        state = result
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
        case .embed:
            // The floor of the ladder. Safe Mode deliberately does NOT suppress this rung: it keeps
            // playback inside the app, which is the thing Safe Mode exists to preserve.
            return .embed(resolved)
        }
    }

    /// Task 4's bridge from an IFrame error to a `StreamState`. Split from `EmbedErrorPolicy`
    /// (which is pure) so the policy stays a truth table.
    func applyEmbedAction(_ action: EmbedErrorAction) {
        switch action {
        case .reloadOnce:
            break                       // the view reloads its own web view; the state does not move
        case .fail(let messageKey):
            state = .error(messageKey: messageKey)
        case .unplayable(let messageKey):
            state = .unplayable(messageKey: messageKey)
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
