import Foundation

/// Canned catalog for tests and previews — the iOS counterpart of Android's FakeContentService.
/// `home`/`content` cursors are stringified indices into `homePages`/`contentPages`.
///
/// Debug-only, and compiled out of Release entirely (gate wave-4 V8) -- same treatment, and the
/// same reason, as `ComponentsGallery`: nothing in Release can select it (`FitrahTubeApp` hardcodes
/// `AppContainer.live()` there and an un-injected `\.container` traps), so shipping the whole
/// canned catalog -- sample categories, home pages, content pages, search results -- plus
/// `AppContainer.fake`/`sharedFake` is dead weight in the binary. `#Preview` bodies reference
/// `.sharedFake` and are compiled in Release too, so they carry the same guard.
#if DEBUG
nonisolated struct FakeCatalogClient: CatalogClient {
    static let sampleCategories = [
        Category(id: "c1", name: "Quran", slug: "quran", parentId: nil),
        Category(id: "c2", name: "Lectures", slug: "lectures", parentId: nil),
        Category(id: "c3", name: "Kids", slug: "kids", parentId: nil),
    ]

    static let sampleHomePages: [CursorPage<HomeSection>] = {
        let sections = (1...2).map { sectionIndex in
            HomeSection(id: "c\(sectionIndex)", name: "Section \(sectionIndex)", localizedNames: nil, icon: nil,
                        items: sampleContentItems(count: 10, prefix: "s\(sectionIndex)"))
        }
        return [CursorPage(items: sections, nextCursor: nil)]
    }()

    static let sampleContentPages: [CursorPage<ContentItem>] = (0..<3).map { pageIndex in
        CursorPage(items: sampleContentItems(count: 20, prefix: "p\(pageIndex)"),
                   nextCursor: pageIndex < 2 ? "\(pageIndex + 1)" : nil)
    }

    static let sampleSearchResults: [ContentItem] = sampleContentItems(count: 5, prefix: "search")

    private static func sampleContentItems(count: Int, prefix: String) -> [ContentItem] {
        (0..<count).map { i in
            ContentItem(id: "\(prefix)-\(i)", type: .video, title: "Video \(prefix)-\(i)", category: nil,
                        description: nil, thumbnailURL: nil, durationSeconds: 120, uploadedDaysAgo: 1,
                        viewCount: 0, channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil)
        }
    }

    private let categoriesStore: [Category]
    private let homePages: [CursorPage<HomeSection>]
    private let contentPages: [CursorPage<ContentItem>]
    private let searchResultsStore: [ContentItem]

    init(categories: [Category] = FakeCatalogClient.sampleCategories,
         homePages: [CursorPage<HomeSection>] = FakeCatalogClient.sampleHomePages,
         contentPages: [CursorPage<ContentItem>] = FakeCatalogClient.sampleContentPages,
         searchResults: [ContentItem] = FakeCatalogClient.sampleSearchResults) {
        categoriesStore = categories
        self.homePages = homePages
        self.contentPages = contentPages
        searchResultsStore = searchResults
    }

    func categories() async throws -> [Category] { categoriesStore }

    func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
        page(cursor, from: homePages)
    }

    func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
        page(cursor, from: contentPages)
    }

    func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { searchResultsStore }

    private func page<Item: Sendable & Hashable>(_ cursor: String?, from pages: [CursorPage<Item>]) -> CursorPage<Item> {
        let index = cursor.flatMap(Int.init) ?? 0
        guard pages.indices.contains(index) else { return CursorPage(items: [], nextCursor: nil) }
        return pages[index]
    }
}
#endif
