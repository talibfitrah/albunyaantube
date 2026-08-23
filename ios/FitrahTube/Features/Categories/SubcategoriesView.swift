import SwiftUI

/// Android's `SubcategoriesFragment` (`search-categories.md:456-497`). Byte-for-byte the same
/// layout/states as `CategoriesView` (shared `CategoryListContent`/`CategoryRow`) with a different
/// data source (`cache.children(of: parentId)`) and a required, already-localized toolbar title.
struct SubcategoriesView: View {
    let parentId: String
    let parentName: String

    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.locale) private var locale

    @State private var viewModel: CategoriesViewModel?

    var body: some View {
        CategoryListContent(categories: container.categories.children(of: parentId), onTap: handleTap)
            .navigationTitle(parentName) // already-localized parent name (search-categories.md:463-466)
            .navigationBarTitleDisplayMode(.inline) // same rationale as CategoriesView
            .task {
                if viewModel == nil {
                    // Gate B1-minor-7: the view's own `\.locale`, not `Locale.current` -- the
                    // persisted "Parent > Sub" label is built from it, and the RTL previews
                    // already render an English label without this.
                    viewModel = CategoriesViewModel(cache: container.categories, filter: container.filters,
                                                    locale: { locale })
                }
                await container.categories.loadIfNeeded()
            }
    }

    // MARK: - Tap (search-categories.md §2.5 tap semantics reused; subcategories are always leaves
    // in Android's data model, but `select(_:)` still checks `cache.children(of:)` generically --
    // a data set with a genuine 3rd level would drill down correctly instead of Android's silent
    // dead end, at no extra cost)

    private func handleTap(_ category: Category) {
        guard let viewModel else { return }
        switch viewModel.select(category) {
        case .drillDown:
            let name = container.categories.displayName(for: category.id, locale: locale) ?? category.name
            router.push(.subcategories(parentId: category.id, parentName: name))
        case .applied(let label):
            router.pendingBanner = BannerMessage(text: Format.localizedFormat("category_filter_applied", locale: locale, label))
            // Pops past *this* Subcategories screen and the Categories screen beneath it in one
            // call, landing on whichever screen originally opened Categories --
            // `popBackStack(categoriesFragment, inclusive = true)` (search-categories.md:519-521).
            router.popToRoot(router.selectedTab)
        }
    }

}

#Preview {
    NavigationStack { SubcategoriesView(parentId: "c1", parentName: "Lectures") }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { SubcategoriesView(parentId: "c1", parentName: "محاضرات") }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
