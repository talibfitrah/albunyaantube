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
    /// I6 (B1 final review): this HEAD runs BEFORE `StreamResolver`'s per-rung 8 s budget and is
    /// not covered by it, so a backend that accepts the connection and then stalls held the user
    /// on "Loading…" for the transport's own 15 s timeout before rung 1 even started. 3 s is well
    /// past a healthy HEAD and well inside spec §6.6's 8 s demotion budget. Injectable so the
    /// timeout can be tested without a 3 s test.
    private let timeout: Duration

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL, timeout: Duration = .seconds(3)) {
        self.transport = transport
        self.baseURL = baseURL
        self.timeout = timeout
    }

    /// §5.2 semantics: `sourceChannelId` present -> check the channel (channel-sourced videos
    /// aren't individually registered); else check the video. 2xx/404 -> available; 410 ->
    /// unavailable (admin-blocked/rejected/archived, a hard stop); a thrown transport/HTTP error
    /// fails open so offline users can still play a cached manifest.
    func verify(videoId: String, sourceChannelId: String?) async throws -> Bool {
        let path = sourceChannelId.map { "api/v1/channels/\($0)" } ?? "api/v1/videos/\(videoId)"
        let request = HTTPRequest(method: "HEAD", url: baseURL.appending(path: path), headers: [:], body: nil)
        guard let response = try? await send(request) else { return true }
        return response.status != 410
    }

    /// `StreamResolver.withTimeout` is `private` to InnerTubeKit, so this is the same race in the
    /// smallest form that reaches here: whichever finishes first wins, and an expiry throws --
    /// which `verify`'s `try?` above turns into the fail-open answer, same as any transport error.
    private func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let transport = self.transport
        let timeout = self.timeout
        return try await withThrowingTaskGroup(of: HTTPResponse.self) { group in
            group.addTask { try await transport.send(request) }
            group.addTask {
                try await Task.sleep(for: timeout)
                throw CancellationError()
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
    }
}
