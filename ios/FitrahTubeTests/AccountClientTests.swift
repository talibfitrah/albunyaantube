import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Ruling F1: `AccountClient` decodes only the fields it reads and maps status codes EXPLICITLY —
/// so this file is the decode shape and the status table, pinned against canned bodies.
@Suite(.perTest)
struct AccountClientTests {

    private static let base = URL(string: "https://api.fitrah.test/")!

    private func client(_ responses: [HTTPResponse]) -> (AccountClient, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        return (AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-123")),
                transport)
    }

    private static let meJSON = """
    {"uid":"u1","email":"student@fitrah.test","displayName":"Aisha","dateOfBirth":"2001-04-09",
     "phoneNumber":"+31612345678","status":"active","role":"MODERATOR","profileCompletedAt":"2026-01-01T00:00:00Z"}
    """

    private func decodeBody(_ request: HTTPRequest?) throws -> [String: String] {
        let data = try #require(request?.body)
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: String])
    }

    // MARK: - Decode shape

    /// Every field the app reads, off the backend's real `AccountMeResponse` shape — including the
    /// `profileCompletedAt` it deliberately does NOT model. `role` is lowercased
    /// (`AccountRepositoryImpl.kt:223`), which is what makes `isModerator` work off a wire "MODERATOR".
    @Test func meDecodesEveryFieldTheAppReads() async throws {
        let (client, transport) = self.client([.json(200, Self.meJSON)])
        let me = try await client.me()
        #expect(me == AccountMe(uid: "u1", email: "student@fitrah.test", displayName: "Aisha",
                                dateOfBirth: "2001-04-09", phoneNumber: "+31612345678",
                                status: .active, role: "moderator"))
        #expect(me.isModerator)
        #expect(transport.sent.first?.url.path() == "/api/account/me")
        #expect(transport.sent.first?.method == "GET")
    }

    /// Unknown -> `.blocked`, deliberately (`AccountStatus.kt:14-27`): BLOCKED drops the user to
    /// guest, where PENDING_PROFILE would trap them in a bootstrap form the backend 409s on re-entry.
    /// A missing `role` reads as "user".
    @Test func anUnknownStatusReadsAsBlockedAndAMissingRoleAsUser() async throws {
        let (client, _) = self.client([.json(200, #"{"uid":"u1","status":"quarantined"}"#)])
        let me = try await client.me()
        #expect(me.status == .blocked)
        #expect(me.role == "user")
        #expect(!me.isModerator)
        #expect(AccountStatus.fromWire(nil) == .blocked)
        #expect(AccountStatus.fromWire("PENDING_PROFILE") == .pendingProfile)
    }

    // MARK: - Request shapes

    /// `POST /api/account/profile` sends exactly the three fields `CompleteProfileRequest` declares.
    @Test func completeProfileSendsExactlyTheThreeFields() async throws {
        let (client, transport) = self.client([.json(200, Self.meJSON)])
        _ = try await client.completeProfile(displayName: "Aisha", dateOfBirth: "2001-04-09",
                                             phoneNumber: "+31612345678")
        let request = transport.sent.first
        #expect(request?.method == "POST")
        #expect(request?.url.path() == "/api/account/profile")
        #expect(try decodeBody(request) == ["displayName": "Aisha", "dateOfBirth": "2001-04-09",
                                            "phoneNumber": "+31612345678"])
    }

    /// `PUT /api/account/profile` OMITS a nil field rather than sending JSON null — the backend's
    /// partial update treats an absent key as "no change" and an explicit null as a clear.
    @Test func updateProfileOmitsNilFieldsRatherThanSendingNull() async throws {
        let (client, transport) = self.client([.json(200, Self.meJSON)])
        _ = try await client.updateProfile(displayName: "Aisha", dateOfBirth: nil, phoneNumber: nil)
        #expect(transport.sent.first?.method == "PUT")
        #expect(try decodeBody(transport.sent.first) == ["displayName": "Aisha"])
    }

    /// Every request carries `X-Device-Id`, on all five operations.
    @Test func everyRequestCarriesTheDeviceIdHeader() async throws {
        let (client, transport) = self.client([
            .json(200, Self.meJSON), .json(200, Self.meJSON), .json(200, Self.meJSON),
            .json(200, "{}"), HTTPResponse(status: 204, headers: [:], body: Data())
        ])
        _ = try await client.me()
        _ = try await client.completeProfile(displayName: "a", dateOfBirth: "2001-04-09", phoneNumber: "+31612345678")
        _ = try await client.updateProfile(displayName: "a", dateOfBirth: nil, phoneNumber: nil)
        try await client.sendVerificationEmail()
        try await client.deleteAccount()
        #expect(transport.sent.count == 5)
        #expect(transport.sent.allSatisfy { $0.headers["X-Device-Id"] == "dev-123" })
        #expect(transport.sent.map { $0.url.path() } == ["/api/account/me", "/api/account/profile",
                                                     "/api/account/profile",
                                                     "/api/account/send-verification-email",
                                                     "/api/account/me"])
    }

    // MARK: - The status table

    /// `AccountRepositoryImpl.kt:226-259`: the tighter `"code"\s*:\s*"X"` match. The pre-fix
    /// `contains` shape matched `validationField: "AGE_INELIGIBLE_input"` and misrouted the user.
    @Test func theAgeIneligibleCodeMatchesOnlyTheCodeField() async throws {
        let (matching, _) = client([.json(422, #"{"code" : "AGE_INELIGIBLE","message":"13+"}"#)])
        await #expect(throws: AccountError.ageIneligible) {
            _ = try await matching.completeProfile(displayName: "a", dateOfBirth: "2020-01-01", phoneNumber: "+3161")
        }
        // The same text in another field is NOT the code: this must fall through to validation.
        let (decoy, _) = client([.json(422, #"{"validationField":"AGE_INELIGIBLE_input","message":"dateOfBirth: bad"}"#)])
        await #expect(throws: AccountError.validation(field: "dateOfBirth", message: "bad")) {
            _ = try await decoy.completeProfile(displayName: "a", dateOfBirth: "2020-01-01", phoneNumber: "+3161")
        }
    }

    /// 429: the body's `retryAfterSeconds` first, then the `Retry-After` header, then 60.
    @Test func rateLimitedPrefersTheBodyThenTheHeaderThenSixty() async throws {
        let (fromBody, _) = client([.json(429, #"{"code":"RATE_LIMITED","retryAfterSeconds":15}"#)])
        await #expect(throws: AccountError.rateLimited(retryAfterSeconds: 15)) {
            try await fromBody.sendVerificationEmail()
        }
        let (fromHeader, _) = client([.json(429, #"{"code":"RATE_LIMITED"}"#, headers: ["Retry-After": "30"])])
        await #expect(throws: AccountError.rateLimited(retryAfterSeconds: 30)) {
            try await fromHeader.sendVerificationEmail()
        }
        let (fallback, _) = client([.json(429, "{}")])
        await #expect(throws: AccountError.rateLimited(retryAfterSeconds: 60)) {
            try await fallback.sendVerificationEmail()
        }
    }

    /// `AccountUpdateRepository.kt:81-91`: only `displayName`/`dateOfBirth`/`phoneNumber` are honoured
    /// as a field prefix, so an unrelated colon ("Error: HTTP 500") is not read as a field name.
    @Test func aValidationMessageHonoursOnlyTheThreeKnownFieldNames() async throws {
        let (known, _) = client([.json(400, #"{"code":"VALIDATION","message":"displayName: too long"}"#)])
        await #expect(throws: AccountError.validation(field: "displayName", message: "too long")) {
            _ = try await known.updateProfile(displayName: "x", dateOfBirth: nil, phoneNumber: nil)
        }
        let (unknown, _) = client([.json(400, #"{"code":"BAD_REQUEST","message":"Error: HTTP 500"}"#)])
        await #expect(throws: AccountError.validation(field: nil, message: "Error: HTTP 500")) {
            _ = try await unknown.updateProfile(displayName: "x", dateOfBirth: nil, phoneNumber: nil)
        }
    }

    /// `POST /profile` 403 `EMAIL_NOT_VERIFIED` (`AccountController.java:76-81`) and 409
    /// `PROFILE_ALREADY_COMPLETED` — the bootstrap form's two terminal answers.
    @Test func completeProfileMapsEmailNotVerifiedAndAlreadyCompleted() async throws {
        let (unverified, _) = client([.json(403, #"{"code":"EMAIL_NOT_VERIFIED","message":"Verify your email first"}"#)])
        await #expect(throws: AccountError.emailNotVerified) {
            _ = try await unverified.completeProfile(displayName: "a", dateOfBirth: "2001-04-09", phoneNumber: "+3161")
        }
        let (done, _) = client([.json(409, #"{"code":"PROFILE_ALREADY_COMPLETED"}"#)])
        await #expect(throws: AccountError.profileAlreadyCompleted) {
            _ = try await done.completeProfile(displayName: "a", dateOfBirth: "2001-04-09", phoneNumber: "+3161")
        }
    }

    /// `DELETE /api/account/me` (`AccountController.java:214-220`): 204 on both the first call and an
    /// idempotent retry, 409 for the last admin, 403 for the account-lifecycle envelope.
    @Test func deleteAccountMapsItsThreeStatuses() async throws {
        let (ok, transport) = client([HTTPResponse(status: 204, headers: [:], body: Data())])
        try await ok.deleteAccount()
        #expect(transport.sent.first?.method == "DELETE")

        let (lastAdmin, _) = client([.json(409, #"{"code":"LAST_ADMIN"}"#)])
        await #expect(throws: AccountError.lastAdmin) { try await lastAdmin.deleteAccount() }

        let (gone, _) = client([.json(403, #"{"code":"ACCOUNT_DELETED"}"#)])
        await #expect(throws: AccountError.deletedAccount) { try await gone.deleteAccount() }
    }

    /// A transport that throws is `.network`, never a status; an unmapped status keeps its number so
    /// the caller can log it.
    @Test func aTransportFailureIsNetworkAndAnUnmappedStatusKeepsItsNumber() async throws {
        let (offline, _) = client([.failing(URLError(.notConnectedToInternet))])
        await #expect(throws: AccountError.network) { _ = try await offline.me() }

        let (broken, _) = client([.json(500, #"{"code":"LAZY_CREATE_FAILED"}"#)])
        await #expect(throws: AccountError.unknown(status: 500)) { _ = try await broken.me() }
    }
}
