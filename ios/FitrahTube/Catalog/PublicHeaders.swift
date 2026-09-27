import FitrahAPI
import Foundation
import InnerTubeKit

/// The detail screens' two backend header substitutes -- a deep-linked playlist's title/count
/// (`Route` carries none, CF-C-9) and a bot-checked channel's name/avatar (degraded mode) --
/// hand-written over `HTTPTransport` like `IndexClient`. Not the generated `getPublicPlaylist` /
/// `getPublicChannel`: `api-specification.yaml` types `createdAt`/`updatedAt` as `date-time`
/// strings while the backend emits Firestore `Timestamp` objects (`{"seconds":…,"nanos":…}`), so
/// the generated decode threw on every production response and both substitutes were silently
/// dead (C T6 live finding; `PublicHeadersTests` pins the mismatch). Delete once spec and backend
/// agree and the generated call decodes -- the pin test flips to tell you.
struct PublicHeaders: Sendable {
    private let transport: HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    func playlist(_ id: String) async throws -> PlaylistHeader {
        let dto: PlaylistDTO = try await get("api/v1/playlists/\(id)")
        return PlaylistHeader(title: dto.title, thumbnailURL: dto.thumbnailUrl.flatMap(URL.init(string:)), count: dto.itemCount)
    }

    func channel(_ id: String) async throws -> ChannelHeader {
        let dto: ChannelDTO = try await get("api/v1/channels/\(id)")
        return ChannelHeader(id: dto.youtubeId, name: dto.name, avatarURL: dto.thumbnailUrl.flatMap(URL.init(string:)))
    }

    /// Only the fields the headers read; everything else in the DTO (timestamps included) is ignored.
    private struct PlaylistDTO: Decodable {
        var title: String
        var thumbnailUrl: String?
        var itemCount: Int?
    }

    private struct ChannelDTO: Decodable {
        var youtubeId: String
        var name: String
        var thumbnailUrl: String?
    }

    private func get<T: Decodable>(_ path: String) async throws -> T {
        let request = HTTPRequest(method: "GET", url: baseURL.appending(path: path),
                                  headers: ["X-Device-Id": deviceId.value], body: nil)
        let response = try await transport.send(request)
        guard (200..<300).contains(response.status) else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(T.self, from: response.body)
    }
}
