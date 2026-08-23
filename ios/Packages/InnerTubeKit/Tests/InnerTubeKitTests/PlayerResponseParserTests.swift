import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct PlayerResponseParserTests {
    private let parser = PlayerResponseParser()

    @Test func okHlsFixtureYieldsPlayableStreamingData() throws {
        let result = try parser.parse(try loadFixture("player-ok-hls"))
        switch result {
        case .ok(let streaming):
            #expect(streaming.hlsManifestURL != nil)
            #expect(streaming.expiresInSeconds == 21540)
        default:
            Issue.record("expected .ok, got \(result)")
        }
    }

    // The real capture (Task 1 probe) is UNPLAYABLE with no streamingData at all — the
    // androidItag18 fallback comes from a *separate* ANDROID-client request per
    // ios-app-plan.md §6.2 step 4, out of scope for parsing one response body. `.unplayableKids`
    // also carries no associated payload, so there is no itag18 URL to assert here.
    @Test func unplayableKidsFixtureYieldsUnplayableKids() throws {
        let result = try parser.parse(try loadFixture("player-unplayable-kids"))
        #expect(result == .unplayableKids)
    }

    @Test func ageGatedFixtureYieldsAgeGate() throws {
        let result = try parser.parse(try loadFixture("player-age-gated"))
        #expect(result == .ageGate)
    }

    @Test func liveFixtureYieldsPlayableLiveStreamingData() throws {
        let result = try parser.parse(try loadFixture("player-live"))
        switch result {
        case .ok(let streaming):
            #expect(streaming.isLive)
            #expect(streaming.hlsManifestURL != nil)
        default:
            Issue.record("expected .ok, got \(result)")
        }
    }

    @Test func botcheckFixtureYieldsBotCheck() throws {
        let result = try parser.parse(try loadFixture("player-botcheck"))
        #expect(result == .botCheck)
    }

    // Synthetic fixture (not probe-captured — the embed-only case wasn't reproducible live in
    // Task 1): a response missing `streamingData` entirely, with a non-branching status.
    @Test func embedOnlyFixtureMissingStreamingDataYieldsUnavailable() throws {
        let result = try parser.parse(try loadFixture("player-embed-only"))
        #expect(result == .unavailable(reason: "This video is unavailable"))
    }

    private func loadFixture(_ name: String) throws -> Data {
        let url = try #require(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }
}
