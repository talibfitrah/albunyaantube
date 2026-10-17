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
