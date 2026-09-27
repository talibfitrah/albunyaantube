import FitrahAPI
import Foundation
import InnerTubeKit

nonisolated enum Disposition: String, Sendable {
    case approved = "APPROVED", pending = "PENDING", rejected = "REJECTED", error = "ERROR"

    /// An unknown disposition writes nothing and counts as rejectedOrError, **never** as approved.
    /// Failing open here would put an unreviewed row in a curated list.
    static func fromWire(_ raw: String?) -> Disposition { Disposition(rawValue: raw ?? "") ?? .error }
}

/// The backend's `ContentItemDto` narrowed to what a local row needs — canonical metadata,
/// preferred over the candidate's when present (`YouTubeImportRepository.kt:187-242`).
///
/// **`name` and `title` are BOTH here on purpose.** `ContentItemMapper.fromChannel`
/// (`ContentItemMapper.java:26-36`) sets `name` and leaves `title` null, while `fromPlaylist` and
/// `fromVideo` set `title`; `@JsonInclude(NON_NULL)` then omits the other. Reading only `title`
/// would silently keep YouTube's own copy of every imported channel's name instead of the curated
/// one. Android reads the same pair (`content?.name ?: content?.title`).
nonisolated struct ImportedContent: Decodable, Sendable, Equatable {
    var title: String?
    var name: String?
    var thumbnailUrl: String?
    var channelTitle: String?
    var durationSeconds: Int?
}

nonisolated struct ImportResult: Sendable, Equatable {
    var youtubeId: String
    var type: CandidateType
    var disposition: Disposition
    /// Non-nil ONLY for APPROVED (`ImportDtos.kt:38-46`, `ImportController.java:110-112`).
    var content: ImportedContent?
}

/// `POST /api/account/import/resolve` (`ImportController.java:78`), over the shared `HTTPTransport`
/// — ruling F1's sixth hand-written client, same shape as `AccountClient`/`ApprovalsClient`.
///
/// **The YouTube bearer never touches this client.** Only resolved ids and their metadata go to the
/// backend; the OAuth token is a credential for googleapis.com and is not the backend's business.
/// The transport this is given is `AuthorizedTransport`, which is what puts the FIREBASE bearer on.
nonisolated struct ImportClient: Sendable {

    /// == the server's `@Size(max = 200)` on `ImportResolveRequest.items`. Chunking is the
    /// PIPELINE's job; this client sends exactly what it is handed.
    static let batchSize = 200

    private let transport: any HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    /// A 429 is the per-user daily item budget — 1 000 items in a sliding 24 h window
    /// (`SubmissionRateLimiter.java:31-32`), rejected all-or-nothing, with the seconds in the
    /// `Retry-After` header (`GlobalExceptionHandler.java:310`).
    func resolve(_ items: [ImportCandidate]) async throws(AccountError) -> [ImportResult] {
        let body = try encode(RequestBody(items: items.map(ItemBody.init)))
        // The device id and the content type, and nothing else. No `Authorization` is set HERE:
        // the Firebase bearer is `AuthorizedTransport`'s to add, and the YouTube one belongs to a
        // different host entirely.
        let headers = ["X-Device-Id": deviceId.value, "Content-Type": "application/json"]
        let response: HTTPResponse
        do {
            response = try await transport.send(
                HTTPRequest(method: "POST", url: baseURL.appending(path: "api/account/import/resolve"),
                            headers: headers, body: body))
        } catch {
            // As in `AccountClient.send`: everything the transport can throw is "the request did
            // not happen" to this caller, cancellation included.
            throw AccountError.network
        }
        guard response.status == 200 else { throw Self.failure(response) }
        // A 200 that is not a results envelope is a FAILURE, never "nothing resolved": an import
        // that quietly wrote nothing is indistinguishable from a clean run the user believed.
        guard let decoded = try? JSONDecoder().decode(ResponseBody.self, from: response.body) else {
            throw AccountError.unknown(status: response.status)
        }
        return decoded.results.compactMap(Self.result)
    }

    // MARK: - Wire

    private struct RequestBody: Encodable { let items: [ItemBody] }

    /// `ImportItem`'s five fields verbatim (`ImportItem.java:19-24`).
    private struct ItemBody: Encodable {
        let type: String
        let youtubeId: String
        let title: String
        let thumbnailUrl: String?
        let channelId: String?

        init(_ candidate: ImportCandidate) {
            type = candidate.type.rawValue
            youtubeId = candidate.youtubeId
            title = candidate.title
            thumbnailUrl = candidate.thumbnailUrl
            channelId = candidate.channelId
        }
    }

    private struct ResponseBody: Decodable { let results: [ResultBody] }

    private struct ResultBody: Decodable {
        let youtubeId: String
        let type: String?
        let disposition: String?
        let content: ImportedContent?
    }

    /// nil for a row whose `type` this build cannot name — the pipeline routes the write by it.
    private static func result(_ row: ResultBody) -> ImportResult? {
        guard let type = CandidateType.fromWire(row.type) else { return nil }
        return ImportResult(youtubeId: row.youtubeId, type: type,
                            disposition: .fromWire(row.disposition), content: row.content)
    }

    private func encode(_ body: some Encodable) throws(AccountError) -> Data {
        guard let data = try? JSONEncoder().encode(body) else { throw AccountError.unknown(status: 0) }
        return data
    }

    /// One status this endpoint says something specific about. Everything else is generic — a 403
    /// here is `AuthorizedTransport`'s account-lifecycle envelope, already turned into a session
    /// event before this client sees the body.
    private static func failure(_ response: HTTPResponse) -> AccountError {
        switch response.status {
        case 429: .rateLimited(retryAfterSeconds: ApiErrorEnvelope.retryAfterSeconds(response))
        default: .unknown(status: response.status)
        }
    }
}
