import FitrahAPI
import Foundation
import InnerTubeKit

/// Client-side mirror of the backend's `UserStatus`; the wire form is `AccountMeResponse.status`.
nonisolated enum AccountStatus: String, Sendable, CaseIterable {
    case active = "active", pendingProfile = "pending_profile", blocked = "blocked", deleted = "deleted"

    /// Unknown -> `.blocked`, deliberately (`AccountStatus.kt:14-27`): BLOCKED drops the user to
    /// guest, where PENDING_PROFILE would trap them in a bootstrap form the backend 409s on
    /// re-entry (`AccountController.java` -> `ProfileAlreadyCompletedException`).
    static func fromWire(_ raw: String?) -> AccountStatus {
        AccountStatus(rawValue: raw?.lowercased() ?? "") ?? .blocked
    }
}

/// The caller's account, reduced to the fields the app renders and decides on. The backend also
/// sends `profileCompletedAt`; nothing reads it, so nothing decodes it (ruling F1).
nonisolated struct AccountMe: Sendable, Equatable {
    var uid: String
    var email: String?
    var displayName: String?
    /// ISO `yyyy-MM-dd` — the wire shape, kept as text. Parsing it into a `Date` here would invent
    /// a time zone the backend never had (`AccountMeResponse.java` builds it at `ZoneOffset.UTC`).
    var dateOfBirth: String?
    var phoneNumber: String?
    var status: AccountStatus
    /// Lowercased (`AccountRepositoryImpl.kt:223`); absent reads as "user".
    var role: String
    /// Case-INSENSITIVE (`MeFragment.kt:270-271`, `ignoreCase = true`). `decode` already lowercases
    /// what the wire sent, but an `AccountMe` built anywhere else (a test, a future local
    /// construction) is not covered by that, and the role gate deciding on capitalisation is the
    /// kind of defect that only shows up as a moderator with a three-item kebab.
    var isModerator: Bool { role.lowercased() == "moderator" || role.lowercased() == "admin" }
}

nonisolated enum AccountError: Error, Equatable {
    case ageIneligible                          // 422 + {"code":"AGE_INELIGIBLE"}
    case profileAlreadyCompleted                // 409 on POST /profile
    case rateLimited(retryAfterSeconds: Int)    // 429; body retryAfterSeconds -> Retry-After -> 60
    case validation(field: String?, message: String)   // 400/422 "<field>: <reason>"
    case emailNotVerified                       // 403 {"code":"EMAIL_NOT_VERIFIED"} on POST /profile
    case blocked, deletedAccount                // the 403 account-lifecycle envelope
    case lastAdmin                              // 409 on DELETE /me
    case network, unknown(status: Int)
}

/// The ONE `"code": "X"` matcher, shared with `AuthorizedTransport`'s 403 envelope check.
/// `AccountRepositoryImpl.kt:226-259`: the pre-fix shape (`contains("\"code\"")` AND
/// `contains("\"X\"")`) also matched `validationField: "AGE_INELIGIBLE_input"` and misrouted the
/// user, so this pins the exact key/value pair. The byte cap belongs to the CALLER — 1 KiB for the
/// transport's envelope peek, 4 KiB for an error body — because the two have different budgets.
nonisolated enum ApiErrorEnvelope {
    static func hasCode(_ code: String, in body: Data) -> Bool {
        guard let regex = try? Regex("\"code\"\\s*:\\s*\"\(code)\"") else { return false }
        return String(decoding: body, as: UTF8.self).contains(regex)
    }
}

/// Hand-written `/api/account/*` over the shared `HTTPTransport` (ruling F1: explicit status
/// semantics, decoding only the fields it reads). Same shape as `ReportClient`/`IndexClient`; the
/// transport it is given is `AuthorizedTransport`, which is what puts the Bearer on every request.
nonisolated struct AccountClient: Sendable {
    private let transport: any HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: any HTTPTransport, baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    // MARK: - Operations

    /// `GET /api/account/me` (`AccountController.java:136`). 200 is the only success — the backend
    /// lazily creates the row, so there is no 404 leg for a first-time caller.
    func me() async throws(AccountError) -> AccountMe {
        let response = try await send("GET", "api/account/me")
        guard response.status == 200 else { throw failure(response) }
        return try decode(response)
    }

    /// `POST /api/account/profile` (`AccountController.java:69`) — the bootstrap form.
    func completeProfile(displayName: String, dateOfBirth: String,
                         phoneNumber: String) async throws(AccountError) -> AccountMe {
        let body = try encode(CompleteProfileBody(displayName: displayName, dateOfBirth: dateOfBirth,
                                                  phoneNumber: phoneNumber))
        let response = try await send("POST", "api/account/profile", body: body)
        guard response.status == 200 else {
            throw failure(response) { status, peek -> AccountError? in
                switch status {
                case 403 where ApiErrorEnvelope.hasCode("EMAIL_NOT_VERIFIED", in: peek): .emailNotVerified
                case 409: .profileAlreadyCompleted
                default: nil
                }
            }
        }
        return try decode(response)
    }

    /// `PUT /api/account/profile` (`AccountController.java:92`) — partial update; a nil field is
    /// OMITTED, never sent as JSON null, so "no change" cannot be read as "clear this".
    func updateProfile(displayName: String?, dateOfBirth: String?,
                       phoneNumber: String?) async throws(AccountError) -> AccountMe {
        let body = try encode(UpdateProfileBody(displayName: displayName, dateOfBirth: dateOfBirth,
                                                phoneNumber: phoneNumber))
        let response = try await send("PUT", "api/account/profile", body: body)
        guard response.status == 200 else { throw failure(response) }
        return try decode(response)
    }

    /// `POST /api/account/send-verification-email` (`AccountController.java:106`). 200 covers both
    /// "sent" and "already verified" — neither is an error to the caller.
    func sendVerificationEmail() async throws(AccountError) {
        let response = try await send("POST", "api/account/send-verification-email")
        guard response.status == 200 else { throw failure(response) }
    }

    /// `DELETE /api/account/me` (`AccountController.java:214`). 204 on both the first call and an
    /// idempotent retry; 409 is `LastAdminException`.
    func deleteAccount() async throws(AccountError) {
        let response = try await send("DELETE", "api/account/me")
        guard response.status == 204 else {
            throw failure(response) { status, _ -> AccountError? in status == 409 ? .lastAdmin : nil }
        }
    }

    // MARK: - Wire

    private struct CompleteProfileBody: Encodable { let displayName, dateOfBirth, phoneNumber: String }
    /// Optional properties are synthesised as `encodeIfPresent`, so a nil is absent from the JSON.
    private struct UpdateProfileBody: Encodable { let displayName, dateOfBirth, phoneNumber: String? }

    private struct MeBody: Decodable {
        let uid: String
        let email, displayName, dateOfBirth, phoneNumber, status, role: String?
    }

    /// `MAX_ERROR_BODY_BYTES` (`AccountRepositoryImpl.kt:259`): a misbehaving server returning a
    /// multi-MB error body must not be read whole.
    private static let maxErrorBodyBytes = 4096
    private static let knownValidationFields = ["displayName", "dateOfBirth", "phoneNumber"]

    private func send(_ method: String, _ path: String, body: Data? = nil) async throws(AccountError) -> HTTPResponse {
        var headers = ["X-Device-Id": deviceId.value]
        if body != nil { headers["Content-Type"] = "application/json" }
        do {
            return try await transport.send(HTTPRequest(method: method, url: baseURL.appending(path: path),
                                                        headers: headers, body: body))
        } catch {
            // Everything the transport can throw is "the request did not happen" to this caller,
            // cancellation included: `throws(AccountError)` has no third outcome, and a cancelled
            // screen is gone before it renders anything.
            throw AccountError.network
        }
    }

    private func encode(_ body: some Encodable) throws(AccountError) -> Data {
        guard let data = try? JSONEncoder().encode(body) else { throw AccountError.unknown(status: 0) }
        return data
    }

    private func decode(_ response: HTTPResponse) throws(AccountError) -> AccountMe {
        guard let body = try? JSONDecoder().decode(MeBody.self, from: response.body) else {
            throw AccountError.unknown(status: response.status)
        }
        return AccountMe(uid: body.uid, email: body.email, displayName: body.displayName,
                         dateOfBirth: body.dateOfBirth, phoneNumber: body.phoneNumber,
                         status: .fromWire(body.status), role: (body.role ?? "user").lowercased())
    }

    /// The ONE status table. `own` is the endpoint's own codes and is consulted FIRST, because the
    /// same status means different things per endpoint — 409 is `profileAlreadyCompleted` on
    /// `POST /profile` and `lastAdmin` on `DELETE /me`, and only the endpoint knows which.
    private func failure(_ response: HTTPResponse,
                         _ own: (Int, Data) -> AccountError? = { _, _ in nil }) -> AccountError {
        let peek = response.body.prefix(Self.maxErrorBodyBytes)
        if let own = own(response.status, peek) { return own }
        switch response.status {
        case 403 where ApiErrorEnvelope.hasCode("ACCOUNT_BLOCKED", in: peek): return .blocked
        case 403 where ApiErrorEnvelope.hasCode("ACCOUNT_DELETED", in: peek): return .deletedAccount
        case 422 where ApiErrorEnvelope.hasCode("AGE_INELIGIBLE", in: peek): return .ageIneligible
        case 429: return .rateLimited(retryAfterSeconds: retryAfterSeconds(response, peek))
        case 400, 422: return validationFailure(peek)
        default: return .unknown(status: response.status)
        }
    }

    private func retryAfterSeconds(_ response: HTTPResponse, _ peek: Data) -> Int {
        struct Body: Decodable { let retryAfterSeconds: Int? }
        if let seconds = (try? JSONDecoder().decode(Body.self, from: peek))?.retryAfterSeconds { return seconds }
        if let header = response.header("Retry-After"), let seconds = Int(header) { return seconds }
        return 60
    }

    /// `"<field>: <reason>"` (`ProfileValidationException` -> `AccountUpdateRepository.kt:81-91`),
    /// honouring ONLY the three field names the backend can prefix, so an unrelated colon
    /// ("Error: HTTP 500") is not read as a field. Android defaults those to "displayName", which
    /// points the error at an input the user never touched; iOS reports no field instead and shows
    /// the whole message.
    private func validationFailure(_ peek: Data) -> AccountError {
        struct Body: Decodable { let message: String? }
        let raw = (try? JSONDecoder().decode(Body.self, from: peek))?.message ?? "Validation failed"
        guard let separator = raw.range(of: ": ") else { return .validation(field: nil, message: raw) }
        let field = String(raw[raw.startIndex..<separator.lowerBound])
        guard Self.knownValidationFields.contains(field) else { return .validation(field: nil, message: raw) }
        return .validation(field: field,
                           message: String(raw[separator.upperBound...]).trimmingCharacters(in: .whitespaces))
    }
}
