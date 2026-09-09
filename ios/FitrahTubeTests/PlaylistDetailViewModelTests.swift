import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftData
import Testing
@testable import FitrahTube

/// Plan C Task 4: the playlist screen's view model over a fake `BrowseSource`, plus the B5 launch
/// contract's three call sites (`2026-08-27-ios-phase2b5-fullscreen-queue.md` Task 1, CF-B5-1).
@Suite(.perTest)
struct PlaylistDetailViewModelTests {

    // MARK: - Doubles

    /// Canned pages of `perPage` items; `failWith` makes the next `playlistItems` throw; `gate`
    /// holds the next call open until released (for in-flight assertions).
    private final class FakeSource: BrowseSource, @unchecked Sendable {
        var pages = 2
        var perPage = 5
        var failWith: (any Error)?
        var gate: Gate?
        /// When set, every item carries this id -- a playlist that lists the same video repeatedly.
        var repeatedId: String?
        private(set) var calls: [String?] = []

        final class Gate: @unchecked Sendable {
            private var resume: CheckedContinuation<Void, Never>?
            func wait() async { await withCheckedContinuation { resume = $0 } }
            func release() { resume?.resume(); resume = nil }
        }

        func isDegraded() async -> Bool { false }
        func channelHeader(_ id: String) async throws -> ChannelHeader { ChannelHeader(id: id, name: "x") }
        func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem> { BrowsePage(items: [], nextContinuation: nil) }
        func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem> { BrowsePage(items: [], nextContinuation: nil) }
        func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile> { BrowsePage(items: [], nextContinuation: nil) }

        func playlistItems(_ playlistId: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
            calls.append(continuation)
            if let gate { self.gate = nil; await gate.wait() }
            if let failWith { self.failWith = nil; throw failWith }
            let n = continuation.flatMap { Int($0) } ?? 0
            let items = (0..<perPage).map { i in
                VideoItem(id: repeatedId ?? "\(n)-\(i)", title: "Item \(n * perPage + i)", durationSeconds: 60, viewCountText: "1.2K views")
            }
            return BrowsePage(items: items, nextContinuation: n + 1 < pages ? "\(n + 1)" : nil)
        }
    }

    @Observable final class FakeSaved: SavedPlaylistsStore {
        var ids: Set<String> = []
        /// Task 9: `UserScoped` is now a protocol requirement; this fake is never re-scoped.
        var currentUserId: String = ""
        var throwOnToggle = false
        struct Boom: Error {}
        var items: [SavedPlaylist] { [] }
        func isSaved(_ playlistId: String) -> Bool { ids.contains(playlistId) }
        func toggle(id: String, title: String?, thumbnailURL: URL?, itemCount: Int?) throws {
            try SwiftDataSavedPlaylistsStore.validate(id)
            if throwOnToggle { throw Boom() }
            if !ids.insert(id).inserted { ids.remove(id) }
        }
        /// Task 28: this fake keeps no tombstones, so "any state" is the same set.
        func containsAny(_ playlistId: String) -> Bool { ids.contains(playlistId) }
        func importPlaylist(id: String, title: String, thumbnailUrl: String?, uploaderName: String?,
                            approvalStatus: String, at: Date) throws {
            try SwiftDataSavedPlaylistsStore.validate(id)
            ids.insert(id)
        }
    }

    private func makeVM(source: FakeSource = FakeSource(), saved: FakeSaved = FakeSaved(), title: String? = "Lectures",
                        count: Int? = 10, id: String = "PL1",
                        fetchHeader: (@Sendable (String) async throws -> PlaylistHeader)? = nil) -> PlaylistDetailViewModel {
        PlaylistDetailViewModel(playlistId: id, title: title, category: "Aqeedah", count: count,
                                browse: source, saved: saved, fetchHeader: fetchHeader)
    }

    // MARK: - Deep-linked header count (Cubic #19)

    @Test func aDeepLinkDoesNotAdoptAPartialFirstPageAsTheHeaderCount() async {
        // Cubic #19: the fallback adopted `page.items.count` even when a continuation remained, so
        // a deep-linked multi-page playlist's hero claimed "5 videos" for a 10-video playlist. No
        // count beats a wrong one; the fake's page 1 carries a continuation.
        let vm = makeVM(title: nil, count: nil)
        await vm.load()
        #expect(vm.header.count == nil)
    }

    @Test func aDeepLinkAdoptsTheFirstPageCountWhenItIsTheWholePlaylist() async {
        let source = FakeSource()
        source.pages = 1
        let vm = makeVM(source: source, title: nil, count: nil)
        await vm.load()
        #expect(vm.header.count == 5)
    }

    // MARK: - Gate + errors

    @Test func aBlockedPlaylistIsTerminalWithNoRetry() async {
        // RULING 14/15 + PlaylistDetailViewModel.kt:120-124. A 410 is the backend saying the catalog
        // pulled this; retrying cannot change it, and a Retry button that reloads the same 410 is worse
        // than no button.
        let source = FakeSource()
        source.failWith = BrowseSourceError.unavailable
        let vm = makeVM(source: source)
        await vm.load()
        #expect(vm.items == .errorInitial(messageKey: "content_unavailable_message"))
        #expect(vm.isUnavailable)
        #expect(vm.canRetry == false)
    }

    @Test func aTransportFailureOnTheGateFailsOpenAndStillLoads() async {
        // :112-119 -- through the REAL LiveBrowseSource + BackendAvailabilityGate: the HEAD probe
        // throws, the browse POST answers the fixture, the screen loads.
        let transport = RoutingTransport(head: .throwing)
        let vm = PlaylistDetailViewModel(playlistId: "PL1", title: nil, category: nil, count: nil,
                                         browse: Self.liveSource(transport), saved: FakeSaved(), fetchHeader: nil)
        await vm.load()
        #expect(vm.items.items.count > 0)
        #expect(vm.isUnavailable == false)
    }

    @Test func aGate410ThroughTheLiveSourceIsUnavailable() async {
        let transport = RoutingTransport(head: .status(410))
        let vm = PlaylistDetailViewModel(playlistId: "PL1", title: nil, category: nil, count: nil,
                                         browse: Self.liveSource(transport), saved: FakeSaved(), fetchHeader: nil)
        await vm.load()
        #expect(vm.items == .errorInitial(messageKey: "content_unavailable_message"))
        #expect(transport.browseCalls == 0)
    }

    @Test func aBotCheckIsAGenericErrorWithRetryNeverTheRawDescription() async {
        // CF-C-7 / Task 2 ledger: playlistItems has no degraded substitute -> error + Retry.
        let source = FakeSource()
        source.failWith = BrowseError.botCheck
        let vm = makeVM(source: source)
        await vm.load()
        #expect(vm.items == .errorInitial(messageKey: "channel_tab_error_generic"))
        #expect(vm.canRetry)
        await vm.load()
        #expect(vm.items.items.count == 5)
    }

    @Test func anEmptyFirstPageIsTheEmptyStateNotLoadedEmpty() async {
        // CF-C-7: never `.loaded(items: [])`.
        let source = FakeSource()
        source.perPage = 0
        source.pages = 1
        let vm = makeVM(source: source)
        await vm.load()
        #expect(vm.items == .empty(messageKey: "playlist_empty_state"))
        #expect(vm.playAllArgs(shuffled: false) == nil)
    }

    // MARK: - Pagination

    @Test func positionsAreOneBasedAndContinueAcrossPages() async {
        // NewPipePlaylistDetailRepository.kt:175-177,211 -- nextItemOffset = itemOffset + items.size.
        // Page 2's first row is 6, not 1: the number is the item's place in the playlist, not in the page.
        let vm = makeVM()
        await vm.load()
        #expect(vm.rows.first?.position == 1)
        #expect(await vm.loadMore())
        #expect(vm.rows.count == 10)
        #expect(vm.rows[5].position == 6)
        #expect(vm.rows[5].item.id == "1-0")
    }

    @Test func rowsStayDistinctWhenAPlaylistRepeatsAVideo() async {
        // Cubic P2: a YouTube playlist may list the same video twice. Keying the SwiftUI list on the
        // video id would collapse those rows; `Row.id` is the row's position, and only the tap
        // payload keeps the video id.
        let source = FakeSource()
        source.repeatedId = "xc7keR2piUM"
        let vm = makeVM(source: source)
        await vm.load()
        #expect(vm.rows.count == 5)
        #expect(Set(vm.rows.map(\.id)).count == 5)
        #expect(vm.playerArgs(for: vm.rows[3]).videoId == "xc7keR2piUM")
    }

    @Test func appendingShowsTheFooterSpinner() async {
        // RULING 47, fixing defect 28: Android sets isAppending and renders nothing. `ListFooter`
        // draws the spinner from `isAppending`, so the state must carry it while the append is in flight.
        let source = FakeSource()
        let vm = makeVM(source: source)
        await vm.load()
        let gate = FakeSource.Gate()
        source.gate = gate
        let task = Task { await vm.loadMore() }
        while !vm.items.isAppending { await Task.yield() }
        #expect(vm.visible.isAppending)
        gate.release()
        #expect(await task.value)
        #expect(vm.items.isAppending == false)
        #expect(vm.items.items.count == 10)
    }

    @Test func anAppendFailureKeepsTheItemsAndTheCursor() async {
        let source = FakeSource()
        let vm = makeVM(source: source)
        await vm.load()
        source.failWith = URLError(.timedOut)
        #expect(await vm.loadMore())
        #expect(vm.items == .errorAppend(messageKey: "load_more_error", items: vm.items.items, continuation: "1", showsLoadMore: false))
        #expect(await vm.loadMore())
        #expect(vm.items.items.count == 10)
    }

    @Test func loadMoreRefusesWithoutACursorOrWhileAppending() async {
        let source = FakeSource()
        source.pages = 1
        let vm = makeVM(source: source)
        await vm.load()
        #expect(await vm.loadMore() == false)
        #expect(source.calls.count == 1)
    }

    // MARK: - B5 launch contract (three call sites, four rules)

    @Test func playAllEmitsEvenWhenItemsHaveNotLoaded() async {
        // CF-B5-1, verbatim: "Plan C must NOT wait for its own items to load before navigating".
        // The first known item survives a reload: while the list is back in `.loadingInitial`,
        // Play All still emits with that id (a real, playable one -- rule 2).
        let source = FakeSource()
        let vm = makeVM(source: source)
        await vm.load()
        let gate = FakeSource.Gate()
        source.gate = gate
        let reload = Task { await vm.load() }
        while vm.items != .loadingInitial { await Task.yield() }
        let args = vm.playAllArgs(shuffled: false)
        #expect(args?.videoId == "0-0")
        #expect(args?.playlistId == "PL1")
        #expect(args?.startIndex == 0)
        #expect(args?.shuffled == false)
        #expect(args?.targetVideoId == nil)
        gate.release()
        await reload.value
    }

    @Test func aRowTapPassesTargetVideoIdAndTheIndexAsAHint() async {
        let vm = makeVM()
        await vm.load()
        let args = vm.playerArgs(for: vm.rows[3])
        #expect(args.targetVideoId == "0-3")     // authoritative
        #expect(args.videoId == "0-3")
        #expect(args.startIndex == 3)            // hint only
        #expect(args.shuffled == false)
        #expect(args.playlistId == "PL1")
        #expect(args.title == "Item 3")
    }

    @Test func shuffleSetsTheFlagAndNoTargetVideo() async {
        // PlaylistDetailViewModel.kt:413-417
        let vm = makeVM()
        await vm.load()
        let args = vm.playAllArgs(shuffled: true)
        #expect(args?.shuffled == true)
        #expect(args?.targetVideoId == nil)
        #expect(args?.startIndex == 0)
        #expect(args?.videoId == "0-0")
    }

    // MARK: - Search

    @Test func searchFiltersLoadedItemsAndSuppressesPagination() async {
        // PlaylistDetailFragment.kt:326-346 -- and RULING 46's distinct copy for zero matches, which
        // Android does not have (its empty_state is never configured at all, :427-433).
        let vm = makeVM()
        await vm.load()
        vm.query = "zzz"
        #expect(vm.visible.emptyMessageKey == "search_no_results")
        #expect(vm.rows.isEmpty)
        vm.query = "Item 3"
        #expect(vm.rows.map(\.position) == [4])
        #expect(vm.visible.continuation == nil)
        #expect(await vm.loadMore() == false)
        vm.query = ""
        #expect(vm.visible.continuation == "1")
        let source = FakeSource()
        source.perPage = 0
        source.pages = 1
        let empty = makeVM(source: source)
        await empty.load()
        #expect(empty.visible.emptyMessageKey == "playlist_empty_state")
    }

    // MARK: - Header + copy

    @Test func theMetadataLineIsCountOnly() async {
        // RULING 49 -- totalDurationSeconds is always nil upstream, so the "%1$d videos • %2$s"
        // variant can never render truthfully and playlist_metadata_duration_format stays an orphan.
        let vm = makeVM(count: 12)
        let en = Locale(identifier: "en")
        #expect(vm.metadataLine(locale: en) == "12 videos")
        #expect(vm.metadataLine(locale: en)?.contains("•") == false)
        let deepLink = makeVM(title: nil, count: nil)
        #expect(deepLink.metadataLine(locale: en) == nil)
        await deepLink.load()
        // Cubic #19: the fake's first page carries a continuation, so its count is PARTIAL -- the
        // line stays absent rather than claiming "5 videos" for a 10-video playlist. See
        // `aDeepLinkAdoptsTheFirstPageCountWhenItIsTheWholePlaylist` for the adopting side.
        #expect(deepLink.metadataLine(locale: en) == nil)
    }

    @Test func aDeepLinkFetchesTheHeaderAndTheFirstPageInParallel() async {
        // Task 4 review: the two were serial. Hold the header fetch open; the first page must land
        // (and the list must be populated) before the header is released.
        let gate = FakeSource.Gate()
        let source = FakeSource()
        let vm = makeVM(source: source, title: nil, count: nil) { id in
            await gate.wait()
            return PlaylistHeader(title: "From \(id)", count: 40)
        }
        let load = Task { await vm.load() }
        while source.calls.isEmpty { await Task.yield() }
        for _ in 0..<50 where vm.items.items.isEmpty { await Task.yield() }
        #expect(vm.header.title == nil)
        gate.release()
        await load.value
        #expect(vm.header.title == "From PL1")
        #expect(vm.items.items.count == 5)
    }

    @Test func aDeepLinkFillsTheHeaderFromTheBackend() async {
        let vm = makeVM(title: nil, count: nil) { id in
            PlaylistHeader(title: "From \(id)", thumbnailURL: URL(string: "https://x/y.jpg"), count: 40)
        }
        #expect(vm.header.title == nil)
        await vm.load()
        #expect(vm.header.title == "From PL1")
        #expect(vm.header.count == 40)
        let fast = makeVM(title: "Known", count: 3) { _ in Issue.record("must not fetch on the fast path"); return PlaylistHeader() }
        await fast.load()
        #expect(fast.header.title == "Known")
    }

    @Test func viewCountsUseThePluralNotThePlainString() {
        // RULING 48 -- PlaylistVideosAdapter.kt:71 uses video_views_format ("%s views"); a VideoItem
        // carries YouTube's own localized text, which already contains the unit, so the row renders it
        // verbatim (reconciliation note 4's rule) and never wraps it in a second "views".
        let item = VideoItem(id: "a", title: "t", viewCountText: "1.2K views", publishedText: "2 years ago")
        #expect(PlaylistDetailViewModel.rowSubtitle(item) == "1.2K views • 2 years ago")
        #expect(PlaylistDetailViewModel.rowSubtitle(VideoItem(id: "a", title: "t")) == nil)
        #expect(PlaylistDetailViewModel.rowSubtitle(item)?.contains("views views") == false)
    }

    // MARK: - Save

    @Test func savingRefusesAMalformedPlaylistId() async {
        // PlaylistDetailFragment.kt:787 ^[A-Za-z0-9_-]{3,128}$
        let saved = FakeSaved()
        let vm = makeVM(saved: saved, id: "PL bad")
        #expect(throws: SavedPlaylistsError.invalidPlaylistId) { try vm.toggleSaved() }
        #expect(vm.isSaved == false)
        #expect(saved.ids.isEmpty)
        #expect(SwiftDataSavedPlaylistsStore.isValid("PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"))
        #expect(SwiftDataSavedPlaylistsStore.isValid("ab") == false)
        #expect(SwiftDataSavedPlaylistsStore.isValid(String(repeating: "a", count: 129)) == false)
    }

    @Test func saveIsOptimisticAndRevertsOnThrow() async {
        let saved = FakeSaved()
        let vm = makeVM(saved: saved)
        #expect(vm.isSaved == false)
        try? vm.toggleSaved()
        #expect(vm.isSaved)
        #expect(saved.ids == ["PL1"])
        saved.throwOnToggle = true
        #expect(throws: FakeSaved.Boom.self) { try vm.toggleSaved() }
        #expect(vm.isSaved)
    }

    @Test func theSwiftDataStoreTombstonesAndResurrects() throws {
        let container = try ModelContainer(for: SavedPlaylist.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let store = SwiftDataSavedPlaylistsStore(modelContainer: container)
        try store.toggle(id: "PL1", title: "A", thumbnailURL: nil, itemCount: 3)
        #expect(store.isSaved("PL1"))
        #expect(store.items.map(\.playlistId) == ["PL1"])
        try store.toggle(id: "PL1", title: "A", thumbnailURL: nil, itemCount: 3)
        #expect(store.isSaved("PL1") == false)
        #expect(store.items.isEmpty)
        try store.toggle(id: "PL1", title: "B", thumbnailURL: nil, itemCount: 4)
        #expect(store.items.first?.title == "B")
        #expect(store.items.first?.dirty == true)
        #expect(throws: SavedPlaylistsError.invalidPlaylistId) {
            try store.toggle(id: "no spaces", title: nil, thumbnailURL: nil, itemCount: nil)
        }
    }

    @Test func aV1StoreOnDiskMigratesToV2KeepingFavorites() throws {
        // FavoriteVideo.swift's promised lightweight stage: a store written by V1 (favorites only)
        // opens under V2 with its rows intact and accepts a SavedPlaylist.
        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
        defer { for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) } }
        do {
            let v1 = Schema(versionedSchema: FavoritesSchemaV1.self)
            let container = try ModelContainer(for: v1, configurations: ModelConfiguration(schema: v1, url: url))
            let context = ModelContext(container)
            // Named through V1 deliberately (fix round 1 / C1): `FavoriteVideo`'s shape has not
            // changed since `2c611683`, so V2-V5 alias it -- but the seed says which one it means.
            context.insert(FavoritesSchemaV1.FavoriteVideo(videoId: "v1", title: "T", channelName: "C", thumbnailUrl: nil, durationSeconds: 1))
            try context.save()
        }
        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url)
        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<FavoriteVideo>()) == 1)
        let store = SwiftDataSavedPlaylistsStore(modelContainer: container)
        try store.toggle(id: "PL1", title: "A", thumbnailURL: nil, itemCount: 1)
        #expect(store.isSaved("PL1"))
    }

    // MARK: - Index push

    @Test func eachLoadedPagePushesToTheStreamIndex() async {
        // RULING 25 + NewPipePlaylistDetailRepository.kt:202-208: the push rides inside
        // `LiveBrowseSource.playlistItems`, so it is proven through the real source: one POST to
        // /api/v1/index/streams per loaded page, sourceType PLAYLIST, X-Device-Id attached.
        let transport = RoutingTransport(head: .status(200))
        let vm = PlaylistDetailViewModel(playlistId: "PL1", title: nil, category: nil, count: nil,
                                         browse: Self.liveSource(transport), saved: FakeSaved(), fetchHeader: nil)
        await vm.load()
        #expect(vm.items.items.count > 0)
        for _ in 0..<50 where transport.indexPushes.isEmpty { await Task.yield() }
        #expect(transport.indexPushes.count == 1)
        let push = transport.indexPushes.first
        #expect(push?.headers["X-Device-Id"] == "test-device")
        let body = push?.body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        #expect(body?["sourceType"] as? String == "PLAYLIST")
        #expect(body?["sourceId"] as? String == "PL1")
        #expect((body?["items"] as? [Any])?.count == min(vm.items.items.count, IndexClient.batchSize))
    }

    // MARK: - Live-source harness

    /// Routes by request: the gate's HEAD, the browse POST (InnerTubeKit's own `browse-playlist.json`,
    /// read off disk), the index push (recorded), and everything else (remote config) throws.
    private nonisolated final class RoutingTransport: HTTPTransport, @unchecked Sendable {
        enum Head { case throwing, status(Int) }
        private let head: Head
        private let lock = NSLock()
        private var pushes: [HTTPRequest] = []
        private var browse = 0
        var indexPushes: [HTTPRequest] { lock.withLock { pushes } }
        var browseCalls: Int { lock.withLock { browse } }

        init(head: Head) { self.head = head }

        private static let fixture: Data = {
            let url = URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent().deletingLastPathComponent()
                .appending(path: "Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/browse-playlist.json")
            return try! Data(contentsOf: url)
        }()

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            if request.method == "HEAD" {
                switch head {
                case .throwing: throw URLError(.notConnectedToInternet)
                case .status(let s): return HTTPResponse(status: s, headers: [:], body: Data())
                }
            }
            if request.url.path.hasSuffix("index/streams") {
                lock.withLock { pushes.append(request) }
                return HTTPResponse(status: 200, headers: [:], body: Data())
            }
            if request.url.host() == "youtubei.googleapis.com" {
                lock.withLock { browse += 1 }
                return HTTPResponse(status: 200, headers: [:], body: Self.fixture)
            }
            throw URLError(.badServerResponse)
        }
    }

    private nonisolated final class InMemoryKeyValueStore: KeyValueStore, @unchecked Sendable {
        private var storage: [String: Data] = [:]
        func get(_ key: String) -> Data? { storage[key] }
        func set(_ key: String, _ value: Data) { storage[key] = value }
    }

    private static func liveSource(_ transport: RoutingTransport) -> LiveBrowseSource {
        let store = InMemoryKeyValueStore()
        let base = URL(string: "https://app.fitrahtube.com/")!
        return LiveBrowseSource(
            client: BrowseClient(transport: transport,
                                 remoteConfigStore: RemoteConfigStore(transport: transport, keyValueStore: store, url: base),
                                 sessionStore: SessionStore(monotonicClock: SystemClock(), wallClock: SystemClock(), keyValueStore: store),
                                 locale: InnerTubeLocale(hl: "en", gl: "US")),
            atom: AtomFeedFetcher(transport: transport, keyValueStore: store),
            latch: DegradedLatch(store: store),
            index: IndexClient(transport: transport, baseURL: base, deviceId: DeviceId(value: "test-device")),
            gate: BackendAvailabilityGate(transport: transport, baseURL: base),
            degradedHeader: nil)
    }
}
