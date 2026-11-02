import AVFoundation
import Foundation
import Testing
@testable import InnerTubeKit

/// Spec §4.3 loader on macOS AVFoundation with a fake transport over the ffmpeg fixture (§6.1).
@Suite struct SyntheticHLSLoaderTests {
    /// Serves the fixture by file name, honouring `Range`; `statuses` scripts the first answers (a 403, say). `caps`
    /// records each GET's body cap (stage 6 I-C1).
    final class FixtureFiles: BoundedHTTPTransport, @unchecked Sendable {
        private let lock = NSLock()
        private var statuses: [Int]
        private var sent: [HTTPRequest] = []
        private var sentCaps: [Int] = []
        private let dir = Bundle.module.resourceURL!.appending(path: "Fixtures/synthetic")

        init(statuses: [Int] = []) { self.statuses = statuses }

        var requests: [HTTPRequest] { lock.withLock { sent } }
        var caps: [Int] { lock.withLock { sentCaps } }

        func send(_ request: HTTPRequest, maxBodyBytes: Int) async throws -> HTTPResponse {
            let scripted: Int? = lock.withLock {
                sent.append(request)
                sentCaps.append(maxBodyBytes)
                return statuses.isEmpty ? nil : statuses.removeFirst()
            }
            if let scripted { return HTTPResponse(status: scripted, headers: [:], body: Data()) }
            let file = try Data(contentsOf: dir.appending(path: request.url.lastPathComponent))
            let bounds = (request.headers["Range"] ?? "").dropFirst("bytes=".count).split(separator: "-").compactMap { Int($0) }
            return HTTPResponse(status: 206, headers: [:], body: file.subdata(in: bounds[0]..<(bounds[1] + 1)))
        }
    }

    /// The fixture's model with absolute (unreachable) https URLs: only the `sidx` GETs go through `FixtureFiles`.
    private func model() throws -> SyntheticHLS {
        let dir = try #require(Bundle.module.resourceURL).appending(path: "Fixtures/synthetic")
        var model = try JSONDecoder().decode(SyntheticHLS.self, from: Data(contentsOf: dir.appending(path: "synthetic.json")))
        for i in model.video.indices { model.video[i].url = URL(string: "https://fixture.invalid/" + model.video[i].url.relativeString)! }
        for i in model.audio.indices { model.audio[i].url = URL(string: "https://fixture.invalid/" + model.audio[i].url.relativeString)! }
        return model
    }

    final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func add() { lock.withLock { count += 1 } }
        var value: Int { lock.withLock { count } }
    }

    private func loader(_ files: FixtureFiles, refusals: Counter = Counter(),
                        retryDelay: Duration = .milliseconds(10)) throws -> SyntheticHLSLoader {
        SyntheticHLSLoader(model: try model(), userAgent: "TV-UA", transport: files, retryDelay: retryDelay,
                           onRefused: { refusals.add() })
    }

    @Test func theMasterIsServedWithoutTheNetwork() async throws {
        let files = FixtureFiles()
        #expect(try await loader(files).answer("master.m3u8") == (try model()).masterPlaylist())
        #expect(files.requests.isEmpty)
    }

    @Test func aMediaPlaylistCostsOneRangedGETWithTheMintingUserAgent() async throws {   // R14, R17
        let files = FixtureFiles()
        let loader = try loader(files)
        let text = try await loader.answer("v0.m3u8")
        _ = try await loader.answer("v0.m3u8")                           // kept per loader
        let v0 = try model().video[0]
        #expect(files.requests.count == 1)
        #expect(files.requests[0].headers["User-Agent"] == "TV-UA")
        #expect(files.requests[0].headers["Range"] == "bytes=\(v0.indexRange.lowerBound)-\(v0.indexRange.upperBound)")
        #expect(text.hasPrefix("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD"))
        #expect(text.hasSuffix("#EXT-X-ENDLIST\n"))
    }

    @Test func a403IsRetriedOnceThenServed() async throws {   // R42
        let files = FixtureFiles(statuses: [403])
        let refusals = Counter()
        let loader = try loader(files, refusals: refusals, retryDelay: .milliseconds(200))
        let start = ContinuousClock.now
        _ = try await loader.answer("a0.m3u8")
        #expect(ContinuousClock.now - start >= .milliseconds(200))   // the measured wait (§3.7); a lower bound only
        #expect(files.requests.count == 2)
        #expect(refusals.value == 0)
    }

    @Test func twoRefusalsFailThePlaylistAndAreReportedOnce() async throws {   // R42 (revision 4)
        let files = FixtureFiles(statuses: [403, 403])
        let refusals = Counter()
        let loader = try loader(files, refusals: refusals)
        await #expect(throws: SyntheticHLSLoader.LoaderError.http(403)) { try await loader.answer("a0.m3u8") }
        #expect(files.requests.count == 2)
        #expect(refusals.value == 1)
    }

    /// A wrong solve costs at most the first playlist's two GETs: every later `sidx` request fails at once.
    @Test func aRefusalFailsEveryLaterPlaylistWithoutAGET() async throws {   // R42
        let files = FixtureFiles(statuses: [403, 403])
        let refusals = Counter()
        let loader = try loader(files, refusals: refusals)
        await #expect(throws: SyntheticHLSLoader.LoaderError.http(403)) { try await loader.answer("a0.m3u8") }
        await #expect(throws: SyntheticHLSLoader.LoaderError.http(403)) { try await loader.answer("v0.m3u8") }
        await #expect(throws: SyntheticHLSLoader.LoaderError.http(403)) { try await loader.answer("a0.m3u8") }
        #expect(files.requests.count == 2)
        #expect(refusals.value == 1)
    }

    /// Task 12 hand-off: only a 206 of exactly the index range is parsed. A 200 (the whole file) or a short 206 fails the
    /// playlist like any other non-2xx: no retry, no refusal report.
    @Test(arguments: [200, 206, 404])
    func anyOtherStatusOrLengthFailsWithoutARetry(_ status: Int) async throws {   // R42
        let files = FixtureFiles(statuses: [status])
        let refusals = Counter()
        let loader = try loader(files, refusals: refusals)
        await #expect(throws: SyntheticHLSLoader.LoaderError.http(status)) { try await loader.answer("v1.m3u8") }
        #expect(files.requests.count == 1)
        #expect(refusals.value == 0)
    }

    /// Stage 6 I-C1 (Codex P1): a CDN that ignores `Range` answers 200 with the whole video. The `sidx` GET reads at most
    /// the index's length (+1, to see that it is too long), never the body it was sent.
    @Test func theSidxGETIsCappedAtTheIndexLength() async throws {
        let files = FixtureFiles()
        let loader = try loader(files)
        _ = try await loader.answer("v0.m3u8")
        _ = try await loader.answer("a0.m3u8")
        let model = try model()
        #expect(files.caps == [model.video[0].indexRange.count, model.audio[0].indexRange.count])
    }

    /// Stage 6 I-C3 (Codex P2): Apple's data-request contract. The answer starts at `currentOffset` and ends at the
    /// requested length, or at the end of the playlist when the request asks for everything to its end.
    @Test func aDataRequestGetsTheBytesItAskedFor() {
        let text = Data("#EXTM3U\n#EXT-X-VERSION:7\n".utf8)
        #expect(SyntheticHLSLoader.slice(text, requestedOffset: 0, requestedLength: text.count, currentOffset: 0,
                                         toEnd: false) == text)                                       // offset 0
        #expect(SyntheticHLSLoader.slice(text, requestedOffset: 3, requestedLength: 4, currentOffset: 3,
                                         toEnd: false) == text.subdata(in: 3..<7))                     // a middle range
        #expect(SyntheticHLSLoader.slice(text, requestedOffset: 8, requestedLength: 2, currentOffset: 8,
                                         toEnd: true) == text.subdata(in: 8..<text.count))             // to EOF
        #expect(SyntheticHLSLoader.slice(text, requestedOffset: 2, requestedLength: 6, currentOffset: 5,
                                         toEnd: false) == text.subdata(in: 5..<8))                     // partly answered
        #expect(SyntheticHLSLoader.slice(text, requestedOffset: 90, requestedLength: 4, currentOffset: 90,
                                         toEnd: false).isEmpty)                                        // past the end
    }

    @Test func anUnknownPlaylistFails() async throws {
        await #expect(throws: SyntheticHLSLoader.LoaderError.unknownPlaylist("v9.m3u8")) {
            try await loader(FixtureFiles()).answer("v9.m3u8")
        }
    }

    @Test(.timeLimit(.minutes(1))) func anAssetReadsItsVariantsThroughTheDelegate() async throws {   // R12
        let asset = SyntheticHLSLoader.asset(url: URL(string: "fitrah-hls://\(UUID().uuidString)/master.m3u8")!, model: try model(),
                                             userAgent: "TV-UA", transport: FixtureFiles(), retryDelay: .milliseconds(10))
        #expect(try await asset.load(.variants).count == 2)
    }

    @Test func theLoaderLivesExactlyAsLongAsItsAsset() throws {
        weak var weakLoader: SyntheticHLSLoader?
        try autoreleasepool {
            let asset = SyntheticHLSLoader.asset(url: URL(string: "fitrah-hls://\(UUID().uuidString)/master.m3u8")!,
                                                 model: try model(), userAgent: "TV-UA")
            weakLoader = asset.resourceLoader.delegate as? SyntheticHLSLoader
            #expect(weakLoader != nil)
        }
        #expect(weakLoader == nil)
    }
}
