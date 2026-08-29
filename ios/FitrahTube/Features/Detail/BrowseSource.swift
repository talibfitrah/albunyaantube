import Foundation
import InnerTubeKit

/// The browse seam both detail screens are assembled over (Plan C Task 2). One protocol, two
/// implementations -- `LiveBrowseSource` and the simulator's fake -- for the same reason
/// `StreamResolving` exists: the screens need something a fixture can drive.
protocol BrowseSource: Sendable {
    func channelHeader(_ id: String) async throws -> ChannelHeader
    func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem>
    func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem>
    func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile>
    func playlistItems(_ playlistId: String, continuation: String?) async throws -> BrowsePage<VideoItem>
    /// True while the browse-only latch holds -- the screens show `browse_degraded_notice` and
    /// route the in-header search to the backend index instead of the loaded items.
    func isDegraded() async -> Bool
}

/// Thrown by `LiveBrowseSource` when `BackendAvailabilityGate` answers 410 (RULING 15): the
/// screen shows `content_unavailable_*`, never a network error.
nonisolated enum BrowseSourceError: Error, Sendable, Equatable {
    case unavailable
}

/// CF-C3 + reconciliation note 3: whether a failed browse call switches the app into degraded mode.
/// A bot-check does and latches for a flat hour (its own key, never `SessionStore`'s cooldown --
/// that ladder silences the *player*); anything else is a real error and is surfaced with Retry.
nonisolated enum BrowseFallback {
    enum Decision: Equatable, Sendable {
        case surfaceError
        case degrade(latchUntil: Date)
        case alreadyDegraded
    }

    static let latchDuration: TimeInterval = 3600

    static func isLatched(until: Date?, now: Date) -> Bool {
        until.map { $0 > now } ?? false
    }

    static func decide(_ error: any Error, latchedUntil: Date?, now: Date) -> Decision {
        if isLatched(until: latchedUntil, now: now) { return .alreadyDegraded }
        guard case BrowseError.botCheck = error else { return .surfaceError }
        return .degrade(latchUntil: now.addingTimeInterval(latchDuration))
    }
}

/// The persistence half of the latch. Key namespace matches InnerTubeKit's own
/// (`SessionStore.swift:11-12`) so everything this app persists under UserDefaults is greppable
/// from one prefix. `store` is the SAME UserDefaults-backed store InnerTubeKit already uses.
struct DegradedLatch: Sendable {
    static let key = "FitrahTube.Browse.degradedUntil"
    let store: any KeyValueStore

    var until: Date? {
        get { store.get(Self.key).flatMap { try? JSONDecoder().decode(Date.self, from: $0) } }
        nonmutating set { if let d = try? JSONEncoder().encode(newValue) { store.set(Self.key, d) } }
    }
}

/// Production `BrowseSource`: gate → browse → `BrowseFallback.decide` on throw → substitute →
/// push to the index. **Untested by design** (the rule B1 applies to `LiveStreamResolver` and B5
/// to `LivePlaylistQueueSource`): everything decidable lives in `BrowseFallback`, `DegradedLatch`,
/// `IndexClient` and `BackendAvailabilityGate`, all tested; this is a pass-through whose behaviour
/// is the clients' own.
///
/// Degraded mode is three different substitutions, not one (plan Task 2 table): the header comes
/// from the backend's `Channel` DTO, Videos from the Atom feed (15 newest, no pagination), and
/// Live / Shorts / Playlists / playlist items have NO substitute -- they rethrow so the tab shows
/// `channel_tab_error_generic` with Retry rather than an empty state that lies about the channel.
struct LiveBrowseSource: BrowseSource {
    let client: BrowseClient
    let atom: AtomFeedFetcher
    let latch: DegradedLatch
    let index: IndexClient
    let gate: BackendAvailabilityGate
    /// `getPublicChannel` mapped to a header; nil (fake containers) means no header substitute.
    let degradedHeader: (@Sendable (String) async throws -> ChannelHeader)?

    func isDegraded() async -> Bool { BrowseFallback.isLatched(until: latch.until, now: Date()) }

    func channelHeader(_ id: String) async throws -> ChannelHeader {
        guard await gate.verify(channelId: id) else { throw BrowseSourceError.unavailable }
        return try await attempt({ try await client.channelHeader(id) }, degraded: degradedHeader.map { f in { try await f(id) } })
    }

    func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
        try await attempt({
            let page = try await client.channelVideos(id, continuation: continuation)
            Task { await index.push(sourceType: .channel, sourceId: id, items: page.items) }
            return page
        }, degraded: {
            // The feed is one page; a continuation request in degraded mode has nothing to add.
            guard continuation == nil else { return BrowsePage(items: [], nextContinuation: nil) }
            return BrowsePage(items: try await atom.latest(id), nextContinuation: nil)
        })
    }

    func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem> {
        try await attempt({
            let page = try await client.channelTab(id, tab: tab, continuation: continuation)
            Task { await index.push(sourceType: .channel, sourceId: id, items: page.items) }
            return page
        }, degraded: nil)
    }

    func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile> {
        try await attempt({ try await client.channelPlaylists(id, continuation: continuation) }, degraded: nil)
    }

    func playlistItems(_ playlistId: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
        if continuation == nil, await !gate.verify(playlistId: playlistId) { throw BrowseSourceError.unavailable }
        return try await attempt({
            let page = try await client.playlistItems(playlistId, continuation: continuation)
            Task { await index.push(sourceType: .playlist, sourceId: playlistId, items: page.items) }
            return page
        }, degraded: nil)
    }

    /// A live latch skips the probe entirely; a fresh bot-check writes the latch and substitutes;
    /// no substitute (or a non-bot-check error) rethrows so the screen shows its error state.
    private func attempt<T>(_ live: () async throws -> T, degraded: (() async throws -> T)?) async throws -> T {
        if BrowseFallback.isLatched(until: latch.until, now: Date()) {
            guard let degraded else { throw BrowseError.botCheck }
            return try await degraded()
        }
        do {
            return try await live()
        } catch {
            switch BrowseFallback.decide(error, latchedUntil: latch.until, now: Date()) {
            case .surfaceError:
                throw error
            case .degrade(let until):
                latch.until = until
                fallthrough
            case .alreadyDegraded:
                guard let degraded else { throw error }
                return try await degraded()
            }
        }
    }
}

#if DEBUG
/// Fixture-backed `BrowseSource` for previews and UI tests: `pages` canned pages of `perPage`
/// items per tab, a two-tile Playlists tab, and a `degraded` switch for the notice capture.
struct FakeBrowseSource: BrowseSource {
    var pages = 2
    var perPage = 12
    var degraded = false

    func isDegraded() async -> Bool { degraded }

    func channelHeader(_ id: String) async throws -> ChannelHeader {
        ChannelHeader(id: id, name: "Fixture Channel", subscriberText: degraded ? nil : "1.2M subscribers")
    }

    func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
        page(prefix: "video", continuation: continuation)
    }

    func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem> {
        if degraded { throw BrowseError.botCheck }
        return page(prefix: "\(tab)", continuation: continuation)
    }

    func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile> {
        if degraded { throw BrowseError.botCheck }
        return BrowsePage(items: (0..<2).map { PlaylistTile(id: "PL\($0)", title: "Playlist \($0)", itemCountText: "12 videos") },
                          nextContinuation: nil)
    }

    func playlistItems(_ playlistId: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
        if degraded { throw BrowseError.botCheck }
        return page(prefix: "item", continuation: continuation)
    }

    private func page(prefix: String, continuation: String?) -> BrowsePage<VideoItem> {
        let n = continuation.flatMap { Int($0) } ?? 0
        let items = (0..<perPage).map { i in
            VideoItem(id: "\(prefix)-\(n)-\(i)", title: "\(prefix.capitalized) \(n * perPage + i)",
                      durationSeconds: degraded ? nil : 600, viewCountText: degraded ? nil : "1K views")
        }
        return BrowsePage(items: items, nextContinuation: n + 1 < pages ? "\(n + 1)" : nil)
    }
}
#endif
