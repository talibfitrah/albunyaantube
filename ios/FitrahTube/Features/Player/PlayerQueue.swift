import Foundation
import InnerTubeKit

/// The pure playlist-queue machine (Plan B5 Task 1; `PlayerViewModel.kt:1044-1093,1786-1978`).
/// Value type, no I/O: the view model owns paging and stream resolution, this owns the order.
nonisolated struct PlayerQueue: Equatable, Sendable {
    /// Reconciliation note 8: playlist PAGING fires at <=5 remaining (`PlayerViewModel.kt:1786,1911`).
    /// A page is one `browse` call and resolves no streams.
    static let pageThreshold = 5
    /// Reconciliation note 8: STREAM prefetch is the next 2 only (`PlayerViewModel.kt:198`). Five
    /// would spend half of `ExtractionRateLimiter`'s 10-per-60s global budget on speculation.
    static let streamPrefetchCount = 2

    private(set) var items: [ContentItem]
    private(set) var index: Int
    private(set) var cursor: String?
    private(set) var pagingFailed: Bool = false

    var current: ContentItem? { items.indices.contains(index) ? items[index] : nil }
    var upcoming: ArraySlice<ContentItem> { items.dropFirst(index + 1) }
    var hasMorePages: Bool { cursor != nil && !pagingFailed }
    var hasNext: Bool { !upcoming.isEmpty || hasMorePages }
    var needsPage: Bool { upcoming.count <= Self.pageThreshold && hasMorePages }
    var streamPrefetchTargets: [ContentItem] { Array(upcoming.prefix(Self.streamPrefetchCount)) }

    /// `targetVideoId` is authoritative, `startIndex` the fallback hint (`PlaylistDetailFragment.kt:747`).
    /// Shuffle pins the resolved start item first, randomizes the rest and disables paging
    /// (`PlaylistDetailFragment.kt:300-303`).
    static func start(
        items: [ContentItem], targetVideoId: String?, startIndex: Int, shuffled: Bool,
        cursor: String?, using generator: inout some RandomNumberGenerator
    ) -> PlayerQueue {
        let clampedIndex = items.isEmpty ? 0 : min(max(startIndex, 0), items.count - 1)
        let start = targetVideoId.flatMap { id in items.firstIndex { $0.id == id } } ?? clampedIndex
        let cursor = shuffled ? nil : cursor   // shuffle always disables paging, even on an empty page
        guard shuffled, !items.isEmpty else {
            return PlayerQueue(items: items, index: start, cursor: cursor)
        }
        var rest = items
        let pinned = rest.remove(at: start)
        rest.shuffle(using: &generator)
        return PlayerQueue(items: [pinned] + rest, index: 0, cursor: nil)
    }

    static func start(
        items: [ContentItem], targetVideoId: String?, startIndex: Int, shuffled: Bool, cursor: String?
    ) -> PlayerQueue {
        var generator = SystemRandomNumberGenerator()
        return start(items: items, targetVideoId: targetVideoId, startIndex: startIndex,
                     shuffled: shuffled, cursor: cursor, using: &generator)
    }

    /// Next item, or nil at the end (`PlayerViewModel.kt:1920-1923`: playback stops).
    mutating func advance() -> ContentItem? {
        guard index + 1 < items.count else { return nil }
        index += 1
        return current
    }

    /// Up Next tap (`PlayerViewModel.kt:355-387`), index-addressed: a playlist can legitimately
    /// repeat a video id (Cubic P2, same class as 23b3c325), and an id match sent a tap on a later
    /// duplicate back to its first occurrence. A desync (out-of-range index) is a no-op.
    mutating func select(at index: Int) -> ContentItem? {
        guard items.indices.contains(index) else { return nil }
        self.index = index
        return current
    }

    /// One fetched page. The cursor is ignored once `pagingFailed` latched (`PlayerViewModel.kt:1946-1978`).
    mutating func append(_ page: [ContentItem], cursor: String?) {
        items.append(contentsOf: page)
        if !pagingFailed { self.cursor = cursor }
    }

    /// Keeps the continuation token; the latch alone gates `hasMorePages`.
    mutating func markPagingFailed() {
        pagingFailed = true
    }
}

/// `PlayerViewModel.kt:1352-1357`, MAX_CONSECUTIVE_SKIPS = 3 (`:1787`): a named, tested constant,
/// not an inline `< 3`. `consecutive` is the number of dead items already skipped in a row.
nonisolated enum AutoSkipPolicy {
    static func decide(consecutive: Int, limit: Int) -> Bool { consecutive < limit }
}

/// Side B of the Plan C contract: B5 defines it and ships `LivePlaylistQueueSource`; Plan C may
/// provide a different implementation.
protocol PlaylistQueueSource: Sendable {
    /// One page of a playlist, oldest cursor first. `continuation == nil` means "first page".
    /// Returns the page's items and the cursor for the next page, or nil when exhausted.
    func page(playlistId: String, continuation: String?) async throws
        -> (items: [ContentItem], continuation: String?)
}

extension ContentItem {
    /// The views and age are NUMBERS (browse is read in English and parsed, `EnglishCounts`), so
    /// every browse row renders through the one `Format` line Home uses, in the app's language and
    /// digits. `ageText` is always YouTube's text: "Streamed 3 days ago" parses to minutes but keeps its
    /// prefix only through `Format.englishStat`. `category` stays nil -- the real channel name goes to `channelTitle`, never leaked
    /// through `category` (RULINGS #17).
    /// `channelTitle`: a channel's own tabs carry no byline, so the header's name stands in.
    init(video: VideoItem, channelTitle: String? = nil) {
        self.init(id: video.id, type: .video, title: video.title, category: nil, description: nil,
                  thumbnailURL: video.thumbnailURL, durationSeconds: video.durationSeconds,
                  uploadedDaysAgo: video.uploadedDaysAgo, viewCount: video.viewCount, channelTitle: channelTitle ?? video.channelName,
                  subscribers: nil, videoCount: nil, itemCount: nil, uploadedMinutesAgo: video.uploadedMinutesAgo,
                  viewsText: video.viewCount == nil ? video.viewCountText : nil,
                  ageText: video.publishedText)
    }

    /// A channel's Playlists-tab tile; `itemCountText` only when it did not parse ("12 episodes").
    init(tile: PlaylistTile) {
        self.init(id: tile.id, type: .playlist, title: tile.title, category: nil, description: nil,
                  thumbnailURL: tile.thumbnailURL, durationSeconds: nil, uploadedDaysAgo: nil, viewCount: nil,
                  channelTitle: tile.channelName, subscribers: nil, videoCount: nil, itemCount: tile.itemCount,
                  itemCountText: tile.itemCount == nil ? tile.itemCountText : nil)
    }
}

/// The shipped `PlaylistQueueSource` over InnerTubeKit's `BrowseClient.playlistItems`. Untested by
/// design (the rule B1 applies to `LiveStreamResolver`): everything decidable lives in
/// `PlayerQueue`; this is a pass-through whose behaviour is the network client's own.
struct LivePlaylistQueueSource: PlaylistQueueSource {
    let client: BrowseClient

    func page(playlistId: String, continuation: String?) async throws
        -> (items: [ContentItem], continuation: String?) {
        let page = try await client.playlistItems(playlistId, continuation: continuation)
        return (page.items.map { ContentItem(video: $0) }, page.nextContinuation)
    }
}
