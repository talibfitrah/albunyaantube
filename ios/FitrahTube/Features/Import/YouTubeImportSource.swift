import Foundation
import InnerTubeKit

nonisolated enum CandidateType: String, Sendable, CaseIterable {
    case channel = "CHANNEL", playlist = "PLAYLIST", video = "VIDEO"

    /// The wire's own `type` on an `ImportResult`. nil for anything else, and the row is dropped:
    /// the pipeline routes the WRITE by this type, so a guessed one would put a channel's row in
    /// the favorites table (Task 25 deviation 3, one endpoint over).
    static func fromWire(_ raw: String?) -> CandidateType? {
        switch raw?.uppercased() {
        case "CHANNEL": .channel
        case "PLAYLIST": .playlist
        case "VIDEO": .video
        default: nil
        }
    }
}

nonisolated struct ImportCandidate: Sendable, Equatable, Identifiable {
    var type: CandidateType
    var youtubeId: String
    var title: String
    var thumbnailUrl: String?
    /// The uploader's channel id, present for VIDEO candidates only. It is an id, never a name —
    /// see `ImportPipeline`'s PENDING-video arm.
    var channelId: String?

    var id: String { youtubeId }
}

/// The YouTube Data API v3, with the OAuth token per-call as `Authorization: Bearer …` and **no api
/// key anywhere** (`YouTubeImportApi.kt:16-18` — confirmed against the Android source; the fork is
/// closed). A key shipped in a binary is a key anyone can lift, and the OAuth token already
/// identifies the only account whose lists this reads.
///
/// Three INDEPENDENT paginators: one type's 403 (a resource the account does not expose) must not
/// suppress the other two (`YouTubeImportRemoteSource.kt:36-66`).
///
/// **This is the only type in the app that addresses googleapis.com.** It reads lists; it renders
/// nothing, links nowhere, and hands its output to `ImportPipeline` as plain values.
nonisolated struct YouTubeImportSource: Sendable {

    static let baseURL = URL(string: "https://www.googleapis.com/youtube/v3/")!
    /// F12: a hard cap against an infinite-pagination bug, <= 2 000 items per type. Reaching it
    /// TRUNCATES, which is not a failure — the types that answered still answered.
    static let maxPages = 40
    static let pageSize = 50

    private let transport: any HTTPTransport

    init(transport: any HTTPTransport) { self.transport = transport }

    private enum Failure: Error { case status(Int) }

    func fetchAll(accessToken: String) async -> (candidates: [ImportCandidate], failedTypes: Set<CandidateType>) {
        let bearer = "Bearer \(accessToken)"
        var candidates: [ImportCandidate] = []
        var failedTypes: Set<CandidateType> = []

        for type in CandidateType.allCases {
            // Cancellable between types as well as between pages: a user who left the screen must
            // not be charged two more round trips.
            if Task.isCancelled { break }
            do {
                candidates += try await fetch(type, bearer: bearer)
            } catch {
                // Never logged with the bearer in scope; the token is not part of any diagnostic.
                failedTypes.insert(type)
            }
        }
        return (candidates, failedTypes)
    }

    // MARK: - The three paginators

    private func fetch(_ type: CandidateType, bearer: String) async throws -> [ImportCandidate] {
        switch type {
        case .channel:
            // `snippet.resourceId.channelId`, NOT `item.id` — `item.id` is the *subscription* id,
            // which would resolve to nothing on the backend and store a row nothing can match.
            return try await paginate("subscriptions", [.init(name: "part", value: "snippet"),
                                                        .init(name: "mine", value: "true")],
                                      bearer) { (item: SubscriptionItem) in
                ImportCandidate(type: .channel, youtubeId: item.snippet.resourceId.channelId,
                                title: item.snippet.title, thumbnailUrl: item.snippet.thumbnails?.bestUrl,
                                channelId: nil)
            }
        case .playlist:
            return try await paginate("playlists", [.init(name: "part", value: "snippet"),
                                                    .init(name: "mine", value: "true")],
                                      bearer) { (item: PlaylistItem) in
                ImportCandidate(type: .playlist, youtubeId: item.id, title: item.snippet.title,
                                thumbnailUrl: item.snippet.thumbnails?.bestUrl, channelId: nil)
            }
        case .video:
            return try await paginate("videos", [.init(name: "part", value: "snippet"),
                                                 .init(name: "myRating", value: "like")],
                                      bearer) { (item: LikedVideoItem) in
                ImportCandidate(type: .video, youtubeId: item.id, title: item.snippet.title,
                                thumbnailUrl: item.snippet.thumbnails?.bestUrl,
                                channelId: item.snippet.channelId)
            }
        }
    }

    /// The ONE page loop. Stops on the page cap, on a token the server has already handed back
    /// (a repeat would re-fetch the same page 38 more times before the cap noticed), on an absent
    /// token, and on cancellation.
    private func paginate<Item: Decodable>(_ path: String, _ query: [URLQueryItem], _ bearer: String,
                                           _ map: (Item) -> ImportCandidate) async throws -> [ImportCandidate] {
        var out: [ImportCandidate] = []
        var pageToken: String?
        var seenTokens: Set<String> = []
        var pages = 0

        while pages < Self.maxPages {
            if Task.isCancelled { break }
            var items = query
            items.append(URLQueryItem(name: "maxResults", value: String(Self.pageSize)))
            if let pageToken { items.append(URLQueryItem(name: "pageToken", value: pageToken)) }
            let url = Self.baseURL.appending(path: path).appending(queryItems: items)
            // The bearer, and nothing else: no device id, no app headers. This host is not the
            // FitrahTube backend and must learn nothing about the device from these calls.
            let response = try await transport.send(
                HTTPRequest(method: "GET", url: url, headers: ["Authorization": bearer], body: nil))
            guard response.status == 200 else { throw Failure.status(response.status) }
            let page = try JSONDecoder().decode(Page<Item>.self, from: response.body)
            out += page.items.map(map)
            pages += 1
            guard let next = page.nextPageToken, !next.isEmpty, seenTokens.insert(next).inserted else { break }
            pageToken = next
        }
        return out
    }

    // MARK: - Wire

    private struct Page<Item: Decodable>: Decodable {
        let items: [Item]
        let nextPageToken: String?
    }

    /// Only `default` and `medium` are captured; higher resolutions are ignored, and medium wins
    /// (`YtThumbnails.bestUrl`). Optional as a whole: a row with no thumbnails costs its picture,
    /// never the page.
    private struct Thumbnails: Decodable {
        struct Entry: Decodable { let url: String }
        let `default`: Entry?
        let medium: Entry?
        var bestUrl: String? { medium?.url ?? `default`?.url }
    }

    private struct SubscriptionItem: Decodable {
        struct Snippet: Decodable {
            struct ResourceId: Decodable { let channelId: String }
            let title: String
            let resourceId: ResourceId
            let thumbnails: Thumbnails?
        }
        let snippet: Snippet
    }

    private struct PlaylistItem: Decodable {
        struct Snippet: Decodable {
            let title: String
            let thumbnails: Thumbnails?
        }
        let id: String
        let snippet: Snippet
    }

    private struct LikedVideoItem: Decodable {
        struct Snippet: Decodable {
            let title: String
            let channelId: String
            let thumbnails: Thumbnails?
        }
        let id: String
        let snippet: Snippet
    }
}
