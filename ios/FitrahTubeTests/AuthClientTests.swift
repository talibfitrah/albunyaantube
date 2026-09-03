import FitrahAPI
import Foundation
import Testing
@testable import FitrahTube

@Suite struct AuthClientTests {

    private static let user = AuthUser(uid: "u1", email: "student@fitrah.test", isEmailVerified: true,
                                       providerIDs: ["password"])

    /// Subscribes to a stream and collects what it sees, with a BOUNDED yield loop to wait on —
    /// never a sleep, and never an unbounded `for await` that would hang the suite when a subscriber
    /// starves (which is exactly the bug the two tests below exist to catch).
    @MainActor private final class Collector {
        private(set) var seen: [AuthState] = []
        /// Held only so it is not discarded; it ends on its own when the client (and so the
        /// continuation feeding this stream) is released at the end of the test.
        private var task: Task<Void, Never>?

        init(_ stream: AsyncStream<AuthState>) {
            task = Task { @MainActor [weak self] in
                for await state in stream { self?.seen.append(state) }
            }
        }

        func wait(for count: Int) async { for _ in 0..<500 where seen.count < count { await Task.yield() } }
        func settle() async { for _ in 0..<200 { await Task.yield() } }
    }

    // MARK: - FakeAuthClient (app target, #if DEBUG — Task 13's screenshot hook needs it too)

    /// Task 4 shipped ONE stored `AsyncStream` handed to every caller, and `AsyncStream` is
    /// single-consumer: the second subscriber starved forever, silently. Task 9 is the first task
    /// with two of them, so the contract is settled — every `state` access is a FRESH stream that
    /// replays the current state and then carries every transition.
    @Test func everyStateAccessIsAFreshStreamReplayingTheCurrentState() async throws {
        let client = FakeAuthClient(state: .signedOut, user: Self.user)
        let first = Collector(client.state)
        let second = Collector(client.state)
        await first.wait(for: 1)
        await second.wait(for: 1)
        #expect(first.seen == [.signedOut])
        #expect(second.seen == [.signedOut])

        let signedIn = try await client.signIn(email: "student@fitrah.test", password: "hunter2")
        await first.wait(for: 2)
        await second.wait(for: 2)
        #expect(first.seen == [.signedOut, .signedIn(signedIn)])
        #expect(second.seen == [.signedOut, .signedIn(signedIn)])
    }

    /// A no-op transition emits NOTHING: Firebase's listener does not re-announce an unchanged
    /// state, so a fixture that did would let a ViewModel test pass against a sequence the real
    /// client can never produce.
    @Test func signingOutWhileAlreadySignedOutEmitsNothing() async {
        let client = FakeAuthClient(state: .signedOut, user: Self.user)
        let states = Collector(client.state)
        await states.wait(for: 1)

        client.signOut()
        await states.settle()
        #expect(states.seen == [.signedOut])
    }


    /// Fix round 1 / I2. `AuthStateBroadcaster` registered the new observer and read `current` under
    /// the mutex but yielded the replay AFTER releasing it, and `send` snapshotted the observers under
    /// the mutex and yielded after — so a `send(S1)` landing in either window delivered S1 to a new
    /// subscriber BEFORE its replayed S0, leaving the stale state last. Both yields now happen inside
    /// `withLock`, which serializes "become current" with "tell everyone".
    ///
    /// **No deterministic red exists for the race itself**: the window is between a `withLock`
    /// returning and the next statement, with no seam to interpose on, and a stress loop would be a
    /// flaky test rather than a proof. What is pinned instead is the CONTRACT the fix makes total —
    /// a stream opened after a transition replays the NEW state, and an existing subscriber sees the
    /// two in order.
    @Test func aStreamOpenedAfterATransitionReplaysTheNewStateAndTheOldSubscriberSeesBothInOrder() async throws {
        let client = FakeAuthClient(state: .signedOut, user: Self.user)
        let existing = Collector(client.state)
        await existing.wait(for: 1)

        let signedIn = try await client.signIn(email: "student@fitrah.test", password: "hunter2")
        await existing.wait(for: 2)

        let late = Collector(client.state)
        await late.wait(for: 1)
        await late.settle()
        #expect(late.seen == [.signedIn(signedIn)], "a late subscriber replays the CURRENT state, once")
        #expect(existing.seen == [.signedOut, .signedIn(signedIn)], "and in order, never inverted")
    }


    /// The stream is 1:1 with Firebase's auth-state listener: the CURRENT state first, then one
    /// element per transition. Buffering is unbounded, so a yield that lands before the consumer
    /// starts is still delivered — no rendezvous, no sleep.
    @Test func theFakeClientYieldsItsInitialStateAndThenWhatOperationsDo() async throws {
        let client = FakeAuthClient(state: .signedOut, user: Self.user)
        var states = client.state.makeAsyncIterator()

        let first = await states.next()
        #expect(first == .signedOut)

        let signedIn = try await client.signIn(email: "student@fitrah.test", password: "hunter2")
        let second = await states.next()
        #expect(second == .signedIn(signedIn))

        client.signOut()
        let third = await states.next()
        #expect(third == .signedOut)
    }

    @Test func theFakeClientHandsBackItsUserAndATokenOnlyWhenSignedIn() async throws {
        let signedOut = FakeAuthClient(state: .signedOut, user: Self.user)
        #expect(await signedOut.currentUser() == nil)
        #expect(await signedOut.idToken(forceRefresh: false) == nil)

        let signedIn = FakeAuthClient(state: .signedIn(Self.user), user: Self.user)
        #expect(await signedIn.currentUser() == Self.user)
        #expect(await signedIn.idToken(forceRefresh: false) != nil)
        let reloaded = try await signedIn.reload()
        #expect(reloaded == Self.user)
    }

    /// The per-call failure leg. `nextError` fails exactly the next operation and then clears —
    /// otherwise a ViewModel test scripting one failure would get an unsigned-in client forever.
    @Test func theFakeClientFailsTheNextCallWithNextErrorAndThenClearsIt() async throws {
        let client = FakeAuthClient(state: .signedOut, user: Self.user)
        client.nextError = .wrongPassword

        await #expect(throws: AuthErrorCode.wrongPassword) {
            try await client.signIn(email: "student@fitrah.test", password: "wrong")
        }
        #expect(client.nextError == nil)
        _ = try await client.signIn(email: "student@fitrah.test", password: "hunter2")
    }

    @Test func theFakeClientReplaysItsScriptedErrorsInOrder() async throws {
        let client = FakeAuthClient(state: .signedOut, user: Self.user,
                                    scriptedErrors: [.network, .tooManyRequests])

        await #expect(throws: AuthErrorCode.network) { try await client.sendPasswordReset(email: "a@b.test") }
        await #expect(throws: AuthErrorCode.tooManyRequests) { try await client.sendPasswordReset(email: "a@b.test") }
        try await client.sendPasswordReset(email: "a@b.test")
    }

    // MARK: - UnavailableAuthClient (no GoogleService-Info.plist — this machine, CI, every checkout)

    @Test func theUnavailableClientYieldsSignedOutOnceAndFinishes() async {
        var seen: [AuthState] = []
        for await state in UnavailableAuthClient().state { seen.append(state) }
        #expect(seen == [.signedOut])
    }

    @Test func theUnavailableClientHasNoUserNoTokenAndASilentSignOut() async {
        let client = UnavailableAuthClient()
        // Ruling F12, pinned by the compiler: `AuthClient` REFINES `AuthTokenProviding`, so the
        // container's one auth object is handed straight to `AuthMiddleware` (Task 6) and
        // `AuthorizedTransport` (Task 7) with no adapter. A merely matching method signature does
        // not create conformance in Swift, and this line would not compile.
        let provider: any AuthTokenProviding = client
        #expect(await provider.idToken(forceRefresh: true) == nil)
        #expect(await client.currentUser() == nil)
        client.signOut()
        #expect(await client.currentUser() == nil, "a silent sign-out still leaves no user")
    }

    /// Contradiction 5: with no plist every auth operation must FAIL, visibly and identically —
    /// never succeed silently, never trap.
    @Test func everyUnavailableClientOperationThrowsUnknown() async {
        let client = UnavailableAuthClient()
        let credential = OAuthCredential(providerID: "google.com", idToken: "t", accessTokenOrNonce: nil)

        await #expect(throws: AuthErrorCode.unknown) { try await client.signIn(email: "a@b.test", password: "p") }
        await #expect(throws: AuthErrorCode.unknown) { try await client.signUp(email: "a@b.test", password: "p") }
        await #expect(throws: AuthErrorCode.unknown) { try await client.signIn(with: credential) }
        await #expect(throws: AuthErrorCode.unknown) { try await client.sendPasswordReset(email: "a@b.test") }
        await #expect(throws: AuthErrorCode.unknown) { try await client.sendVerificationEmail() }
        await #expect(throws: AuthErrorCode.unknown) { try await client.reload() }
        await #expect(throws: AuthErrorCode.unknown) { try await client.reauthenticate(password: "p") }
        await #expect(throws: AuthErrorCode.unknown) { try await client.updatePassword("p") }
        await #expect(throws: AuthErrorCode.unknown) { try await client.verifyBeforeUpdateEmail("a@b.test") }
        await #expect(throws: AuthErrorCode.unknown) { try await client.deleteUser() }
    }
}
