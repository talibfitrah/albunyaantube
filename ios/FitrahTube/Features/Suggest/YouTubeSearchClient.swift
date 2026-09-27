import FitrahAPI
import Foundation
import InnerTubeKit

/// The four values of `YouTubeContentType` (`YouTubeContentType.java`), which is what the `type`
/// query param is parsed into. `.all` is a real filter the controller defaults to, not an omission.
nonisolated enum SuggestType: String, Sendable, CaseIterable {
    case all = "ALL", channels = "CHANNEL", playlists = "PLAYLIST", videos = "VIDEO"

    var labelKey: String {
        switch self {
        case .all: "suggest_type_all"
        case .channels: "suggest_type_channels"
        case .playlists: "suggest_type_playlists"
        case .videos: "suggest_type_videos"
        }
    }

    /// A HIT's own `contentType`, which is `"CHANNEL" | "PLAYLIST" | "VIDEO"` and nothing else
    /// (`YouTubeSearchService.channelHit/playlistHit/videoHit`). **nil for anything else, including
    /// `"ALL"`, and the row is dropped**: `ALL` is a query filter, never a hit's own type, and the
    /// type a hit carries is what Task 27 puts in the `POST api/admin/registry/{type}` PATH — so a
    /// guessed type would submit the row to another collection. Same rule, same reason, as
    /// `SubmissionType.fromWire`.
    static func fromWire(_ raw: String?) -> SuggestType? {
        switch raw?.uppercased() {
        case "CHANNEL": .channels
        case "PLAYLIST": .playlists
        case "VIDEO": .videos
        default: nil
        }
    }
}

/// One search hit. The wire record is
/// `SearchHit(youtubeId, name, url, thumbnailUrl, secondary, alreadyKnown, knownStatus, contentType)`
/// — four of those names differ from the app's, which is half of why this client is hand-written.
///
/// `url` (the canonical YouTube URL) is deliberately NOT decoded: nothing in this app links out to
/// YouTube, so a field that exists only to be opened there has no reader here. `alreadyKnown` is not
/// decoded either — it is exactly `knownStatus != nil` (`YouTubeSearchService.annotateKnown:214-216`)
/// and two fields that can disagree are worse than one.
nonisolated struct SuggestItem: Sendable, Equatable, Identifiable {
    var youtubeId: String
    var type: SuggestType
    var title: String
    var thumbnailUrl: String?
    var channelTitle: String?
    /// The registry state the backend already knows (wire `knownStatus`), so a row renders
    /// `suggest_already_in_registry` / `_already_pending` / `_already_rejected` instead of a Submit
    /// button. nil means "not in the registry" — submittable.
    var registryState: String?

    var id: String { youtubeId }
}

nonisolated struct SuggestPage: Sendable, Equatable {
    var items: [SuggestItem]
    var nextPageToken: String?
}

/// Deliberately NOT `AccountError`: the two types disagree about what a 403 means. On
/// `/api/account/*` a 403 is the account-lifecycle envelope (blocked / deleted); here it is the
/// ROLE GATE — `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")` — answering a plain user, which is
/// a real verdict with its own string rather than a session event.
nonisolated enum SuggestError: Error, Equatable {
    case forbidden                            // 403 -> suggest_error_not_allowed (the role gate)
    case rateLimited(retryAfterSeconds: Int)  // 429
    case network                              // the transport never got an answer
    case server(status: Int)                  // any other non-2xx -> suggest_error_server
}

/// Ruling F1's FIFTH hand-written client (the ruling named four; no OpenAPI path covers this one).
/// `GET api/admin/youtube/search?q&type&pageToken` (`YouTubeSearchController.java:26-27` for the
/// mapping and the `@PreAuthorize` that makes 403 the role gate, `:51-56` for the three params),
/// over `AuthorizedTransport` — an admin path, so the Bearer is required and a 403 is the role gate
/// answering, not a network fault.
///
/// **This is a BACKEND call.** The search runs server-side (NewPipe, in `YouTubeSearchService`);
/// this client never addresses youtube.com and never hands the user a link to it.
///
/// Server-side `q` is `@NotBlank @Size(max = 200)` and `pageToken` `@Size(max = 2048)`
/// (`:52-54`). **This client truncates neither** — an over-long query surfaces as the server's 400,
/// mapped to `.server(status: 400)`, because a long query is real user input whose rejection the
/// user should see, not text to silently trim and search for something they did not type.
nonisolated struct YouTubeSearchClient: Sendable {
    private let transport: any HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    func search(q: String, type: SuggestType, pageToken: String?) async throws(SuggestError) -> SuggestPage {
        var query = [URLQueryItem(name: "q", value: q),
                     URLQueryItem(name: "type", value: type.rawValue)]
        // OMITTED when nil, never sent empty — the first page is "no token", which is also what
        // Retrofit does for Android's identical `@Query("pageToken") String?` (`YouTubeSearchApi.kt:13`).
        if let pageToken { query.append(URLQueryItem(name: "pageToken", value: pageToken)) }

        let request = HTTPRequest(method: "GET",
                                  url: Self.url(baseURL.appending(path: "api/admin/youtube/search"), query),
                                  headers: ["X-Device-Id": deviceId.value], body: nil)

        let response: HTTPResponse
        do {
            response = try await transport.send(request)
        } catch {
            // As in `AccountClient.send`: everything the transport can throw is "the request did
            // not happen" to this caller, cancellation included.
            throw SuggestError.network
        }

        guard response.status == 200 else { throw Self.failure(response) }
        // A 200 that is not a page at all is a FAILURE, never an empty result: the empty result is a
        // real answer this screen has its own word for (`suggest_empty_results`), and the two must
        // not look alike.
        guard let body = try? JSONDecoder().decode(PageBody.self, from: response.body) else {
            throw SuggestError.server(status: response.status)
        }
        return SuggestPage(items: (body.items ?? []).compactMap(Self.item),
                           nextPageToken: body.nextPageToken)
    }

    // MARK: - Wire

    private struct PageBody: Decodable {
        let items: [HitBody]?
        let nextPageToken: String?
    }

    private struct HitBody: Decodable {
        let youtubeId: String
        let name: String?
        let thumbnailUrl: String?
        let secondary: String?
        let knownStatus: String?
        let contentType: String?
    }

    /// nil for a hit whose `contentType` this build cannot name — see `SuggestType.fromWire`. One
    /// bad row must not cost the page (the backend already drops what it cannot extract,
    /// `YouTubeSearchService.toHit:137-140`; this is the same tolerance one hop later).
    private static func item(_ hit: HitBody) -> SuggestItem? {
        guard let type = SuggestType.fromWire(hit.contentType) else { return nil }
        return SuggestItem(youtubeId: hit.youtubeId, type: type, title: hit.name ?? "",
                           thumbnailUrl: hit.thumbnailUrl, channelTitle: hit.secondary,
                           registryState: hit.knownStatus)
    }

    private static func failure(_ response: HTTPResponse) -> SuggestError {
        switch response.status {
        case 403: .forbidden
        // `GlobalExceptionHandler.handleYouTubeSearchRateLimited:292` sends the seconds in the
        // `Retry-After` header; the body leg and the 60 s floor come from the one shared table.
        case 429: .rateLimited(retryAfterSeconds: ApiErrorEnvelope.retryAfterSeconds(response))
        default: .server(status: response.status)
        }
    }

    // MARK: - Query encoding

    /// `urlQueryAllowed` MINUS the five characters a servlet container would read as structure.
    /// `+` is the one that matters: Foundation's own query encoder leaves it LITERAL (it is a member
    /// of `urlQueryAllowed`), and Tomcat decodes a literal `+` in a query string as a SPACE — so
    /// plain `URL.appending(queryItems:)` silently mutates both of this endpoint's inputs. The page
    /// token is OPAQUE (`YouTubeGateway.encodePageToken:551` hands back a YouTube continuation URL
    /// verbatim) and `q` is the user's own text, so neither may be altered in transit. Encoding
    /// here, rather than trimming or rejecting, is the same rule the no-truncation assertion states.
    private static let queryValueAllowed =
        CharacterSet.urlQueryAllowed.subtracting(CharacterSet(charactersIn: "+&=?#"))

    private static func url(_ path: URL, _ query: [URLQueryItem]) -> URL {
        guard var components = URLComponents(url: path, resolvingAgainstBaseURL: false) else {
            return path.appending(queryItems: query)
        }
        components.percentEncodedQueryItems = query.map {
            URLQueryItem(name: $0.name,
                         value: $0.value?.addingPercentEncoding(withAllowedCharacters: queryValueAllowed))
        }
        return components.url ?? path.appending(queryItems: query)
    }
}
