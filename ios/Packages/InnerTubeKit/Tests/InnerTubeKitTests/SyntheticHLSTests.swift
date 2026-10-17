import Foundation
import Testing
@testable import InnerTubeKit

/// Spec §4.3 playlists and the `sidx` parser (R14, R19).
@Suite struct SyntheticHLSTests {
    /// A hand-built `sidx`: timescale 1000, first_offset 0, the given references (SAP word 0x90000000).
    private func box(version: UInt8, references: [(size: UInt32, duration: UInt32)], hierarchical: Bool = false) -> Data {
        var b: [UInt8] = []
        func u32(_ v: UInt32) { b += [UInt8(v >> 24), UInt8(v >> 16 & 0xff), UInt8(v >> 8 & 0xff), UInt8(v & 0xff)] }
        u32(0); b += Array("sidx".utf8); b += [version, 0, 0, 0]; u32(1); u32(1000)
        if version == 0 { u32(0); u32(0) } else { u32(0); u32(0); u32(0); u32(0) }
        b += [0, 0, 0, UInt8(references.count)]
        for r in references { u32((hierarchical ? 0x8000_0000 : 0) | r.size); u32(r.duration); u32(0x9000_0000) }
        let size = UInt32(b.count)
        b[0] = UInt8(size >> 24); b[1] = UInt8(size >> 16 & 0xff); b[2] = UInt8(size >> 8 & 0xff); b[3] = UInt8(size & 0xff)
        return Data(b)
    }

    @Test(arguments: [UInt8(0), UInt8(1)])
    func aSidxBecomesByteRangesThatTileTheFile(_ version: UInt8) throws {
        let segments = try SIDX.parse(box(version: version, references: [(100, 2000), (200, 1500)]), indexEnd: 99, contentLength: 400)
        #expect(segments == [SIDX.Segment(duration: 2.0, size: 100, offset: 100), SIDX.Segment(duration: 1.5, size: 200, offset: 200)])
    }

    @Test func aHierarchicalSidxIsRefused() {
        #expect(throws: SIDX.ParseError.hierarchical) {
            try SIDX.parse(box(version: 0, references: [(100, 2000)], hierarchical: true), indexEnd: 99, contentLength: 200)
        }
    }

    @Test func aSidxThatDoesNotTileTheFileIsRefused() {
        #expect(throws: SIDX.ParseError.doesNotTileTheFile) {
            try SIDX.parse(box(version: 0, references: [(100, 2000)]), indexEnd: 99, contentLength: 999)
        }
    }

    @Test func somethingElseIsNotASidx() {
        #expect(throws: SIDX.ParseError.notSIDX) { try SIDX.parse(Data(repeating: 0, count: 40), indexEnd: 0, contentLength: 0) }
    }

    @Test func theFixtureClipsSidxTilesEveryRendition() throws {
        let dir = try #require(Bundle.module.resourceURL).appending(path: "Fixtures/synthetic")
        let model = try JSONDecoder().decode(SyntheticHLS.self, from: Data(contentsOf: dir.appending(path: "synthetic.json")))
        for r in model.video + model.audio {
            let file = try Data(contentsOf: dir.appending(path: r.url.relativeString))
            let segments = try SIDX.parse(file.subdata(in: r.indexRange.lowerBound..<(r.indexRange.upperBound + 1)),
                                          indexEnd: r.indexRange.upperBound, contentLength: r.contentLength)
            #expect(abs(segments.map(\.duration).reduce(0, +) - 16) < 0.2, "\(r.url)")   // Task 8's 16 s clip
            #expect(segments.count >= 7, "\(r.url)")
        }
    }

    // Fuzz-style: the parser reads untrusted network bytes, so a malformed box throws and never traps.

    /// The fixture's `v0.mp4` sidx box (ffmpeg, version 1) and its rendition.
    private func fixtureSidx() throws -> (box: [UInt8], rendition: SyntheticHLS.Rendition) {
        let dir = try #require(Bundle.module.resourceURL).appending(path: "Fixtures/synthetic")
        let model = try JSONDecoder().decode(SyntheticHLS.self, from: Data(contentsOf: dir.appending(path: "synthetic.json")))
        let r = try #require(model.video.first)
        let file = try Data(contentsOf: dir.appending(path: r.url.relativeString))
        return ([UInt8](file[r.indexRange]), r)
    }

    @Test func everyTruncationOfARealSidxThrows() throws {
        let (sidx, r) = try fixtureSidx()
        for n in 0..<sidx.count {
            #expect(throws: n < 8 ? SIDX.ParseError.notSIDX : .truncated, "\(n) of \(sidx.count) bytes") {
                try SIDX.parse(Data(sidx[..<n]), indexEnd: r.indexRange.upperBound, contentLength: r.contentLength)
            }
        }
    }

    @Test func anyByteOfARealSidxCorruptedNeverTraps() throws {
        let (sidx, r) = try fixtureSidx()
        for i in sidx.indices {
            for value: UInt8 in [0x00, 0x7f, 0x80, 0xff] {
                var bytes = sidx
                bytes[i] = value
                // Whatever parses still tiles the file (the parser's own invariant).
                if let segments = try? SIDX.parse(Data(bytes), indexEnd: r.indexRange.upperBound, contentLength: r.contentLength),
                   let last = segments.last {
                    #expect(last.offset + last.size == r.contentLength, "byte \(i) = \(value)")
                }
            }
        }
    }

    @Test func aReferenceCountPastTheDataIsTruncated() {
        var data = box(version: 0, references: [(100, 2000)])
        data[30] = 0xff; data[31] = 0xff      // reference_count 65535, one reference present
        #expect(throws: SIDX.ParseError.truncated) { try SIDX.parse(data, indexEnd: 99, contentLength: 200) }
    }

    @Test func anUnknownVersionIsNotASidx() {
        #expect(throws: SIDX.ParseError.notSIDX) {
            try SIDX.parse(box(version: 2, references: [(100, 2000)]), indexEnd: 99, contentLength: 200)
        }
    }

    /// The byte ranges are server-sent too (Task 11 review): an overflow, a negative end or an index past the file throws.
    @Test func offsetsOutsideTheFileAreRefused() {
        func firstOffset(_ high: UInt8) -> Data {   // version 1: first_offset is bytes 28..<36
            var data = box(version: 1, references: [(100, 2000)])
            data.replaceSubrange(28..<36, with: [high] + [UInt8](repeating: 0xff, count: 7))
            return data
        }
        #expect(throws: SIDX.ParseError.doesNotTileTheFile) {   // ≥ 2^63: negative as an Int
            try SIDX.parse(firstOffset(0xff), indexEnd: 99, contentLength: 200)
        }
        #expect(throws: SIDX.ParseError.doesNotTileTheFile) {   // Int.max: indexEnd + 1 + first_offset overflows
            try SIDX.parse(firstOffset(0x7f), indexEnd: 99, contentLength: .max)
        }
        #expect(throws: SIDX.ParseError.doesNotTileTheFile) {
            try SIDX.parse(box(version: 0, references: [(100, 2000)]), indexEnd: .max, contentLength: .max)
        }
        #expect(throws: SIDX.ParseError.doesNotTileTheFile) {   // the running offset overflows
            try SIDX.parse(box(version: 0, references: [(100, 2000), (100, 2000)]), indexEnd: Int.max - 150, contentLength: .max)
        }
        #expect(throws: SIDX.ParseError.doesNotTileTheFile) {   // would tile from offset -9 otherwise
            try SIDX.parse(box(version: 0, references: [(100, 2000)]), indexEnd: -10, contentLength: 91)
        }
        #expect(throws: SIDX.ParseError.doesNotTileTheFile) {   // the index ends past the file
            try SIDX.parse(box(version: 0, references: [(100, 2000)]), indexEnd: 500, contentLength: 400)
        }
    }

    private let renditionURL = URL(string: "https://example.com/videoplayback?itag=137&n=abc")!

    @Test func theMediaPlaylistHasTheSpecsShape() {
        let r = SyntheticHLS.Rendition(url: renditionURL, codecs: "avc1.640028", bandwidth: 1, initRange: 0...741,
                                       indexRange: 742...4457, contentLength: 4758)
        let text = SyntheticHLS.mediaPlaylist(r, segments: [.init(duration: 2.0, size: 100, offset: 4458),
                                                            .init(duration: 1.5, size: 200, offset: 4558)])
        #expect(text == """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-TARGETDURATION:2
        #EXT-X-MAP:URI="https://example.com/videoplayback?itag=137&n=abc",BYTERANGE="742@0"
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:100@4458
        https://example.com/videoplayback?itag=137&n=abc
        #EXTINF:1.500000,
        #EXT-X-BYTERANGE:200@4558
        https://example.com/videoplayback?itag=137&n=abc
        #EXT-X-ENDLIST

        """)
    }

    @Test func theMasterPlaylistHasTheSpecsShapeAndNoSubtitles() {   // R19
        let v = SyntheticHLS.Rendition(url: renditionURL, codecs: "avc1.640028", bandwidth: 3_658_016, averageBandwidth: 766_893,
                                       width: 1920, height: 1080, fps: 25, initRange: 0...741, indexRange: 742...4457, contentLength: 1)
        let original = SyntheticHLS.Rendition(url: renditionURL, codecs: "mp4a.40.2", bandwidth: 131_603, averageBandwidth: 129_000,
                                              initRange: 0...631, indexRange: 632...1000, contentLength: 1, language: "en-US",
                                              name: "English (United States) original", isDefault: true)
        let dub = SyntheticHLS.Rendition(url: renditionURL, codecs: "mp4a.40.2", bandwidth: 131_000, initRange: 0...631,
                                         indexRange: 632...1000, contentLength: 1, language: "de-DE", name: "German \"dub\"")
        let text = SyntheticHLS(video: [v], audio: [original, dub]).masterPlaylist()
        #expect(text == """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",LANGUAGE="en-US",NAME="English (United States) original",DEFAULT=YES,AUTOSELECT=YES,URI="a0.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",LANGUAGE="de-DE",NAME="German 'dub'",DEFAULT=NO,AUTOSELECT=YES,URI="a1.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=3789619,AVERAGE-BANDWIDTH=895893,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=25,AUDIO="a"
        v0.m3u8

        """)
        #expect(!text.contains("SUBTITLES"))
    }

    /// RFC 8216 §4.2: a quoted-string has no CR or LF, so a server-sent name cannot add a playlist line.
    @Test func aNewlineInANameCannotAddALine() {
        let a = SyntheticHLS.Rendition(url: renditionURL, codecs: "mp4a.40.2", bandwidth: 1, initRange: 0...1, indexRange: 2...3,
                                       contentLength: 1, language: "en\r\n", name: "x\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://h/v",
                                       isDefault: true)
        let lines = SyntheticHLS(video: [], audio: [a]).masterPlaylist().split(separator: "\n")
        #expect(lines.count == 4)
        #expect(lines[3] == #"#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",LANGUAGE="en",NAME="x#EXT-X-STREAM-INF:BANDWIDTH=1https://h/v",DEFAULT=YES,AUTOSELECT=YES,URI="a0.m3u8""#)
    }

    /// Server-sent numbers at `Int.max` make an invalid playlist (the staged item fails, 360p plays), never a trap.
    @Test func absurdServerNumbersNeverTrap() {
        let v = SyntheticHLS.Rendition(url: renditionURL, codecs: "avc1.640028", bandwidth: .max, averageBandwidth: .max,
                                       initRange: 0...Int.max, indexRange: 0...1, contentLength: 1)
        let a = SyntheticHLS.Rendition(url: renditionURL, codecs: "mp4a.40.2", bandwidth: .max, initRange: 0...1,
                                       indexRange: 2...3, contentLength: 1, isDefault: true)
        #expect(SyntheticHLS(video: [v], audio: [a]).masterPlaylist().hasPrefix("#EXTM3U\n"))
        #expect(SyntheticHLS.mediaPlaylist(v, segments: []).hasPrefix("#EXTM3U\n"))
    }
}
