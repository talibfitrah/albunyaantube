import Foundation
import Testing
@testable import FitrahTube

/// The live UI test's credential channel: a file the runner writes and the app deletes the moment it
/// reads it, so the email and password never touch a launch argument, the pasteboard, `typeText`
/// (which logs a TextField's text) or an attachment.
@Suite(.perTest)
struct LiveSignInFileTests {
    private func write(_ text: String) throws -> String {
        let url = FileManager.default.temporaryDirectory.appending(path: "live-signin-\(UUID().uuidString).json")
        try Data(text.utf8).write(to: url)
        return url.path()
    }

    @Test func readsTheCredentialsAndDeletesTheFile() throws {
        let path = try write(#"{"email":"a@example.invalid","password":"not-real-1"}"#)
        let credentials = LiveSignInFile.take(path: path)
        #expect(credentials?.email == "a@example.invalid")
        #expect(credentials?.password == "not-real-1")
        #expect(!FileManager.default.fileExists(atPath: path), "the credentials outlived the read")
    }

    @Test func aMalformedFileIsStillDeleted() throws {
        let path = try write("not json")
        #expect(LiveSignInFile.take(path: path) == nil)
        #expect(!FileManager.default.fileExists(atPath: path))
    }
}
