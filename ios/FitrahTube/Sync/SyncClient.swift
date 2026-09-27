import FitrahAPI
import Foundation
import InnerTubeKit

/// The seam Task 23's tests script. `SyncClient` is the one live conformer -- a bare struct cannot
/// be substituted, and `SyncManager` needs to drive every status path without a network.
///
/// `cursors` and `ids` are keyed by `SyncEntityType.rawValue`, which is what `SyncState.entityType`
/// stores and what `SyncDecisions.page` compares. The `subs` query-name drift lives INSIDE this
/// client (`SyncEntityType.queryName`) and nowhere else, so a caller never has to know that one of
/// the three types is spelled differently on the pull than on the push.
nonisolated protocol SyncTransporting: Sendable {
    func pull(cursors: [String: Int], ids: [String: String?]) async throws -> SyncResponse
    func put(_ type: SyncEntityType, id: String, body: Data) async throws -> (status: Int, dto: SyncRowEcho?)
    func delete(_ type: SyncEntityType, id: String) async throws -> (status: Int, dto: SyncRowEcho?)
}

/// The ARCHIVE ECHO (SYNC-ECHO-01): a PUT that answers `deleted: true` means the server's
/// projection knows a parent was archived, and the row is tombstoned locally rather than merely
/// cleared (`SyncManager.kt:454-458`). Ruling F1 -- the echo body is a whole `SubscriptionSyncDto`
/// (or its siblings) and these are the only two fields anything reads: the archive verdict, and the
/// server timestamp `clearDirty` stamps on the row instead of a local clock.
nonisolated struct SyncRowEcho: Decodable, Sendable { var deleted: Bool; var updatedAt: Int }

nonisolated enum SyncClientError: Error, Equatable {
    /// `GET /api/account/sync` answered something other than 200. Carried rather than collapsed,
    /// because a 401 (the bearer is gone) and a 503 (retry) are different verdicts to the caller.
    case pullStatus(Int)
}

/// Hand-written `/api/account/sync` + the six push endpoints, over the shared `HTTPTransport`
/// (ruling F1). Same shape as `AccountClient`; the transport it is given is `AuthorizedTransport`,
/// which is what puts the Bearer on every request.
nonisolated struct SyncClient: SyncTransporting, Sendable {
    private let transport: any HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    /// `GET api/account/sync?subs&playlists&favorites&subs_id&playlists_id&favorites_id`
    /// (`SyncController.java:41-64`). An absent cursor is sent as the server's own default, 0.
    func pull(cursors: [String: Int], ids: [String: String?]) async throws -> SyncResponse {
        var items: [URLQueryItem] = []
        for type in SyncEntityType.allCases {
            items.append(URLQueryItem(name: type.queryName, value: String(cursors[type.rawValue] ?? 0)))
            // `ids[key]` is a `String??`; the flatten is what makes an explicitly-nil entry and an
            // absent one the same thing -- both mean "no tiebreaker for this type".
            guard let id = ids[type.rawValue] ?? nil, Self.isValidCursorId(id) else { continue }
            items.append(URLQueryItem(name: "\(type.queryName)_id", value: id))
        }
        let response = try await send("GET", baseURL.appending(path: "api/account/sync")
                                                     .appending(queryItems: items))
        guard response.status == 200 else { throw SyncClientError.pullStatus(response.status) }
        return try JSONDecoder().decode(SyncResponse.self, from: response.body)
    }

    /// `PUT api/account/{subscriptions|playlists|favorites}/{id}` (`SyncController.java:86-138`) --
    /// the PATH is the rawValue, NOT the pull's `subs`. The status comes back raw for
    /// `SyncDecisions.push` to classify, and `dto == nil` is its `hasBody: false`.
    func put(_ type: SyncEntityType, id: String, body: Data) async throws -> (status: Int, dto: SyncRowEcho?) {
        let response = try await send("PUT", url(type, id), body: body)
        return (response.status, try? JSONDecoder().decode(SyncRowEcho.self, from: response.body))
    }

    /// `DELETE api/account/{type}/{id}`. The status is returned rather than thrown on: 404 is
    /// `.ok` (an idempotent tombstone) and only `SyncDecisions.push` holds that table. The echo IS
    /// read (Part B gate, Cubic round 1 P1 — Task 22's "a tombstone needs nothing from the body"
    /// was wrong): its `updatedAt` is the server's tombstone time, and a row cleared without it
    /// keeps its LAST PUT's stamp, so a re-add made before the next pull is wiped by the server's
    /// own tombstone (`.applyTombstone`, older local stamp). Android stamps it (`SyncManager.kt:440`).
    func delete(_ type: SyncEntityType, id: String) async throws -> (status: Int, dto: SyncRowEcho?) {
        let response = try await send("DELETE", url(type, id))
        return (response.status, try? JSONDecoder().decode(SyncRowEcho.self, from: response.body))
    }

    // MARK: - Cursor ids

    private static let maxCursorIdBytes = 1500

    /// `SyncController.isValidCursorId` (`:66-82`), mirrored EXACTLY -- including its "empty is
    /// fine" leg. An id this rejects is a guaranteed 400, which would cost the whole three-type
    /// page; dropping it costs only the same-millisecond tiebreaker, and the page re-fetches from
    /// the timestamp alone. Mirrored rather than approximated: a stricter client would silently
    /// stop paginating on ids the server is happy with, and a looser one re-introduces the 400.
    ///
    /// The byte count is UTF-8, not characters (Firestore's own limit), and the control-character
    /// scan runs over unicode scalars -- a surrogate half cannot be < 0x20, so this is the same
    /// verdict Java's UTF-16 `char` loop reaches.
    static func isValidCursorId(_ id: String) -> Bool {
        if id.isEmpty { return true }
        if id.utf8.count > maxCursorIdBytes { return false }
        // Firestore reserves several docId patterns; pre-fix these produced a Firestore-side 500 at
        // `startAfter()` instead of a clean 400.
        if id == "." || id == ".." { return false }
        if id.hasPrefix("__") && id.hasSuffix("__") { return false }
        return !id.unicodeScalars.contains { $0.value == 0x2F || $0.value < 0x20 || $0.value == 0x7F }
    }

    // MARK: - Wire

    /// `appending(component:)`, not `appending(path:)`: a `/` inside an entity id is escaped rather
    /// than read as another path segment.
    private func url(_ type: SyncEntityType, _ id: String) -> URL {
        baseURL.appending(path: "api/account/\(type.path)").appending(component: id)
    }

    private func send(_ method: String, _ url: URL, body: Data? = nil) async throws -> HTTPResponse {
        var headers = ["X-Device-Id": deviceId.value]
        if body != nil { headers["Content-Type"] = "application/json" }
        return try await transport.send(HTTPRequest(method: method, url: url, headers: headers, body: body))
    }
}
