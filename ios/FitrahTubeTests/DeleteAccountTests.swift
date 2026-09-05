import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// `DELETE /api/account/me` -> 204 -> wipe -> Firebase `delete()` -> guest, and the three refusals
/// that must leave the device exactly as it was. CF-G-5 lives here: Android runs the post-204
/// cleanup in `viewModelScope`, so leaving the screen while the response is in flight keeps every
/// local row of an account that no longer exists.
@Suite(.perTest)
struct DeleteAccountTests {

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let meJSON = #"{"uid":"fake-uid","email":"student@fitrah.test","status":"active","role":"user"}"#

    /// What the wipe saw when it ran. `wasCancelled` is CF-G-5's whole point: an inline cleanup
    /// inherits the caller's cancellation, a detached one cannot. The other two pin the order —
    /// the wipe runs before Firebase is asked for anything and before the session is dropped.
    nonisolated struct WipeObservation: Sendable, Equatable {
        var wasCancelled: Bool
        var authOperations: [FakeAuthClient.Operation]
        var wasSignedOut: Bool
    }

    @MainActor final class WipeSpy {
        private(set) var observations: [WipeObservation] = []
        var session: AccountSession?
        var auth: FakeAuthClient?
        var count: Int { observations.count }

        func record() {
            observations.append(WipeObservation(wasCancelled: Task.isCancelled,
                                                authOperations: auth?.operations ?? [],
                                                wasSignedOut: session?.state == .signedOut))
        }
    }

    private struct Fixture {
        let model: DeleteAccountViewModel
        let session: AccountSession
        let auth: FakeAuthClient
        let status: AccountStatusCenter
        let transport: ScriptedTransport
        let wipes: WipeSpy
        let google: FakeOAuthProvider
    }

    /// `responses` are consumed in order: the `/me` that loads the account, then the DELETE.
    ///
    /// Stage 4 / I3: the fixture account is a PASSWORD account (`FakeAuthClient.defaultUser`), so
    /// every test that expects the DELETE to go out fills `model.password` first — the confirm now
    /// re-authenticates before it sends anything.
    private func makeFixture(delete response: HTTPResponse,
                             user: AuthUser = FakeAuthClient.defaultUser,
                             google: FakeOAuthProvider = FakeOAuthProvider()) -> Fixture {
        let auth = FakeAuthClient(state: .signedOut, user: user)
        let transport = ScriptedTransport([.json(200, Self.meJSON), response])
        let status = AccountStatusCenter()
        let wipes = WipeSpy()
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [], status: status,
                                     sleep: { _ in }, wipe: { [wipes] in wipes.record(); return nil })
        wipes.session = session
        wipes.auth = auth
        let model = DeleteAccountViewModel(account: account, session: session, auth: auth,
                                           google: google, apple: FakeOAuthProvider(isAvailable: false))
        model.password = "hunter2"
        return Fixture(model: model, session: session, auth: auth, status: status,
                       transport: transport, wipes: wipes, google: google)
    }

    /// Drives `start()` to a loaded account. Bounded `Task.yield()` loops, never a sleep.
    private func signedIn(_ fixture: Fixture) async throws -> Task<Void, Never> {
        let running = Task { await fixture.session.start() }
        _ = try await fixture.auth.signIn(email: "a@b.test", password: "p")
        await yieldUntil { fixture.session.state.me != nil }
        return running
    }

    /// The cleanup is detached, so the caller's `await` returns before it has finished.
    private func settle(_ fixture: Fixture) async {
        await yieldUntil { fixture.session.state == .signedOut }
        await yieldUntil(200) { fixture.status.pending != nil }
    }

    /// Fix round 1 / M7: a `while`, not `for … where` — `where` SKIPS an iteration rather than
    /// ending the loop, so that shape always spends the whole bound (`AccountSession.awaitAccount`'s
    /// own comment). The bound is the safety net; the condition is the exit.
    private func yieldUntil(_ bound: Int = 500, _ done: () -> Bool) async {
        var yields = 0
        while !done(), yields < bound {
            yields += 1
            await Task.yield()
        }
    }

    // MARK: - The successful path

    @Test func aSuccessfulDeleteWipesThenDeletesTheFirebaseUserThenSignsOutAndPostsTheTerminalEvent() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }

        await fixture.model.delete()
        await settle(fixture)

        #expect(fixture.transport.sent.last?.method == "DELETE")
        #expect(fixture.wipes.count == 1)
        #expect(fixture.wipes.observations.first?.authOperations == [.reauthenticate],
                "Firebase was asked to delete the user before the device was wiped")
        #expect(fixture.wipes.observations.first?.wasSignedOut == false,
                "the session was dropped before the device was wiped")
        #expect(fixture.auth.operations == [.reauthenticate, .deleteUser])
        #expect(fixture.session.state == .signedOut)
        // The terminal alert owns the screen from here, so the view model never reports success.
        #expect(fixture.model.state == .deleting)
        #expect(fixture.status.consume() == .deleted)
    }

    /// CF-G-5. The calling task is cancelled while the DELETE is in flight; the cleanup still runs,
    /// to completion, and cannot see the cancellation. An inline cleanup sees `isCancelled == true`
    /// and every cancellation-aware step inside it (the engine's URLSession work, a `Task.sleep`)
    /// would abandon a device the server has already erased.
    ///
    /// Fix round 1 / M3: named for what it pins. An UNSTRUCTURED `Task { }` does not inherit
    /// cancellation either, so this is inline-vs-not-inline — not `Task { }` vs `Task.detached { }`
    /// (the detachment buys isolation from the caller's task-local values, which nothing here reads).
    @Test func theCleanupIsNotPartOfTheCallingTask() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }

        let deleting = Task { await fixture.model.delete() }
        deleting.cancel()
        await deleting.value
        await settle(fixture)

        #expect(fixture.wipes.count == 1)
        #expect(fixture.wipes.observations.first?.wasCancelled == false)
        #expect(fixture.session.state == .signedOut)
        #expect(fixture.auth.operations == [.reauthenticate, .deleteUser])
    }

    /// Both paths reach the wiper, and only one of them ever runs it: the 204 path posts `.deleted`
    /// itself, so `RootView` hands that same event straight back to `handle(_:)`.
    @Test func theUsersOwnDeleteAndTheDeletedEnvelopeCannotWipeTwice() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }

        await fixture.model.delete()
        await settle(fixture)
        fixture.session.handle(.deleted)
        for _ in 0..<200 { await Task.yield() }

        #expect(fixture.wipes.count == 1)
    }

    /// Fix round 1 / I2: the envelope can arrive BEFORE the user's own 204 — `ProfileScreen`
    /// re-syncs on every `session.state` change, so a concurrent `/me` against an account the server
    /// deletes mid-DELETE is ordinary. The latch was created with `deletingFirebaseUser: false`, so
    /// the later `true` was dropped and the Firebase credential outlived the account on the one path
    /// where deleting it was still possible. Chained onto the latched task: exactly one wipe, and
    /// exactly one `deleteUser` — never a second wipe to get the delete in.
    @Test func aLateFirebaseDeleteIsChainedOntoTheLatchInsteadOfBeingDropped() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }

        fixture.session.handleDeletion(deletingFirebaseUser: false)
        fixture.session.handleDeletion(deletingFirebaseUser: true)
        await settle(fixture)
        await yieldUntil { !fixture.auth.operations.isEmpty }

        #expect(fixture.auth.operations == [.deleteUser],
                "the Firebase credential outlived the account the server deleted")
        #expect(fixture.wipes.count == 1, "the device was wiped twice to get the Firebase delete in")
    }

    /// The admin-side deletion: the 403 `ACCOUNT_DELETED` envelope. The account is already gone
    /// server-side and this user cannot re-authenticate, so NO Firebase delete is attempted — the
    /// device is wiped and the session dropped, nothing more.
    @Test func anAdminSideDeletionWipesTheDeviceButDeletesNoFirebaseUser() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }

        fixture.session.handle(.deleted)
        await settle(fixture)

        #expect(fixture.wipes.count == 1)
        #expect(fixture.auth.operations.isEmpty)
        #expect(fixture.session.state == .signedOut)
    }

    // MARK: - The refusals: nothing local is touched

    @Test func aLastAdminRefusalLeavesTheDeviceCompletelyUntouched() async throws {
        let body = #"{"code":"LAST_ADMIN","message":"last active administrator"}"#
        let fixture = makeFixture(delete: .json(409, body))
        let running = try await signedIn(fixture); defer { running.cancel() }

        await fixture.model.delete()
        for _ in 0..<200 { await Task.yield() }

        #expect(fixture.model.state == .failedLastAdmin)
        #expect(fixture.wipes.count == 0)
        #expect(fixture.auth.operations == [.reauthenticate])
        #expect(fixture.session.state.me?.uid == "fake-uid")
        #expect(fixture.status.pending == nil)
    }

    @Test func aNetworkFailureLeavesTheDeviceCompletelyUntouched() async throws {
        let fixture = makeFixture(delete: .failing(URLError(.notConnectedToInternet)))
        let running = try await signedIn(fixture); defer { running.cancel() }

        await fixture.model.delete()
        for _ in 0..<200 { await Task.yield() }

        #expect(fixture.model.state == .failedNetwork)
        #expect(fixture.wipes.count == 0)
        #expect(fixture.session.state.me?.uid == "fake-uid")
    }

    @Test func anUnexpectedStatusLeavesTheDeviceCompletelyUntouched() async throws {
        let fixture = makeFixture(delete: .json(500, #"{"message":"boom"}"#))
        let running = try await signedIn(fixture); defer { running.cancel() }

        await fixture.model.delete()
        for _ in 0..<200 { await Task.yield() }

        #expect(fixture.model.state == .failedUnknown)
        #expect(fixture.wipes.count == 0)
        #expect(fixture.session.state.me?.uid == "fake-uid")
    }

    /// Three refusals, three messages — and `idle`/`deleting` carry none, so nothing can render an
    /// error banner over a request that has not failed.
    @Test func eachFailureStateCarriesItsOwnMessage() {
        let keys = [DeleteAccountState.failedLastAdmin, .failedNetwork, .failedUnknown]
            .map { DeleteAccountViewModel.messageKey(for: $0) }

        #expect(keys == ["profile_delete_account_error_last_admin",
                         "profile_delete_account_error_network",
                         "profile_delete_account_error_unknown"])
        #expect(DeleteAccountViewModel.messageKey(for: .idle) == nil)
        #expect(DeleteAccountViewModel.messageKey(for: .deleting) == nil)
        #expect(DeleteAccountViewModel.messageKey(for: .reauthenticating) == nil)
        // Reused, not authored: the same copy the password sheet renders for the same refusal.
        #expect(DeleteAccountViewModel.messageKey(for: .failedReauth(password: true))
                == "edit_password_wrong_current")
        // Stage 7 fix 2 / I2: a Google or Apple account has no password, so "Incorrect current
        // password" is a WHY, and a false one. WHAT, not WHY — and still nothing authored.
        #expect(DeleteAccountViewModel.messageKey(for: .failedReauth(password: false))
                == "auth_error_generic")
    }

    // MARK: - Stage 4 / I3: the re-authentication gate

    /// The pin. An `.alert` confirm button was the entire barrier in front of an IRREVERSIBLE,
    /// unrecoverable operation, while every reversible one (`EditEmailSheet`, `EditPasswordSheet`)
    /// re-authenticated first. Anyone with a briefly unlocked device could destroy the account.
    @Test func aWrongCurrentPasswordSendsNoDeleteAtAll() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }
        fixture.auth.nextError = .wrongPassword
        fixture.model.password = "not-the-password"

        await fixture.model.delete()
        for _ in 0..<200 { await Task.yield() }

        #expect(fixture.model.state == .failedReauth(password: true))
        #expect(fixture.transport.sent.contains { $0.method == "DELETE" } == false,
                "the account was deleted without proving who was holding the device")
        #expect(fixture.wipes.count == 0)
        #expect(fixture.session.state.me?.uid == "fake-uid")
    }

    /// The password is not left in memory once the attempt is over, whichever way it went.
    @Test func theTypedPasswordIsClearedAfterTheAttempt() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }
        fixture.auth.nextError = .wrongPassword

        await fixture.model.delete()

        #expect(fixture.model.password.isEmpty)
    }

    /// A Google-only account has no password to type, so the proof is the provider's own sheet.
    @Test func aGoogleOnlyAccountReAuthenticatesThroughItsProvider() async throws {
        let google = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                              isEmailVerified: true, providerIDs: ["google.com"])
        let fixture = makeFixture(delete: .json(204, ""), user: google)
        let running = try await signedIn(fixture); defer { running.cancel() }

        #expect(fixture.model.requiresPassword == false)
        await fixture.model.delete()
        await settle(fixture)

        #expect(fixture.google.presentCount == 1, "the provider sheet was never presented")
        #expect(fixture.auth.operations.contains(.reauthenticateCredential))
        #expect(fixture.transport.sent.last?.method == "DELETE")
    }

    /// Stage 9 / P1. The federated leg redeemed its credential with `auth.signIn(with:)`, which
    /// REPLACES the Firebase session with whoever the sheet returned — so on a device with a second
    /// Google account, picking the wrong one re-pointed the session and the `DELETE` that follows
    /// tombstoned that account instead. Irreversible, and `BearerRetry`'s cross-account guard cannot
    /// see a swap that happened before the request started. The absence is the assertion: no sign-in
    /// entry point is reached at all.
    @Test func aFederatedReAuthenticationNeverSignsIn() async throws {
        let google = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                              isEmailVerified: true, providerIDs: ["google.com"])
        let fixture = makeFixture(delete: .json(204, ""), user: google)
        let running = try await signedIn(fixture); defer { running.cancel() }

        await fixture.model.delete()
        await settle(fixture)

        #expect(fixture.auth.operations == [.reauthenticateCredential, .deleteUser])
        // `[.signIn]` is the fixture's own sign-in above; a `.credential` alongside it is the
        // session replacement this fix exists to stop.
        #expect(fixture.auth.entryPoints == [.signIn],
                "the delete confirmation signed in with the sheet's credential")
    }

    /// The other half of P1: a credential for a DIFFERENT account. Firebase answers
    /// `User.reauthenticate(with:)` with `userMismatch` — which `AuthErrorCode(firebaseCode:)` does
    /// not name and therefore reads as `.unknown` — and that refusal must leave the device exactly
    /// as a dismissed sheet does. `signIn(with:)` had no such answer: it succeeded, under the other
    /// account.
    @Test func aMismatchedProviderCredentialSendsNoDelete() async throws {
        let google = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                              isEmailVerified: true, providerIDs: ["google.com"])
        let fixture = makeFixture(delete: .json(204, ""), user: google)
        let running = try await signedIn(fixture); defer { running.cancel() }
        fixture.auth.nextError = .unknown

        await fixture.model.delete()
        for _ in 0..<200 { await Task.yield() }

        #expect(fixture.model.state == .failedReauth(password: false))
        #expect(fixture.auth.operations == [.reauthenticateCredential],
                "the refusal came from a sign-in, which cannot refuse a valid credential for another account")
        #expect(fixture.transport.sent.contains { $0.method == "DELETE" } == false,
                "a credential for another account deleted this one")
        #expect(fixture.wipes.count == 0)
        #expect(fixture.session.user?.uid == "fake-uid", "the session was re-pointed at another account")
    }

    /// A dismissed provider sheet is a refusal like any other — nothing is deleted.
    @Test func aRefusedProviderReAuthenticationSendsNoDelete() async throws {
        let google = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                              isEmailVerified: true, providerIDs: ["google.com"])
        let fixture = makeFixture(delete: .json(204, ""), user: google)
        let running = try await signedIn(fixture); defer { running.cancel() }
        fixture.google.error = .cancelled

        await fixture.model.delete()
        for _ in 0..<200 { await Task.yield() }

        #expect(fixture.model.state == .failedReauth(password: false))
        // Stage 7 fix 2 / I2: and the message it renders is not "Incorrect current password" —
        // this account has no password to have got wrong.
        #expect(DeleteAccountViewModel.messageKey(for: fixture.model.state) == "auth_error_generic")
        #expect(fixture.transport.sent.contains { $0.method == "DELETE" } == false)
        #expect(fixture.wipes.count == 0)
    }

    /// Stage 9 round 5 / NB-D: an UNAVAILABLE provider is never PRESENTED. The sign-in screen
    /// refuses that call (`SignInViewModel:125`) and this leg did not, so the uncatchable
    /// `NSInvalidArgumentException` R5-P1 closed on the sign-in path stayed reachable one caller
    /// over: a client id whose reversed callback scheme is not in this bundle makes
    /// `GIDSignIn` raise, and an Objective-C exception is not a Swift `Error` — the app terminates.
    /// Unavailable is a refusal like a dismissed sheet: nothing is deleted, and the copy says WHAT.
    ///
    /// `presentCount == 0` is the discriminating assertion: `FakeOAuthProvider` THROWS when it is
    /// unavailable, so the end state was already right for the wrong reason — the real provider
    /// traps instead of throwing.
    @Test func anUnavailableProviderIsNeverPresentedAndSendsNoDelete() async throws {
        let googleUser = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                  isEmailVerified: true, providerIDs: ["google.com"])
        let fixture = makeFixture(delete: .json(204, ""), user: googleUser,
                                  google: FakeOAuthProvider(isAvailable: false))
        let running = try await signedIn(fixture); defer { running.cancel() }

        await fixture.model.delete()
        for _ in 0..<200 { await Task.yield() }

        #expect(fixture.google.presentCount == 0, "an unavailable provider was presented")
        #expect(fixture.model.state == .failedReauth(password: false))
        #expect(DeleteAccountViewModel.messageKey(for: fixture.model.state) == "auth_error_generic")
        #expect(fixture.transport.sent.contains { $0.method == "DELETE" } == false)
        #expect(fixture.wipes.count == 0)
    }

    // MARK: - Stage 5 / C1.2 + C2.2: the durable marker

    /// A cleanup interrupted by process death — or refused by a full store — is owed to this device
    /// forever otherwise: the server has revoked and deleted the Firebase user, so the next `/me`
    /// answers a bare 401 and nothing can reach `handleDeletion()` again.
    @Test func aPendingMarkerMakesTheNextLaunchWipe() async throws {
        let marker = InMemoryDeletionMarker()
        marker.pendingUid = "fake-uid"
        let wipes = WipeSpy()
        let auth = FakeAuthClient(state: .signedOut)
        let transport = ScriptedTransport([.json(200, Self.meJSON)])
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return nil }, marker: marker)

        await session.resumePendingDeletion()

        #expect(wipes.count == 1)
        #expect(marker.pendingUid == nil, "the marker survived a wipe that succeeded")
    }

    /// Stage 7 fix 2 / M2. The marker carries a uid and nothing read it: a wipe that failed for
    /// account A left it set, and the next launch wiped the device even though account B had since
    /// signed in on it. The marker is redeemed only for the account it names — a different signed-in
    /// uid clears it instead, because a wipe owed to A can no longer be performed without destroying
    /// B's library.
    @Test func aPendingMarkerForAnotherAccountIsClearedNotWiped() async throws {
        let marker = InMemoryDeletionMarker()
        marker.pendingUid = "someone-elses-uid"
        let wipes = WipeSpy()
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let transport = ScriptedTransport([.json(200, Self.meJSON)])
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return nil }, marker: marker)

        await session.resumePendingDeletion()

        #expect(wipes.count == 0, "another account's pending wipe erased this account's library")
        #expect(marker.pendingUid == nil, "the stale marker survived and will wipe on the next launch too")
    }

    /// The other half of M2: `handleDeletion` used to store `""` when no uid was known, and the
    /// getter reports `""` as pending — a marker that matches nobody, on a device that would then
    /// wipe itself on the next launch whoever signs in. No uid, no marker; the wipe this call owes
    /// the device still runs.
    ///
    /// The wipe FAILS here on purpose: a successful one clears the marker on its way out, which
    /// would hide whatever was written.
    @Test func noMarkerIsWrittenWithoutAUid() async throws {
        struct StoreFull: Error {}
        let marker = InMemoryDeletionMarker()
        let wipes = WipeSpy()
        let auth = FakeAuthClient(state: .signedOut)
        let transport = ScriptedTransport([.json(200, Self.meJSON)])
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return StoreFull() }, marker: marker)

        await session.handleDeletion(deletingFirebaseUser: false).value

        #expect(marker.pendingUid == nil, "an empty uid was stored as a pending deletion")
        #expect(wipes.count == 1, "the wipe this deletion owes the device did not run")

        // Stage 7 re-review 2 / m1: and not an EMPTY uid either. `if let uid` accepted `""`, which
        // `UserDefaultsDeletionMarker`'s getter reports as pending — the same marker naming nobody,
        // reached through a record that carries one rather than through no record at all.
        let emptyMarker = InMemoryDeletionMarker()
        let emptyWipes = WipeSpy()
        let emptyTransport = ScriptedTransport([.json(200, #"{"uid":"","email":"a@b.test","status":"active","role":"user"}"#)])
        let emptySession = AccountSession(
            auth: FakeAuthClient(state: .signedOut),
            account: AccountClient(transport: emptyTransport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in },
            wipe: { [emptyWipes] in emptyWipes.record(); return StoreFull() }, marker: emptyMarker)
        await emptySession.refresh()
        #expect(emptySession.state.me?.uid == "", "the fixture could not carry an empty uid")

        await emptySession.handleDeletion(deletingFirebaseUser: false).value

        #expect(emptyMarker.pendingUid == nil, "an empty uid was stored as a pending deletion")
    }

    @Test func noMarkerMeansNoLaunchWipe() async throws {
        let marker = InMemoryDeletionMarker()
        let wipes = WipeSpy()
        let auth = FakeAuthClient(state: .signedOut)
        let transport = ScriptedTransport([.json(200, Self.meJSON)])
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return nil }, marker: marker)

        await session.resumePendingDeletion()

        #expect(wipes.count == 0)
    }

    /// A wipe that hit a full or corrupt store KEEPS the marker, so the next launch tries again
    /// rather than leaving the rows on disk under an "account deleted" alert.
    @Test func aFailedWipeKeepsTheMarkerSoTheNextLaunchTriesAgain() async throws {
        struct StoreFull: Error {}
        let marker = InMemoryDeletionMarker()
        let auth = FakeAuthClient(state: .signedOut)
        let transport = ScriptedTransport([.json(200, Self.meJSON), .json(204, "")])
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let status = AccountStatusCenter()
        let session = AccountSession(auth: auth, account: account, stores: [], status: status,
                                     sleep: { _ in }, wipe: { StoreFull() }, marker: marker)
        let running = Task { await session.start() }; defer { running.cancel() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await yieldUntil { session.state.me != nil }

        await session.handleDeletion(deletingFirebaseUser: false).value

        #expect(marker.pendingUid == "fake-uid",
                "the app announced the account erased over rows that are still on disk")
    }
}
