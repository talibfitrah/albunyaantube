import Foundation
import Testing
@testable import FitrahTube

/// `search-categories.md:336-412` (Categories) + `:471-581` (Subcategories tap). One
/// `CategoriesViewModel` serves both screens: `select(_:)` only needs the tapped `Category` itself
/// -- whether it has children (drill-down) and, if it's a leaf with a `parentId`, the parent's own
/// display name for the "Parent › Sub" label -- both come straight out of the shared
/// `CategoriesCache.all`, so no separate "which screen/parent context" parameter is needed.
@Suite(.perTest)
struct CategoriesViewModelTests {
    // MARK: - Test doubles (same pattern as HomeViewModelTests/ContentListViewModelTests)

    @MainActor @Observable fileprivate final class FakeCategoriesCache: CategoriesCache {
        private(set) var all: [FitrahTube.Category]
        private(set) var isLoading = false
        private(set) var error: Error?

        init(all: [FitrahTube.Category]) { self.all = all }

        func loadIfNeeded() async {}
        func reload() async {}
        func topLevel() -> [FitrahTube.Category] { all.filter { $0.parentId == nil } }
        func children(of id: String) -> [FitrahTube.Category] { all.filter { $0.parentId == id } }
        func displayName(for id: String, locale: Locale) -> String? {
            guard let category = all.first(where: { $0.id == id }) else { return nil }
            return Format.categoryDisplayName(category, locale: locale)
        }
    }


    // MARK: - Fixtures

    private let parent = Category(id: "p1", name: "Lectures", slug: "lectures", parentId: nil,
                                   localizedNames: ["ar": "محاضرات"])
    private let leafTopLevel = Category(id: "t1", name: "Quran", slug: "quran", parentId: nil)
    private let subcategory = Category(id: "s1", name: "Tafsir", slug: "tafsir", parentId: "p1",
                                        localizedNames: ["ar": "تفسير"])
    private let subcategoryNoLocalization = Category(id: "s2", name: "Seerah", slug: "seerah", parentId: "p1")

    private func makeViewModel(locale: Locale = Locale(identifier: "en")) -> (CategoriesViewModel, FakeFilterStore) {
        let cache = FakeCategoriesCache(all: [parent, leafTopLevel, subcategory, subcategoryNoLocalization])
        let filter = FakeFilterStore()
        let vm = CategoriesViewModel(cache: cache, filter: filter, locale: { locale })
        return (vm, filter)
    }

    // MARK: - Drill-down vs apply

    @Test func categoryWithChildrenReturnsDrillDownAndDoesNotTouchTheFilter() {
        let (vm, filter) = makeViewModel()

        let selection = vm.select(parent)

        #expect(selection == .drillDown(parent))
        #expect(filter.state.categoryId == nil)
    }

    @Test func leafTopLevelCategoryAppliesWithItsOwnNameOnly() {
        let (vm, filter) = makeViewModel()

        let selection = vm.select(leafTopLevel)

        #expect(selection == .applied(label: "Quran"))
        #expect(filter.state.categoryId == "t1")
        #expect(filter.state.categoryName == "Quran")
    }

    @Test func leafSubcategoryAppliesWithParentChildLabel() {
        let (vm, filter) = makeViewModel()

        let selection = vm.select(subcategoryNoLocalization)

        guard case .applied(let label) = selection else { Issue.record("expected .applied, got \(selection)"); return }
        #expect(label == "\u{2068}Lectures\u{2069} › \u{2068}Seerah\u{2069}")
        #expect(filter.state.categoryId == "s2")
        #expect(filter.state.categoryName == label)
    }

    // MARK: - Localization (RULINGS #26: filter stores the *localized* display name)

    @Test func appliedLabelUsesLocalizedNamesForBothParentAndChildWhenAvailable() {
        let (vm, filter) = makeViewModel(locale: Locale(identifier: "ar"))

        let selection = vm.select(subcategory)

        guard case .applied(let label) = selection else { Issue.record("expected .applied, got \(selection)"); return }
        #expect(label == "\u{2068}محاضرات\u{2069} › \u{2068}تفسير\u{2069}")
        #expect(filter.state.categoryName == label)
    }

    @Test func appliedLabelFallsBackToRawNameWhenNoLocalizedNameForTheLocale() {
        let (vm, _) = makeViewModel(locale: Locale(identifier: "ar"))

        // subcategoryNoLocalization has no `localizedNames` at all -- falls back to the raw name
        // for both itself and its parent (parent *does* have "ar", so only the child falls back).
        let selection = vm.select(subcategoryNoLocalization)

        guard case .applied(let label) = selection else { Issue.record("expected .applied, got \(selection)"); return }
        #expect(label == "\u{2068}محاضرات\u{2069} › \u{2068}Seerah\u{2069}")
    }

    @Test func selectDoesNotMutateStateOnDrillDown() {
        // A subcategory drill-down check reuses `cache.children(of:)` the same way the top-level
        // check does -- proves the single `select(_:)` works uniformly for both Categories and
        // Subcategories screens without a separate "parent context" input.
        let cache = FakeCategoriesCache(all: [
            parent,
            Category(id: "mid", name: "Mid", slug: "mid", parentId: "p1"),
            Category(id: "leaf", name: "Leaf", slug: "leaf", parentId: "mid"),
        ])
        let filter = FakeFilterStore()
        let vm = CategoriesViewModel(cache: cache, filter: filter, locale: { Locale(identifier: "en") })

        let midSelection = vm.select(Category(id: "mid", name: "Mid", slug: "mid", parentId: "p1"))
        #expect(midSelection == .drillDown(Category(id: "mid", name: "Mid", slug: "mid", parentId: "p1")))
        #expect(filter.state.categoryId == nil)
    }
}
