import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct CategoriesCacheTests {
    // `FitrahTube.` prefix disambiguates from `objc/runtime.h`'s `typedef ... *Category`,
    // pulled into scope transitively once this file declares a Swift `actor` (CallCounter below).
    private func sample() -> [FitrahTube.Category] {
        [
            Category(id: "b", name: "Beta", slug: "beta", parentId: nil, displayOrder: 2),
            Category(id: "a", name: "Alpha", slug: "alpha", parentId: nil, displayOrder: 1),
            Category(id: "c", name: "Gamma", slug: "gamma", parentId: nil, displayOrder: nil),
            Category(id: "a1", name: "Alpha Child", slug: "alpha-child", parentId: "a", displayOrder: 1),
        ]
    }

    private actor CallCounter {
        private(set) var count = 0
        func increment() { count += 1 }
    }

    private struct CountingCatalogClient: CatalogClient {
        let categoriesResult: [FitrahTube.Category]
        let counter: CallCounter

        func categories() async throws -> [FitrahTube.Category] {
            await counter.increment()
            return categoriesResult
        }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    private struct FailingCatalogClient: CatalogClient {
        struct Boom: Error {}
        func categories() async throws -> [FitrahTube.Category] { throw Boom() }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    @Test func loadIfNeededFetchesOncePerProcess() async {
        let counter = CallCounter()
        let cache = LiveCategoriesCache(client: CountingCatalogClient(categoriesResult: sample(), counter: counter))

        await cache.loadIfNeeded()
        await cache.loadIfNeeded()

        #expect(await counter.count == 1)
        #expect(cache.all.count == 4)
    }

    @Test func concurrentLoadIfNeededFetchesOnlyOnce() async {
        // Two callers racing loadIfNeeded() before the first fetch resolves (e.g. two views
        // appearing at once) must not double-fetch: `isLoading` is set before the first `await`
        // inside fetch(), so the second Task's guard check already sees it true.
        let counter = CallCounter()
        let cache = LiveCategoriesCache(client: CountingCatalogClient(categoriesResult: sample(), counter: counter))

        async let first: Void = Task { await cache.loadIfNeeded() }.value
        async let second: Void = Task { await cache.loadIfNeeded() }.value
        _ = await (first, second)

        #expect(await counter.count == 1)
        #expect(cache.all.count == 4)
    }

    @Test func reloadAlwaysFetches() async {
        let counter = CallCounter()
        let cache = LiveCategoriesCache(client: CountingCatalogClient(categoriesResult: sample(), counter: counter))

        await cache.reload()
        await cache.reload()

        #expect(await counter.count == 2)
    }

    @Test func topLevelSortsByDisplayOrderThenNameWithNilLast() async {
        let cache = LiveCategoriesCache(client: FakeCatalogClient(categories: sample()))
        await cache.loadIfNeeded()

        #expect(cache.topLevel().map(\.id) == ["a", "b", "c"])
    }

    @Test func childrenFiltersByParentId() async {
        let cache = LiveCategoriesCache(client: FakeCatalogClient(categories: sample()))
        await cache.loadIfNeeded()

        #expect(cache.children(of: "a").map(\.id) == ["a1"])
        #expect(cache.children(of: "b").isEmpty)
    }

    @Test func displayNameResolvesLocalizedNameOrFallsBackToRawName() async {
        let categories = [
            Category(id: "x", name: "Fallback", slug: "x", parentId: nil, localizedNames: ["ar": "بديل"]),
        ]
        let cache = LiveCategoriesCache(client: FakeCatalogClient(categories: categories))
        await cache.loadIfNeeded()

        #expect(cache.displayName(for: "x", locale: Locale(identifier: "ar")) == "بديل")
        #expect(cache.displayName(for: "x", locale: Locale(identifier: "en")) == "Fallback")
        #expect(cache.displayName(for: "missing", locale: Locale(identifier: "en")) == nil)
    }

    @Test func fetchFailureSurfacesErrorAndLeavesAllEmpty() async {
        let cache = LiveCategoriesCache(client: FailingCatalogClient())

        await cache.loadIfNeeded()

        #expect(cache.error != nil)
        #expect(cache.all.isEmpty)
        #expect(cache.isLoading == false)
    }
}
