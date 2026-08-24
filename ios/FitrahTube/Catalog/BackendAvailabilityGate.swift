import Foundation
import InnerTubeKit

/// FitrahAPI-backed `AvailabilityGate` (CF-B3, `extraction.md` §5.2): a HEAD probe against the
/// public video/channel endpoint, run once at the start of every resolve job before
/// `StreamResolver` spends a `player` POST on content the backend has already pulled.
///
/// `/api/v1/videos/{id}` and `/api/v1/channels/{id}` are GET-only in `api-specification.yaml`,
/// but Spring MVC auto-serves HEAD for any `@GetMapping` (same route, no body) -- so a real HEAD
/// reaches the backend without going through the OpenAPI-generated `Client`, which only emits the
/// methods declared in the spec. Reusing InnerTubeKit's own `HTTPTransport` (already public, no
/// cookies, 15 s timeout) avoids inventing a second transport seam for this one call.
struct BackendAvailabilityGate: AvailabilityGate {
    private let transport: HTTPTransport
    private let baseURL: URL

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL) {
        self.transport = transport
        self.baseURL = baseURL
    }

    /// §5.2 semantics: `sourceChannelId` present -> check the channel (channel-sourced videos
    /// aren't individually registered); else check the video. 2xx/404 -> available; 410 ->
    /// unavailable (admin-blocked/rejected/archived, a hard stop); a thrown transport/HTTP error
    /// fails open so offline users can still play a cached manifest.
    func verify(videoId: String, sourceChannelId: String?) async throws -> Bool {
        let path = sourceChannelId.map { "api/v1/channels/\($0)" } ?? "api/v1/videos/\(videoId)"
        let request = HTTPRequest(method: "HEAD", url: baseURL.appending(path: path), headers: [:], body: nil)
        guard let response = try? await transport.send(request) else { return true }
        return response.status != 410
    }
}
