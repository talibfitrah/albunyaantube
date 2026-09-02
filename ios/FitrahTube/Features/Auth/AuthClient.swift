import FitrahAPI
import Foundation

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
        case .userNotFound: "auth_error_user_not_found"
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

/// What a provider hands back. Declared here (Task 4 owns the file); POPULATED by Task 5's
/// providers. Opaque above this layer.
nonisolated struct OAuthCredential: Sendable {
    let providerID: String
    let idToken: String
    let accessTokenOrNonce: String?
}

/// Refines `AuthTokenProviding` so ONE token source really is one type: `AppContainer.auth` is
/// handed straight to `AuthMiddleware` (Task 6) and to `AuthorizedTransport` (Task 7) with no
/// adapter (ruling F12). A matching method signature does NOT create conformance in Swift, so the
/// refinement is declared, not assumed.
nonisolated protocol AuthClient: AuthTokenProviding {
    var state: AsyncStream<AuthState> { get }
    func currentUser() async -> AuthUser?
    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser
    func sendPasswordReset(email: String) async throws(AuthErrorCode)
    func sendVerificationEmail() async throws(AuthErrorCode)
    func reload() async throws(AuthErrorCode) -> AuthUser
    func reauthenticate(password: String) async throws(AuthErrorCode)
    func updatePassword(_ new: String) async throws(AuthErrorCode)
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode)
    func deleteUser() async throws(AuthErrorCode)
    func signOut()
    // `idToken(forceRefresh:)` is inherited from AuthTokenProviding — do not redeclare it.
}

/// The no-plist conformer, and the one `AppContainer` builds on this machine, on CI and in every
/// fresh checkout: `state` yields `.signedOut` once and finishes, `currentUser()`/`idToken` are
/// nil, every operation throws `.unknown`, `signOut()` is a no-op. No branches — the app comes up
/// as a guest and nothing traps.
nonisolated struct UnavailableAuthClient: AuthClient {
    var state: AsyncStream<AuthState> { AsyncStream { $0.yield(.signedOut); $0.finish() } }
    func currentUser() async -> AuthUser? { nil }
    func idToken(forceRefresh: Bool) async -> String? { nil }
    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { throw .unknown }
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { throw .unknown }
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser { throw .unknown }
    func sendPasswordReset(email: String) async throws(AuthErrorCode) { throw .unknown }
    func sendVerificationEmail() async throws(AuthErrorCode) { throw .unknown }
    func reload() async throws(AuthErrorCode) -> AuthUser { throw .unknown }
    func reauthenticate(password: String) async throws(AuthErrorCode) { throw .unknown }
    func updatePassword(_ new: String) async throws(AuthErrorCode) { throw .unknown }
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode) { throw .unknown }
    func deleteUser() async throws(AuthErrorCode) { throw .unknown }
    func signOut() {}
}
