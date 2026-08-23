import SwiftUI

/// Android's `CategoriesFragment` (`search-categories.md:280-334`). RULINGS #29: gets real
/// skeleton/error/empty states (Android has none -- silent failure to a blank screen) plus
/// pull-to-refresh (RULINGS #12) via `CategoriesCache.reload()`.
struct CategoriesView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.locale) private var locale

    @State private var viewModel: CategoriesViewModel?

    var body: some View {
        CategoryListContent(categories: container.categories.topLevel(), onTap: handleTap)
            .navigationTitle(String(localized: "categories"))
            // Android's toolbar title is a plain inline Headline6, not a collapsing large title
            // (search-categories.md §2.2); `.inline` also avoids the large title visually
            // colliding with the first row's own text on a raw (non-`List`) `ScrollView`.
            .navigationBarTitleDisplayMode(.inline)
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

    // MARK: - Tap (search-categories.md §2.5)

    private func handleTap(_ category: Category) {
        guard let viewModel else { return }
        switch viewModel.select(category) {
        case .drillDown:
            let name = container.categories.displayName(for: category.id, locale: locale) ?? category.name
            router.push(.subcategories(parentId: category.id, parentName: name))
        case .applied(let label):
            // The origin screen (Home or Channels, whichever pushed .categories) is whatever's at
            // the current tab's root -- `popToRoot` truncates past both Categories and (if reached
            // through it) Subcategories in one call, matching Android's
            // `popBackStack(categoriesFragment, inclusive = true)`.
            router.pendingBanner = BannerMessage(text: Format.localizedFormat("category_filter_applied", locale: locale, label))
            router.popToRoot(router.selectedTab)
        }
    }

}

// MARK: - Shared list body (Categories top-level + Subcategories children -- identical layout,
// states, and tap handling; only which categories from the cache render differs)

struct CategoryListContent: View {
    let categories: [Category]
    let onTap: (Category) -> Void

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.md(widthClass)) {
                stateContent
            }
            .padding(Spacing.md(widthClass))
        }
        .background(Color.surfaceVariant.ignoresSafeArea())
        .refreshable { await container.categories.reload() }
    }

    @ViewBuilder
    private var stateContent: some View {
        if container.categories.isLoading, container.categories.all.isEmpty {
            SkeletonListView()
        } else if container.categories.error != nil, container.categories.all.isEmpty {
            ErrorStateView(message: String(localized: "list_error_description")) {
                Task { await container.categories.reload() }
            }
            .containerRelativeFrame(.vertical)
        } else if categories.isEmpty {
            EmptyStateView(systemImage: "square.grid.2x2", message: String(localized: "home_empty_content"))
                .containerRelativeFrame(.vertical)
        } else {
            ForEach(categories) { category in
                CategoryRow(category: category, hasChildren: !container.categories.children(of: category.id).isEmpty) {
                    onTap(category)
                }
            }
        }
    }
}

// MARK: - Row (item_category.xml -- card-style row, contract §2.2)

struct CategoryRow: View {
    let category: Category
    let hasChildren: Bool
    let onTap: () -> Void

    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: Spacing.sm) {
                Text(Format.categoryDisplayName(category, locale: locale))
                    .font(TypeScale.subtitle)
                    .foregroundStyle(Color.textPrimary)
                Spacer(minLength: 0)
                if hasChildren {
                    // Icon deliberately hidden on this row (Android: "Icons are surfaced on the
                    // home screen section headers... crowds long localized names" --
                    // CategoryAdapter.kt:41-44); only the drill-down chevron survives here.
                    Image(systemName: "chevron.forward")
                        .foregroundStyle(Color.textSecondary)
                }
            }
            .padding(Spacing.lg(widthClass))
            .frame(maxWidth: .infinity)
            .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    NavigationStack { CategoriesView() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { CategoriesView() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
