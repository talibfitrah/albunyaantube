import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct ModelsTests {
    @Test func resolvedIsExpiredWhenExpiresAtInPast() {
        let now = Date(timeIntervalSince1970: 1000)
        let past = Resolved(
            stream: .embed(videoId: "abc123def45"),
            client: .visionos,
            userAgent: "ua",
            resolvedAt: Date(timeIntervalSince1970: 0),
            expiresAt: Date(timeIntervalSince1970: 999)
        )
        #expect(past.isExpired(now: now))

        let noExpiry = Resolved(
            stream: .embed(videoId: "abc123def45"),
            client: .visionos,
            userAgent: "ua",
            resolvedAt: Date(timeIntervalSince1970: 0),
            expiresAt: nil
        )
        #expect(!noExpiry.isExpired(now: now))

        let future = Resolved(
            stream: .embed(videoId: "abc123def45"),
            client: .visionos,
            userAgent: "ua",
            resolvedAt: Date(timeIntervalSince1970: 0),
            expiresAt: Date(timeIntervalSince1970: 1001)
        )
        #expect(!future.isExpired(now: now))
    }

    @Test func extractionErrorIsEquatable() {
        #expect(ExtractionError.ageRestricted == ExtractionError.ageRestricted)
        #expect(ExtractionError.unavailable(videoId: "abc123def45") == ExtractionError.unavailable(videoId: "abc123def45"))
        #expect(ExtractionError.unavailable(videoId: "abc123def45") != ExtractionError.unavailable(videoId: "zzz"))
        #expect(ExtractionError.transport("boom") == ExtractionError.transport("boom"))
        #expect(ExtractionError.ageRestricted != ExtractionError.geoBlocked)
    }

    @Test func terminalIsTrueOnlyForTerminalCases() {
        let terminal: [ExtractionError] = [
            .ageRestricted,
            .geoBlocked,
            .private,
            .removed,
            .unavailable(videoId: "abc123def45"),
            .liveOffline(startsAt: nil),
        ]
        for error in terminal {
            #expect(error.terminal, "\(error) should be terminal")
        }

        let nonTerminal: [ExtractionError] = [
            .invalidVideoId,
            .botCheck,
            .allRungsFailed,
            .cancelled,
            .transport("network down"),
        ]
        for error in nonTerminal {
            #expect(!error.terminal, "\(error) should not be terminal")
        }
    }

    @Test func clientFamilyUserAgentIsRequired() {
        #expect(ClientFamily.visionos.userAgentIsRequired)
        #expect(ClientFamily.android.userAgentIsRequired)
        #expect(!ClientFamily.web.userAgentIsRequired)
    }
}
