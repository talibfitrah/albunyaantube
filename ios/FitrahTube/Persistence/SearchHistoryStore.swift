import Foundation
import Observation

/// Android's `SearchFragment` history (`SharedPreferences` file `search_prefs`, key
/// `search_history`, max 10, dedupe-then-prepend) --
/// `docs/superpowers/plans/2026-08-23-ios-phase1-research/search-categories.md:63-84`. Android
/// serialises as a single `|`-joined string, which silently mis-splits a query containing `|`; iOS
/// keeps the same key but stores a native `[String]` array instead (no delimiter bug, per that
/// contract's §1.3). One-way: no Android data to import.
@MainActor protocol SearchHistoryStore: AnyObject, Observable {
    var entries: [String] { get }
    func add(_ query: String)
    func remove(_ query: String)
    func clear()
}

@MainActor @Observable final class UserDefaultsSearchHistoryStore: SearchHistoryStore {
    private static let key = "search_history"
    private static let maxEntries = 10

    private let defaults: UserDefaults
    private(set) var entries: [String]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        entries = defaults.stringArray(forKey: Self.key) ?? []
    }

    func add(_ query: String) {
        entries.removeAll { $0 == query }
        entries.insert(query, at: 0)
        if entries.count > Self.maxEntries {
            entries.removeLast(entries.count - Self.maxEntries)
        }
        defaults.set(entries, forKey: Self.key)
    }

    func remove(_ query: String) {
        entries.removeAll { $0 == query }
        defaults.set(entries, forKey: Self.key)
    }

    func clear() {
        entries = []
        // Removes the key entirely (matches Android's `prefs.edit().remove(KEY)`) rather than
        // persisting an empty array.
        defaults.removeObject(forKey: Self.key)
    }
}
