import FitrahAPI
import Foundation
import InnerTubeKit
import Synchronization
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
        /// Task 33: the marker AS THE WIPE SAW IT. `performDeletion` clears it again the moment the
        /// wipe reports success, so the value at wipe time is the only moment it is observable —
        /// and it is the value that decides whether an INTERRUPTED wipe can ever be resumed.
        var marker: (any DeletionMarking)?
        private(set) var markedUids: [String?] = []
        var count: Int { observations.count }

        func record() {
            markedUids.append(marker?.pendingUid)
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
                             google: FakeOAuthProvider = FakeOAuthProvider(),
                             marker: any DeletionMarking = InMemoryDeletionMarker()) -> Fixture {
        let auth = FakeAuthClient(state: .signedOut, user: user)
        let transport = ScriptedTransport([.json(200, Self.meJSON), response])
        let status = AccountStatusCenter()
        let wipes = WipeSpy()
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [], status: status,
                                     sleep: { _ in }, wipe: { [wipes] in wipes.record(); return nil },
                                     marker: marker)
        wipes.session = session
        wipes.auth = auth
        wipes.marker = marker
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
        let completion = fixture.status.consume()
        #expect(completion?.event == .deleted)
        // Task 33 / cold review: `performDeletion`'s completion announcement is UNATTRIBUTED and
        // must stay so — it posts after `dropSession()` has cleared `user`, and `handle` honours
        // nil unconditionally, which is what lets the user's own deletion raise its terminal alert
        // at all. (That same unconditional arm is CF-A-47's second shape: if a new account has
        // signed in by the time `RootView` consumes this, it re-enters `handle` and starts a
        // SECOND wipe. Pinned here as it stands, not as it should eventually be.)
        #expect(completion?.uid == nil, "the deletion completion announcement acquired a uid")
    }

    /// Task 33 / CF-A-44: the verdict belongs to ONE account, and it is refused by every other.
    ///
    /// The record end has carried the uid since Cubic round 6 / P2b — `refreshRefusal(signedFor:)`
    /// answers only the account whose bearer the asking request carried. The DELIVERY end carried
    /// nothing: `AuthorizedTransport` posted a bare `.deleted`, and `handleDeletion()` stamps its
    /// marker from `user?.uid ?? state.me?.uid` — whoever is signed in at that instant — and wipes
    /// THAT account's scope. So a `.deleted` minted for A while A's request was in flight, arriving
    /// after B completed a sign-in, erased B's library and wrote B's uid into A's marker. The
    /// window is narrow and it is not reachable without a `GoogleService-Info.plist`, which is
    /// exactly why it is worth closing now rather than under the pressure of shipping one.
    ///
    /// Both directions in one test: the stranger's verdict is refused, and the account's own is
    /// still honoured immediately afterwards — a guard that refused everything would pass the first
    /// half and silently disable the wipe altogether.
    @Test func aVerdictMintedForAnotherAccountIsRefusedAndTheOwnersIsNotHere() async throws {
        let fixture = makeFixture(delete: .json(204, ""))
        let running = try await signedIn(fixture); defer { running.cancel() }

        let acted = fixture.session.handle(.deleted, for: "uid-b")
        await yieldUntil(200) { fixture.wipes.count > 0 }

        #expect(acted == false, "a verdict for an account that is not signed in was acted on")
        #expect(fixture.wipes.count == 0, "the stranger's deletion wiped the signed-in account's library")
        #expect(fixture.session.state.me != nil, "the stranger's deletion dropped the wrong session")
        #expect(fixture.status.pending == nil, "the refused verdict still raised the terminal alert")

        // The positive control, on the same session: this account's own verdict still wipes.
        #expect(fixture.session.handle(.deleted, for: FakeAuthClient.defaultUser.uid))
        await settle(fixture)
        #expect(fixture.wipes.count == 1, "the guard refused the account its own deletion")
        #expect(fixture.status.consume()?.event == .deleted)
    }

    /// Task 33, review C1 — the half the first cut of CF-A-44 got WRONG, and the more dangerous
    /// half: a guard that refuses a legitimate verdict silently disables the wipe forever.
    ///
    /// On the bare-401 path the account is ALWAYS signed out by the time the verdict is delivered.
    /// Firebase force-signs it out inside the very mint that produces the verdict
    /// (`FirebaseAuthClient.idToken`'s trace of `signOutIfTokenIsInvalid` → `signOutByForce`), and
    /// `BearerRetry` then re-sends the signed original — so `.signedOut` reaches `start()` a whole
    /// network round trip before `handle` sees the verdict, and `user` (with `state.me`) is nil.
    /// Checking `user` alone therefore refused exactly the deletions the wipe exists for, with no
    /// recovery: nothing writes a marker, the refusal box is consume-once, and the next launch has
    /// no Firebase user to sign a request with.
    ///
    /// `lastKnownUid` is what answers it — the account that just LEFT is precisely who a late
    /// verdict can legitimately name, while an account that has been REPLACED is precisely who it
    /// must not. Both directions are asserted: the departed owner's verdict wipes and marks, and
    /// the stranger's still does not.
    @Test func aVerdictForTheAccountFirebaseJustSignedOutStillWipesAndMarks() async throws {
        let marker = InMemoryDeletionMarker()
        let fixture = makeFixture(delete: .json(204, ""), marker: marker)
        let running = try await signedIn(fixture); defer { running.cancel() }

        // What Firebase does to itself inside the refused mint, before the verdict is delivered.
        try fixture.auth.signOut()
        await yieldUntil { fixture.session.state == .signedOut }
        #expect(fixture.session.state.me == nil, "the precondition is that nobody is signed in")

        #expect(fixture.session.handle(.deleted, for: FakeAuthClient.defaultUser.uid),
                "the verdict for the account that was just signed out was refused")
        await yieldUntil(500) { fixture.wipes.count > 0 }

        #expect(fixture.wipes.count == 1, "the ruling-C13 wipe never ran for a deleted account")
        // Read at WIPE time: a successful wipe clears the marker again on its way out, so this is
        // the only moment the durable record of "whose deletion is owed" is observable — and it is
        // what a launch after a crashed wipe would redeem.
        #expect(fixture.wipes.markedUids == [FakeAuthClient.defaultUser.uid],
                "the marker named the wrong account, or none, so an interrupted wipe was unresumable")

        // And the stranger is still refused with nobody signed in — `lastKnownUid` admits ONE
        // account, not every account that ever held this session.
        #expect(fixture.session.handle(.deleted, for: "uid-b") == false)
    }

    /// CF-A-44, the `land()` window — the same wrong direction as review C1, by another route.
    ///
    /// `SignInViewModel.land()` refreshes on a sign-in `start()` has not observed yet, and `user`
    /// is nil there ON PURPOSE (`refreshIfSignedIn`'s doc). So is `lastKnownUid`, which only
    /// `start()`'s `.signedIn` arm wrote — and `fetch`'s own `startedFor` is `user?.uid`, nil too.
    /// A verdict the transport attributes to the account Firebase really holds was therefore
    /// refused, on the app's primary sign-in path. The round itself is the only thing running in
    /// that window, so it is what asks Firebase who it is running for.
    ///
    /// Both directions: the arriving account's verdict wipes and marks, and the stranger's is
    /// still refused — the seed admits the ONE account Firebase holds, not "anyone while `user`
    /// is nil".
    @Test func aVerdictForTheAccountStartHasNotObservedYetStillWipesAndMarks() async throws {
        let arriving = AuthUser(uid: "uid-b", email: "b@fitrah.test", isEmailVerified: true,
                                providerIDs: ["password"])
        // Round 2 / item 1: the DURABLE record is seeded with the in-memory one. B's verdict below
        // writes `pendingUid = B`; if the process dies before `start()` drains `.signedIn(B)`, the
        // relaunch reads the holder from here — and a holder still naming the PREVIOUS account
        // downgrades B's own device wipe to a row-only delete.
        let marker = InMemoryDeletionMarker()
        marker.lastSignedInUid = "uid-previous"
        let fixture = makeFixture(delete: .json(204, ""), user: arriving, marker: marker)
        // NO `start()`: Firebase holds B and the session has not heard.
        _ = try await fixture.auth.signIn(email: "b@fitrah.test", password: "p")
        let round = Task { await fixture.session.refresh(maxAttempts: 1) }
        await yieldUntil { fixture.transport.sent.count == 1 }
        #expect(fixture.session.user == nil, "the precondition is the window start() has not closed")
        #expect(marker.lastSignedInUid == arriving.uid, "the durable holder still names the previous account")

        #expect(fixture.session.handle(.deleted, for: "uid-stranger") == false,
                "a verdict for an account Firebase does not hold was acted on")
        #expect(fixture.wipes.count == 0)

        #expect(fixture.session.handle(.deleted, for: arriving.uid),
                "the verdict for the account that is signing in was refused")
        await yieldUntil(500) { fixture.wipes.count > 0 }
        #expect(fixture.wipes.count == 1, "the ruling-C13 wipe never ran for a deleted account")
        #expect(fixture.wipes.markedUids == [arriving.uid])
        await round.value
    }

    /// Round 2 / item 1, the seed's re-check. `currentUser()` answers X and the round suspends on
    /// the hop; inside it X signs out, B signs in, and `start()` — which is authoritative — sets
    /// `user = B` and the latch to B. A seed that resumed and wrote its stale X over that latch
    /// made `handle` ADMIT a verdict for X, the REPLACED account, while B is signed in: B's
    /// library wiped for X's deletion, which is the hole the attribution exists to close. `user`
    /// is therefore read AFTER the await.
    ///
    /// `ParkedCurrentUserAuth` (below) is what makes the interleaving a sequence instead of a race.
    @Test func aSeedThatResumesAfterTheAccountWasReplacedDoesNotReadmitTheOldOne() async throws {
        let replaced = AuthUser(uid: "uid-x", email: "x@fitrah.test", isEmailVerified: true, providerIDs: ["password"])
        let base = FakeAuthClient(state: .signedOut, user: replaced)
        let auth = ParkedCurrentUserAuth(base)
        let wipes = WipeSpy()
        let transport = ScriptedTransport([.json(200, Self.meJSON)])
        let session = AccountSession(auth: auth,
                                     account: AccountClient(transport: transport, baseURL: Self.base,
                                                            deviceId: DeviceId(value: "dev-1")),
                                     stores: [], status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return nil })
        // Firebase holds X; no `start()` yet, so the round below is nil-started and parks on X.
        _ = try await base.signIn(email: "x@fitrah.test", password: "p")
        auth.parkNextCurrentUser()
        let round = Task { await session.refresh(maxAttempts: 1) }
        await yieldUntil { auth.isParked }

        // Inside the hop: X leaves, B arrives, and `start()` observes B.
        try base.signOut()
        base.user = FakeAuthClient.defaultUser
        _ = try await base.signIn(email: "a@b.test", password: "p")
        let running = Task { await session.start() }; defer { running.cancel() }
        await yieldUntil { session.user?.uid == FakeAuthClient.defaultUser.uid }
        #expect(session.user?.uid == FakeAuthClient.defaultUser.uid, "the precondition is that start() got there first")

        auth.release()
        await round.value

        #expect(session.handle(.deleted, for: replaced.uid) == false,
                "the stale seed re-admitted the account that was replaced")
        #expect(wipes.count == 0, "B's library was wiped for X's deletion")
        // The positive control: the account that IS signed in keeps its own verdict.
        #expect(session.handle(.deleted, for: FakeAuthClient.defaultUser.uid))
        await yieldUntil(500) { wipes.count > 0 }
        #expect(wipes.count == 1)
    }

    /// The other edge of the same seed: it only ever ADDS an identity. A round also starts with no
    /// identity when nobody is signed in at all — the Retry cards in `MeTabRoot` and `SettingsView`
    /// call `refresh()` unguarded — and Firebase answers nil there. Writing THAT over
    /// `lastKnownUid` would forget the account that just left, which is review C1's defect again:
    /// its late verdict refused, the wipe silently disabled.
    ///
    /// What this does NOT pin: it passes with the seed line deleted entirely. It catches only the
    /// NAIVE unconditional seed (`lastKnownUid = await auth.currentUser()?.uid`); the seed's
    /// presence is `aVerdictForTheAccountStartHasNotObservedYetStillWipesAndMarks`' job.
    @Test func aGuestRefreshDoesNotForgetTheAccountThatJustLeft() async throws {
        let fixture = makeFixture(delete: .json(401, "{}"))
        let running = try await signedIn(fixture); defer { running.cancel() }
        try fixture.auth.signOut()
        await yieldUntil { fixture.session.state == .signedOut }

        await fixture.session.refresh(maxAttempts: 1)
        #expect(fixture.transport.sent.count == 2, "the precondition is that the guest round ran")

        #expect(fixture.session.handle(.deleted, for: FakeAuthClient.defaultUser.uid),
                "a guest refresh erased the account a late verdict can still legitimately name")
        await yieldUntil(500) { fixture.wipes.count > 0 }
        #expect(fixture.wipes.count == 1)
    }

    /// Task 33, review I3 — every reviewer made the same point, and it was fair: the two new tests
    /// pinned the transport END and the session END and nothing pinned the WIRE between them, so
    /// changing only the publisher to `onStatusEvent(event, nil)` left both of them green while
    /// wrong-account deletion stayed reachable.
    ///
    /// This drives the real `AuthorizedTransport` into the real `AccountStatusCenter` the session
    /// was built with, and then does what `RootView` does with the result. It is one link short of
    /// end to end — `RootView`'s `consume()` → `handle` → alert is SwiftUI and is not constructed
    /// here. That last link (CF-A-48) is pinned on its own since: it is `RootView.route`, and
    /// `RootViewDestinationTests` drives it in both directions.
    @Test func aVerdictKeepsItsAccountAllTheWayFromTheTransportToTheWipe() async throws {
        let marker = InMemoryDeletionMarker()
        let fixture = makeFixture(delete: .json(204, ""), marker: marker)
        let running = try await signedIn(fixture); defer { running.cancel() }

        // A SECOND transport, over the same auth client and posting into the same centre the
        // session holds — the production wiring, assembled by hand.
        let base = ScriptedTransport([.json(401, "{}"), .json(401, "{}")])
        let authorized = AuthorizedTransport(
            base: base, apiHost: "api.fitrah.test", tokens: fixture.auth,
            onStatusEvent: { [status = fixture.status] event, uid in status.post(event, for: uid) },
            refreshRefusal: { [auth = fixture.auth] uid in await auth.refreshRefusal(signedFor: uid) },
            currentUid: { [auth = fixture.auth] in await auth.currentUser()?.uid })
        fixture.auth.nextMintRefusal = .userNotFound

        _ = try? await authorized.send(
            HTTPRequest(method: "GET", url: URL(string: "https://api.fitrah.test/api/account/me")!,
                        headers: [:], body: nil))

        await yieldUntil(500) { fixture.status.pending != nil }
        let signal = try #require(fixture.status.consume(), "the transport posted nothing at all")
        #expect(signal.event == .deleted)
        #expect(signal.uid == FakeAuthClient.defaultUser.uid,
                "the verdict lost its account between the transport and the centre")

        var alert: AccountStatusAlert?
        RootView.route(signal, session: fixture.session, alert: &alert)
        #expect(alert == AccountStatusAlert(.deleted), "the account's own verdict was refused at the last link")
        await yieldUntil(500) { fixture.wipes.count > 0 }
        #expect(fixture.wipes.markedUids == [FakeAuthClient.defaultUser.uid],
                "the wipe ran for the wrong account, or marked nobody")
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
    /// signed in on it. The DEVICE wipe is redeemed only for the account it names. Review I3: for a
    /// different signed-in uid the debt is paid by uid instead (A's rows carry A's `userId`). The
    /// scoped delete is a SPY here — the default refuses, so that a session built without one can
    /// never "redeem" a debt by deleting nothing — and what is pinned is that it was asked for the
    /// marker's account and the device wipe was not; `LocalAccountWiperTests` pins which rows go.
    @Test func aPendingMarkerForAnotherAccountIsClearedNotWiped() async throws {
        let marker = InMemoryDeletionMarker()
        marker.pendingUid = "someone-elses-uid"
        let wipes = WipeSpy()
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let transport = ScriptedTransport([.json(200, Self.meJSON)])
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let asked = Mutex<[String]>([])
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return nil },
                                     wipeRows: { uid in asked.withLock { $0.append(uid) }; return nil },
                                     marker: marker)

        await session.resumePendingDeletion()

        #expect(asked.withLock { $0 } == ["someone-elses-uid"], "the departed account's rows were never asked for")
        #expect(wipes.count == 0, "another account's pending wipe erased this account's library")
        #expect(marker.pendingUid == nil, "the stale marker survived and will wipe on the next launch too")
    }

    /// Task 34 / CF-A-44's positive control, and it is not optional. The refusing half — nobody
    /// signed in, and the durable record names somebody ELSE, so the device wipe must not run — is
    /// `LocalAccountWiperTests.aMarkerOwedToAnAccountThatNoLongerHoldsTheDeviceDeletesOnlyThat
    /// AccountsRows` (its `false` case), against real rows. A guard that refused every
    /// nil-current-user redemption would pass that while making the durable marker useless — an
    /// interrupted wipe would then be owed to the device forever, which is the exact failure the
    /// marker was introduced to prevent. Same marker, one field different.
    @Test func aPendingMarkerIsStillRedeemedForTheAccountThatLastHeldTheDevice() async throws {
        let marker = InMemoryDeletionMarker()
        marker.pendingUid = "uid-a"
        marker.lastSignedInUid = "uid-a"     // nobody else has used this device
        let wipes = WipeSpy()
        let auth = FakeAuthClient(state: .signedOut)
        let transport = ScriptedTransport([.json(200, Self.meJSON)])
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return nil }, marker: marker)

        await session.resumePendingDeletion()

        #expect(wipes.count == 1, "the deleted account's own interrupted wipe was refused")
        #expect(marker.pendingUid == nil)
        // Review I2: the wiper's own standard (its prefix sweep) is that no record of the deleted
        // account's uid outlives the wipe, and this key is outside that sweep.
        #expect(marker.lastSignedInUid == nil, "the deleted account's uid survived its own wipe")
    }

    /// Round 2 / item 3: a session built WITHOUT a scoped delete must not fail open. The default
    /// used to answer nil — "deleted, no error" having deleted nothing — so any construction that
    /// forgot to pass one redeemed an irreversible debt. It refuses instead, and the marker stays.
    @Test func aSessionBuiltWithoutAScopedDeleteKeepsTheMarkerRatherThanPretending() async throws {
        let marker = InMemoryDeletionMarker()
        marker.pendingUid = "uid-a"
        marker.lastSignedInUid = "uid-b"
        let account = AccountClient(transport: ScriptedTransport([]), baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: FakeAuthClient(state: .signedOut), account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in }, wipe: { nil }, marker: marker)

        await session.resumePendingDeletion()

        #expect(marker.pendingUid == "uid-a", "a debt was marked paid by a delete that was never wired")
    }

    /// Round 2 / item 6: WHO holds the device is the live session first and the durable record only
    /// when there is none — and nothing let the two disagree, so `lastSignedInUid ?? signedIn`
    /// passed the suite. They disagree for real: B signed in under a build that predates the key,
    /// so the record still names A, which is also the pending account. Read in the wrong order the
    /// holder "is" A, the marker matches, and the DEVICE wipe runs against a live, signed-in B.
    @Test func theLiveSessionOutranksTheDurableRecordOfWhoHoldsTheDevice() async throws {
        let marker = InMemoryDeletionMarker()
        marker.pendingUid = "uid-a"
        marker.lastSignedInUid = "uid-a"
        let wipes = WipeSpy()
        let asked = Mutex<[String]>([])
        let holder = AuthUser(uid: "uid-b", email: "b@fitrah.test", isEmailVerified: true, providerIDs: ["password"])
        let account = AccountClient(transport: ScriptedTransport([]), baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: FakeAuthClient(state: .signedIn(holder)), account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return nil },
                                     wipeRows: { uid in asked.withLock { $0.append(uid) }; return nil },
                                     marker: marker)

        await session.resumePendingDeletion()

        #expect(wipes.count == 0, "the device-wide wipe ran against the account that is signed in right now")
        #expect(asked.withLock { $0 } == ["uid-a"])
        #expect(marker.pendingUid == nil)
    }

    /// Review I3's other half: the scoped delete is a wipe like any other, and one that reported an
    /// error KEEPS the marker — dropping it there is the same stranding by another route. Both
    /// mismatch shapes (B signed in now; B signed out), and the uid it was asked for is A's.
    @Test(arguments: [false, true])
    func aScopedDeleteThatFailsKeepsTheMarkerSoTheNextLaunchTriesAgain(holderIsSignedIn: Bool) async throws {
        struct StoreFull: Error {}
        let marker = InMemoryDeletionMarker()
        marker.pendingUid = "uid-a"
        marker.lastSignedInUid = "uid-b"
        let wipes = WipeSpy()
        let asked = Mutex<[String]>([])
        let holder = AuthUser(uid: "uid-b", email: "b@fitrah.test", isEmailVerified: true, providerIDs: ["password"])
        let auth = FakeAuthClient(state: holderIsSignedIn ? .signedIn(holder) : .signedOut)
        let account = AccountClient(transport: ScriptedTransport([]), baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { [wipes] in wipes.record(); return nil },
                                     wipeRows: { uid in asked.withLock { $0.append(uid) }; return StoreFull() },
                                     marker: marker)

        await session.resumePendingDeletion()

        #expect(asked.withLock { $0 } == ["uid-a"], "the scoped delete was not asked for the account the marker names")
        #expect(wipes.count == 0)
        #expect(marker.pendingUid == "uid-a", "A's rows are still on disk and nothing will come back for them")
    }

    /// Review I1: the WRITE side. Every test above seeds `lastSignedInUid` by hand, so deleting the
    /// one production line that writes it (`start()`'s `.signedIn` arm) restored the original bug —
    /// a marker owed to A wiping the device B has used since — with the whole suite green. Two
    /// sessions on ONE marker, which is what two launches are.
    @Test func theAccountThatHeldTheDeviceIsRecordedBySigningInNotByTheTest() async throws {
        let marker = InMemoryDeletionMarker()
        let holder = AuthUser(uid: "uid-b", email: "b@fitrah.test", isEmailVerified: true, providerIDs: ["password"])
        let first = makeFixture(delete: .json(204, ""), user: holder, marker: marker)
        let running = try await signedIn(first)
        try first.auth.signOut()
        await yieldUntil { first.session.state == .signedOut }
        running.cancel()

        // The next launch: A's failed wipe is still owed, and nobody is signed in.
        marker.pendingUid = "uid-a"
        let wipes = WipeSpy()
        let asked = Mutex<[String]>([])
        let second = AccountSession(
            auth: FakeAuthClient(state: .signedOut),
            account: AccountClient(transport: ScriptedTransport([]), baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in },
            wipe: { [wipes] in wipes.record(); return nil },
            wipeRows: { uid in asked.withLock { $0.append(uid) }; return nil }, marker: marker)
        await second.resumePendingDeletion()

        #expect(wipes.count == 0, "a wipe owed to A erased the device B has used since")
        #expect(asked.withLock { $0 } == ["uid-a"], "A's debt was dropped instead of paid by uid")
        #expect(marker.pendingUid == nil)
    }

    /// …and the production conformer's half of it, which nothing touched: the key, the read from a
    /// SECOND instance (what a relaunch is), and the clear. An isolated suite, never `.standard`.
    @Test func theLastSignedInUidRoundTripsThroughUserDefaults() {
        let suiteName = "DeleteAccountTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        UserDefaultsDeletionMarker(defaults: defaults).lastSignedInUid = "uid-b"
        #expect(UserDefaultsDeletionMarker(defaults: defaults).lastSignedInUid == "uid-b")
        #expect(defaults.string(forKey: UserDefaultsDeletionMarker.lastSignedInKey) == "uid-b")
        #expect(UserDefaultsDeletionMarker(defaults: defaults).pendingUid == nil, "the two keys are one key")

        UserDefaultsDeletionMarker(defaults: defaults).lastSignedInUid = nil
        #expect(UserDefaultsDeletionMarker(defaults: defaults).lastSignedInUid == nil)
        #expect(defaults.object(forKey: UserDefaultsDeletionMarker.lastSignedInKey) == nil)
    }

    /// Review I2, on the in-process path: a redeemed deletion forgets ITS OWN uid and nobody
    /// else's. `true` is an account that signed in while the wipe was running — `start()` has
    /// already written them as the device's holder, and erasing that would let the next stale
    /// marker fall through the nil arm and wipe their library.
    ///
    /// Round 2 / item 2: …and was then deleted too, so the MARKER is theirs by the time this wipe
    /// completes. The completion used to read `pendingUid` live, so the first account's wipe
    /// cleared the second account's debt — and compared `lastSignedInUid` against it as well.
    @Test(arguments: [false, true])
    func aRedeemedDeletionForgetsItsOwnUidAndNobodyElses(someoneElseArrivedMidWipe: Bool) async throws {
        let marker = InMemoryDeletionMarker()
        let auth = FakeAuthClient(state: .signedOut)
        let account = AccountClient(transport: ScriptedTransport([.json(200, Self.meJSON)]), baseURL: Self.base,
                                    deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [], status: AccountStatusCenter(),
                                     sleep: { _ in },
                                     wipe: {
                                         if someoneElseArrivedMidWipe {
                                             marker.lastSignedInUid = "uid-b"
                                             marker.pendingUid = "uid-b"
                                         }
                                         return nil
                                     },
                                     marker: marker)
        let running = Task { await session.start() }; defer { running.cancel() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await yieldUntil { session.state.me != nil }
        #expect(marker.lastSignedInUid == "fake-uid")

        await session.handleDeletion(deletingFirebaseUser: false).value

        #expect(marker.pendingUid == (someoneElseArrivedMidWipe ? "uid-b" : nil))
        #expect(marker.lastSignedInUid == (someoneElseArrivedMidWipe ? "uid-b" : nil))
    }

    /// Round 2 / item 5: the under-13 teardown deletes the account through Firebase and used to
    /// leave that child's uid in `UserDefaults`. Only while the record still names them: `true` is
    /// somebody else having become the device's holder across the Firebase await.
    @Test(arguments: [false, true])
    func theAgeIneligibleTeardownForgetsThatAccountsUidAndNobodyElses(someoneElseHoldsTheDevice: Bool) async throws {
        let marker = InMemoryDeletionMarker()
        let fixture = makeFixture(delete: .json(204, ""), marker: marker)
        let running = try await signedIn(fixture); defer { running.cancel() }
        #expect(marker.lastSignedInUid == FakeAuthClient.defaultUser.uid)
        if someoneElseHoldsTheDevice { marker.lastSignedInUid = "uid-b" }

        await fixture.session.terminateAgeIneligible()

        #expect(marker.lastSignedInUid == (someoneElseHoldsTheDevice ? "uid-b" : nil))
        #expect(fixture.wipes.count == 0, "an age-ineligible teardown is not a device wipe")
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

/// `FakeAuthClient` with ONE `currentUser()` call that answers and then parks until released — the
/// interleaving `aSeedThatResumesAfterTheAccountWasReplacedDoesNotReadmitTheOldOne` needs is "the
/// answer was read, then the account changed, then the caller resumed", and a real hop offers no
/// handle on its middle. Everything else forwards, so the fake's state machine is the one in use.
private nonisolated final class ParkedCurrentUserAuth: AuthClient {
    private let base: FakeAuthClient
    private let gate = Mutex<(armed: Bool, parked: CheckedContinuation<Void, Never>?)>((false, nil))

    init(_ base: FakeAuthClient) { self.base = base }

    func parkNextCurrentUser() { gate.withLock { $0.armed = true } }
    var isParked: Bool { gate.withLock { $0.parked != nil } }
    func release() {
        gate.withLock { gate in
            let parked = gate.parked
            gate.parked = nil
            return parked
        }?.resume()
    }

    func currentUser() async -> AuthUser? {
        let answer = await base.currentUser()
        let armed = gate.withLock { gate in
            let armed = gate.armed
            gate.armed = false
            return armed
        }
        guard armed else { return answer }
        await withCheckedContinuation { continuation in gate.withLock { $0.parked = continuation } }
        return answer
    }

    var state: AsyncStream<AuthState> { base.state }
    func idToken(forceRefresh: Bool) async -> BearerToken? { await base.idToken(forceRefresh: forceRefresh) }
    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { try await base.signIn(email: email, password: password) }
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { try await base.signUp(email: email, password: password) }
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser { try await base.signIn(with: credential) }
    func sendPasswordReset(email: String) async throws(AuthErrorCode) { try await base.sendPasswordReset(email: email) }
    func sendVerificationEmail() async throws(AuthErrorCode) { try await base.sendVerificationEmail() }
    func reload() async throws(AuthErrorCode) -> AuthUser { try await base.reload() }
    func reauthenticate(password: String) async throws(AuthErrorCode) { try await base.reauthenticate(password: password) }
    func reauthenticate(with credential: OAuthCredential) async throws(AuthErrorCode) { try await base.reauthenticate(with: credential) }
    func updatePassword(_ new: String) async throws(AuthErrorCode) { try await base.updatePassword(new) }
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode) { try await base.verifyBeforeUpdateEmail(new) }
    func deleteUser() async throws(AuthErrorCode) { try await base.deleteUser() }
    func signOut() throws(AuthErrorCode) { try base.signOut() }
    func refreshRefusal(signedFor uid: String?) async -> AuthErrorCode? { await base.refreshRefusal(signedFor: uid) }
}
