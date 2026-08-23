import SwiftUI

/// Android's `FeaturedListFragment` (`content-lists.md:603-701`, §7.5). Sections mode reuses
/// `HomeView`'s carousel row shape; flat mode reuses `ContentListView`'s per-type row components in
/// one heterogeneous grid/list. RULINGS #20: unlike Android, gets an empty state and pull-to-refresh
/// for free -- both live inside the one `.refreshable` `ScrollView` (RULINGS #12).
struct FeaturedView: View {
    let categoryId: String?
    let categoryName: String?

    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var viewModel: FeaturedViewModel?
    @State private var containerWidth: CGFloat = 0
    @State private var isLoadingMore = false
    @State private var paginationGuard = PaginationGuard()
    @State private var contentFits = false

    var body: some View {
        // No `ScrollViewReader`/top anchor: Featured is never a tab root, so nothing ever emits a
        // scroll-to-top signal for it and the proxy was unused (gate B1-minor-11).
        ScrollView {
            stateContent
        }
        .refreshable {
            paginationGuard = PaginationGuard()
            await viewModel?.refresh()
        }
        .onContentFits { fits in
            contentFits = fits
            triggerAutoFill()
        }
        // Re-arms autofill after every completed load (gate B1-C1).
        .onChange(of: viewModel?.state) { _, _ in triggerAutoFill() }
        .background(Color.homeSurface.ignoresSafeArea())
        .navigationTitle(navTitle)
        // Android's toolbar title is a plain inline Headline6, never a collapsing large title
        // (content-lists.md §7.5); on iPhone the default `.large` mode reserves height above the
        // content that a raw (non-`List`) `ScrollView` doesn't auto-inset for, so the first
        // section header visually collided with it. `.inline` matches both.
        .navigationBarTitleDisplayMode(.inline)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { containerWidth = $0 }
        .task {
            if viewModel == nil {
                viewModel = FeaturedViewModel(categoryId: categoryId, categoryName: categoryName, catalog: container.catalog)
            }
            await container.categories.loadIfNeeded() // best-effort, for the localized title
            await viewModel?.load()
        }
    }

    // MARK: - Title (content-lists.md §4.2: resolve to always-localized, regardless of entry path)

    /// Home passes the section's raw name; Featured-to-Featured navigation passes the localized
    /// one (content-lists.md's own noted inconsistency). Re-deriving the title here from the
    /// shared `CategoriesCache` -- rather than trusting whichever name the caller happened to pass
    /// -- fixes that without touching `HomeView`'s push call.
    ///
    /// `resolvedId` prefers the ViewModel's already-resolved `categoryId`; before `.task` creates
    /// the ViewModel (a fraction-of-a-second window), it calls the same static resolver instead of
    /// duplicating the fallback ternary locally (task-12 fold-in).
    private var navTitle: String {
        let resolvedId = viewModel?.categoryId ?? FeaturedViewModel.resolvedCategoryId(categoryId)
        if let localized = container.categories.displayName(for: resolvedId, locale: locale) {
            return localized
        }
        if let categoryName, !categoryName.isEmpty { return categoryName }
        return String(localized: "section_featured")
    }

    // MARK: - State body

    @ViewBuilder
    private var stateContent: some View {
        if let viewModel {
            switch viewModel.state {
            case .loading:
                SkeletonCarousel(cards: 4).padding(.top, Spacing.md(widthClass))
            case .error(let message):
                ErrorStateView(message: message) {
                    Task { await viewModel.load() }
                }
                .containerRelativeFrame(.vertical)
            case .empty:
                EmptyStateView(systemImage: "film.stack", message: String(localized: "home_empty_content"))
                    .containerRelativeFrame(.vertical)
            case .content(let mode, let hasMore):
                content(mode, hasMore: hasMore)
            }
        }
    }

    @ViewBuilder
    private func content(_ mode: FeaturedViewModel.Mode, hasMore: Bool) -> some View {
        switch mode {
        case .sections(let sections):
            sectionsContent(sections, hasMore: hasMore)
        case .flat(let items):
            flatContent(items, hasMore: hasMore)
        }
    }

    // MARK: - Sections mode (mirrors HomeView's carousel rows)

    private func sectionsContent(_ sections: [HomeSection], hasMore: Bool) -> some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(sections.enumerated()), id: \.element.id) { index, section in
                sectionRow(section)
                    // codex-P2: sections mode had no row-appearance trigger at all. Its only path
                    // was `onContentFits`, which by definition declines once the content is tall
                    // enough to scroll (and is off entirely at compact width), so every section
                    // past the first page of 10 was unreachable.
                    .onAppear {
                        guard index >= max(0, sections.count - 5) else { return }
                        triggerScrollLoadMore(hasMore: hasMore)
                    }
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

    private func sectionRow(_ section: HomeSection) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            // content-lists.md §4.2: nested sections navigate recursively into another Featured
            // screen (by destination, so it works whether this screen itself was reached from Home
            // or from another Featured push) -- and always with the localized name, fixing
            // Android's own Home-vs-Featured naming inconsistency at the source.
            SectionHeader(
                emoji: section.icon,
                title: sectionTitle(section),
                onSeeAll: { router.push(.featured(categoryId: section.id, categoryName: sectionTitle(section))) },
                seeAllAccessibilityLabel: String(format: String(localized: "home_see_all_category"), sectionTitle(section))
            )
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: GridRules.cardGap(widthClass)) {
                    ForEach(section.items) { item in
                        sectionItemView(item)
                    }
                }
                .padding(.horizontal, Spacing.homeHorizontalMargin(widthClass))
                .padding(.bottom, Spacing.sm)
            }
        }
        .padding(.top, Spacing.lg(widthClass))
    }

    private func sectionTitle(_ section: HomeSection) -> String {
        let lang = locale.language.languageCode?.identifier ?? "en"
        return section.localizedNames?[lang] ?? section.name
    }

    @ViewBuilder
    private func sectionItemView(_ item: ContentItem) -> some View {
        switch item.type {
        case .video:
            MediaCard(item: item, width: cardWidth(for: .video)) { router.push(.player(PlayerArgs(item: item))) }
        case .playlist:
            MediaCard(item: item, width: cardWidth(for: .playlist)) {
                router.push(.playlist(id: item.id, title: item.title, category: item.category, count: item.itemCount))
            }
        case .channel:
            HomeChannelItem(item: item) { router.push(.channel(id: item.id, name: item.title, avatarURL: item.thumbnailURL)) }
        }
    }

    private func cardWidth(for type: ContentType) -> CGFloat {
        guard containerWidth > 0 else { return 0 }
        let visible = GridRules.carouselVisible(type, widthClass)
        return max(0, GridRules.carouselCardWidth(
            container: containerWidth, margin: Spacing.homeHorizontalMargin(widthClass), gap: GridRules.cardGap(widthClass), visible: visible
        ))
    }

    // MARK: - Flat mode (mirrors ContentListView's Videos grid -- flat mode is always mixed types
    // in one column, since there's no per-type grid rule for a heterogeneous list)

    private func flatContent(_ items: [ContentItem], hasMore: Bool) -> some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                flatRow(item)
                    .onAppear {
                        // `>=`, not `==` -- see the same fix in `ContentListView` (gate B1-I2).
                        guard index >= max(0, items.count - 5) else { return }
                        triggerScrollLoadMore(hasMore: hasMore)
                    }
            }
            if isLoadingMore {
                ProgressView()
                    .tint(.brand)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.md(widthClass))
                    .accessibilityLabel(String(localized: "loading_more"))
            }
        }
        .padding(.top, Spacing.sm)
    }

    @ViewBuilder
    private func flatRow(_ item: ContentItem) -> some View {
        switch item.type {
        case .video:
            VideoRow(item: item) { router.push(.player(PlayerArgs(item: item))) }
        case .channel:
            ChannelRow(item: item) { router.push(.channel(id: item.id, name: item.title, avatarURL: item.thumbnailURL)) }
        case .playlist:
            PlaylistRow(item: item) { router.push(.playlist(id: item.id, title: item.title, category: item.category, count: item.itemCount)) }
        }
    }

    // MARK: - Pagination triggers (content-lists.md §7.4: scroll threshold + guarded content-fits
    // autofill, both gated on the ViewModel's silent-failure latch so a broken endpoint can't spin)

    private func triggerScrollLoadMore(hasMore: Bool) {
        guard hasMore, !isLoadingMore, viewModel?.lastLoadFailed == false else { return }
        Task { await runLoadMore() }
    }

    private func triggerAutoFill() {
        guard !isLoadingMore, let viewModel, case .content(_, let hasMore) = viewModel.state else { return }
        guard paginationGuard.shouldAutoLoad(widthClass: widthClass, hasMore: hasMore, paginationError: viewModel.lastLoadFailed,
                                              contentFits: contentFits, itemCount: currentItemCount) else { return }
        Task { await runLoadMore() }
    }

    private var currentItemCount: Int {
        guard let viewModel, case .content(let mode, _) = viewModel.state else { return 0 }
        switch mode {
        case .sections(let sections): return sections.count
        case .flat(let items): return items.count
        }
    }

    private func runLoadMore() async {
        isLoadingMore = true
        await viewModel?.loadMore()
        isLoadingMore = false
    }
}

#Preview {
    NavigationStack {
        FeaturedView(categoryId: nil, categoryName: nil)
    }
    .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack {
        FeaturedView(categoryId: nil, categoryName: nil)
    }
    .environment(\.container, .sharedFake)
    .environment(\.locale, Locale(identifier: "ar"))
    .environment(\.layoutDirection, .rightToLeft)
}
