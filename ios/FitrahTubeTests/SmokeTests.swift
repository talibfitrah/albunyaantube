import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct SmokeTests {
    @Test func bundleIdentifierIsAlbunyaan() {
        #expect(Bundle.main.bundleIdentifier == "com.albunyaan.tube")
    }
}
