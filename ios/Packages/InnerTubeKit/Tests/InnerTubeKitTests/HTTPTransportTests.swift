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

    /// A byte source that counts what was pulled from it, so a test can see how much of a body was ever read.
    final class Pulled: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func add() { lock.withLock { count += 1 } }
        var value: Int { lock.withLock { count } }
    }

    struct CountingBytes: AsyncSequence {
        typealias Element = UInt8
        let total: Int
        let pulled: Pulled
        struct AsyncIterator: AsyncIteratorProtocol {
            var sent = 0
            let total: Int
            let pulled: Pulled
            mutating func next() async throws -> UInt8? {
                guard sent < total else { return nil }
                sent += 1
                pulled.add()
                return 0x2A
            }
        }
        func makeAsyncIterator() -> AsyncIterator { AsyncIterator(total: total, pulled: pulled) }
    }

    /// Stage 6 I-C1: a body over the cap is read one byte past it and no further (a 5 MB stand-in for a whole video).
    @Test func aBoundedReadStopsOneBytePastTheCap() async throws {
        let pulled = Pulled()
        let body = try await URLSessionTransport.boundedBody(CountingBytes(total: 5_000_000, pulled: pulled), status: 206,
                                                             declaredLength: -1, maxBytes: 100)
        #expect(body.count == 101)
        #expect(pulled.value == 101)
    }

    /// No body byte is read for a declared oversize length or a non-2xx.
    @Test(arguments: [(200, Int64(5_000_000)), (403, Int64(-1)), (404, Int64(50))])
    func aBoundedReadOfARefusedHeadReadsNothing(_ status: Int, _ declaredLength: Int64) async throws {
        let pulled = Pulled()
        let body = try await URLSessionTransport.boundedBody(CountingBytes(total: 5_000_000, pulled: pulled), status: status,
                                                             declaredLength: declaredLength, maxBytes: 100)
        #expect(body.isEmpty)
        #expect(pulled.value == 0)
    }

    @Test func aBoundedReadWithinTheCapIsReadWhole() async throws {
        let pulled = Pulled()
        let body = try await URLSessionTransport.boundedBody(CountingBytes(total: 100, pulled: pulled), status: 206,
                                                             declaredLength: 100, maxBytes: 100)
        #expect(body == Data(repeating: 0x2A, count: 100))
    }

    @Test func manualClockAdvances() {
        let c = ManualClock()
        #expect(c.now == .zero)
        c.advance(by: .seconds(30))
        #expect(c.now == .seconds(30))
    }
}
