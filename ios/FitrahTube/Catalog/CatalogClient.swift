import Foundation

/// Backend catalog access (`/api/v1/*`).
nonisolated protocol CatalogClient: Sendable {
    func categories() async throws -> [Category]
    func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection>
    func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem>
    func search(query: String, type: ListType?, limit: Int) async throws -> SearchResult
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
