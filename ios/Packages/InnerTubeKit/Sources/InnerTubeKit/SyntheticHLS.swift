import Foundation

/// The compact model behind a `tv_samsung` stream (spec §4.3, R13, R14): one rendition per selected avc1 video and per
/// audio group, with the byte ranges a playlist needs and no segment table (`SyntheticHLSLoader` builds those lazily).
/// `Codable` so the simulator hook can read the ffmpeg fixture's model (R34).
public struct SyntheticHLS: Sendable, Equatable, Codable {
    public struct Rendition: Sendable, Equatable, Codable {
        public var url: URL
        public var codecs: String
        public var bandwidth: Int
        public var averageBandwidth: Int?
        public var width: Int?
        public var height: Int?
        public var fps: Int?
        public var initRange: ClosedRange<Int>
        public var indexRange: ClosedRange<Int>
        public var contentLength: Int
        public var language: String?
        public var name: String?
        public var isDefault: Bool

        public init(url: URL, codecs: String, bandwidth: Int, averageBandwidth: Int? = nil, width: Int? = nil,
                    height: Int? = nil, fps: Int? = nil, initRange: ClosedRange<Int>, indexRange: ClosedRange<Int>,
                    contentLength: Int, language: String? = nil, name: String? = nil, isDefault: Bool = false) {
            self.url = url
            self.codecs = codecs
            self.bandwidth = bandwidth
            self.averageBandwidth = averageBandwidth
            self.width = width
            self.height = height
            self.fps = fps
            self.initRange = initRange
            self.indexRange = indexRange
            self.contentLength = contentLength
            self.language = language
            self.name = name
            self.isDefault = isDefault
        }
    }

    /// Highest bandwidth first: AVPlayer starts on the first variant.
    public var video: [Rendition]
    /// One per audio group, the DEFAULT one first.
    public var audio: [Rendition]

    public init(video: [Rendition], audio: [Rendition]) {
        self.video = video
        self.audio = audio
    }
}

/// One rendition's `sidx` box (ISO/IEC 14496-12 §8.16.3) as byte-range segments (spec §4.3).
enum SIDX {
    struct Segment: Equatable, Sendable {
        var duration: Double
        var size: Int
        var offset: Int
    }

    enum ParseError: Error, Equatable {
        case notSIDX
        case truncated
        case hierarchical
        case doesNotTileTheFile
    }

    /// Versions 0 and 1; hierarchical references are refused. Media starts `first_offset` bytes after the box
    /// (`indexEnd + 1`), and the segments must end exactly at `contentLength` (Gate 0's self-check).
    /// The bytes come from the network: every read is bounds-checked and every offset sum overflow-checked.
    static func parse(_ data: Data, indexEnd: Int, contentLength: Int) throws -> [Segment] {
        let b = [UInt8](data)
        func u32(_ i: Int) throws -> Int {
            guard i >= 0, i + 4 <= b.count else { throw ParseError.truncated }
            return Int(b[i]) << 24 | Int(b[i + 1]) << 16 | Int(b[i + 2]) << 8 | Int(b[i + 3])
        }
        /// `offset + n`; past the end of the file (or past `Int.max`) it can no longer tile it.
        func advance(_ offset: Int, by n: Int) throws -> Int {
            let (sum, overflow) = offset.addingReportingOverflow(n)
            guard !overflow, sum <= contentLength else { throw ParseError.doesNotTileTheFile }
            return sum
        }
        guard b.count >= 8, b[4..<8].elementsEqual("sidx".utf8) else { throw ParseError.notSIDX }
        guard indexEnd >= 0 else { throw ParseError.doesNotTileTheFile }   // server-sent: a negative end puts media before byte 0
        let version = try u32(8) >> 24
        guard version <= 1 else { throw ParseError.notSIDX }
        let timescale = try u32(16)
        let firstOffset: Int
        let header: Int
        if version == 0 {
            firstOffset = try u32(24)
            header = 28
        } else {
            let high = try u32(28), low = try u32(32)
            guard high >> 31 == 0 else { throw ParseError.doesNotTileTheFile }   // ≥ 2^63: past any file
            firstOffset = high << 32 | low
            header = 36
        }
        guard timescale > 0, header + 4 <= b.count else { throw ParseError.truncated }
        let count = Int(b[header + 2]) << 8 | Int(b[header + 3])
        guard header + 4 + count * 12 <= b.count else { throw ParseError.truncated }
        var segments: [Segment] = []
        var offset = try advance(advance(indexEnd, by: 1), by: firstOffset)
        var cursor = header + 4
        for _ in 0..<count {
            let reference = try u32(cursor)
            let duration = try u32(cursor + 4)
            cursor += 12
            if reference >> 31 == 1 { throw ParseError.hierarchical }
            let size = reference & 0x7fff_ffff
            segments.append(Segment(duration: Double(duration) / Double(timescale), size: size, offset: offset))
            offset = try advance(offset, by: size)
        }
        guard offset == contentLength else { throw ParseError.doesNotTileTheFile }
        return segments
    }
}

extension SyntheticHLS {
    /// Spec §4.3: one EXT-X-MEDIA per audio group (DEFAULT on the first), one EXT-X-STREAM-INF per video rendition.
    /// No SUBTITLES group (R19). A quoted-string holds no `"`, CR or LF (RFC 8216 §4.2): `"` becomes `'`, line breaks go.
    /// `&+` on server-sent numbers: an absurd one makes an invalid playlist (the item fails), never a trap.
    func masterPlaylist() -> String {
        func quoted(_ s: String) -> String { s.replacingOccurrences(of: "\"", with: "'").filter { !$0.isNewline } }
        var lines = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS"]
        for (i, a) in audio.enumerated() {
            var attributes = "TYPE=AUDIO,GROUP-ID=\"a\""
            if let language = a.language { attributes += ",LANGUAGE=\"\(quoted(language))\"" }
            attributes += ",NAME=\"\(quoted(a.name ?? "Default"))\",DEFAULT=\(a.isDefault ? "YES" : "NO"),AUTOSELECT=YES,URI=\"a\(i).m3u8\""
            lines.append("#EXT-X-MEDIA:" + attributes)
        }
        let main = audio.first(where: \.isDefault) ?? audio.first
        for (i, v) in video.enumerated() {
            var attributes = "BANDWIDTH=\(v.bandwidth &+ (main?.bandwidth ?? 0))"
            if let average = v.averageBandwidth {
                attributes += ",AVERAGE-BANDWIDTH=\(average &+ (main?.averageBandwidth ?? main?.bandwidth ?? 0))"
            }
            attributes += ",CODECS=\"\(v.codecs)\(main.map { "," + $0.codecs } ?? "")\""
            if let width = v.width, let height = v.height { attributes += ",RESOLUTION=\(width)x\(height)" }
            if let fps = v.fps { attributes += ",FRAME-RATE=\(fps)" }
            if main != nil { attributes += ",AUDIO=\"a\"" }
            lines += ["#EXT-X-STREAM-INF:" + attributes, "v\(i).m3u8"]
        }
        return (lines + [""]).joined(separator: "\n")
    }

    /// Spec §4.3: VOD, EXT-X-MAP over the init range, one EXT-X-BYTERANGE per `sidx` reference, absolute URLs.
    static func mediaPlaylist(_ rendition: Rendition, segments: [SIDX.Segment]) -> String {
        let url = rendition.url.absoluteString
        let target = Int((segments.map(\.duration).max() ?? 1).rounded(.up))
        var lines = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-TARGETDURATION:\(target)",
                     "#EXT-X-MAP:URI=\"\(url)\",BYTERANGE=\"\(rendition.initRange.upperBound &+ 1)@0\""]
        for segment in segments {
            lines += [String(format: "#EXTINF:%.6f,", segment.duration), "#EXT-X-BYTERANGE:\(segment.size)@\(segment.offset)", url]
        }
        return (lines + ["#EXT-X-ENDLIST", ""]).joined(separator: "\n")
    }
}
