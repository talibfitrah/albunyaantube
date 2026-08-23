import Foundation
import Testing
@testable import FitrahTube

struct SmokeTests {
    @Test func bundleIdentifierIsAlbunyaan() {
        #expect(Bundle.main.bundleIdentifier == "com.albunyaan.tube")
    }
}
