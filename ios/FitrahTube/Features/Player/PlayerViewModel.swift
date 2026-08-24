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

    private let resolver: any StreamResolving
    private let catalog: any CatalogClient
    private let favorites: any FavoritesStore
    private let settings: any SettingsStore
    private let args: PlayerArgs

    private var generation = 0
    private var resolveTask: Task<Void, Never>?

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

    private func resolve(forceRefresh: Bool) async {
        generation += 1
        let myGeneration = generation
        resolveTask?.cancel()
        state = .loading
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
