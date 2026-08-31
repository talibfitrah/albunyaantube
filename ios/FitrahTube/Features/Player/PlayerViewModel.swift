import AVFoundation
import Foundation
import InnerTubeKit

/// Wraps InnerTubeKit's `StreamResolver` actor behind a protocol `PlayerViewModel` depends on --
/// depending on the concrete actor directly would leave tests unable to script resolve outcomes.
protocol StreamResolving: Sendable {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved
}

/// Production `StreamResolving` -- thin pass-through to the real actor (`AppContainer.resolver`).
/// `kind` is ignored here on purpose: `StreamResolver.resolve` takes no such argument, and its
/// `purpose:` is documented as "reserved for caller-side rate-limiter lane coordination" -- which is
/// exactly what `RateLimitedResolver` (the decorator wrapped around this) now performs.
struct LiveStreamResolver: StreamResolving {
    let resolver: StreamResolver

    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        try await resolver.resolve(videoId, purpose: purpose, sourceChannelId: sourceChannelId, forceRefresh: forceRefresh)
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
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
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
                                                 sourceChannelId: sourceChannelId, forceRefresh: forceRefresh)
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

    /// M4 (B1 final review): `catalog` and `favorites` were stored and never read -- `PlayerToolbar`
    /// reaches favorites through the environment container on its own, and nothing in the player
    /// touches the catalog.
    init(resolver: any StreamResolving, settings: any SettingsStore, args: PlayerArgs,
         queueSource: (any PlaylistQueueSource)? = nil) {
        self.resolver = resolver
        self.settings = settings
        self.args = args
        self.queueSource = queueSource
        self.audioOnly = settings.audioOnly
    }

    /// The Settings "Background play" value, read live so a change made in Settings while the player
    /// is open takes effect on the next background transition (ruling 34).
    var backgroundPlay: Bool { settings.backgroundPlay }

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
        await resolve(forceRefresh: false, kind: .player, showLoading: false)
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
        var next = PlayerArgs(item: item)
        next.playlistId = args.playlistId
        next.shuffled = args.shuffled
        args = next
        currentTime = 0                      // a new video starts at the beginning
        videoZoomed = false                  // B5 Task 3: the fit/zoom override is per stream
        videoIsPortrait = false              // unknown reads as landscape until the new item is ready
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
        // I3: one failure raises several observers at once -- `AVPlayerItemFailedToPlayToEndTime`
        // and `status == .failed` land together on a dead stream. Without this the same incident
        // spent two budget slots and fired two resolves, one of which the generation guard then
        // discarded. First event in wins; the rest are the same incident.
        guard !isRecovering, let resolved = state.resolved else { return }
        isRecovering = true
        defer { isRecovering = false }
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

    private func resolve(forceRefresh: Bool, kind: RequestKind, resetBudget: Bool = true,
                         showLoading: Bool = true, silent: Bool = false) async {
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
