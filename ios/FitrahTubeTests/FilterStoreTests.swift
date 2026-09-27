import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct FilterStoreTests {
    private func makeStore() -> (UserDefaultsFilterStore, UserDefaults, String) {
        let suiteName = "FilterStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        return (UserDefaultsFilterStore(defaults: defaults), defaults, suiteName)
    }

    @Test func defaultStateHasNoActiveFilters() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        #expect(store.state == FilterState())
    }

    @Test func setCategoryPersistsIdAndNameUnderVerbatimKeys() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.setCategory(id: "c1", name: "Quran")

        #expect(store.state.categoryId == "c1")
        #expect(store.state.categoryName == "Quran")
        #expect(defaults.string(forKey: "filter_category") == "c1")
        #expect(defaults.string(forKey: "filter_category_name") == "Quran")
    }

    @Test func setCategoryWithNilNameRemovesOnlyTheNameKey() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.setCategory(id: "c1", name: "Quran")
        store.setCategory(id: "c1", name: nil)

        #expect(store.state.categoryId == "c1")
        #expect(store.state.categoryName == nil)
        #expect(defaults.string(forKey: "filter_category") == "c1")
        #expect(defaults.object(forKey: "filter_category_name") == nil)
    }

    @Test func setCategoryWithNilIdAlsoClearsAnyGivenName() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.setCategory(id: nil, name: "X")

        #expect(store.state.categoryId == nil)
        #expect(store.state.categoryName == nil)
        #expect(defaults.object(forKey: "filter_category_name") == nil)
    }

    @Test func setCategoryWithEmptyStringIdIsTreatedAsClear() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.setCategory(id: "", name: "X")

        #expect(store.state.categoryId == nil)
        #expect(store.state.categoryName == nil)
        #expect(defaults.object(forKey: "filter_category") == nil)
        #expect(defaults.object(forKey: "filter_category_name") == nil)
    }

    @Test func clearCategoryRemovesBothKeys() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.setCategory(id: "c1", name: "Quran")
        store.clearCategory()

        #expect(store.state.categoryId == nil)
        #expect(store.state.categoryName == nil)
        #expect(defaults.object(forKey: "filter_category") == nil)
        #expect(defaults.object(forKey: "filter_category_name") == nil)
    }

    @Test func rereadingFromDefaultsRestoresPersistedCategory() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.setCategory(id: "c9", name: "Tafsir")

        let reloaded = UserDefaultsFilterStore(defaults: defaults)
        #expect(reloaded.state.categoryId == "c9")
        #expect(reloaded.state.categoryName == "Tafsir")
    }

    @Test func lengthDateSortRoundTripByRawValueAndSurviveClearCategory() {
        let (_, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set(LengthFilter.long.rawValue, forKey: "filter_length")
        defaults.set(DateFilter.last7Days.rawValue, forKey: "filter_date")
        defaults.set(SortFilter.mostPopular.rawValue, forKey: "filter_sort")

        let reloaded = UserDefaultsFilterStore(defaults: defaults)
        #expect(reloaded.state.length == .long)
        #expect(reloaded.state.date == .last7Days)
        #expect(reloaded.state.sort == .mostPopular)

        // Only category is ever mutated (content-lists.md §6.3) -- clearing it must not touch
        // whatever length/date/sort happen to be persisted.
        reloaded.setCategory(id: "c1", name: "Quran")
        reloaded.clearCategory()
        #expect(reloaded.state.length == .long)
        #expect(reloaded.state.date == .last7Days)
        #expect(reloaded.state.sort == .mostPopular)
    }

    @Test func unknownPersistedEnumRawValueFallsBackToNilInsteadOfCrashing() {
        let (_, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.set("NOT_A_REAL_LENGTH", forKey: "filter_length")

        let reloaded = UserDefaultsFilterStore(defaults: defaults)
        #expect(reloaded.state.length == nil)
    }
}
