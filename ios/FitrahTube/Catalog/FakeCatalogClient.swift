import Foundation

/// Canned catalog for tests and previews — the iOS counterpart of Android's FakeContentService.
nonisolated struct FakeCatalogClient: CatalogClient {
    static let sampleCategories = [
        Category(id: "c1", name: "Quran", slug: "quran", parentId: nil),
        Category(id: "c2", name: "Lectures", slug: "lectures", parentId: nil),
        Category(id: "c3", name: "Kids", slug: "kids", parentId: nil),
    ]

    private let stored: [Category]

    init(categories: [Category] = FakeCatalogClient.sampleCategories) {
        stored = categories
    }

    func categories() async throws -> [Category] { stored }
}
