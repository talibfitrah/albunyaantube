import Foundation
import HTTPTypes
import Testing
@testable import FitrahAPI

@Suite(.perTest)
struct DeviceIdMiddlewareTests {
    @Test func addsDeviceIdHeaderAndApiBasePath() async throws {
        let transport = RecordingTransport()
        let client = FitrahAPIClient.make(
            baseURL: URL(string: "https://example.test/")!,
            deviceId: DeviceId(value: "dev-123"),
            transport: transport
        )
        _ = try await client.listPublicCategories()
        let header = transport.lastRequest?.headerFields[HTTPField.Name("X-Device-Id")!]
        #expect(header == "dev-123")
        #expect(transport.lastBaseURL?.absoluteString == "https://example.test/api")
        #expect(transport.lastRequest?.path == "/v1/categories")
    }
}
