import Foundation

/// Android's `HomeViewModel` (`shell-home.md` §B9-B11). One `Task`-typed property per "load kind" --
/// `loadTask` for a full reload (`load`/`refresh`, Android's `loadJob`) and
/// `loadMoreTask` for pagination (Android's `loadMoreJob`) -- so a fresh full reload always
/// supersedes an in-flight load-more (B10 step 1), while load-more's own in-flight guard
/// (`isLoadingMore`, set before its first `await`) is enough on its own: Android never cancels
/// `loadJob` from `loadMoreSections()`, so a full load never cancels an unrelated load-more here.
@MainActor @Observable final class HomeViewModel {
    enum State: Equatable {
        case loading
        case content(sections: [HomeSection], hasMore: Bool, isLoadingMore: Bool)
        case error
        case empty
    }

    private(set) var state: State = .loading

    private let catalog: any CatalogClient
    private let filter: any FilterStore
    private let widthClass: () -> WidthClass

    private var sections: [HomeSection] = []
    private var nextCursor: String?
    private var hasMore = true // optimistic default, mirrors Android (shell-home.md:B9)
    private var isLoadingMore = false
    private var category: String?

    private var loadTask: Task<Void, Never>?
    private var loadMoreTask: Task<Void, Never>?

    private static let categoryLimit = 5

    init(catalog: any CatalogClient, filter: any FilterStore, widthClass: @escaping () -> WidthClass) {
        self.catalog = catalog
        self.filter = filter
        self.widthClass = widthClass
        // RULING 10 (double initial fetch): FilterStore is synchronously readable (UserDefaults,
        // not Android's async DataStore Flow), so the persisted category is captured once here --
        // the first load() issues exactly one request instead of Android's unfiltered-then-filtered pair.
        category = filter.state.categoryId
    }

    func load() async { await performFullLoad(showLoading: true) }

    /// RULINGS #12: never shows `.loading` -- the caller (`.refreshable`) holds its own spinner
    /// while the existing content stays on screen, swapped only once the new page arrives.
    func refresh() async { await performFullLoad(showLoading: false) }

    func loadMore() async {
        guard hasMore, !isLoadingMore else { return }
        isLoadingMore = true // set before the first `await` -- the in-flight guard (shell-home.md:B10)
        if case .content(let currentSections, let currentHasMore, _) = state {
            state = .content(sections: currentSections, hasMore: currentHasMore, isLoadingMore: true)
        }
        let task = Task { await self.fetchMore() }
        loadMoreTask = task
        await task.value
    }

    /// shell-home.md:207: VoiceOver label for a section's See-all control -- "See all content in
    /// {displayName}" (`home_see_all_category`), built from the same untruncated display name as
    /// the section title itself (`localizedNames[lang] ?? name`).
    func seeAllLabel(for section: HomeSection) -> String {
        let lang = Locale.current.language.languageCode?.identifier ?? "en"
        let displayName = section.localizedNames?[lang] ?? section.name
        return String(format: String(localized: "home_see_all_category"), displayName)
    }

    /// RULINGS #17: `channelName` prefers the video's real `channelTitle`, falling back to
    /// `category` only when it's nil (Android always used `category`, a mapping bug).
    func playerArgs(for item: ContentItem) -> PlayerArgs {
        PlayerArgs(
            videoId: item.id,
            title: item.title,
            channelName: item.channelTitle ?? item.category,
            thumbnailURL: item.thumbnailURL,
            description: item.description,
            durationSeconds: item.durationSeconds,
            viewCount: item.viewCount
        )
    }

    private func performFullLoad(showLoading: Bool) async {
        loadTask?.cancel()
        loadMoreTask?.cancel()
        let task = Task { await self.fetchFirstPage(showLoading: showLoading) }
        loadTask = task
        await task.value
    }

    private func fetchFirstPage(showLoading: Bool) async {
        if showLoading { state = .loading }
        do {
            let page = try await catalog.home(cursor: nil, categoryLimit: Self.categoryLimit,
                                               contentLimit: contentLimit(), category: category)
            guard !Task.isCancelled else { return }
            sections = page.items
            nextCursor = page.nextCursor
            hasMore = page.hasMore // CursorPage.hasMore == (nextCursor != nil)
            isLoadingMore = false
            state = sections.isEmpty ? .empty : .content(sections: sections, hasMore: hasMore, isLoadingMore: false)
        } catch {
            guard !Task.isCancelled else { return }
            state = .error
        }
    }

    private func fetchMore() async {
        do {
            let page = try await catalog.home(cursor: nextCursor, categoryLimit: Self.categoryLimit,
                                               contentLimit: contentLimit(), category: category)
            guard !Task.isCancelled else { return }
            let existingIDs = Set(sections.map(\.id))
            sections.append(contentsOf: page.items.filter { !existingIDs.contains($0.id) }) // dedupe by section id
            nextCursor = page.nextCursor
            hasMore = page.hasMore
            isLoadingMore = false
            state = .content(sections: sections, hasMore: hasMore, isLoadingMore: false)
        } catch {
            // RULINGS #13: pagination failures on Home are silent -- no error state, the footer
            // spinner just disappears and the user can scroll again to retry.
            guard !Task.isCancelled else { return }
            isLoadingMore = false
            state = .content(sections: sections, hasMore: hasMore, isLoadingMore: false)
        }
    }

    private func contentLimit() -> Int {
        widthClass() == .compact ? 10 : 20
    }
}
