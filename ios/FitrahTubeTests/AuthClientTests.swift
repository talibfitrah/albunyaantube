import FitrahAPI
import Foundation
import Testing
@testable import FitrahTube

@Suite struct AuthClientTests {

    private static let user = AuthUser(uid: "u1", email: "student@fitrah.test", isEmailVerified: true,
                                       providerIDs: ["password"])

    // MARK: - FakeAuthClient (app target, #if DEBUG — Task 13's screenshot hook needs it too)

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
