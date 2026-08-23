import SwiftUI

/// Android's `SearchFragment` (`search-categories.md:40-149,259-281`). Single entry point: Home's
/// search button (`HomeView.header`, pre-existing). No pagination (§1.5 -- one request, `limit=50`).
struct SearchView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @FocusState private var fieldFocused: Bool

    @State private var viewModel: SearchViewModel?

    var body: some View {
        VStack(spacing: 0) {
            searchField
            stateContent
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "search"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if viewModel == nil {
                viewModel = SearchViewModel(catalog: container.catalog, history: container.searchHistory)
            }
            fieldFocused = true // search-categories.md:41-42 -- auto-focus on appear
            #if DEBUG
            // Acceptance-screenshot hook: `simctl` can land on this screen (`-fitrah-route
            // search`) but can't type into a focused field. `submit()` bypasses the debounce
            // entirely, so this settles into `.results`/`.noResults` deterministically with no
            // arbitrary sleep needed before the screenshot.
            if let seed = Self.debugSeedQuery {
                viewModel?.query = seed
                await viewModel?.submit()
            }
            #endif
        }
    }

    #if DEBUG
    private static var debugSeedQuery: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let flagIndex = args.firstIndex(of: "-fitrah-search-query"), args.indices.contains(flagIndex + 1) else { return nil }
        return args[flagIndex + 1]
    }
    #endif

    // MARK: - Search field (§1.2 -- 500 ms debounce/min 2 chars live in the ViewModel; submit here
    // bypasses both)

    private var queryBinding: Binding<String> {
        Binding(get: { viewModel?.query ?? "" }, set: { viewModel?.query = $0 })
    }

    /// Shared chrome with `ContentListView`'s inline header field (gate wave-2 W9); this one adds
    /// the two things only this screen does: auto-focus on appear and submit-on-Search.
    private var searchField: some View {
        SearchField(text: queryBinding, accessibilityLabel: String(localized: "search_hint"),
                    focus: $fieldFocused, onSubmit: { Task { await viewModel?.submit() } })
    }

    // MARK: - State body (§1.4 five-state visibility matrix)

    @ViewBuilder
    private var stateContent: some View {
        if let viewModel {
            switch viewModel.state {
            case .zero(let history):
                if history.isEmpty {
                    // RULINGS #23: a deliberate iOS addition -- Android shows a blank screen here.
                    EmptyStateView(systemImage: "magnifyingglass", message: String(localized: "search_hint"))
                } else {
                    historyList(history, viewModel: viewModel)
                }
            case .loading:
                loadingView
            case .results(let items):
                resultsList(items)
            case .noResults:
                EmptyStateView(
                    systemImage: "magnifyingglass",
                    title: String(localized: "search_no_results"),
                    message: Format.localizedFormat("search_try_different", locale: locale, viewModel.lastSearchedQuery)
                )
            case .error:
                // RULINGS #23: Android has no retry button on search error; this one does.
                ErrorStateView(title: String(localized: "error_title"), message: String(localized: "search_error_generic")) {
                    Task { await viewModel.retry() }
                }
            }
        }
    }

    // `EmptyStateView`/`ErrorStateView` both self-expand (`.frame(maxWidth:.infinity,
    // maxHeight:.infinity)` in `StateViews.swift`); `loadingView` matches that so every terminal
    // state fills the remaining VStack space the same way, regardless of which container
    // (`List`/`ScrollView`) the non-terminal states use for their own scrolling.
    private var loadingView: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            ProgressView().tint(.brand)
            Text(String(localized: "search_loading"))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textSecondary)
        }
        .padding(Spacing.xl(widthClass))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - History (§1.3)

    private func historyList(_ history: [String], viewModel: SearchViewModel) -> some View {
        List {
            Section {
                ForEach(history, id: \.self) { entry in
                    HStack(spacing: Spacing.sm) {
                        Button { Task { await viewModel.selectHistory(entry) } } label: {
                            HStack(spacing: Spacing.sm) {
                                Image(systemName: "clock").foregroundStyle(Color.textSecondary)
                                Text(entry).foregroundStyle(Color.textPrimary)
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        // `.plain` + an explicit hit area (gate B1-minor-15): in a `List`, an
                        // unstyled button claims the whole row's tap area, so this one could
                        // swallow taps meant for the trailing delete button beside it.
                        .buttonStyle(.plain)
                        .accessibilityLabel(Format.localizedFormat("a11y_search_history", locale: locale, entry))
                        // search-categories.md §1.6: an always-visible trailing delete button
                        // (Android's persistent 48×48 button), in addition to the swipe action below.
                        Button { viewModel.removeHistory(entry) } label: {
                            Image(systemName: "xmark")
                                .foregroundStyle(Color.textSecondary)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(String(localized: "cd_delete_search_history"))
                    }
                    .swipeActions {
                        Button(role: .destructive) { viewModel.removeHistory(entry) } label: {
                            Label(String(localized: "cd_delete_search_history"), systemImage: "trash")
                        }
                    }
                }
            } header: {
                HStack {
                    Text(String(localized: "search_recent"))
                    Spacer(minLength: 0)
                    Button(String(localized: "search_clear_history")) { viewModel.clearHistory() }
                }
            }
        }
        .listStyle(.plain)
    }

    // MARK: - Results (§1.5 -- flat, heterogeneous, server order kept, no grouping, no paging)

    private func resultsList(_ items: [ContentItem]) -> some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(items) { item in resultRow(item) }
            }
        }
    }

    @ViewBuilder
    private func resultRow(_ item: ContentItem) -> some View {
        switch item.type {
        case .video:
            VideoRow(item: item) { router.push(Route(item: item)) }
        case .channel:
            ChannelRow(item: item) { router.push(Route(item: item)) }
        case .playlist:
            PlaylistRow(item: item) { router.push(Route(item: item)) }
        }
    }

}

#if DEBUG
#Preview {
    NavigationStack { SearchView() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { SearchView() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
