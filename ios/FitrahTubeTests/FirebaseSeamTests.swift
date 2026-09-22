import Foundation
import Testing
@testable import FitrahTube

/// Phase 4 Task 2. The seam's contract on the path this machine (and CI) actually runs:
/// `GoogleService-Info.plist` is git-ignored and USER-BLOCKED, so every member here is exercised
/// with NO options file and the app is expected to come up as a guest rather than trap.
///
/// Deliberately names no Firebase type (plan Global Constraints): the whole SDK surface lives
/// behind `FirebaseBootstrap`, and this bundle depends on neither Firebase package.
@Suite struct FirebaseSeamTests {
    /// Written as `== optionsFileExists`, not `== false`: today the bundle has no plist so this
    /// pins the no-op, and the day a real one lands the SAME line becomes "Firebase actually
    /// configured" with no test edit.
    @Test func configureReportsWhetherFirebaseIsConfigured() {
        #expect(FirebaseBootstrap.configureIfPossible() == FirebaseBootstrap.optionsFileExists)
    }

    /// `optionsFileExists` pinned to the BUNDLE, not to itself — otherwise the assertion above is
    /// a tautology that would hold with the flag hard-coded either way.
    @Test func theOptionsFileFlagIsPinnedToTheBundle() {
        #expect(FirebaseBootstrap.optionsFileExists
            == (Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil))
    }

    /// Idempotence is load-bearing, not hygiene: `AppContainer.live()` runs from a stored-property
    /// initializer BEFORE `FitrahTubeApp.init()`'s body, so the auth builder (Task 4) and the
    /// warm-up both call this on every launch. A second `FirebaseApp.configure()` logs a fatal
    /// error, so the latch is what keeps the app alive.
    @Test func configureIsIdempotent() {
        let first = FirebaseBootstrap.configureIfPossible()
        #expect(FirebaseBootstrap.configureIfPossible() == first)
    }

    /// The app's own deep link must fall THROUGH to `DeepLinkParser` — `.onOpenURL` gives Google
    /// first refusal, and a bootstrap that swallowed everything would silently kill every
    /// `albunyaantube://` link.
    @Test func handleOpenURLDeclinesTheAppsOwnDeepLinkScheme() throws {
        let url = try #require(URL(string: "albunyaantube://video/xc7keR2piUM"))
        #expect(FirebaseBootstrap.handleOpenURL(url) == false)
    }

    /// The four data types Phase 4 adds to what the app collects, plus the Firebase uid and the
    /// pre-existing device id. Read from the BUILT bundle (`Bundle.main` in this hosted target is
    /// the app bundle), so this fails if the manifest stops being copied as a resource too.
    @Test func thePrivacyManifestDeclaresWhatPhase4Collects() throws {
        let url = try #require(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        let manifest = try PropertyListSerialization.propertyList(
            from: Data(contentsOf: url), format: nil) as? [String: Any]
        let declared = try #require(manifest?["NSPrivacyCollectedDataTypes"] as? [[String: Any]])

        for suffix in ["EmailAddress", "Name", "PhoneNumber", "OtherDataTypes", "UserID"] {
            let name = "NSPrivacyCollectedDataType\(suffix)"
            let entry = try #require(
                declared.first { $0["NSPrivacyCollectedDataType"] as? String == name },
                "\(name) is not declared")
            #expect(entry["NSPrivacyCollectedDataTypeLinked"] as? Bool == true, "\(name) linked")
            #expect(entry["NSPrivacyCollectedDataTypeTracking"] as? Bool == false, "\(name) tracking")
            #expect(entry["NSPrivacyCollectedDataTypePurposes"] as? [String]
                == ["NSPrivacyCollectedDataTypePurposeAppFunctionality"], "\(name) purposes")
        }
        // Pre-existing, unchanged: the `X-Device-Id` bucket, NOT linked to identity.
        #expect(declared.contains { $0["NSPrivacyCollectedDataType"] as? String
            == "NSPrivacyCollectedDataTypeDeviceID" })
    }

    /// The WHOLE required-reason table, so the next such API added without a declaration has an
    /// obvious place to fail. Disk space, E174.1 (Apple: "check whether there is sufficient disk
    /// space to write files… The app must behave differently based on disk space in a way that is
    /// observable to users"): `OfflineStorage` reads `volumeAvailableCapacityForImportantUsage`
    /// to refuse a save that cannot fit. Read from the BUILT bundle, like the test above.
    @Test func thePrivacyManifestRequiredReasonTableIsPinned() throws {
        let url = try #require(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        let manifest = try PropertyListSerialization.propertyList(
            from: Data(contentsOf: url), format: nil) as? [String: Any]
        let accessed = try #require(manifest?["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        let pairs = accessed.compactMap { entry -> (String, [String])? in
            guard let type = entry["NSPrivacyAccessedAPIType"] as? String,
                  let codes = entry["NSPrivacyAccessedAPITypeReasons"] as? [String] else { return nil }
            return (type, codes)
        }
        // A category declared twice must FAIL, not trap (`uniqueKeysWithValues:` would).
        #expect(pairs.count == accessed.count, "an entry is missing its type or reasons")
        let reasons = Dictionary(pairs, uniquingKeysWith: { first, _ in first })
        #expect(reasons.count == pairs.count, "a category is declared more than once")
        #expect(reasons == [
            "NSPrivacyAccessedAPICategoryUserDefaults": ["CA92.1"],
            "NSPrivacyAccessedAPICategorySystemBootTime": ["35F9.1"],
            "NSPrivacyAccessedAPICategoryDiskSpace": ["E174.1"],
        ])
    }

    /// There is NO runtime API to read your own entitlements, and an unsigned simulator build
    /// carries none at all — so Sign in with Apple's availability is a BUILD-TIME fact, baked into
    /// Info.plist from `FITRAH_TEAM_ID`. Task 5's `SignInCapabilities.apple` reads exactly this key.
    /// Non-empty here because both xcconfigs set the owner's Team ID.
    @Test func theAppleSignInCapabilityFlagCarriesTheTeamID() throws {
        let flag = try #require(
            Bundle.main.object(forInfoDictionaryKey: "FITRAH_APPLE_SIGNIN") as? String)
        #expect(!flag.isEmpty)
    }
}
