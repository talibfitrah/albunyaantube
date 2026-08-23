import Foundation

nonisolated struct Category: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let slug: String
    let parentId: String?
}

/// Backend catalog access (`/api/v1/*`). Phase 1 adds home/content/search/HEAD gates.
nonisolated protocol CatalogClient: Sendable {
    func categories() async throws -> [Category]
}
