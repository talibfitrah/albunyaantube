import Foundation

/// Backend catalog access (`/api/v1/*`).
nonisolated protocol CatalogClient: Sendable {
    func categories() async throws -> [Category]
    func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection>
    func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem>
    func search(query: String, type: ListType?, limit: Int) async throws -> SearchResult
    func liveChannel(id: String) async throws -> LiveChannelLives
}

/// The default for conformers that never serve live categories (test doubles): live-channel mode treats it as "no
/// answer" and retries (spec §9.1 step 4). `LiveCatalogClient` and `FakeCatalogClient` implement it.
nonisolated struct LiveChannelUnsupported: Error {}

/// The backend's 404: the channel is not a servable live channel. Live-channel mode stops instead of retrying
/// (Android: 404 stops, IO or JSON errors retry).
nonisolated struct LiveChannelNotFound: Error {}

/// Any other answer (a 5xx, an undocumented status): retryable, never a `LiveChannelNotFound`.
nonisolated struct LiveChannelUnexpectedStatus: Error { let statusCode: Int }

extension CatalogClient {
    func liveChannel(id: String) async throws -> LiveChannelLives { throw LiveChannelUnsupported() }
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
