#if DEBUG
import FitrahAPI
import Foundation
import Synchronization

/// The fixture `AuthClient`. It lives in the APP target, not `FitrahTubeTests`, for the same reason
/// `ParkedOfflineEngine`/`FixedStatusTransport` do: `AppContainer.fake()` and Task 13's
/// `-fitrah-fake-auth` screenshot hook both need it, and neither can see the test bundle.
/// `#if DEBUG` is the whole fixture surface — Release has no fake auth path at all.
///
/// Scripted, never timed: `scriptedErrors` is a queue consumed in order and `nextError` fails
/// exactly the next call. No sleeps, no clock.
nonisolated final class FakeAuthClient: AuthClient {
    /// A fresh stream per access, replaying the current state — the ONE `AuthClient.state` contract
    /// (`AuthClient.swift`). Two subscribers both see everything.
    var state: AsyncStream<AuthState> { broadcaster.stream }

    /// Fix round 1 / M5: `signIn(email:)`, `signUp(email:)` and `signIn(with:)` share one body, so
    /// nothing downstream could tell which one ran — `SignInViewModelTests`' mode-toggle test named
    /// a fact it did not pin. Recorded in order.
    nonisolated enum EntryPoint: Sendable, Equatable { case signIn, signUp, credential }

    /// Task 17: the credential-mutating calls, in order. The email sheet's contract is "re-auth,
    /// then `verifyBeforeUpdateEmail`, and NEVER an `updateEmail`-shaped call" — an ORDER and an
    /// absence, neither of which a per-method flag can express. Only calls that mutate the
    /// CREDENTIAL are recorded: nothing else in the suite asserts on a call sequence.
    /// Task 18 adds `deleteUser` — the delete flow's contract is the same shape (the device wipe
    /// runs first, and the admin-side path must delete no Firebase user at all).
    /// Stage 9 / P1 adds `reauthenticateCredential` — the federated proof is an ABSENCE assertion
    /// (`entryPoints` must never gain a `.credential`, i.e. the delete never signed anybody in)
    /// paired with a presence one, which is exactly this recorder's shape.
    nonisolated enum Operation: Sendable, Equatable {
        case reauthenticate, reauthenticateCredential, updatePassword, verifyBeforeUpdateEmail, deleteUser
    }

    private struct Storage {
        var user: AuthUser
        var scriptedErrors: [AuthErrorCode]
        var nextError: AuthErrorCode?
        var reloadedUser: AuthUser?
        var entryPoints: [EntryPoint] = []
        var operations: [Operation] = []
        /// Stage 5 / C3.1: the claims the ID TOKEN carries, which LAG the user record until a
        /// forced refresh — nil until one happens, i.e. the token still says what it said when the
        /// fixture was built. `reload()` refreshes the record and never the token, which is the
        /// exact gap `EmailVerificationViewModel` has to close before the backend (which gates on
        /// the token claim) will accept a just-verified account.
        var tokenClaims: AuthUser?
        /// Every `idToken(forceRefresh:)` argument, in order.
        var tokenRefreshes: [Bool] = []
        /// Stage 9 round 3 / R3-P1: the code the next FORCED mint is refused with, and what that
        /// refusal recorded for `refreshRefusal()` to report. The fake had no way to fail a mint at
        /// all, so the one production supplier of the terminal verdict was unexpressible and every
        /// test handed `AuthorizedTransport` an inline closure instead — which is how the real
        /// supplier came to be dead code with a fully pinned consumer.
        var nextMintRefusal: AuthErrorCode?
        var recordedRefusal: AuthErrorCode?
    }

    /// `Mutex` rather than `@unchecked Sendable` + bare vars: `AuthClient` is `Sendable` (it refines
    /// `AuthTokenProviding`) and a fixture is driven from whatever isolation a test happens to use.
    private let storage: Mutex<Storage>
    private let broadcaster: AuthStateBroadcaster

    /// The default account when a caller does not care who is signed in.
    static let defaultUser = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                      isEmailVerified: true, providerIDs: ["password"])

    init(state: AuthState, user: AuthUser? = nil, scriptedErrors: [AuthErrorCode] = []) {
        broadcaster = AuthStateBroadcaster(current: state)
        storage = Mutex(Storage(user: user ?? Self.defaultUser,
                                scriptedErrors: scriptedErrors, nextError: nil))
    }

    /// Stage 9 round 2 / P1: the identity the NEXT sign-in presents. A `let` here meant one fixture
    /// carried ONE account for its whole life, so the cross-account window — account A's `/me` in
    /// flight while B signs in on the same stream — was not expressible at all, and the uid guards
    /// that stop A's record being published under B's session rested on inspection. Nothing else
    /// changes: unset, every sign-in still answers the account the fixture was built with.
    var user: AuthUser {
        get { storage.withLock { $0.user } }
        set { storage.withLock { $0.user = newValue } }
    }

    /// The per-call failure leg for a FORCED token mint: set it, and the next
    /// `idToken(forceRefresh: true)` answers nil and records this code for `refreshRefusal()`.
    var nextMintRefusal: AuthErrorCode? {
        get { storage.withLock { $0.nextMintRefusal } }
        set { storage.withLock { $0.nextMintRefusal = newValue } }
    }

    /// The per-call failure leg: set it, and the next operation throws it and clears it.
    var nextError: AuthErrorCode? {
        get { storage.withLock { $0.nextError } }
        set { storage.withLock { $0.nextError = newValue } }
    }

    /// Task 11: what the next `reload()` answers, when that differs from the state the fixture was
    /// constructed with. Firebase's listener does not fire on a reload, so a real client can hold a
    /// stale `isEmailVerified` while `reload()` returns the flipped one — this is that gap, and
    /// `EmailVerificationViewModel` exists to close it. Unset -> `reload()` answers the current user.
    var reloadedUser: AuthUser? {
        get { storage.withLock { $0.reloadedUser } }
        set { storage.withLock { $0.reloadedUser = newValue } }
    }

    func currentUser() async -> AuthUser? { signedInUser() }

    /// The uid rides along so `BearerRetry`'s cross-account guard is exercisable from a fixture:
    /// two `FakeAuthClient`s with different uids are two different signing identities.
    ///
    /// Stage 5 / C3.1: the token's CLAIMS are versioned separately from the user record. Until a
    /// forced refresh the value carries the claims the fixture was built with, even after a
    /// `reload()` has flipped `isEmailVerified` on the record — the real client behaves exactly
    /// this way and the old one-line fake could never disagree with itself.
    func idToken(forceRefresh: Bool) async -> BearerToken? {
        // Stage 9 round 4 / NB-A: the no-user guard CLEARS the box, mirroring the real client.
        // Answering nil here without clearing is what let one identity's unconsumed refusal be
        // read as a later, session-less 401's own verdict.
        guard let user = signedInUser() else {
            storage.withLock { $0.recordedRefusal = nil }
            return nil
        }
        let claims = storage.withLock { storage -> AuthUser? in
            storage.tokenRefreshes.append(forceRefresh)
            // Stage 9 round 3 / R3-P1: a refused mint RECORDS its code here, exactly where the real
            // client records it — `refreshRefusal()` reports what this call saw and never mints
            // again, because Firebase has already signed the user out by then.
            if forceRefresh, let refusal = storage.nextMintRefusal {
                storage.nextMintRefusal = nil
                storage.recordedRefusal = refusal
                return nil
            }
            // Stage 9 round 4 / R4-P2: and a SUCCESSFUL mint clears it. The box says why the last
            // mint was refused; a mint that worked is the refusal's expiry.
            storage.recordedRefusal = nil
            if forceRefresh { storage.tokenClaims = storage.reloadedUser ?? user }
            return storage.tokenClaims ?? user
        }
        guard let claims else { return nil }
        return BearerToken(value: "fake-id-token-\(claims.uid)-verified-\(claims.isEmailVerified)",
                           identity: claims.uid)
    }

    /// Every `idToken(forceRefresh:)` argument, in order.
    var tokenRefreshes: [Bool] { storage.withLock { $0.tokenRefreshes } }

    /// What the last refused forced mint recorded, consumed on read — the contract
    /// `FirebaseAuthClient` now honours (Stage 9 round 3 / R3-P1). Nil with no refusal, which is
    /// every suite that never sets `nextMintRefusal`.
    func refreshRefusal() async -> AuthErrorCode? {
        storage.withLock { storage in
            let recorded = storage.recordedRefusal
            storage.recordedRefusal = nil
            return recorded
        }
    }

    /// Every sign-in entry point that has been called, in order.
    var entryPoints: [EntryPoint] { storage.withLock { $0.entryPoints } }

    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds(.signIn) }
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds(.signUp) }
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds(.credential) }

    /// Every recorded operation, in order.
    var operations: [Operation] { storage.withLock { $0.operations } }

    func sendPasswordReset(email: String) async throws(AuthErrorCode) { try consumeError() }
    func sendVerificationEmail() async throws(AuthErrorCode) { try consumeError() }

    // Recorded BEFORE the scripted error is consumed, for `signInSucceeds`'s reason: a refused
    // attempt still reached the call, which is the fact a sequence assertion needs.
    func reauthenticate(password: String) async throws(AuthErrorCode) { try record(.reauthenticate) }
    func reauthenticate(with credential: OAuthCredential) async throws(AuthErrorCode) { try record(.reauthenticateCredential) }
    func updatePassword(_ new: String) async throws(AuthErrorCode) { try record(.updatePassword) }
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode) { try record(.verifyBeforeUpdateEmail) }

    func reload() async throws(AuthErrorCode) -> AuthUser {
        try consumeError()
        guard let user = signedInUser() else { throw AuthErrorCode.unknown }
        return reloadedUser ?? user
    }

    /// Stage 3 / M1: the "is anyone signed in?" guard `reload()` above and every `requireUser()`
    /// member of `FirebaseAuthClient` already have. Without it this fake deleted nothing and said
    /// it had, which is what let `AccountSession`'s chained late-delete ship green over a path that
    /// ran AFTER the sign-out and could therefore never delete anything.
    /// The guard runs BEFORE the recording, unlike the three `record(...)` members above: a
    /// scripted refusal still *reached* Firebase and is worth recording, but a delete with nobody
    /// signed in never touched a credential at all — recording it is the exact lie this guard
    /// exists to stop.
    func deleteUser() async throws(AuthErrorCode) {
        guard signedInUser() != nil else { throw AuthErrorCode.unknown }
        try record(.deleteUser)
        transition(to: .signedOut)
    }

    /// Stage 5 / C3.2: failure-injectable. A total, unconditional `signOut()` could never observe
    /// production swallowing a Keychain error. Not in `operations` — that sequence is about
    /// CREDENTIAL mutations, and a sign-out mutates none.
    func signOut() throws(AuthErrorCode) {
        try consumeError()
        transition(to: .signedOut)
    }

    // MARK: -

    /// Recorded BEFORE the scripted error is consumed: a refused attempt still reached this entry
    /// point, which is the fact a caller-routing assertion needs.
    private func signInSucceeds(_ entryPoint: EntryPoint) throws(AuthErrorCode) -> AuthUser {
        storage.withLock { $0.entryPoints.append(entryPoint) }
        try consumeError()
        let user = storage.withLock { $0.user }
        transition(to: .signedIn(user))
        return user
    }

    private func record(_ operation: Operation) throws(AuthErrorCode) {
        storage.withLock { $0.operations.append(operation) }
        try consumeError()
    }

    /// `nextError` first, then the scripted queue. Both are consumed, so a scripted failure never
    /// wedges the fixture into failing forever.
    private func consumeError() throws(AuthErrorCode) {
        let error = storage.withLock { storage -> AuthErrorCode? in
            if let next = storage.nextError {
                storage.nextError = nil
                return next
            }
            return storage.scriptedErrors.isEmpty ? nil : storage.scriptedErrors.removeFirst()
        }
        if let error { throw error }
    }

    private func signedInUser() -> AuthUser? {
        if case .signedIn(let user) = broadcaster.current { return user }
        return nil
    }

    /// A no-op transition emits NOTHING — `signOut()` on an already-signed-out fixture must not
    /// produce a duplicate `.signedOut` the Firebase listener would never send. The drop lives in
    /// `AuthStateBroadcaster.send`, so both conformers get it.
    private func transition(to state: AuthState) { broadcaster.send(state) }
}
#endif
