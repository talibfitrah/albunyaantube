import FirebaseCore
import Foundation
import GoogleSignIn

/// The ONE file in the app that imports FirebaseCore (plan Global Constraints: Firebase names live
/// in five named files and nowhere else — never in a ViewModel, never in a test-visible interface).
///
/// Every member is safe to call with NO `GoogleService-Info.plist`. That file is git-ignored and
/// USER-BLOCKED, so the absent path is the one this machine, CI and every fresh checkout take: the
/// app comes up as a guest and nothing here traps.
nonisolated enum FirebaseBootstrap {
    /// Whether the bundle carries a Firebase options file. `copy-firebase-plist.sh` (a `test.sh`
    /// pre-stage) is what puts one there, and it exits 0 when the source is absent.
    static var optionsFileExists: Bool { optionsPath != nil }

    /// The OAuth client id Google Sign-In needs, straight out of the options file — `nil` with no
    /// plist, which is exactly "the Google button has nothing to sign into" (Task 5's capability).
    /// Read through `FirebaseOptions` rather than a hand-rolled plist parse so the key names stay
    /// the SDK's problem.
    ///
    /// Stage 9 round 3 / R3-P3: a `static let`, i.e. parsed ONCE. As a computed property it read
    /// and parsed the plist on every access, and `SignInCapabilities.current()` reads it from
    /// `GoogleAuthProvider.isAvailable`, `AppleAuthProvider.isAvailable`,
    /// `SignInViewModel.signIn(with:)` and `AppContainer.init` — main-thread file I/O on every
    /// sign-in button render and tap. The bundle cannot gain a plist mid-process, so a value that
    /// never changes is now read as one.
    static let googleClientID: String? = optionsPath.flatMap(FirebaseOptions.init(contentsOfFile:))?.clientID

    /// IDEMPOTENT and SELF-CALLING: `AppContainer.live()` runs from a stored-property initializer,
    /// which Swift evaluates BEFORE the body of `FitrahTubeApp.init()`, so the auth builder (Task 4)
    /// calls this itself and must never assume the warm-up already ran. A second
    /// `FirebaseApp.configure()` logs a fatal error, so `didConfigure` — a `static let`, i.e. the
    /// `dispatch_once` shape with none of the ceremony — is what makes calling it twice safe.
    ///
    /// - Returns: whether Firebase is configured (always `false` with no options file).
    @discardableResult static func configureIfPossible() -> Bool { didConfigure }

    /// `true` when the URL was a Google Sign-In callback and was consumed. Anything else — every
    /// `albunyaantube://` deep link — returns `false` and falls through to `DeepLinkParser`.
    static func handleOpenURL(_ url: URL) -> Bool { GIDSignIn.sharedInstance.handle(url) }

    private static var optionsPath: String? {
        Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist")
    }

    private static let didConfigure: Bool = {
        guard optionsFileExists else { return false }
        FirebaseApp.configure()
        // Set here, not in `googleClientID`'s getter: the SDK hand-off is a side effect and belongs
        // on the one path that runs exactly once, before any sign-in can be attempted — every
        // caller of Google sign-in goes through `configureIfPossible()` first.
        if let clientID = googleClientID {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        }
        return FirebaseApp.app() != nil
    }()
}
