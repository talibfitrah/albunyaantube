import Foundation
import Testing
@testable import InnerTubeKit

/// Spec §4.3 playlists and the `sidx` parser (R14, R19).
@Suite struct SyntheticHLSTests {
    /// A hand-built `sidx`: timescale 1000, the given EPT, first_offset and references (SAP word 0x90000000).
    private func box(version: UInt8, references: [(size: UInt32, duration: UInt32)], hierarchical: Bool = false,
                     ept: UInt32 = 0, firstOffset: UInt32 = 0) -> Data {
        var b: [UInt8] = []
        func u32(_ v: UInt32) { b += [UInt8(v >> 24), UInt8(v >> 16 & 0xff), UInt8(v >> 8 & 0xff), UInt8(v & 0xff)] }
        u32(0); b += Array("sidx".utf8); b += [version, 0, 0, 0]; u32(1); u32(1000)
        if version == 0 { u32(ept); u32(firstOffset) } else { u32(0); u32(ept); u32(0); u32(firstOffset) }
        b += [0, 0, UInt8(references.count >> 8), UInt8(references.count & 0xff)]
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

    /// Real YouTube boxes are v0, ffmpeg's are v1: EPT and first_offset sit at different bytes, and only first_offset
    /// moves the media (Fix round 1, Important 1).
    @Test(arguments: [UInt8(0), UInt8(1)])
    func firstOffsetMovesTheMediaAndTheEPTDoesNot(_ version: UInt8) throws {
        let data = box(version: version, references: [(100, 2000), (200, 1500)], ept: 0xBEEF, firstOffset: 7)
        #expect(try SIDX.parse(data, indexEnd: 99, contentLength: 407).map(\.offset) == [107, 207])
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
        data[31] = 2      // reference_count 2, one reference present
        #expect(throws: SIDX.ParseError.truncated) { try SIDX.parse(data, indexEnd: 99, contentLength: 200) }
    }

    @Test func anUnknownVersionIsNotASidx() {
        #expect(throws: SIDX.ParseError.notSIDX) {
            try SIDX.parse(box(version: 2, references: [(100, 2000)]), indexEnd: 99, contentLength: 200)
        }
    }

    @Test func anEmptyOrZeroReferenceIsNotASidx() {
        for references: [(size: UInt32, duration: UInt32)] in [[], [(0, 2000), (100, 2000)], [(100, 0)]] {
            #expect(throws: SIDX.ParseError.notSIDX, "\(references)") {
                try SIDX.parse(box(version: 0, references: references), indexEnd: 99,
                               contentLength: 100 + references.map { Int($0.size) }.reduce(0, +))
            }
        }
    }

    @Test func aZeroTimescaleIsNotASidx() {
        var data = box(version: 0, references: [(100, 2000)])
        data[18] = 0; data[19] = 0      // timescale 1000 -> 0
        #expect(throws: SIDX.ParseError.notSIDX) { try SIDX.parse(data, indexEnd: 99, contentLength: 200) }
    }

    /// The index range must be exactly the box: size 0 ("to the end of the file"), 1 (largesize) and a range that runs past
    /// the box are refused; a size past the data is a truncation.
    @Test func theBoxSizeMustBeTheIndexRange() {
        let good = box(version: 0, references: [(100, 2000)])   // 44 bytes
        func sized(_ size: UInt8) -> Data { var data = good; data[3] = size; return data }
        for data in [sized(0), sized(1), good + [0]] {
            #expect(throws: SIDX.ParseError.notSIDX, "\(data.count) bytes, size \(data[3])") {
                try SIDX.parse(data, indexEnd: 99, contentLength: 200)
            }
        }
        #expect(throws: SIDX.ParseError.truncated) { try SIDX.parse(sized(45), indexEnd: 99, contentLength: 200) }
    }

    @Test func moreThanTwentyThousandReferencesIsNotASidx() throws {   // the parser's ponytail cap
        let references = [(size: UInt32, duration: UInt32)](repeating: (1, 1000), count: 20_000)
        #expect(try SIDX.parse(box(version: 0, references: references), indexEnd: 99, contentLength: 20_100).count == 20_000)
        #expect(throws: SIDX.ParseError.notSIDX) {
            try SIDX.parse(box(version: 0, references: references + [(1, 1000)]), indexEnd: 99, contentLength: 20_101)
        }
    }

    /// The byte ranges are server-sent too (Task 11 review): an overflow, a negative end or an index past the file throws.
    @Test func offsetsOutsideTheFileAreRefused() {
        func firstOffset(_ high: UInt8) -> Data {   // version 1: first_offset is bytes 28..<36
            var data = box(version: 1, references: [(100, 2000)])
            data.replaceSubrange(28..<36, with: [high] + [UInt8](repeating: 0xff, count: 7))
            return data
        }
        #expect(throws: SIDX.ParseError.doesNotTileTheFile) {   // ≥ 2^63: as an Int it is -1, which would tile from 99
            try SIDX.parse(firstOffset(0xff), indexEnd: 99, contentLength: 199)
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

    /// Golden: the fixture clip's real (ffmpeg, v1) boxes, through the parser, into media playlists.
    @Test func theFixtureClipsMediaPlaylistsAreGolden() throws {
        let dir = try #require(Bundle.module.resourceURL).appending(path: "Fixtures/synthetic")
        let model = try JSONDecoder().decode(SyntheticHLS.self, from: Data(contentsOf: dir.appending(path: "synthetic.json")))
        func playlist(_ r: SyntheticHLS.Rendition?) throws -> String {
            let r = try #require(r)
            let file = try Data(contentsOf: dir.appending(path: r.url.relativeString))
            let segments = try SIDX.parse(file[r.indexRange], indexEnd: r.indexRange.upperBound, contentLength: r.contentLength)
            return SyntheticHLS.mediaPlaylist(r, segments: segments)
        }
        #expect(try playlist(model.video.first) == """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-TARGETDURATION:2
        #EXT-X-MAP:URI="v0.mp4",BYTERANGE="795@0"
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:9133@931
        v0.mp4
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:7229@10064
        v0.mp4
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:6596@17293
        v0.mp4
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:7110@23889
        v0.mp4
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:6249@30999
        v0.mp4
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:6551@37248
        v0.mp4
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:5824@43799
        v0.mp4
        #EXTINF:2.000000,
        #EXT-X-BYTERANGE:7432@49623
        v0.mp4
        #EXT-X-ENDLIST

        """)
        let audio = try playlist(model.audio.first)   // AAC segments of 2.020136 s: ceil is 3
        #expect(audio.contains("\n#EXT-X-TARGETDURATION:3\n#EXT-X-MAP:URI=\"a0.mp4\",BYTERANGE=\"732@0\"\n#EXTINF:2.020136,\n"))
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
        #EXT-X-STREAM-INF:BANDWIDTH=3789619,AVERAGE-BANDWIDTH=895893,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=25,AUDIO="a",CLOSED-CAPTIONS=NONE
        v0.m3u8

        """)
        #expect(!text.contains("SUBTITLES"))
        // Without CLOSED-CAPTIONS, AVFoundation assumes in-band CEA-608 and shows an empty "CC" menu entry.
        let streamInf = text.split(separator: "\n").filter { $0.hasPrefix("#EXT-X-STREAM-INF:") }
        #expect(!streamInf.isEmpty && streamInf.allSatisfy { $0.contains(",CLOSED-CAPTIONS=NONE") })
    }

    /// RFC 8216 §4.2: a quoted-string has no CR or LF, so a server-sent name or codecs value cannot add a playlist line.
    @Test func aNewlineInAQuotedStringCannotAddALine() {
        let a = SyntheticHLS.Rendition(url: renditionURL, codecs: "mp4a.40.2\n#EXT-X-ENDLIST", bandwidth: 1, initRange: 0...1,
                                       indexRange: 2...3, contentLength: 1, language: "en\r\n",
                                       name: "x\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://h/v", isDefault: true)
        let v = SyntheticHLS.Rendition(url: renditionURL, codecs: "avc1.640028\nhttps://h/v.m3u8", bandwidth: 1, initRange: 0...1,
                                       indexRange: 2...3, contentLength: 1)
        let lines = SyntheticHLS(video: [v], audio: [a]).masterPlaylist().split(separator: "\n")
        #expect(lines.count == 6)
        #expect(lines[3] == #"#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",LANGUAGE="en",NAME="x#EXT-X-STREAM-INF:BANDWIDTH=1https://h/v",DEFAULT=YES,AUTOSELECT=YES,URI="a0.m3u8""#)
        #expect(lines[4] == #"#EXT-X-STREAM-INF:BANDWIDTH=2,CODECS="avc1.640028https://h/v.m3u8,mp4a.40.2#EXT-X-ENDLIST",AUDIO="a",CLOSED-CAPTIONS=NONE"#)
    }

    /// RFC 8216 §4.3.4.1.1: NAMEs are unique in a group. A nil name falls back to the language, then to the index; a repeat
    /// gets its index appended.
    @Test func audioNamesAreUniqueInTheGroup() {
        func audio(_ language: String?, _ name: String?) -> SyntheticHLS.Rendition {
            SyntheticHLS.Rendition(url: renditionURL, codecs: "mp4a.40.2", bandwidth: 1, initRange: 0...1, indexRange: 2...3,
                                   contentLength: 1, language: language, name: name)
        }
        let text = SyntheticHLS(video: [], audio: [audio("en", nil), audio(nil, nil), audio("en", nil), audio(nil, "English"),
                                                   audio("de", "English")]).masterPlaylist()
        let names = text.split(separator: "\n").dropFirst(3).map { $0.split(separator: ",").first { $0.hasPrefix("NAME=") } }
        #expect(names == [#"NAME="en""#, #"NAME="Audio 2""#, #"NAME="en (3)""#, #"NAME="English""#, #"NAME="English (5)""#])
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
