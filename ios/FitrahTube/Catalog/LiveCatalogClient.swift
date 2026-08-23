import FitrahAPI
import Foundation

nonisolated struct LiveCatalogClient: CatalogClient {
    private let client: Client

    init(client: Client) { self.client = client }

    func categories() async throws -> [Category] {
        let dtos = try await client.listPublicCategories().ok.body.json
        return dtos.map { Category(id: $0.id, name: $0.name, slug: $0.slug, parentId: $0.parentId) }
    }
}
