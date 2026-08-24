import AVFoundation
import Foundation
import InnerTubeKit

/// Wraps InnerTubeKit's `StreamResolver` actor behind a protocol `PlayerViewModel` depends on --
/// depending on the concrete actor directly would leave tests unable to script resolve outcomes.
protocol StreamResolving: Sendable {
    func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved
}

/// Production `StreamResolving` -- thin pass-through to the real actor (`AppContainer.resolver`).
struct LiveStreamResolver: StreamResolving {
    let resolver: StreamResolver

    func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        try await resolver.resolve(videoId, purpose: purpose, sourceChannelId: sourceChannelId, forceRefresh: forceRefresh)
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

    /// Guards the one-time "auto-enable the first track when VoiceOver's closed-captioning
    /// setting is on" default (spec §10) so it never fights a later manual "Off" pick across
    /// re-renders of the same session.
    var captionsAutoEnableApplied = false

    private let resolver: any StreamResolving
    private let catalog: any CatalogClient
    private let favorites: any FavoritesStore
    private let settings: any SettingsStore
    private let args: PlayerArgs

    private var generation = 0
    private var resolveTask: Task<Void, Never>?
    private var recoveryBudget = RecoveryBudget()

    init(resolver: any StreamResolving, catalog: any CatalogClient, favorites: any FavoritesStore,
         settings: any SettingsStore, args: PlayerArgs) {
        self.resolver = resolver
        self.catalog = catalog
        self.favorites = favorites
        self.settings = settings
        self.args = args
    }

    func open() async {
        await resolve(forceRefresh: false)
    }

    /// CF-B1: `.cooldown` is terminal -- a manual retry while still inside the window must not
    /// re-hit the network (that's exactly the traffic the cooldown exists to suppress). Once
    /// `until` has passed this is a normal forced re-resolve.
    func retry() async {
        if case .cooldown(let until) = state, until > Date() { return }
        await resolve(forceRefresh: true)
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
        guard let resolved = Self.playable(state) else { return }
        let action = PlaybackRecovery.decide(event: event, state: recoveryBudget)
        recoveryBudget.apply(action, for: event)
        switch action {
        case .exhausted:
            state = .recoveryExhausted(resolved)
        case .reResolveSameRung, .stepDownRung:
            await resolve(forceRefresh: true, resetBudget: false, showLoading: false)
        }
    }

    /// The stream is genuinely playing again: refund what Android refunds (`RecoveryBudget`).
    func recordPlaybackProgress() {
        recoveryBudget.recordPlaybackProgress()
    }

    private static func playable(_ state: StreamState) -> Resolved? {
        switch state {
        case .ready(let resolved), .rung2Progressive(let resolved): return resolved
        default: return nil
        }
    }

    private func resolve(forceRefresh: Bool, resetBudget: Bool = true, showLoading: Bool = true) async {
        generation += 1
        let myGeneration = generation
        resolveTask?.cancel()
        // A user-initiated open/retry is a fresh stream (or a deliberate fresh start on the same
        // one): hand it a full budget. Recovery's own re-resolves must not refill their own budget.
        if resetBudget { recoveryBudget = RecoveryBudget() }
        if showLoading { state = .loading }
        let task = Task { await self.performResolve(generation: myGeneration, forceRefresh: forceRefresh) }
        resolveTask = task
        await task.value
    }

    private func performResolve(generation: Int, forceRefresh: Bool) async {
        let result: StreamState
        do {
            let resolved = try await resolver.resolve(
                args.videoId, purpose: .player, sourceChannelId: args.channelId, forceRefresh: forceRefresh)
            result = Self.map(resolved)
        } catch {
            result = Self.map(error)
        }
        // A superseding `open()`/`retry()` both cancels this job and bumps `generation` before its
        // own resolve starts -- either signal alone is enough to discard a late/stale completion,
        // matching `HomeViewModel.fetchFirstPage`'s belt-and-suspenders check.
        guard !Task.isCancelled, generation == self.generation else { return }
        state = result
    }

    private static func map(_ resolved: Resolved) -> StreamState {
        switch resolved.stream {
        case .hls:
            return .ready(resolved)
        case .progressive:
            return .rung2Progressive(resolved)
        case .embed, .openInYouTube:
            // ponytail: B3 wires the real embed rung; B1 has no embed player yet, so both fall
            // outcomes back to a generic error.
            return .error(messageKey: "player_error_generic")
        }
    }

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
