import FitrahAPI
import Foundation
import InnerTubeKit

/// The three registry collections a submission can live in. The rawValue is the PATH segment
/// (`api/admin/registry/{channels|playlists|videos}`, `RegistryController.java:37,203,526,903`);
/// the wire's own `type` field is the SINGULAR uppercase form, which `fromWire` maps.
nonisolated enum SubmissionType: String, Sendable {
    case channels, playlists, videos

    /// `PendingApprovalDto.type` is "CHANNEL" | "PLAYLIST" | "VIDEO" (`ApprovalDtos.kt:12`).
    /// **nil for anything else, and the row is dropped**: every submitter action puts this type in
    /// the path, so a guessed type would point Edit and Delete at another collection's document.
    static func fromWire(_ raw: String?) -> SubmissionType? {
        switch raw?.uppercased() {
        case "CHANNEL": .channels
        case "PLAYLIST": .playlists
        case "VIDEO": .videos
        default: nil
        }
    }
}

/// FOUR values. The OpenAPI `status` query enum lists only three
/// (`api-specification.yaml:1929-1933`); the DTO and the adapter carry the fourth
/// (`ApprovalDtos.kt:21`, `PendingApprovalDto.java:66`).
nonisolated enum SubmissionStatus: String, Sendable, CaseIterable {
    case pending = "PENDING", approved = "APPROVED", rejected = "REJECTED", requestChanges = "REQUEST_CHANGES"

    /// An unknown value reads as `.pending` — Android's `else ->` arm
    /// (`MySubmissionAdapter.kt:80`). It is the safe arm twice over: a row this build cannot name
    /// is still awaiting *something*, and `.pending` is the arm that keeps the row manageable, so
    /// the server adjudicates the disagreement with a 409 instead of the client hiding an
    /// affordance the user is entitled to.
    static func fromWire(_ raw: String?) -> SubmissionStatus { SubmissionStatus(rawValue: raw ?? "") ?? .pending }

    var labelKey: String {
        switch self {
        case .pending: "my_submissions_status_pending"
        case .approved: "my_submissions_status_approved"
        case .rejected: "my_submissions_status_rejected"
        case .requestChanges: "my_submissions_status_request_changes"
        }
    }

    var symbolName: String {
        switch self {
        case .pending: "clock"
        case .approved: "checkmark.circle.fill"
        case .rejected: "xmark.circle.fill"
        case .requestChanges: "exclamationmark.triangle.fill"
        }
    }

    /// `RegistryController.SUBMITTER_OWNED_STATUSES` (`:44`) — the only two statuses the submitter
    /// may still edit or delete. An adjudicated row shows no kebab at all rather than a disabled
    /// one that would 409 (RULING 28).
    var isManageable: Bool { self == .pending || self == .requestChanges }
}

/// One row of My Submissions. Ruling F1: only the fields this screen renders — the DTO also carries
/// `entityId`, `category`, `youtubeId`, `submittedBy*`, `rejectionReason`, `source` and a free-form
/// `metadata` map, and nothing here reads any of them.
nonisolated struct Submission: Sendable, Equatable, Identifiable {
    var id: String
    var type: SubmissionType
    var title: String?
    var thumbnailUrl: String?
    var status: SubmissionStatus
    var submitterNote: String?
    /// The ADMIN's note back to the submitter, not the submitter's own. On the wire since
    /// `PendingApprovalDto.java:84`, populated on every submissions path by
    /// `ApprovalService.enrichWithStatusFields` (`:847-853`). Fix round 1 / I3: the brief's field
    /// list omitted it, which left the one status that asks the user to ACT unable to say what to
    /// change. Additive, so no consumer of `Submission` had to move.
    var reviewNotes: String?
    var submittedAt: Date?

    /// The note to render, or nil — Android's gate, verbatim (`MySubmissionAdapter.kt:107`:
    /// `status == "REQUEST_CHANGES" && !reviewNotes.isNullOrBlank()`). It lives here rather than in
    /// the row view so the rule is pinned by a test instead of by a `#Preview`: an approved row
    /// carries the note that BOUNCED it, and showing that under a green "Approved" pill would read
    /// as a fresh objection. Blank-not-empty, because `isNullOrBlank` trims.
    var reviewNoteToShow: String? {
        guard status == .requestChanges, let notes = reviewNotes,
              !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return notes
    }
}

nonisolated struct SubmissionPage: Sendable, Equatable {
    var items: [Submission]
    var nextCursor: String?
}

/// Hand-written `/api/admin/approvals/my-submissions` + the three submitter-owned registry writes,
/// over the shared `HTTPTransport` (ruling F1). Same shape as `AccountClient`/`SyncClient`; the
/// transport it is given is `AuthorizedTransport`, which is what puts the Bearer on every request.
///
/// It is hand-written for TWO shape traps the generated client cannot express:
///
///  1. **the array key is `data`, not `items`** (`CursorPageDto.java:23`) — Android renames it with
///     `@Json(name = "data")` (`ApprovalDtos.kt:30-32`), and a client that reads `items` gets an
///     empty page with no error at all;
///  2. **`submittedAt` is a Firestore `Timestamp`** (`PendingApprovalDto.java:41` declares
///     `com.google.cloud.Timestamp`, which Jackson serialises as the bean `{seconds,nanos}`).
///
/// Errors are `AccountError`, the same table Task 7 established — the endpoints share the 429 shape
/// and the 403 account-lifecycle envelope (which `AuthorizedTransport` turns into a session event
/// before this client ever sees the body).
nonisolated struct ApprovalsClient: Sendable {
    private let transport: any HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    // MARK: - Operations

    /// `GET api/admin/approvals/my-submissions?status&cursor&limit` (`ApprovalController.java:156`).
    /// A nil `status` is OMITTED, never sent empty: the controller 400s on a value it cannot parse,
    /// and "omit for everything" is its documented default — which is also the branch that does NOT
    /// paginate (`ApprovalService.getMySubmissions:496,513` routes it to `getMySubmissionsAllStatuses`,
    /// which takes no cursor and answers `nextCursor = null`). The cursor parameter is still real:
    /// every SINGLE-status branch pages properly, which is what a status filter would ask for.
    func mySubmissions(status: String?, cursor: String?, limit: Int) async throws(AccountError) -> SubmissionPage {
        var items: [URLQueryItem] = []
        if let status, !status.isEmpty { items.append(URLQueryItem(name: "status", value: status)) }
        if let cursor, !cursor.isEmpty { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        items.append(URLQueryItem(name: "limit", value: String(limit)))

        let response = try await send("GET", baseURL.appending(path: "api/admin/approvals/my-submissions")
                                                    .appending(queryItems: items))
        guard response.status == 200 else { throw Self.failure(response) }
        // A 200 that is not a page at all is a FAILURE, never an empty list: the empty list is a
        // real answer this screen renders as its empty state, and the two must not look alike.
        guard let page = try? JSONDecoder().decode(PageBody.self, from: response.body) else {
            throw AccountError.unknown(status: response.status)
        }
        return SubmissionPage(items: (page.data ?? []).compactMap(Self.submission),
                              nextCursor: page.pageInfo?.nextCursor)
    }

    /// `POST api/admin/registry/{channels|playlists|videos}` (`RegistryController.java:203,526,903`).
    /// 201 on a new row, 200 on a re-submit of an admin-bounced (`REQUEST_CHANGES`) one; 409 when
    /// the youtubeId is already in the registry under somebody else's submission.
    ///
    /// **`status` is always `PENDING`, and is not a parameter.** Task 25 sent neither it nor the
    /// categories, and `normalizeStatusAndApprovedBy` (`:128-152`) defaults an ADMIN's own POST to
    /// `APPROVED` with `approvedBy = self` — so an admin suggesting content from this app published
    /// an uncategorised row with no review at all. Every suggestion enters the approval flow
    /// regardless of the caller's role: that is the product rule, and the controller honours an
    /// explicit `PENDING` (`VALID_STATUSES` at `:40` contains it, and the `APPROVED` branch at
    /// `:148` is the only one that stamps `approvedBy`). One value, so a parameter would only offer
    /// callers a way to get it wrong.
    ///
    /// `categoryIds` is likewise mandatory here: an APPROVED row with none is invisible to every
    /// public category filter, and the submitter is the one person who knows which category it
    /// belongs in (Android's sheet requires the same pick, `SubmitContentBottomSheet.kt:100-101`).
    func submit(type: SubmissionType, youtubeId: String, note: String?,
                categoryIds: [String]) async throws(AccountError) {
        let body = try encode(SubmitBody(youtubeId: youtubeId, submitterNote: note,
                                         categoryIds: categoryIds, status: "PENDING"))
        let response = try await send("POST", baseURL.appending(path: "api/admin/registry/\(type.rawValue)"),
                                      body: body)
        guard response.status == 200 || response.status == 201 else { throw Self.failure(response) }
    }

    /// `PATCH …/{id}/submitter-note` (`RegistryController.java:403,698,1098`) — 204, deliberately
    /// with no body (the response would carry the note back as null, `WRITE_ONLY`).
    func updateNote(type: SubmissionType, id: String, note: String) async throws(AccountError) {
        let body = try encode(NoteBody(submitterNote: note))
        let response = try await send("PATCH", url(type, id, "submitter-note"), body: body)
        guard response.status == 204 || response.status == 200 else { throw Self.failure(response) }
    }

    /// `DELETE …/{id}/submission` (`RegistryController.java:445,732`) — the SUBMITTER's delete, not
    /// the admin `DELETE …/{id}`: it refuses (409) once the row has been adjudicated.
    func deleteSubmission(type: SubmissionType, id: String) async throws(AccountError) {
        let response = try await send("DELETE", url(type, id, "submission"))
        guard response.status == 204 || response.status == 200 else { throw Self.failure(response) }
    }

    // MARK: - Wire

    private struct SubmitBody: Encodable {
        let youtubeId: String
        let submitterNote: String?
        let categoryIds: [String]
        let status: String
    }
    private struct NoteBody: Encodable { let submitterNote: String }

    private struct PageBody: Decodable {
        /// **Trap 1.** Not `items`. Optional so a renamed key decodes to an empty page rather than
        /// throwing — a decode error would name neither the key nor the shape that shipped.
        let data: [RowBody]?
        let pageInfo: PageInfoBody?
        struct PageInfoBody: Decodable { let nextCursor: String? }
    }

    private struct RowBody: Decodable {
        let id: String
        let type: String?
        let title: String?
        let thumbnailUrl: String?
        let status: String?
        let submitterNote: String?
        let reviewNotes: String?
        let submittedAt: FirestoreTimestamp?
    }

    /// `appending(component:)` for the id, not `appending(path:)`: a `/` inside a document id is
    /// escaped rather than read as another path segment (`SyncClient.url`'s reason).
    private func url(_ type: SubmissionType, _ id: String, _ leaf: String) -> URL {
        baseURL.appending(path: "api/admin/registry/\(type.rawValue)")
            .appending(component: id)
            .appending(path: leaf)
    }

    private func send(_ method: String, _ url: URL, body: Data? = nil) async throws(AccountError) -> HTTPResponse {
        var headers = ["X-Device-Id": deviceId.value]
        if body != nil { headers["Content-Type"] = "application/json" }
        do {
            return try await transport.send(HTTPRequest(method: method, url: url, headers: headers, body: body))
        } catch {
            // As in `AccountClient.send`: everything the transport can throw is "the request did
            // not happen" to this caller, cancellation included.
            throw AccountError.network
        }
    }

    private func encode(_ body: some Encodable) throws(AccountError) -> Data {
        guard let data = try? JSONEncoder().encode(body) else { throw AccountError.unknown(status: 0) }
        return data
    }

    /// The status table. Only TWO statuses mean anything specific here — 409 (the row left
    /// `{PENDING, REQUEST_CHANGES}` while the sheet was open, or the youtubeId is already in the
    /// registry) and 429 — because those are the only two the UI can say something useful about.
    /// A 403 is "not your submission", which is not an instruction to the user either, so it takes
    /// the same generic arm as a 500.
    /// nil for a row whose `type` this build cannot name — see `SubmissionType.fromWire`.
    private static func submission(_ row: RowBody) -> Submission? {
        guard let type = SubmissionType.fromWire(row.type) else { return nil }
        return Submission(id: row.id, type: type, title: row.title, thumbnailUrl: row.thumbnailUrl,
                          status: .fromWire(row.status), submitterNote: row.submitterNote,
                          reviewNotes: row.reviewNotes, submittedAt: row.submittedAt?.date)
    }

    private static func failure(_ response: HTTPResponse) -> AccountError {
        switch response.status {
        case 409: .conflict
        case 429: .rateLimited(retryAfterSeconds: ApiErrorEnvelope.retryAfterSeconds(response))
        default: .unknown(status: response.status)
        }
    }
}

/// **Trap 2**, and the reason `Submission.submittedAt` cannot be a plain `Date`.
/// `PendingApprovalDto.submittedAt` is a `com.google.cloud.Timestamp`, which Jackson serialises as
/// the BEAN `{"seconds":N,"nanos":N}` — no ISO string, no epoch number. Android carries the same
/// three-way tolerance in `FirestoreTimestampAdapter` (`ApprovalDtos.kt:48-77`) and this mirrors it
/// arm for arm, so the two clients cannot read the same wire value differently:
///
///  - an OBJECT is `seconds + nanos/1e9`;
///  - a plain NUMBER is EPOCH MILLISECONDS. Android's object branch returns
///    `seconds * 1000 + nanos / 1_000_000` and its row formats that as millis, so millis is what
///    "pre-flattened" means to this backend's other client. Guessing by magnitude instead would be
///    a heuristic the two clients could not agree on;
///  - anything else — a string, a bool, a shape nobody has seen — is nil, never a throw. Android
///    `skipValue()`s it, and one unreadable field must not cost the whole page.
///
/// A JSON `null` never reaches this initializer at all: `RowBody.submittedAt` is Optional, so the
/// synthesized decoder answers it with nil.
nonisolated struct FirestoreTimestamp: Decodable, Sendable {
    let date: Date?

    private struct Parts: Decodable { let seconds: Int64?; let nanos: Int64? }

    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let parts = try? container.decode(Parts.self) {
            date = Date(timeIntervalSince1970: Double(parts.seconds ?? 0) + Double(parts.nanos ?? 0) / 1_000_000_000)
        } else if let millis = try? container.decode(Int64.self) {
            date = Date(timeIntervalSince1970: Double(millis) / 1000)
        } else {
            date = nil
        }
    }
}
