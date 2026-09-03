import Foundation
import HTTPTypes
import OpenAPIRuntime
import Testing
@testable import FitrahAPI

/// Only the two ADAPTER facts: the retry state machine itself is pinned once, in
/// `BearerRetryTests`, and is not re-tested here.
@Suite(.perTest)
struct AuthMiddlewareTests {

    private struct ScriptedTokens: AuthTokenProviding {
        let token: String?
        func idToken(forceRefresh: Bool) async -> BearerToken? {
            token.map { BearerToken(value: $0, identity: "uid-A") }
        }
    }

    /// Fact 1: the Bearer reaches the configured API host, and is ABSENT on a request bound
    /// anywhere else — the same middleware instance, asked to sign a YouTube URL, refuses.
    @Test func theBearerReachesTheApiHostAndNeverLeavesIt() async throws {
        let transport = RecordingTransport()
        let client = FitrahAPIClient.make(
            baseURL: URL(string: "https://example.test/")!,
            deviceId: DeviceId(value: "dev-123"),
            tokens: ScriptedTokens(token: "tok-abc"),
            transport: transport
        )
        _ = try await client.listPublicCategories()
        #expect(transport.lastRequest?.headerFields[.authorization] == "Bearer tok-abc")

        let middleware = AuthMiddleware(apiHost: "example.test", tokens: ScriptedTokens(token: "tok-abc"))
        _ = try await middleware.intercept(
            HTTPRequest(method: .get, scheme: "https", authority: "www.youtube.com", path: "/watch?v=xc7keR2piUM"),
            body: nil,
            baseURL: URL(string: "https://www.youtube.com/")!,
            operationID: "offHost",
            next: { request, body, url in
                try await transport.send(request, body: body, baseURL: url, operationID: "offHost")
            }
        )
        #expect(transport.lastRequest?.headerFields[.authorization] == nil)
    }

    /// Fact 2: `AuthMiddleware` is installed BESIDE `DeviceIdMiddleware`, never instead of it, so
    /// `X-Device-Id` survives on every request — with a token and without one.
    @Test func theDeviceIdHeaderSurvivesWithAndWithoutAToken() async throws {
        let signed = RecordingTransport()
        _ = try await FitrahAPIClient.make(
            baseURL: URL(string: "https://example.test/")!,
            deviceId: DeviceId(value: "dev-456"),
            tokens: ScriptedTokens(token: "tok-abc"),
            transport: signed
        ).listPublicCategories()
        #expect(signed.lastRequest?.headerFields[DeviceIdMiddleware.headerName] == "dev-456")
        #expect(signed.lastRequest?.headerFields[.authorization] == "Bearer tok-abc")

        // tokens: nil -> no AuthMiddleware at all, which is production today (CF-A-17).
        let unsigned = RecordingTransport()
        _ = try await FitrahAPIClient.make(
            baseURL: URL(string: "https://example.test/")!,
            deviceId: DeviceId(value: "dev-456"),
            transport: unsigned
        ).listPublicCategories()
        #expect(unsigned.lastRequest?.headerFields[DeviceIdMiddleware.headerName] == "dev-456")
        #expect(unsigned.lastRequest?.headerFields[.authorization] == nil)
    }
}
