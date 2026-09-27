import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct SearchHistoryStoreTests {
    private func makeStore() -> (UserDefaultsSearchHistoryStore, UserDefaults, String) {
        let suiteName = "SearchHistoryStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        return (UserDefaultsSearchHistoryStore(defaults: defaults), defaults, suiteName)
    }

    @Test func startsEmpty() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }
        #expect(store.entries.isEmpty)
    }

    @Test func addInsertsMostRecentFirst() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.add("quran")
        store.add("tafsir")

        #expect(store.entries == ["tafsir", "quran"])
        #expect(defaults.stringArray(forKey: "search_history") == ["tafsir", "quran"])
    }

    @Test func addDedupesExactMatchByMovingItToFront() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.add("quran")
        store.add("tafsir")
        store.add("quran")

        #expect(store.entries == ["quran", "tafsir"])
    }

    @Test func addCapsAtTenEntriesDroppingOldest() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        for i in 0..<12 { store.add("q\(i)") }

        #expect(store.entries.count == 10)
        #expect(store.entries.first == "q11")
        #expect(!store.entries.contains("q0"))
        #expect(!store.entries.contains("q1"))
    }

    @Test func removeDropsOneEntry() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.add("quran")
        store.add("tafsir")
        store.remove("quran")

        #expect(store.entries == ["tafsir"])
        #expect(defaults.stringArray(forKey: "search_history") == ["tafsir"])
    }

    @Test func clearRemovesTheKeyEntirely() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.add("quran")
        store.clear()

        #expect(store.entries.isEmpty)
        #expect(defaults.object(forKey: "search_history") == nil)
    }

    @Test func rereadingFromDefaultsRestoresPersistedHistory() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.add("quran")
        store.add("tafsir")

        let reloaded = UserDefaultsSearchHistoryStore(defaults: defaults)
        #expect(reloaded.entries == ["tafsir", "quran"])
    }

    /// Gate A-M14: a blank entry used to occupy one of the 10 slots as an invisible row, and the
    /// exact-match dedupe treated `"quran"` and `"quran "` as two distinct entries.
    @Test func blankQueriesAreRejectedAndWhitespaceIsTrimmed() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.add("")
        store.add("   ")
        #expect(store.entries.isEmpty)

        store.add("quran")
        store.add("  quran ")
        #expect(store.entries == ["quran"]) // collapsed, not two slots
    }
}
