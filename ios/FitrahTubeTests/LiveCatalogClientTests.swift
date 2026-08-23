import FitrahAPI
import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct LiveCatalogClientTests {
    @Test func mapsCategoryDtosToDomain() async throws {
        let json = Data(#"[{"id":"c1","name":"Quran","slug":"quran","parentId":null},{"id":"c2","name":"Tafsir","slug":"tafsir","parentId":"c1"}]"#.utf8)
        let transport = RecordingTransport()
        transport.responseBody = json
        let client = FitrahAPIClient.make(baseURL: URL(string: "https://example.test/")!,
                                          deviceId: DeviceId(value: "t"), transport: transport)
        let sut = LiveCatalogClient(client: client)
        let categories = try await sut.categories()
        #expect(categories == [
            Category(id: "c1", name: "Quran", slug: "quran", parentId: nil),
            Category(id: "c2", name: "Tafsir", slug: "tafsir", parentId: "c1"),
        ])
        #expect(transport.lastRequest?.path == "/v1/categories")
    }
}
