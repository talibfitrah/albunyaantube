import Foundation
import Observation

/// Android's `FilterManager` (DataStore-backed app-wide singleton) --
/// `docs/superpowers/plans/2026-08-23-ios-phase1-research/content-lists.md:510-599`. Only category
/// is ever mutated in this app (§6.3, RULINGS #18/#26): `length`/`date`/`sort` are read back for
/// forward compatibility with the persisted keys but have no writer here.
@MainActor protocol FilterStore: AnyObject, Observable {
    var state: FilterState { get }
    /// `id == nil` also clears the name. `name == nil` (with a non-nil id) removes just the name,
    /// so the chip falls back to showing the raw id, matching `FilterManager.setCategory` (§6.2).
    func setCategory(id: String?, name: String?)
    func clearCategory()
}

@MainActor @Observable final class UserDefaultsFilterStore: FilterStore {
    private enum Keys {
        static let categoryId = "filter_category"
        static let categoryName = "filter_category_name"
        static let length = "filter_length"
        static let date = "filter_date"
        static let sort = "filter_sort"
    }

    private let defaults: UserDefaults
    private(set) var state: FilterState

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        state = FilterState(
            categoryId: defaults.string(forKey: Keys.categoryId),
            categoryName: defaults.string(forKey: Keys.categoryName),
            length: defaults.string(forKey: Keys.length).flatMap(LengthFilter.init(rawValue:)),
            date: defaults.string(forKey: Keys.date).flatMap(DateFilter.init(rawValue:)),
            sort: defaults.string(forKey: Keys.sort).flatMap(SortFilter.init(rawValue:))
        )
    }

    func setCategory(id: String?, name: String?) {
        // Android's FilterManager.kt:49 clears on `isNullOrEmpty()`, not just null -- an empty
        // string id (e.g. a chip cleared via an empty text field) must clear the same as nil.
        let id = id?.isEmpty == true ? nil : id
        let name = id == nil ? nil : name
        state.categoryId = id
        state.categoryName = name
        write(id, forKey: Keys.categoryId)
        write(name, forKey: Keys.categoryName)
    }

    func clearCategory() {
        setCategory(id: nil, name: nil)
    }

    private func write(_ value: String?, forKey key: String) {
        if let value {
            defaults.set(value, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}
