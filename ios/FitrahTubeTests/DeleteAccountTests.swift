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
    }

    /// `responses` are consumed in order: the `/me` that loads the account, then the DELETE.
    private func makeFixture(delete response: HTTPResponse) -> Fixture {
        let auth = FakeAuthClient(state: .signedOut)
        let transport = ScriptedTransport([.json(200, Self.meJSON), response])
        let status = AccountStatusCenter()
        let wipes = WipeSpy()
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [], status: status,
                                     sleep: { _ in }, wipe: { [wipes] in wipes.record() })
        wipes.session = session
        wipes.auth = auth
        return Fixture(model: DeleteAccountViewModel(account: account, session: session),
                       session: session, auth: auth, status: status, transport: transport, wipes: wipes)
    }

    /// Drives `start()` to a loaded account. Bounded `Task.yield()` loops, never a sleep.
    private func signedIn(_ fixture: Fixture) async throws -> Task<Void, Never> {
        let running = Task { await fixture.session.start() }
        _ = try await fixture.auth.signIn(email: "a@b.test", password: "p")
        for _ in 0..<500 where fixture.session.state.me == nil { await Task.yield() }
        return running
    }

    /// The cleanup is detached, so the caller's `await` returns before it has finished.
    private func settle(_ fixture: Fixture) async {
        for _ in 0..<500 where fixture.session.state != .signedOut { await Task.yield() }
        for _ in 0..<200 where fixture.status.pending == nil { await Task.yield() }
    }

    // MARK: - The successful path

    @Test func aSuccessfulDeleteWipesThenDeletesTheFirebaseUserThenSignsOutAndPostsTheTerminalEvent() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }

        await fixture.model.delete()
        await settle(fixture)

        #expect(fixture.transport.sent.last?.method == "DELETE")
        #expect(fixture.wipes.count == 1)
        #expect(fixture.wipes.observations.first?.authOperations == [],
                "Firebase was asked to delete the user before the device was wiped")
        #expect(fixture.wipes.observations.first?.wasSignedOut == false,
                "the session was dropped before the device was wiped")
        #expect(fixture.auth.operations == [.deleteUser])
        #expect(fixture.session.state == .signedOut)
        // The terminal alert owns the screen from here, so the view model never reports success.
        #expect(fixture.model.state == .deleting)
        #expect(fixture.status.consume() == .deleted)
    }

    /// CF-G-5. The calling task is cancelled while the DELETE is in flight; the cleanup still runs,
    /// to completion, and cannot see the cancellation. An inline cleanup sees `isCancelled == true`
    /// and every cancellation-aware step inside it (the engine's URLSession work, a `Task.sleep`)
    /// would abandon a device the server has already erased.
    @Test func theCleanupSurvivesTheCallingTaskBeingCancelled() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }

        let deleting = Task { await fixture.model.delete() }
        deleting.cancel()
        await deleting.value
        await settle(fixture)

        #expect(fixture.wipes.count == 1)
        #expect(fixture.wipes.observations.first?.wasCancelled == false)
        #expect(fixture.session.state == .signedOut)
        #expect(fixture.auth.operations == [.deleteUser])
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
        #expect(fixture.auth.operations.isEmpty)
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
    }
}
