import SwiftUI

/// Android's `HomeFragment` (`shell-home.md` §B1-B15). Header + category pill sit outside the
/// scrollable region (always visible, B11); skeleton/error/empty/sections swap inside a single
/// `ScrollView` so `.refreshable` keeps working in every state (RULINGS #12).
struct HomeView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass

    @State private var viewModel: HomeViewModel?
    @State private var containerWidth: CGFloat = 0
    @State private var contentFits = false
    @State private var bannerMessage: BannerMessage?
    /// Home had no `PaginationGuard` at all, which was survivable only because its two geometry
    /// triggers were edge-triggered Bools that (accidentally) never re-fired. Now that
    /// `onContentFits` reports every layout delta, the autofill path needs the same six guards the
    /// other two list screens use -- in particular guard 5's progress invariant, which is what
    /// stops a failing endpoint from being retried on every footer-spinner-driven relayout.
    @State private var paginationGuard = PaginationGuard()

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
                .refreshable {
                    paginationGuard = PaginationGuard()
                    await viewModel?.refresh()
                }
                .onContentFits { fits in
                    contentFits = fits
                    triggerAutoFill()
                }
                .onScrollGeometryChange(for: Bool.self) { geometry in
                    let distanceFromEnd = geometry.contentSize.height - (geometry.containerSize.height + geometry.contentOffset.y)
                    return distanceFromEnd < 200 // shell-home.md:B13 -- device-independent version of Android's 300px threshold
                } action: { _, isNearEnd in
                    guard isNearEnd else { return }
                    triggerScrollLoadMore()
                }
                .onChange(of: router.scrollToTopSignal) { _, signal in
                    guard signal?.tab == .home else { return }
                    withAnimation { proxy.scrollTo(Self.topAnchor, anchor: .top) }
                }
            }
        }
        .background(Color.homeSurface.ignoresSafeArea())
        .transientBanner($bannerMessage)
        // Gate wave-2 W4: a reload failure with sections already on screen keeps them (see
        // `HomeViewModel.fetchFirstPage`) and says so here instead of blanking Home for a
        // full-page error -- the same policy the lists follow (`content-lists.md:277-280`), driven
        // off the same event channel (`errorToken`) so a second consecutive failure still fires.
        // With nothing on screen the state is `.error`, which carries its own retry: no banner.
        .onChange(of: viewModel?.errorToken) { _, _ in
            guard let viewModel, case .content = viewModel.state else { return }
            bannerMessage = BannerMessage(text: String(localized: "list_error_title"),
                                          actionTitle: String(localized: "retry"),
                                          action: { Task { await viewModel.load() } })
        }
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
            paginationGuard = PaginationGuard()
            viewModel = HomeViewModel(catalog: container.catalog, filter: container.filters, widthClass: { widthClass })
            Task { await viewModel?.load() }
        }
        // Re-arm autofill after every completed load, the way Android re-runs its guards from the
        // `submitList` completion callback (gate B1-C1).
        .onChange(of: viewModel?.state) { _, _ in triggerAutoFill() }
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
            isActive: hasActiveFilter,
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
                        // Shared with `FeaturedView` (gate wave-2 W9). Home pushes the section's
                        // raw `name`; Featured pushes the localized one -- the one real difference
                        // between the two call sites, hence the closure.
                        HomeSectionRow(section: section, containerWidth: containerWidth) {
                            router.push(.featured(categoryId: section.id, categoryName: section.name))
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
        }
    }

    /// Same single predicate `ContentListView` uses (gate B1-minor-2): a legacy empty-string
    /// category id is not an active filter, even though it is non-nil.
    private var hasActiveFilter: Bool { !(container.filters.state.categoryId ?? "").isEmpty }

    // MARK: - Pagination triggers (shell-home.md:B11 auto-load, B13 scroll threshold)

    private func triggerScrollLoadMore() {
        guard let viewModel, case .content(_, let hasMore, let isLoadingMore) = viewModel.state,
              hasMore, !isLoadingMore else { return }
        Task { await viewModel.loadMore() }
    }

    /// Gate B1-C1 scenario 4: on a large iPad, Home's first page fits the viewport, so
    /// `distanceFromEnd` is negative from first layout and its near-end Bool is `true` before the
    /// user does anything -- it never *transitions*, so the scroll trigger above never fired, and
    /// neither did the old edge-triggered `onContentFits`. Home simply never paginated there.
    private func triggerAutoFill() {
        guard let viewModel, case .content(let sections, let hasMore, let isLoadingMore) = viewModel.state,
              !isLoadingMore else { return }
        // Home's pagination failures are silent by contract (RULINGS #13), so there is no
        // `paginationError` for guard 3 to read; guard 5's `itemCount > lastCount` is what refuses
        // a retry after a failure left the section count unchanged.
        //
        // The attempt is committed only if the ViewModel actually starts the fetch (gate wave-2
        // W2): a load-more refused during a pull-to-refresh used to spend an attempt and advance
        // `lastCount`, latching autofill off for good once the refresh landed with the same count.
        // Rejections that reset the guard are committed too -- see `ContentListView` (wave-3 D2).
        var attempt = paginationGuard
        guard attempt.shouldAutoLoad(widthClass: widthClass, hasMore: hasMore, paginationError: false,
                                      contentFits: contentFits, itemCount: sections.count) else {
            paginationGuard = attempt
            return
        }
        Task { if await viewModel.loadMore() { paginationGuard = attempt } }
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
