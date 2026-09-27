import Foundation

#if DEBUG
/// The live UI test's credential channel (`ScreenshotTests.testDetailCTask6Live`): a JSON file the
/// runner writes and this deletes the moment it reads it. Every UI-driven way in leaks one of the two:
/// `typeText` records a TextField's text in the xcresult activity log ("Type 'probe-email@exampl...'",
/// measured; a SecureTextField is recorded as '<redacted>'), and the pasteboard reaches the Mac's
/// through Simulator's pasteboard sync. Only the file's PATH travels as a launch argument. The wall
/// itself stays covered by the fixture UI tests (`01-sign-in`, the Phase 4 account screens).
nonisolated enum LiveSignInFile {
    struct Credentials: Decodable, Sendable {
        let email: String
        let password: String
    }

    static func take(path: String) -> Credentials? {
        defer { try? FileManager.default.removeItem(atPath: path) }
        guard let data = FileManager.default.contents(atPath: path) else { return nil }
        return try? JSONDecoder().decode(Credentials.self, from: data)
    }
}
#endif
