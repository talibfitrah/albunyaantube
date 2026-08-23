import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct HTTPTransportTests {
    @Test func fixtureTransportReturnsMatchedResponse() async throws {
        let t = FixtureTransport(routes: [
            .init(match: { $0.url.path.hasSuffix("/player") }, response: .init(status: 200, headers: [:], body: Data("{\"ok\":true}".utf8)))
        ])
        let resp = try await t.send(HTTPRequest(method: "POST", url: URL(string: "https://youtubei.googleapis.com/youtubei/v1/player")!, headers: [:], body: nil))
        #expect(resp.status == 200)
        #expect(String(decoding: resp.body, as: UTF8.self) == "{\"ok\":true}")
    }

    @Test func manualClockAdvances() {
        let c = ManualClock()
        #expect(c.now == .zero)
        c.advance(by: .seconds(30))
        #expect(c.now == .seconds(30))
    }
}
