import FitrahAPI
import Foundation
import Synchronization

/// A signed-in account, reduced to what the app renders and decides on. Firebase's `User` never
/// leaves `FirebaseAuthClient.swift`.
nonisolated struct AuthUser: Sendable, Equatable {
    var uid: String
    var email: String?
    var isEmailVerified: Bool
    /// Firebase provider ids: "password", "google.com", "apple.com".
    var providerIDs: [String]
    var hasPasswordProvider: Bool { providerIDs.contains("password") }
}

/// 1:1 with Firebase's auth-state listener. Operation loading/error state NEVER lives here —
/// it belongs on the calling screen's UI state (`AuthState.kt:11-12`).
nonisolated enum AuthState: Sendable, Equatable { case signedOut, signedIn(AuthUser) }

/// Android's 13 codes minus `MICROSOFT_SIGN_IN_FAILED` (spec §3 Out) plus `appleSignInFailed`.
nonisolated enum AuthErrorCode: String, Error, Sendable, Equatable, CaseIterable {
    case invalidEmail, wrongPassword, userNotFound, userDisabled, emailAlreadyInUse, weakPassword
    case network, tooManyRequests, invalidCredential
    case googleSignInFailed, appleSignInFailed, passwordResetFailed, unknown

    /// The input mapping is rewritten against the iOS SDK (ruling C12): Firebase iOS raises
    /// `NSError` in `AuthErrors.domain` whose `code` is an `AuthErrorCode` raw Int, NOT Android's
    /// "ERROR_INVALID_EMAIL" strings. Taking an Int keeps this table — and its test — free of any
    /// Firebase import, which is what lets the test target name nothing Firebase-side. The Ints are
    /// `FirebaseAuth.AuthErrorCode`'s raw values; the SDK case name is in each comment.
    ///
    /// Anything not listed is `.unknown` — never a nearby case. `FirebaseAuthClient` is what
    /// checks the error's DOMAIN before calling this, so a code from some other `NSError` domain
    /// never reaches the table.
    init(firebaseCode: Int) {
        switch firebaseCode {
        case 17008: self = .invalidEmail                    // invalidEmail
        case 17009: self = .wrongPassword                   // wrongPassword
        case 17011: self = .userNotFound                    // userNotFound
        case 17005: self = .userDisabled                    // userDisabled
        case 17007: self = .emailAlreadyInUse               // emailAlreadyInUse
        case 17026: self = .weakPassword                    // weakPassword
        // Android collapses these two into one code (`AuthErrorMapper.kt:24`) and so do we: an
        // expired/rejected token and a rejected credential are the same "sign in again" to a user.
        case 17004, 17017: self = .invalidCredential        // invalidCredential, invalidUserToken
        case 17020: self = .network                         // networkError
        case 17010: self = .tooManyRequests                 // tooManyRequests
        default: self = .unknown
        }
    }

    var messageKey: String {
        switch self {
        case .invalidEmail: "auth_error_invalid_email"
        case .wrongPassword: "auth_error_wrong_password"
        // Stage 8 / S6: `.wrongPassword`'s copy, and the key it used to own is retired. The one
        // production renderer of `messageKey` (`SignInScreen`) only ever sees codes that have been
        // through `SignInViewModel.presented(_:)`, which collapses this one for Stage 4 / I2's
        // membership-oracle reason — and no other caller renders `messageKey` at all, so the
        // distinct string had become unreachable copy rather than a reserve.
        case .userNotFound: "auth_error_wrong_password"
        case .userDisabled: "auth_error_user_disabled"
        case .emailAlreadyInUse: "auth_error_email_in_use"
        case .weakPassword: "auth_error_weak_password"
        case .network: "auth_error_network"
        case .tooManyRequests: "auth_error_too_many_requests"
        case .invalidCredential: "auth_error_invalid_credential"
        case .googleSignInFailed: "auth_error_google"
        case .appleSignInFailed: "auth_error_apple"      // authored in Task 10
        case .passwordResetFailed: "auth_error_password_reset_failed"
        case .unknown: "auth_error_generic"
        }
    }
}

/// Fans ONE auth-state source out to any number of `AsyncStream` consumers, replaying the current
/// state to each on subscription — the shape both real conformers need and neither should own.
/// `UnavailableAuthClient` needs none of it: it has one state, forever, and its stream finishes.
///
/// **A transition to the state already held is dropped.** Firebase's listener does not re-announce
/// an unchanged state, so a fixture that did (`signOut()` on an already-signed-out account emitting
/// a second `.signedOut`) would let a ViewModel test pass against a sequence the real client can
/// never produce.
nonisolated final class AuthStateBroadcaster: Sendable {
    private struct Storage {
        var current: AuthState
        var observers: [UUID: AsyncStream<AuthState>.Continuation] = [:]
    }

    private let storage: Mutex<Storage>

    init(current: AuthState) { storage = Mutex(Storage(current: current)) }

    var current: AuthState { storage.withLock { $0.current } }

    var stream: AsyncStream<AuthState> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<AuthState>.makeStream()
        // Firebase's listener delivers the current state on registration; so does every subscriber
        // here, so a late subscriber is never left with no state at all.
        //
        // Fix round 1 / I2: the replay yields INSIDE the lock. Registering under it and yielding
        // after left a window in which a `send(S1)` delivered S1 to this brand-new subscriber before
        // its own replayed S0, leaving the stale state last. `yield` does not re-enter the lock.
        storage.withLock { storage in
            storage.observers[id] = continuation
            continuation.yield(storage.current)
        }
        // `[weak self]`, not `[storage]`: `Mutex` is `~Copyable`, so capturing it directly is a
        // consume the compiler refuses.
        continuation.onTermination = { [weak self] _ in self?.remove(id) }
        return stream
    }

    private func remove(_ id: UUID) { storage.withLock { _ = $0.observers.removeValue(forKey: id) } }

    /// Fix round 1 / I2: "become current" and "tell everyone" happen under ONE lock acquisition, so
    /// two concurrent `send`s cannot invert each other's deliveries.
    func send(_ state: AuthState) {
        storage.withLock { storage in
            guard storage.current != state else { return }
            storage.current = state
            storage.observers.values.forEach { $0.yield(state) }
        }
    }
}

/// What a provider hands back. Declared here (Task 4 owns the file); POPULATED by Task 5's
/// providers. Opaque above this layer.
nonisolated struct OAuthCredential: Sendable {
    let providerID: String
    let idToken: String
    let accessTokenOrNonce: String?
}

/// Refines `AuthTokenProviding` so ONE token source really is one type: `AppContainer.auth` is
/// handed straight to `AuthorizedTransport` (Task 7) with no
/// adapter (ruling F12). A matching method signature does NOT create conformance in Swift, so the
/// refinement is declared, not assumed.
nonisolated protocol AuthClient: AuthTokenProviding {
    /// **One contract, every conformer: each access returns a FRESH stream that replays the current
    /// state immediately and then carries every later transition.** Task 4 shipped a single stored
    /// `AsyncStream` handed to every caller, and `AsyncStream` is single-consumer — the second
    /// subscriber starved forever, silently. Task 9 needed exactly that second subscriber
    /// (`AccountSession.start()` alongside anything else that watches auth), so the lifetime is
    /// settled here rather than left as a rule callers must know. `AuthStateBroadcaster` below is
    /// how `FirebaseAuthClient` and `FakeAuthClient` honour it off ONE upstream listener.
    var state: AsyncStream<AuthState> { get }
    func currentUser() async -> AuthUser?
    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser
    func sendPasswordReset(email: String) async throws(AuthErrorCode)
    func sendVerificationEmail() async throws(AuthErrorCode)
    func reload() async throws(AuthErrorCode) -> AuthUser
    func reauthenticate(password: String) async throws(AuthErrorCode)
    /// Stage 9 / P1: the FEDERATED half of the same proof, and never `signIn(with:)`.
    /// `Auth.signIn(with:)` REPLACES `currentUser` with whoever the provider sheet returned, so a
    /// device with a second Google account could re-point the whole Firebase session and the
    /// `DELETE /api/account/me` that follows would tombstone the account the user did not pick.
    /// `User.reauthenticate(with:)` refuses a credential for a different account instead.
    func reauthenticate(with credential: OAuthCredential) async throws(AuthErrorCode)
    func updatePassword(_ new: String) async throws(AuthErrorCode)
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode)
    func deleteUser() async throws(AuthErrorCode)
    /// Stage 5 / C1.3: THROWS. `Auth.signOut()` assigns `_currentUser = nil` only when the Keychain
    /// write succeeded, so a swallowed failure left the app reporting signed-out while still minting
    /// bearers for the previous account — and the next launch restored it.
    func signOut() throws(AuthErrorCode)
    /// Why the LAST mint attempted by `idToken(forceRefresh:)` was refused, consumed on read — nil
    /// when it was not refused, when nobody is signed in, and on every call after the first.
    ///
    /// Stage 9 round 3 / R3-P1: a REPORT of what the mint saw, never a second mint of its own.
    /// Firebase force-signs the user out inside the throw for `userNotFound`/`userDisabled`
    /// (`User.signOutIfTokenIsInvalid`), so a conformer that re-derived the verdict from
    /// `currentUser` afterwards could only ever answer nil for the two codes that decide anything.
    ///
    /// Stage 5 / M1+M2: `idToken(forceRefresh:)` collapses every refusal into nil, so a TERMINATED
    /// account was indistinguishable from a network stall. The backend answers a revoked token with
    /// a bare 401 (`FirebaseAuthFilter` runs `verifyIdToken(token, checkRevoked)` before its 403
    /// lifecycle arms can run), so the client's only local evidence of what happened is what
    /// Firebase says when asked to mint a new token: `.userNotFound` = the record is gone,
    /// `.userDisabled` = blocked.
    func refreshRefusal() async -> AuthErrorCode?
    // `idToken(forceRefresh:)` is inherited from AuthTokenProviding — do not redeclare it.
}

/// The no-plist conformer, and the one `AppContainer` builds on this machine, on CI and in every
/// fresh checkout: `state` yields `.signedOut` once and finishes, `currentUser()`/`idToken` are
/// nil, every operation throws `.unknown`, `signOut()` is a no-op. No branches — the app comes up
/// as a guest and nothing traps.
nonisolated struct UnavailableAuthClient: AuthClient {
    var state: AsyncStream<AuthState> { AsyncStream { $0.yield(.signedOut); $0.finish() } }
    func currentUser() async -> AuthUser? { nil }
    func idToken(forceRefresh: Bool) async -> BearerToken? { nil }
    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { throw .unknown }
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { throw .unknown }
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser { throw .unknown }
    func sendPasswordReset(email: String) async throws(AuthErrorCode) { throw .unknown }
    func sendVerificationEmail() async throws(AuthErrorCode) { throw .unknown }
    func reload() async throws(AuthErrorCode) -> AuthUser { throw .unknown }
    func reauthenticate(password: String) async throws(AuthErrorCode) { throw .unknown }
    func reauthenticate(with credential: OAuthCredential) async throws(AuthErrorCode) { throw .unknown }
    func updatePassword(_ new: String) async throws(AuthErrorCode) { throw .unknown }
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode) { throw .unknown }
    func deleteUser() async throws(AuthErrorCode) { throw .unknown }
    func signOut() throws(AuthErrorCode) {}
    func refreshRefusal() async -> AuthErrorCode? { nil }
}
