import SwiftUI

/// Android's `HomeFragment` (`shell-home.md` §B1-B15). Header + category pill sit outside the
/// scrollable region (always visible, B11); skeleton/error/empty/sections swap inside a single
/// `ScrollView` so `.refreshable` keeps working in every state (RULINGS #12).
struct HomeView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var viewModel: HomeViewModel?
    @State private var containerWidth: CGFloat = 0

    private static let topAnchor = "home-top"

    var body: some View {
        VStack(spacing: 0) {
            header
            categoryPill
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        Color.clear.frame(height: 0).id(Self.topAnchor)
                        stateContent
                    }
                }
                .refreshable { await viewModel?.refresh() }
                .onContentFits { fits in triggerLoadMoreIfNeeded(fits) }
                .onScrollGeometryChange(for: Bool.self) { geometry in
                    let distanceFromEnd = geometry.contentSize.height - (geometry.containerSize.height + geometry.contentOffset.y)
                    return distanceFromEnd < 200 // shell-home.md:B13 -- device-independent version of Android's 300px threshold
                } action: { _, isNearEnd in
                    triggerLoadMoreIfNeeded(isNearEnd)
                }
                .onChange(of: router.scrollToTopSignal) { _, signal in
                    guard signal?.tab == .home else { return }
                    withAnimation { proxy.scrollTo(Self.topAnchor, anchor: .top) }
                }
            }
        }
        .background(Color.homeSurface.ignoresSafeArea())
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { containerWidth = $0 }
        // Fix round 1, finding #4: a category applied on Categories/Subcategories (Task 11) pops
        // straight back to Home without re-running `.task` (this view was never removed from its
        // `NavigationStack`, just the pushed screens above it were). Unlike `ContentListViewModel`,
        // `HomeViewModel` snapshots `filter.state.categoryId` once into `category` at `init` and
        // never re-reads it (RULING 10's "one initial fetch" guard) -- so a plain `load()` on the
        // existing instance would still fetch with the *old* category. Building a fresh
        // `HomeViewModel` re-captures the current filter the same way first appearance does; this
        // is also now the only place `categoryPill`'s Clear routes through (see below), so one
        // filter change never causes two reloads. `.onChange` doesn't fire for the value it's
        // first attached with, so first appearance still fetches exactly once.
        .onChange(of: container.filters.state) { _, _ in
            viewModel = HomeViewModel(catalog: container.catalog, filter: container.filters, widthClass: { widthClass })
            Task { await viewModel?.load() }
        }
        .task {
            if viewModel == nil {
                // ponytail: `widthClass` is captured once here (the environment value at first
                // appearance), not re-read live on every later call -- contentLimit (10 vs 20) can
                // go stale across an iPad Split View resize mid-session. Card *widths* stay live
                // (recomputed from `containerWidth` every body pass); only the server page-size
                // request would need a live provider (e.g. a small reference box) to fully fix.
                viewModel = HomeViewModel(catalog: container.catalog, filter: container.filters, widthClass: { widthClass })
            }
            await viewModel?.load()
        }
    }

    // MARK: - Header (shell-home.md:B2-B3)

    private var header: some View {
        HStack(spacing: 0) {
            Text(String(localized: "app_name"))
                .font(.system(size: 24, weight: .bold)) // RULING 10: 24 pt on every width class, not the scaling TypeScale.headline
                .foregroundStyle(Color.textPrimary)
            Spacer(minLength: 0)
            Button(action: { router.push(.search) }) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 20))
                    .foregroundStyle(Color.brand)
                    .frame(width: 48, height: 48)
            }
            .accessibilityLabel(String(localized: "search"))
            Menu {
                Button {
                    router.push(.favorites)
                } label: {
                    Label(String(localized: "favorites_title"), systemImage: "heart.fill")
                }
                Button {
                    router.push(.settings)
                } label: {
                    Label(String(localized: "settings"), systemImage: "gearshape")
                }
                // Downloads arrives in phase 3 -- task-9 brief.
            } label: {
                Image(systemName: "ellipsis") // iOS convention is horizontal, unlike Android's vertical 3-dot (shell-home.md:B2)
                    .font(.system(size: 20))
                    .foregroundStyle(Color.brand)
                    .frame(width: 48, height: 48)
            }
            .accessibilityLabel(String(localized: "menu"))
        }
        .padding(.horizontal, Spacing.homeHorizontalMargin(widthClass)) // home_horizontal_margin (shell-home.md:227)
        .padding(.top, Spacing.md(widthClass))
        .padding(.bottom, Spacing.sm)
    }

    // MARK: - Category pill (shell-home.md:B4)

    private var categoryPill: some View {
        CategoryPill(
            label: container.filters.state.categoryName ?? String(localized: "filter_category"),
            isActive: container.filters.state.categoryId != nil,
            onTap: { router.push(.categories) },
            // Fix round 1, finding #4: routes through the shared store, so this and an
            // externally-applied category (Categories/Subcategories) both funnel through the
            // single `.onChange(of: container.filters.state)` reload above -- one filter change,
            // one reload, regardless of where it originated.
            onClear: { container.filters.clearCategory() }
        )
        .padding(.horizontal, Spacing.homeHorizontalMargin(widthClass)) // home_horizontal_margin (shell-home.md:227)
        .padding(.bottom, Spacing.md(widthClass))
    }

    // MARK: - State body (shell-home.md:B11-B12)

    @ViewBuilder
    private var stateContent: some View {
        if let viewModel {
            switch viewModel.state {
            case .loading:
                SkeletonCarousel(cards: 4).padding(.top, Spacing.md(widthClass))
            case .error:
                ErrorStateView(message: String(localized: "list_error_description")) {
                    Task { await viewModel.load() }
                }
                .containerRelativeFrame(.vertical)
            case .empty:
                EmptyStateView(
                    systemImage: "film.stack",
                    message: String(localized: "home_empty_content"),
                    action: hasActiveFilter ? (String(localized: "clear_filter"), { container.filters.clearCategory() }) : nil
                )
                .containerRelativeFrame(.vertical)
            case .content(let sections, _, let isLoadingMore):
                LazyVStack(spacing: 0) {
                    ForEach(sections) { section in
                        sectionRow(section, viewModel: viewModel)
                    }
                    if isLoadingMore {
                        ProgressView()
                            .tint(.brand)
                            .frame(maxWidth: .infinity)
                            .padding(.top, Spacing.md(widthClass))
                            .padding(.bottom, Spacing.lg(widthClass))
                            .accessibilityLabel(String(localized: "home_loading_more"))
                    }
                }
            }
        }
    }

    private var hasActiveFilter: Bool { container.filters.state.categoryId != nil }

    // MARK: - Sections / carousel (shell-home.md:B5-B7)

    private func sectionRow(_ section: HomeSection, viewModel: HomeViewModel) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            SectionHeader(
                emoji: section.icon,
                title: sectionTitle(section),
                onSeeAll: { router.push(.featured(categoryId: section.id, categoryName: section.name)) },
                seeAllAccessibilityLabel: viewModel.seeAllLabel(for: section)
            )
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: GridRules.cardGap(widthClass)) {
                    ForEach(section.items) { item in
                        itemView(item, viewModel: viewModel)
                    }
                }
                .padding(.horizontal, Spacing.homeHorizontalMargin(widthClass)) // home_horizontal_margin (shell-home.md:227)
                .padding(.bottom, Spacing.sm)
            }
        }
        .padding(.top, Spacing.lg(widthClass)) // home_vertical_section_spacing 24/32/40 -- matches Spacing.lg exactly
    }

    private func sectionTitle(_ section: HomeSection) -> String {
        let lang = locale.language.languageCode?.identifier ?? "en"
        return section.localizedNames?[lang] ?? section.name
    }

    @ViewBuilder
    private func itemView(_ item: ContentItem, viewModel: HomeViewModel) -> some View {
        switch item.type {
        case .video:
            MediaCard(item: item, width: cardWidth(for: .video)) {
                router.push(.player(viewModel.playerArgs(for: item)))
            }
        case .playlist:
            MediaCard(item: item, width: cardWidth(for: .playlist)) {
                router.push(.playlist(id: item.id, title: item.title, category: item.category, count: item.itemCount))
            }
        case .channel:
            HomeChannelItem(item: item) {
                router.push(.channel(id: item.id, name: item.title, avatarURL: item.thumbnailURL))
            }
        }
    }

    private func cardWidth(for type: ContentType) -> CGFloat {
        guard containerWidth > 0 else { return 0 }
        let visible = GridRules.carouselVisible(type, widthClass)
        return max(0, GridRules.carouselCardWidth(
            container: containerWidth, margin: Spacing.homeHorizontalMargin(widthClass), gap: GridRules.cardGap(widthClass), visible: visible
        ))
    }

    // MARK: - Pagination triggers (shell-home.md:B11 auto-load, B13 scroll threshold)

    private func triggerLoadMoreIfNeeded(_ shouldLoad: Bool) {
        guard shouldLoad, let viewModel,
              case .content(_, let hasMore, let isLoadingMore) = viewModel.state,
              hasMore, !isLoadingMore else { return }
        Task { await viewModel.loadMore() }
    }
}

#Preview {
    MainShellView()
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    MainShellView()
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
