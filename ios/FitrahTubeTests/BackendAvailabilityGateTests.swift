import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct BackendAvailabilityGateTests {
    private static let baseURL = URL(string: "https://app.fitrahtube.com/")!
    private static let videoId = "abcdefghijk"

    /// Shared mutable box so the stub (a `Sendable` struct) can still hand the test the request
    /// it observed. `nonisolated`: the test target defaults new types to `@MainActor`, but
    /// `StubTransport.send` runs on whatever nonisolated context calls it (mirroring the real
    /// `HTTPTransport` conformers), so the box it writes into can't be actor-isolated either.
    private nonisolated final class RequestBox: @unchecked Sendable {
        var request: HTTPRequest?
    }

    private struct StubTransport: HTTPTransport {
        var status: Int?
        var error: Error?
        let box: RequestBox

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            box.request = request
            if let error { throw error }
            return HTTPResponse(status: status ?? 200, headers: [:], body: Data())
        }
    }

    @Test func okStatusIsAvailable() async throws {
        let gate = BackendAvailabilityGate(transport: StubTransport(status: 200, box: RequestBox()), baseURL: Self.baseURL)
        #expect(try await gate.verify(videoId: Self.videoId, sourceChannelId: nil) == true)
    }

    @Test func notFoundIsAvailable() async throws {
        let gate = BackendAvailabilityGate(transport: StubTransport(status: 404, box: RequestBox()), baseURL: Self.baseURL)
        #expect(try await gate.verify(videoId: Self.videoId, sourceChannelId: nil) == true)
    }

    @Test func goneIsUnavailable() async throws {
        let gate = BackendAvailabilityGate(transport: StubTransport(status: 410, box: RequestBox()), baseURL: Self.baseURL)
        #expect(try await gate.verify(videoId: Self.videoId, sourceChannelId: nil) == false)
    }

    @Test func transportErrorFailsOpen() async throws {
        let gate = BackendAvailabilityGate(
            transport: StubTransport(status: nil, error: URLError(.timedOut), box: RequestBox()), baseURL: Self.baseURL)
        #expect(try await gate.verify(videoId: Self.videoId, sourceChannelId: nil) == true)
    }

    /// I6 (B1 final review): a backend that accepts the connection and then never answers must
    /// not hold the player on "Loading…" -- the gate expires and fails open, well inside spec
    /// §6.6's 8 s budget. Driven with a 50 ms timeout so the test itself stays fast; the
    /// production default is 3 s.
    private struct HangingTransport: HTTPTransport {
        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            try await Task.sleep(for: .seconds(60))
            return HTTPResponse(status: 410, headers: [:], body: Data())
        }
    }

    @Test func aHangingBackendExpiresAndFailsOpenWithinTheBudget() async throws {
        let gate = BackendAvailabilityGate(transport: HangingTransport(), baseURL: Self.baseURL,
                                           timeout: .milliseconds(50))
        let started = ContinuousClock.now
        let available = try await gate.verify(videoId: Self.videoId, sourceChannelId: nil)
        #expect(available == true)
        #expect(ContinuousClock.now - started < .seconds(1))
    }

    @Test func noSourceChannelIdHitsVideosEndpointWithHEAD() async throws {
        let box = RequestBox()
        let gate = BackendAvailabilityGate(transport: StubTransport(status: 200, box: box), baseURL: Self.baseURL)
        _ = try await gate.verify(videoId: Self.videoId, sourceChannelId: nil)
        #expect(box.request?.method == "HEAD")
        #expect(box.request?.url.path == "/api/v1/videos/\(Self.videoId)")
    }

    @Test func sourceChannelIdHitsChannelsEndpoint() async throws {
        let box = RequestBox()
        let gate = BackendAvailabilityGate(transport: StubTransport(status: 200, box: box), baseURL: Self.baseURL)
        _ = try await gate.verify(videoId: Self.videoId, sourceChannelId: "UCabc123")
        #expect(box.request?.url.path == "/api/v1/channels/UCabc123")
    }
}
