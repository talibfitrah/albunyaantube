import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// C T6 live finding: production's `Playlist`/`Channel` JSON (shape below, ids and uids
/// redacted) cannot be decoded by the generated DTOs, so the header substitutes go through
/// `PublicHeaders` instead.
@Suite struct PublicHeadersTests {
    /// Verbatim field shapes from `GET /api/v1/playlists/{id}` and `/channels/{id}` on 2026-08-30.
    private static let playlistJSON = Data("""
        {"id":"dJmYHPZymuccd0CYRAVc","youtubeId":"PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc","title":"اناشيد بدون موسيقى",
         "thumbnailUrl":"https://i.ytimg.com/vi/xc7keR2piUM/hqdefault.jpg","itemCount":1535,"categoryIds":[],
         "status":"APPROVED","excludedVideoIds":[],"createdAt":{"seconds":1764112840,"nanos":608000000},
         "updatedAt":{"seconds":1785308468,"nanos":854000000},"submittedBy":"uid","approved":true}
        """.utf8)
    private static let channelJSON = Data("""
        {"id":"abc","youtubeId":"UCmMcOjsVehVlEOteyrhjI2Q","name":"Alafasy","thumbnailUrl":"https://yt3.googleusercontent.com/a.jpg",
         "subscribers":12100000,"categoryIds":[],"status":"APPROVED","createdAt":{"seconds":1764112840,"nanos":608000000},
         "updatedAt":{"seconds":1785308468,"nanos":854000000},"keywords":[]}
        """.utf8)

    private struct Canned: HTTPTransport {
        let body: Data
        var status = 200
        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            #expect(request.headers["X-Device-Id"] == "device-1")
            return HTTPResponse(status: status, headers: [:], body: body)
        }
    }

    private func headers(_ body: Data, status: Int = 200) -> PublicHeaders {
        PublicHeaders(transport: Canned(body: body, status: status), baseURL: URL(string: "https://app.fitrahtube.com/")!,
                      deviceId: DeviceId(value: "device-1"))
    }

    /// The reason this file exists. When it fails, spec and backend agree again: delete
    /// `PublicHeaders` and go back to the generated calls in `AppContainer.live`.
    @Test func theGeneratedPlaylistAndChannelDTOsCannotDecodeProductionTimestamps() {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601  // what the OpenAPI runtime's transcoder expects
        #expect(throws: (any Error).self) { try decoder.decode(Components.Schemas.Playlist.self, from: Self.playlistJSON) }
        #expect(throws: (any Error).self) { try decoder.decode(Components.Schemas.Channel.self, from: Self.channelJSON) }
    }

    @Test func aPlaylistHeaderReadsTitleThumbnailAndCount() async throws {
        let header = try await headers(Self.playlistJSON).playlist("PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc")
        #expect(header == PlaylistHeader(title: "اناشيد بدون موسيقى", thumbnailURL: URL(string: "https://i.ytimg.com/vi/xc7keR2piUM/hqdefault.jpg"), count: 1535))
    }

    @Test func aChannelHeaderReadsIdNameAndAvatar() async throws {
        let header = try await headers(Self.channelJSON).channel("UCmMcOjsVehVlEOteyrhjI2Q")
        #expect(header == ChannelHeader(id: "UCmMcOjsVehVlEOteyrhjI2Q", name: "Alafasy", avatarURL: URL(string: "https://yt3.googleusercontent.com/a.jpg")))
    }

    @Test func aNon2xxThrowsSoTheRouteHeaderStays() async {
        await #expect(throws: (any Error).self) { try await headers(Self.playlistJSON, status: 404).playlist("x") }
    }
}
