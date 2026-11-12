import Foundation

/// Backend catalog access (`/api/v1/*`).
nonisolated protocol CatalogClient: Sendable {
    func categories() async throws -> [Category]
    func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection>
    func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState) async throws -> CursorPage<ContentItem>
    func search(query: String, type: ListType?, limit: Int) async throws -> SearchResult
    /// `live`: a live id the phone read on the channel's own page; the backend serves it only if it passes curation.
    func liveChannel(id: String, live: String?) async throws -> LiveChannelLives
    /// Returns while the exact live link `videoId` is still served in a live category (the 204); `LiveLinkNotFound` once
    /// it is not (the 404).
    func liveLink(videoId: String) async throws
}

/// The default for conformers that never serve live categories (test doubles): live-channel mode treats it as "no
/// answer" and retries (spec §9.1 step 4). `LiveCatalogClient` and `FakeCatalogClient` implement it.
nonisolated struct LiveChannelUnsupported: Error {}

/// The backend's 404: the channel is not a servable live channel. Live-channel mode stops instead of retrying
/// (Android: 404 stops, IO or JSON errors retry).
nonisolated struct LiveChannelNotFound: Error {}

/// The link endpoint's 404: the exact live link is no longer served. The heartbeat stops playback; any other error keeps it.
nonisolated struct LiveLinkNotFound: Error {}

/// Any other answer (a 5xx, an undocumented status): retryable, never a `LiveChannelNotFound`.
nonisolated struct LiveChannelUnexpectedStatus: Error {
    let statusCode: Int
    /// The 503's Retry-After (stage 9 I13): live-channel mode's next wait.
    var retryAfter: Int? = nil
}

extension CatalogClient {
    func liveChannel(id: String, live: String?) async throws -> LiveChannelLives { throw LiveChannelUnsupported() }
    func liveLink(videoId: String) async throws { throw LiveChannelUnsupported() }
}

/// `X-Search-Match` (spec §4.6). A missing or unknown value -- an older backend, or `none` with an
/// empty list -- reads as `.exact`, so this build also works against a backend that never sends it.
nonisolated enum SearchMatch: String, Sendable {
    case exact, approximate, related

    init(header: String?) {
        self = header.flatMap { SearchMatch(rawValue: $0.lowercased()) } ?? .exact
    }
}

/// A global-search answer: the items in server order plus how well the best one matched.
nonisolated struct SearchResult: Hashable, Sendable {
    var items: [ContentItem]
    var match: SearchMatch = .exact
}
