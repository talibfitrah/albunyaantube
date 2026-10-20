import FitrahAPI
import Foundation
import HTTPTypes
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct LiveCatalogClientTests {
    private func makeClient(responseBody: Data) -> (LiveCatalogClient, RecordingTransport) {
        let transport = RecordingTransport()
        transport.responseBody = responseBody
        let client = FitrahAPIClient.make(baseURL: URL(string: "https://example.test/")!,
                                          deviceId: DeviceId(value: "t"), transport: transport)
        return (LiveCatalogClient(client: client), transport)
    }

    @Test func mapsCategoryDtosToDomainIncludingNewFields() async throws {
        let json = Data(#"""
        [
            {"id":"c1","name":"Quran","slug":"quran","parentId":null,"displayOrder":1,"localizedNames":{"ar":"قرآن"},"icon":"📖"},
            {"id":"c2","name":"Tafsir","slug":"tafsir","parentId":"c1"}
        ]
        """#.utf8)
        let (sut, transport) = makeClient(responseBody: json)
        let categories = try await sut.categories()
        #expect(categories == [
            Category(id: "c1", name: "Quran", slug: "quran", parentId: nil, displayOrder: 1, localizedNames: ["ar": "قرآن"], icon: "📖"),
            Category(id: "c2", name: "Tafsir", slug: "tafsir", parentId: "c1"),
        ])
        #expect(transport.lastRequest?.path == "/v1/categories")
    }

    @Test func homeMapsSectionsAndCursor() async throws {
        let json = Data(#"""
        {
            "data": [
                {
                    "id": "c1",
                    "name": "Quran",
                    "slug": "quran",
                    "localizedNames": {"ar": "قرآن"},
                    "displayOrder": 1,
                    "icon": "📖",
                    "items": [
                        {"id":"v1","type":"VIDEO","title":"Video One","category":"Quran","durationSeconds":120,"uploadedDaysAgo":2,"viewCount":100}
                    ],
                    "totalContentCount": 1
                }
            ],
            "pageInfo": {"hasNext": true, "nextCursor": "abc"}
        }
        """#.utf8)
        let (sut, transport) = makeClient(responseBody: json)
        let page = try await sut.home(cursor: nil, categoryLimit: 5, contentLimit: 10, category: nil)
        #expect(page.items == [
            HomeSection(id: "c1", name: "Quran", localizedNames: ["ar": "قرآن"], icon: "📖", items: [
                ContentItem(id: "v1", type: .video, title: "Video One", category: "Quran", description: nil,
                            thumbnailURL: nil, durationSeconds: 120, uploadedDaysAgo: 2, viewCount: 100,
                            channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil),
            ]),
        ])
        #expect(page.nextCursor == "abc")
        #expect(page.hasMore)
        #expect(transport.lastRequest?.path == "/v1/home?categoryLimit=5&contentLimit=10")
    }

    @Test func contentSendsTypeLimitCategoryAndOmitsNilFilters() async throws {
        let json = Data(#"{"data":[],"pageInfo":{"hasNext":false,"nextCursor":null}}"#.utf8)
        let (sut, transport) = makeClient(responseBody: json)
        var filter = FilterState()
        filter.categoryId = "c1"
        let page = try await sut.content(type: .videos, cursor: nil, limit: 20, filter: filter, query: nil)
        #expect(page.items.isEmpty)
        #expect(!page.hasMore)
        #expect(transport.lastRequest?.path == "/v1/content?type=VIDEOS&limit=20&category=c1")
    }

    @Test func filterEnumsRoundTripIntoQuery() async throws {
        let json = Data(#"{"data":[],"pageInfo":{"hasNext":false,"nextCursor":null}}"#.utf8)
        for length in LengthFilter.allCases {
            let (sut, transport) = makeClient(responseBody: json)
            var filter = FilterState()
            filter.length = length
            _ = try await sut.content(type: nil, cursor: nil, limit: 20, filter: filter, query: nil)
            #expect(transport.lastRequest?.path?.contains("length=\(length.rawValue)") == true)
        }
        for date in DateFilter.allCases {
            let (sut, transport) = makeClient(responseBody: json)
            var filter = FilterState()
            filter.date = date
            _ = try await sut.content(type: nil, cursor: nil, limit: 20, filter: filter, query: nil)
            #expect(transport.lastRequest?.path?.contains("date=\(date.rawValue)") == true)
        }
        for sort in SortFilter.allCases {
            let (sut, transport) = makeClient(responseBody: json)
            var filter = FilterState()
            filter.sort = sort
            _ = try await sut.content(type: nil, cursor: nil, limit: 20, filter: filter, query: nil)
            #expect(transport.lastRequest?.path?.contains("sort=\(sort.rawValue)") == true)
        }
    }

    @Test func non2xxResponseThrows() async throws {
        let (sut, transport) = makeClient(responseBody: Data("[]".utf8))
        transport.status = .internalServerError
        await #expect(throws: (any Error).self) {
            try await sut.categories()
        }
    }

    @Test func searchSendsQueryAndReturnsItemsInServerOrder() async throws {
        let json = Data(#"""
        [
            {"id":"ch1","type":"CHANNEL","name":"Some Channel"},
            {"id":"p1","type":"PLAYLIST","title":"Some Playlist"},
            {"id":"v1","type":"VIDEO","title":"Some Video"}
        ]
        """#.utf8)
        let (sut, transport) = makeClient(responseBody: json)
        let result = try await sut.search(query: "quran", type: nil, limit: 50)
        #expect(result.items.map(\.id) == ["ch1", "p1", "v1"])
        #expect(transport.lastRequest?.path == "/v1/search?q=quran&limit=50")
    }

    // MARK: - 503 + Retry-After while the search index builds (Android's retryOnceWhileIndexBuilds)

    private static let page = Data(#"{"data":[{"id":"v1","type":"VIDEO","title":"Some Video"}],"pageInfo":{"hasNext":false,"nextCursor":null}}"#.utf8)
    private static let list = Data(#"[{"id":"v1","type":"VIDEO","title":"Some Video"}]"#.utf8)

    /// The two calls the retry covers, each answered from `transport` and reduced to the ids.
    private static func call(_ search: Bool, _ sut: LiveCatalogClient) async throws -> [String] {
        search ? try await sut.search(query: "quran", type: nil, limit: 50).items.map(\.id)
               : try await sut.content(type: .videos, cursor: nil, limit: 20, filter: FilterState(), query: "quran").items.map(\.id)
    }

    private static func makeRetrying(search: Bool, sleep: @escaping @Sendable (Duration) async throws -> Void)
        -> (LiveCatalogClient, RecordingTransport) {
        let transport = RecordingTransport()
        transport.responseBody = search ? list : page
        let client = FitrahAPIClient.make(baseURL: URL(string: "https://example.test/")!,
                                          deviceId: DeviceId(value: "t"), transport: transport)
        return (LiveCatalogClient(client: client, sleep: sleep), transport)
    }

    @Test(arguments: [false, true], ["5", "10"])
    func a503WithRetryAfterIsRetriedOnceAfterThatWait(search: Bool, retryAfter: String) async throws {
        let sleeps = SleepRecorder()
        let (sut, transport) = Self.makeRetrying(search: search, sleep: sleeps.record)
        transport.statuses = [.serviceUnavailable]
        transport.extraHeaders = [.retryAfter: retryAfter]

        #expect(try await Self.call(search, sut) == ["v1"])
        #expect(transport.requestCount == 2)
        #expect(sleeps.recorded == [.seconds(Int(retryAfter)!)])
    }

    @Test(arguments: [false, true])
    func aSecond503GoesToTheCaller(search: Bool) async {
        let sleeps = SleepRecorder()
        let (sut, transport) = Self.makeRetrying(search: search, sleep: sleeps.record)
        transport.status = .serviceUnavailable
        transport.extraHeaders = [.retryAfter: "5"]

        await #expect(throws: (any Error).self) { try await Self.call(search, sut) }
        #expect(transport.requestCount == 2)
    }

    /// 500, or 429 even with Retry-After, is a real failure; a 503 with no Retry-After, or one
    /// longer than 10 s, is not worth holding a keystroke's search for.
    @Test(arguments: [false, true], [(500, "5"), (429, "5"), (503, nil), (503, "11")] as [(Int, String?)])
    func neverRetried(search: Bool, answer: (Int, String?)) async {
        let sleeps = SleepRecorder()
        let (sut, transport) = Self.makeRetrying(search: search, sleep: sleeps.record)
        transport.status = HTTPResponse.Status(code: answer.0)
        transport.extraHeaders = answer.1.map { [.retryAfter: $0] } ?? [:]

        await #expect(throws: (any Error).self) { try await Self.call(search, sut) }
        #expect(transport.requestCount == 1)
        #expect(sleeps.recorded.isEmpty)
    }

    /// The next keystroke cancels the search: the wait ends at once, nothing is sent again.
    @Test(arguments: [false, true])
    func theWaitIsCancelledWithTheCaller(search: Bool) async {
        let (waiting, waitStarted) = AsyncStream<Void>.makeStream()
        let (sut, transport) = Self.makeRetrying(search: search) { duration in
            waitStarted.yield()
            try await Task.sleep(for: duration)
        }
        transport.statuses = [.serviceUnavailable]
        transport.extraHeaders = [.retryAfter: "10"]

        let task = Task {
            defer { waitStarted.finish() }   // a call that never waits must not hang the test
            return try await Self.call(search, sut)
        }
        var started = waiting.makeAsyncIterator()
        _ = await started.next()   // the client has started its wait
        task.cancel()

        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(transport.requestCount == 1)
    }

    @Test func searchReadsTheMatchHeaderAndDefaultsToExact() async throws {
        let json = Data(#"[{"id":"v1","type":"VIDEO","title":"Some Video"}]"#.utf8)
        let (sut, transport) = makeClient(responseBody: json)

        transport.extraHeaders = [HTTPField.Name("X-Search-Match")!: "approximate"]
        #expect(try await sut.search(query: "zakit", type: nil, limit: 50).match == .approximate)

        transport.extraHeaders = [:]
        #expect(try await sut.search(query: "zakir", type: nil, limit: 50).match == .exact)

        transport.extraHeaders = [HTTPField.Name("X-Search-Match")!: "something-new"]
        #expect(try await sut.search(query: "zakir", type: nil, limit: 50).match == .exact)
    }

    @Test func liveChannelDecodesTheLives() async throws {
        let json = Data(#"{"channelId":"UCOll3M-P7oKs5cSrQ9ytt6g","status":"LIVE","lives":[{"id":"eaOtcUNHDB8","title":"Zad"}]}"#.utf8)
        let (sut, transport) = makeClient(responseBody: json)

        let lives = try await sut.liveChannel(id: "UCOll3M-P7oKs5cSrQ9ytt6g")

        #expect(lives == LiveChannelLives(
            lives: [.init(id: "eaOtcUNHDB8", title: "Zad")]))
        #expect(transport.lastRequest?.path == "/v1/live/channels/UCOll3M-P7oKs5cSrQ9ytt6g")
    }

    @Test func aLiveCategoryItemKeepsItsChannel() async throws {
        let json = Data(#"""
        {"data":[{"id":"eaOtcUNHDB8","type":"VIDEO","title":"Zad","durationSeconds":0,"liveChannelId":"UCOll3M-P7oKs5cSrQ9ytt6g"}],
         "pageInfo":{"hasNext":false}}
        """#.utf8)
        let (sut, _) = makeClient(responseBody: json)

        let page = try await sut.content(type: nil, cursor: nil, limit: 20, filter: FilterState(categoryId: "live"), query: nil)

        #expect(page.items.first?.liveChannelId == "UCOll3M-P7oKs5cSrQ9ytt6g")
        #expect(PlayerArgs(item: page.items[0]).liveChannelId == "UCOll3M-P7oKs5cSrQ9ytt6g")
        #expect(PlayerArgs(item: page.items[0]).channelId == "UCOll3M-P7oKs5cSrQ9ytt6g")
    }

    /// The backend's 404 (GlobalExceptionHandler's body): not a servable live channel, stop asking.
    @Test func aLiveChannel404IsNotFound() async {
        let json = Data(#"{"timestamp":"2026-10-17T15:00:00","status":404,"error":"Not Found","message":"LiveChannel not found","path":"/api/v1/live/channels/UCx"}"#.utf8)
        let (sut, transport) = makeClient(responseBody: json)
        transport.status = .notFound

        await #expect(throws: LiveChannelNotFound.self) { try await sut.liveChannel(id: "UCx") }
    }

    /// Anything else is "no answer": retryable, never mistaken for a 404.
    @Test func aLiveChannel503IsNotNotFound() async {
        let (sut, transport) = makeClient(responseBody: Data())
        transport.status = .serviceUnavailable

        let error = await #expect(throws: (any Error).self) { try await sut.liveChannel(id: "UCx") }
        #expect(error != nil && !(error is LiveChannelNotFound))
        #expect((error as? LiveChannelUnexpectedStatus)?.statusCode == 503)
    }

    @Test func theFakeAnswersAnUnknownChannelLikeThe404() async throws {
        let lives = LiveChannelLives(lives: [])
        let fake = FakeCatalogClient(liveChannels: ["UCa": lives])

        #expect(try await fake.liveChannel(id: "UCa") == lives)
        await #expect(throws: LiveChannelNotFound.self) { try await fake.liveChannel(id: "UCx") }
    }

    @Test func aNonLiveItemOpensThePlayerWithNoChannel() {
        let item = ContentItem(id: "v1", type: .video, title: "Video One", category: "Quran", description: nil,
                               thumbnailURL: nil, durationSeconds: 120, uploadedDaysAgo: 2, viewCount: 100,
                               channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil)

        #expect(PlayerArgs(item: item).channelId == nil)
        #expect(PlayerArgs(item: item).liveChannelId == nil)
    }
}
