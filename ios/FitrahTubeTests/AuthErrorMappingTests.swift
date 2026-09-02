import Foundation
import Testing
@testable import FitrahTube

/// The raw `Int`s Firebase iOS puts in `NSError.code` for the ten conditions Android's
/// `AuthErrorMapper.kt` names. Ruling C12: the iOS SDK raises `NSError`s in `FIRAuthErrorDomain`
/// whose `code` is a `FirebaseAuth.AuthErrorCode` raw value, NOT Android's `"ERROR_INVALID_EMAIL"`
/// strings — so the port is a different table with the same output, and it is pinned by number.
///
/// Copied from `FirebaseAuth/Sources/Swift/Utilities/AuthErrors.swift`; the SDK case name is in the
/// comment. Written out here, rather than read off the SDK, so this target imports nothing
/// Firebase-side (plan Global Constraints: Firebase names live in five app files and nowhere else).
private enum FirebaseCode {
    static let invalidCredential = 17004      // AuthErrorCode.invalidCredential
    static let userDisabled = 17005           // AuthErrorCode.userDisabled
    static let emailAlreadyInUse = 17007      // AuthErrorCode.emailAlreadyInUse
    static let invalidEmail = 17008           // AuthErrorCode.invalidEmail
    static let wrongPassword = 17009          // AuthErrorCode.wrongPassword
    static let tooManyRequests = 17010        // AuthErrorCode.tooManyRequests
    static let userNotFound = 17011           // AuthErrorCode.userNotFound
    static let invalidUserToken = 17017       // AuthErrorCode.invalidUserToken
    static let networkError = 17020           // AuthErrorCode.networkError
    static let weakPassword = 17026           // AuthErrorCode.weakPassword
}

@Suite struct AuthErrorMappingTests {

    /// One row per condition Android's mapper covers, including the two it deliberately collapses
    /// (`ERROR_INVALID_CREDENTIAL` and `ERROR_INVALID_USER_TOKEN` both → `INVALID_CREDENTIAL`,
    /// `AuthErrorMapper.kt:24`).
    @Test func everyConditionAndroidsMapperCoversMapsFromItsIOSRawValue() {
        let table: [(Int, AuthErrorCode)] = [
            (FirebaseCode.invalidEmail, .invalidEmail),
            (FirebaseCode.wrongPassword, .wrongPassword),
            (FirebaseCode.userNotFound, .userNotFound),
            (FirebaseCode.userDisabled, .userDisabled),
            (FirebaseCode.emailAlreadyInUse, .emailAlreadyInUse),
            (FirebaseCode.weakPassword, .weakPassword),
            (FirebaseCode.invalidCredential, .invalidCredential),
            (FirebaseCode.invalidUserToken, .invalidCredential),
            (FirebaseCode.networkError, .network),
            (FirebaseCode.tooManyRequests, .tooManyRequests),
        ]
        for (raw, expected) in table {
            #expect(AuthErrorCode(firebaseCode: raw) == expected, "\(raw) mapped wrong")
        }
    }

    /// The default arm. `17000` is a real SDK code (`invalidCustomToken`) this app can never
    /// provoke, and `-1`/`0` are what a non-Auth `NSError` would carry: all three are `.unknown`,
    /// never a nearby case.
    @Test func aCodeOutsideTheTableIsUnknown() {
        #expect(AuthErrorCode(firebaseCode: 17000) == .unknown)   // AuthErrorCode.invalidCustomToken
        #expect(AuthErrorCode(firebaseCode: 0) == .unknown)
        #expect(AuthErrorCode(firebaseCode: -1) == .unknown)
    }

    /// Android's 13 codes minus `MICROSOFT_SIGN_IN_FAILED` (spec §3 Out) plus `appleSignInFailed`.
    /// A 14th case added without a `messageKey` and without a screen is what this catches.
    @Test func theTableIsThirteenCodes() {
        #expect(AuthErrorCode.allCases.count == 13)
    }

    /// Every code an auth screen can render must have copy in all three locales — a missing key
    /// resolves to the key itself, which is what the user would see.
    ///
    /// `.appleSignInFailed` is EXCLUDED on purpose: `auth_error_apple` is authored in Task 10 with
    /// the Apple button it belongs to (this task ships no strings). Delete the filter then.
    @Test func everyMessageKeyResolvesInEnglishArabicAndDutch() {
        for code in AuthErrorCode.allCases where code != .appleSignInFailed {
            for locale in ["en", "ar", "nl"] {
                let bundle = Format.localizedBundle(for: Locale(identifier: locale))
                let value = bundle.localizedString(forKey: code.messageKey, value: nil, table: nil)
                #expect(value != code.messageKey, "\(locale)/\(code.messageKey) is missing")
            }
        }
    }
}
