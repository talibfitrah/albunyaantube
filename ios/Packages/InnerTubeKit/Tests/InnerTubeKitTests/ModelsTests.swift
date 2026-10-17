import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct ModelsTests {
    @Test func resolvedIsExpiredWhenExpiresAtInPast() {
        let now = Date(timeIntervalSince1970: 1000)
        let past = Resolved(
            stream: .progressive(url: URL(string: "https://example.com/v.mp4")!, label: "360p"),
            client: .visionos,
            userAgent: "ua",
            resolvedAt: Date(timeIntervalSince1970: 0),
            expiresAt: Date(timeIntervalSince1970: 999)
        )
        #expect(past.isExpired(now: now))

        let atBoundary = Resolved(
            stream: .progressive(url: URL(string: "https://example.com/v.mp4")!, label: "360p"),
            client: .visionos,
            userAgent: "ua",
            resolvedAt: Date(timeIntervalSince1970: 0),
            expiresAt: Date(timeIntervalSince1970: 1000)
        )
        #expect(atBoundary.isExpired(now: now))  // exactly-equal counts as expired (<=)

        let noExpiry = Resolved(
            stream: .progressive(url: URL(string: "https://example.com/v.mp4")!, label: "360p"),
            client: .visionos,
            userAgent: "ua",
            resolvedAt: Date(timeIntervalSince1970: 0),
            expiresAt: nil
        )
        #expect(!noExpiry.isExpired(now: now))

        let future = Resolved(
            stream: .progressive(url: URL(string: "https://example.com/v.mp4")!, label: "360p"),
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
            .cooldown(until: Date(timeIntervalSince1970: 1000)),
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

    // MARK: - Playback Phase 2 (spec R3, R13)

    @Test func tvSamsungIsTheTVHTML5Family() {
        #expect(ClientFamily.tvSamsung.expectedClientName == "TVHTML5")
    }

    @Test func onlyASyntheticStreamCarriesAModel() {
        let model = SyntheticHLS(video: [], audio: [])
        let tv = ResolvedStream.hls(url: URL(string: "fitrah-hls://x/master.m3u8")!, isLive: false, audioOnlyURL: nil,
                                    captionTracks: [], synthetic: model)
        #expect(tv.synthetic == model)
        #expect(ResolvedStream.hls(url: URL(string: "https://a.example/b.m3u8")!, isLive: false, audioOnlyURL: nil,
                                   captionTracks: []).synthetic == nil)
        #expect(ResolvedStream.progressive(url: URL(string: "https://a.example/b.mp4")!, label: "360p").synthetic == nil)
    }

    @Test func theFixtureClipsModelDecodes() throws {
        let dir = try #require(Bundle.module.resourceURL).appending(path: "Fixtures/synthetic")
        let model = try JSONDecoder().decode(SyntheticHLS.self, from: Data(contentsOf: dir.appending(path: "synthetic.json")))
        #expect(model.video.map(\.height) == [144, 72])
        #expect(model.audio.map(\.isDefault) == [true])
        #expect(model.video[0].initRange.lowerBound == 0)
        #expect(model.video[0].indexRange.lowerBound == model.video[0].initRange.upperBound + 1)
    }
}
