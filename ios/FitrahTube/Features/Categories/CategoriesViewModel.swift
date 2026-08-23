import Foundation

/// What tapping a category row does: drill into its children, or apply it as the active filter.
/// `.applied`'s `label` is the exact text the "Filtering by: %1$@" banner shows and the exact
/// value written to `FilterStore` as the category's display name (RULINGS #26).
nonisolated enum CategorySelection: Equatable {
    case drillDown(Category)
    case applied(label: String)
}

/// Android's `CategoriesFragment`/`SubcategoriesFragment` tap handler
/// (`search-categories.md:336-412,471-581`). One type serves both screens: `select(_:)` only needs
/// the tapped `Category` itself -- `cache.children(of:)` decides drill-down vs. leaf, and for a
/// leaf subcategory, `category.parentId` plus `cache.all` supplies the parent for the "Parent ›
/// Sub" label, so no separate "which screen" or "current parent" input is needed.
@MainActor @Observable final class CategoriesViewModel {
    private let cache: any CategoriesCache
    private let filter: any FilterStore
    /// Not part of the brief's literal `init(cache:filter:)` -- a defaulted trailing closure, same
    /// precedent as `HomeViewModel`'s `widthClass` / `ContentListViewModel`'s `sleep`, so tests can
    /// inject a deterministic locale for the "Parent › Sub" localized-name assertions.
    private let locale: () -> Locale

    init(cache: any CategoriesCache, filter: any FilterStore, locale: @escaping () -> Locale = { .current }) {
        self.cache = cache
        self.filter = filter
        self.locale = locale
    }

    /// Has children -> `.drillDown` (view pushes Subcategories), untouched filter. Otherwise ->
    /// applies the filter with the localized display label and returns `.applied(label:)` (view
    /// pops to origin and shows the banner).
    func select(_ category: Category) -> CategorySelection {
        guard cache.children(of: category.id).isEmpty else {
            return .drillDown(category)
        }
        let label = displayLabel(for: category)
        filter.setCategory(id: category.id, name: label)
        return .applied(label: label)
    }

    /// RULINGS #25: "Parent › Sub" via the localized `filter_label_parent_child` format (identical
    /// across locales, FSI/PDI-isolated) when `category` has a parent; otherwise just its own name.
    private func displayLabel(for category: Category) -> String {
        let currentLocale = locale()
        let ownName = Format.categoryDisplayName(category, locale: currentLocale)
        guard let parentId = category.parentId,
              let parent = cache.all.first(where: { $0.id == parentId }) else {
            return ownName
        }
        let parentName = Format.categoryDisplayName(parent, locale: currentLocale)
        // Looked up in the passed locale's own `.lproj` rather than through a bare
        // `String(localized:)`, which answers only for the *device's* language (gate B1-minor-7).
        //
        // Deliberately NOT `Format.localizedFormat`, the shared helper every other call site uses:
        // that passes `locale:` to `String(format:)`, which switches on localized substitution and
        // wraps each argument in its own bidi isolates. This key already carries explicit
        // FSI/PDI isolates of its own (RULINGS 25), so the two stack up and the label renders with
        // doubled U+2068/U+2069 around every name.
        let format = Format.localizedBundle(for: currentLocale)
            .localizedString(forKey: "filter_label_parent_child", value: nil, table: nil)
        return String(format: format, parentName, ownName)
    }
}
