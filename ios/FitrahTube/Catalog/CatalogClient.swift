import Foundation

/// Backend catalog access (`/api/v1/*`).
nonisolated protocol CatalogClient: Sendable {
    func categories() async throws -> [Category]
    func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection>
    func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem>
    func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem]
}
