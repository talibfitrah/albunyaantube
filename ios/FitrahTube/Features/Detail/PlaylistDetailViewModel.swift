import Foundation
import InnerTubeKit
import Observation

/// The playlist hero's three fields. Filled from `Route.playlist(id:title:category:count:)` on every
/// list tap (the metadata fast path); a deep link carries none, so `load()` falls back to the
/// backend's `getPublicPlaylist` and to the first page's own item count (plan Task 4 reconciliation).
nonisolated struct PlaylistHeader: Equatable, Sendable {
    var title: String? = nil
    var thumbnailURL: URL? = nil
    var count: Int? = nil
}

/// Android's `PlaylistDetailViewModel.kt` over `BrowseSource.playlistItems` -- one `TabState`, the
/// in-header search over loaded items, the Save toggle, and the B5 launch contract's three call
/// sites (`playAllArgs(shuffled:)` ×2, `playerArgs(forRowAt:)`).
@MainActor @Observable final class PlaylistDetailViewModel {
    nonisolated struct Row: Identifiable, Sendable {
        let index: Int
        let item: VideoItem
        var id: String { item.id }
        /// One-based place in the playlist, not in the page (`NewPipePlaylistDetailRepository.kt:175-177,211`).
        var position: Int { index + 1 }
    }

    let playlistId: String
    let category: String?
    private(set) var header: PlaylistHeader
    private(set) var items: TabState<VideoItem> = .idle
    var query = ""
    private(set) var isSaved = false
    /// RULING 14/15: the gate answered 410. Terminal -- no Retry, the catalog pulled this playlist.
    private(set) var isUnavailable = false
    /// CF-B5-1: Play All / Shuffle emit from this even while `items` is mid-reload; it is the last
    /// first item this screen ever saw, which is a real, playable id (rule 2).
    private(set) var firstKnownItem: VideoItem?

    private let browse: any BrowseSource
    private let saved: any SavedPlaylistsStore
    private let fetchHeader: (@Sendable (String) async throws -> PlaylistHeader)?
    private var generation = 0

    init(playlistId: String, title: String?, category: String?, count: Int?,
         browse: any BrowseSource, saved: any SavedPlaylistsStore,
         fetchHeader: (@Sendable (String) async throws -> PlaylistHeader)?) {
        self.playlistId = playlistId
        self.category = category
        self.header = PlaylistHeader(title: title, count: count)
        self.browse = browse
        self.saved = saved
        self.fetchHeader = fetchHeader
        self.isSaved = saved.isSaved(playlistId)
    }

    // MARK: - Derived

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// What the list renders: the loaded state, or its search-filtered view (no continuation while
    /// filtering, so neither trigger fires mid-search -- `PlaylistDetailFragment.kt:326-346`).
    /// Derived from `rows` -- the one filter pass -- so a zero-match search is
    /// `.empty("search_no_results")` (RULING 5) and `.loaded` never carries `[]` (CF-C-7).
    var visible: TabState<VideoItem> {
        guard !trimmedQuery.isEmpty, !items.items.isEmpty else { return items }
        let hits = rows.map(\.item)
        guard !hits.isEmpty else { return .empty(messageKey: "search_no_results") }
        return .loaded(items: hits, continuation: nil, isAppending: false, showsLoadMore: false)
    }

    var rows: [Row] {
        let trimmed = trimmedQuery
        return items.items.enumerated().compactMap { index, item in
            trimmed.isEmpty || SearchFilter.matches(item, trimmed) ? Row(index: index, item: item) : nil
        }
    }

    var canRetry: Bool { !isUnavailable }

    /// RULING 49: count only -- `totalDurationSeconds` is always nil upstream, so the "• duration"
    /// variant could never render truthfully. Nil until a count is known (deep link, first page pending).
    func metadataLine(locale: Locale) -> String? {
        header.count.map { Format.localizedFormat("playlist_metadata_format", locale: locale, Int64($0)) }
    }

    /// RULING 48 by way of reconciliation note 4: `viewCountText` is YouTube's own localized text and
    /// already carries the unit, so it renders verbatim -- never through `video_views_format`.
    nonisolated static func rowSubtitle(_ item: VideoItem) -> String? {
        let parts = [item.viewCountText, item.publishedText].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " • ")
    }

    // MARK: - Loading

    func load() async {
        generation += 1
        let g = generation
        isUnavailable = false
        items = .loadingInitial
        // Deep link: header and first page in parallel (`ChannelDetailViewModel.kt:109-124`'s reason).
        let headerFetch = header.title == nil ? fetchHeader : nil
        async let fetched = Self.fetchIfNeeded(headerFetch, playlistId)
        let result: Result<BrowsePage<VideoItem>, any Error>
        do { result = .success(try await browse.playlistItems(playlistId, continuation: nil)) } catch { result = .failure(error) }
        if let fetched = await fetched, g == generation { header = fetched }
        do {
            let page = try result.get()
            guard g == generation else { return }
            if page.items.isEmpty {
                items = .empty(messageKey: "playlist_empty_state")
            } else {
                items = .loaded(items: page.items, continuation: page.nextContinuation, isAppending: false, showsLoadMore: false)
                firstKnownItem = page.items.first
            }
            if header.count == nil { header.count = page.items.count }
        } catch {
            guard g == generation else { return }
            items = .errorInitial(messageKey: Self.errorKey(error))
            isUnavailable = Self.isUnavailable(error)
        }
    }

    private nonisolated static func fetchIfNeeded(_ fetch: (@Sendable (String) async throws -> PlaylistHeader)?, _ id: String) async -> PlaylistHeader? {
        guard let fetch else { return nil }
        return try? await fetch(id)
    }

    /// Next page from the surviving cursor. Returns whether a fetch actually started (the
    /// `PaginationGuard` commit contract `ContentListView` uses).
    func loadMore() async -> Bool {
        guard let continuation = visible.continuation, !items.isAppending else { return false }
        let current = items.items
        items = .loaded(items: current, continuation: continuation, isAppending: true, showsLoadMore: false)
        generation += 1
        let g = generation
        do {
            let page = try await browse.playlistItems(playlistId, continuation: continuation)
            guard g == generation else { return true }
            items = .loaded(items: current + page.items, continuation: page.nextContinuation, isAppending: false, showsLoadMore: false)
        } catch {
            guard g == generation else { return true }
            items = .errorAppend(messageKey: "load_more_error", items: current, continuation: continuation, showsLoadMore: false)
        }
        return true
    }

    // MARK: - B5 launch contract (CF-B5-1)

    /// Play All / Shuffle. Emission is unconditional on the list state; nil only when no item was
    /// ever known, which is the empty state, not a player launch.
    func playAllArgs(shuffled: Bool) -> PlayerArgs? {
        firstKnownItem.map { args(for: $0, startIndex: 0, shuffled: shuffled, target: nil) }
    }

    /// Row tap: `targetVideoId` authoritative, `startIndex` a hint (`PlaylistDetailFragment.kt:747`).
    /// Takes the row itself -- it already carries the item, so nothing indexes into a list that a
    /// reload may have shrunk under the tap.
    func playerArgs(for row: Row) -> PlayerArgs {
        args(for: row.item, startIndex: row.index, shuffled: false, target: row.item.id)
    }

    private func args(for item: VideoItem, startIndex: Int, shuffled: Bool, target: String?) -> PlayerArgs {
        var args = PlayerArgs(videoId: item.id, playlistId: playlistId, title: item.title, channelName: item.channelName,
                              thumbnailURL: item.thumbnailURL, durationSeconds: item.durationSeconds, channelId: item.channelId)
        args.startIndex = startIndex
        args.shuffled = shuffled
        args.targetVideoId = target
        return args
    }

    // MARK: - Save

    /// Optimistic flip, reverted on throw; the store validates the id (`FavoriteToggle.perform` pattern).
    func toggleSaved() throws {
        let was = isSaved
        isSaved.toggle()
        do {
            try saved.toggle(id: playlistId, title: header.title, thumbnailURL: header.thumbnailURL, itemCount: header.count)
            isSaved = saved.isSaved(playlistId)
        } catch {
            isSaved = was
            throw error
        }
    }

    // MARK: - Error mapping (never `localizedDescription` -- catalog keys only)

    private static func isUnavailable(_ error: any Error) -> Bool {
        if case BrowseSourceError.unavailable = error { return true }
        return false
    }

    private static func errorKey(_ error: any Error) -> String {
        isUnavailable(error) ? "content_unavailable_message" : "channel_tab_error_generic"
    }
}
