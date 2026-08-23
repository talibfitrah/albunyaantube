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
            _ = try await sut.content(type: .all, cursor: nil, limit: 20, filter: filter, query: nil)
            #expect(transport.lastRequest?.path?.contains("length=\(length.rawValue)") == true)
        }
        for date in DateFilter.allCases {
            let (sut, transport) = makeClient(responseBody: json)
            var filter = FilterState()
            filter.date = date
            _ = try await sut.content(type: .all, cursor: nil, limit: 20, filter: filter, query: nil)
            #expect(transport.lastRequest?.path?.contains("date=\(date.rawValue)") == true)
        }
        for sort in SortFilter.allCases {
            let (sut, transport) = makeClient(responseBody: json)
            var filter = FilterState()
            filter.sort = sort
            _ = try await sut.content(type: .all, cursor: nil, limit: 20, filter: filter, query: nil)
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
        let items = try await sut.search(query: "quran", type: nil, limit: 50)
        #expect(items.map(\.id) == ["ch1", "p1", "v1"])
        #expect(transport.lastRequest?.path == "/v1/search?q=quran&limit=50")
    }
}
