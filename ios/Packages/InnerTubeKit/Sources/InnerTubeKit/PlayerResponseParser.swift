import Foundation

/// The outcome of parsing a `player` InnerTube response body
/// (`extraction.md` §4, `ios-app-plan.md` §6.2 step 4).
public enum Playability: Sendable, Equatable {
    case ok(StreamingData)
    case unplayableKids
    case ageGate
    case botCheck
    case liveOffline(startsAt: Date?)
    case unavailable(reason: String)
}

/// Streaming data extracted from an `OK` player response.
public struct StreamingData: Sendable, Equatable {
    public var hlsManifestURL: URL?
    public var itag18URL: URL?
    public var itag140URL: URL?
    public var expiresInSeconds: Int?
    public var isLive: Bool
    public var captionTracks: [CaptionTrack]

    public init(
        hlsManifestURL: URL? = nil,
        itag18URL: URL? = nil,
        itag140URL: URL? = nil,
        expiresInSeconds: Int? = nil,
        isLive: Bool = false,
        captionTracks: [CaptionTrack] = []
    ) {
        self.hlsManifestURL = hlsManifestURL
        self.itag18URL = itag18URL
        self.itag140URL = itag140URL
        self.expiresInSeconds = expiresInSeconds
        self.isLive = isLive
        self.captionTracks = captionTracks
    }
}

/// Parses a raw `player` InnerTube response body into a `Playability`
/// (`ios-app-plan.md` §6.2 step 4 branching).
public struct PlayerResponseParser: Sendable {
    public init() {}

    /// `visitorData` is `responseContext.visitorData` — the session token §6.3 says to take
    /// from a successful response and send on every later call. It rides alongside the
    /// `Playability` (not inside `.ok`) because YouTube returns it on every status.
    public func parse(_ body: Data) throws -> (playability: Playability, visitorData: String?) {
        let wire = try JSONDecoder().decode(Wire.self, from: body)
        return (playability(wire), wire.responseContext?.visitorData)
    }

    private func playability(_ wire: Wire) -> Playability {
        let status = wire.playabilityStatus.status
        let reason = wire.playabilityStatus.reason ?? status

        switch status {
        case "OK":
            return .ok(streamingData(from: wire))
        case "LOGIN_REQUIRED", "AGE_CHECK_REQUIRED":
            // Locale probe 2026-08-31 (6 live VISIONOS probes, hl=en/ar/nl): `reason` is fully
            // localized (ar age gate: "يجب تسجيل الدخول لتأكيد عمرك." -- no "age", no "bot"
            // substring), so English-substring matching misclassified every non-English response.
            // The locale-independent discriminator is `desktopLegacyAgeGateReason` (= 1 on every
            // age-gated response across locales; absent from the bot-check capture and from ERROR
            // responses).
            if status == "AGE_CHECK_REQUIRED" || wire.playabilityStatus.desktopLegacyAgeGateReason != nil {
                return .ageGate
            }
            // Caveat (unverified live): a private video may also answer LOGIN_REQUIRED; it would
            // classify as .botCheck, which is retryable -- the safer failure direction.
            return .botCheck
        case "UNPLAYABLE":
            // ponytail: every UNPLAYABLE collapses to `.unplayableKids`, so a private/removed
            // video walks the ladder instead of terminating. Splitting on `reason` needs real
            // private/removed specimens to match against — deferred to Plan C.
            return .unplayableKids
        case "LIVE_STREAM_OFFLINE":
            // ponytail: no LIVE_STREAM_OFFLINE probe capture exists to confirm the scheduled-start
            // JSON path, so `startsAt` is always nil for now. Upgrade once a probe fixture exists.
            return .liveOffline(startsAt: nil)
        default:
            return .unavailable(reason: reason)
        }
    }

    private func streamingData(from wire: Wire) -> StreamingData {
        let formats = wire.streamingData?.formats ?? []
        // itag 140 (audio-only m4a) is an ADAPTIVE format -- it is never in `formats`, which carries
        // only the muxed renditions (itag 18/22). Reading it from `formats` made `audioOnlyURL`
        // permanently nil. `formats` stays as a defensive second lookup.
        let adaptive = wire.streamingData?.adaptiveFormats ?? []
        return StreamingData(
            hlsManifestURL: Self.httpsURL(wire.streamingData?.hlsManifestUrl),
            itag18URL: url(forItag: 18, in: formats),
            itag140URL: url(forItag: 140, in: adaptive) ?? url(forItag: 140, in: formats),
            expiresInSeconds: wire.streamingData?.expiresInSeconds.flatMap(Int.init),
            isLive: wire.videoDetails?.isLive ?? wire.videoDetails?.isLiveContent ?? false,
            captionTracks: captionTracks(from: wire.captions)
        )
    }

    private func url(forItag itag: Int, in formats: [Wire.Format]) -> URL? {
        Self.httpsURL(formats.first(where: { $0.itag == itag })?.url)
    }

    /// Defense-in-depth at the trust boundary (M2): a URL handed to AVPlayer must be `https`.
    /// A non-https or unparseable string drops to nil, so that field reads as absent and the
    /// ladder advances rather than playing a spoofable/plain-http URL.
    private static func httpsURL(_ string: String?) -> URL? {
        guard let string, let url = URL(string: string), url.scheme == "https" else { return nil }
        return url
    }

    private func captionTracks(from captions: Wire.Captions?) -> [CaptionTrack] {
        let tracks = captions?.playerCaptionsTracklistRenderer?.captionTracks ?? []
        return tracks.compactMap { track in
            guard let baseUrl = track.baseUrl, let url = Self.httpsURL(baseUrl + "&fmt=vtt") else { return nil }
            let languageCode = track.languageCode ?? ""
            let languageName = track.name?.runs?.first?.text ?? languageCode
            return CaptionTrack(url: url, languageCode: languageCode, languageName: languageName, isAutoGenerated: track.kind == "asr")
        }
    }

    /// Only the fields this parser reads, tolerant of missing keys (Codable's default
    /// behaviour for `Optional` properties).
    private struct Wire: Decodable {
        struct PlayabilityStatus: Decodable {
            var status: String
            var reason: String?
            /// Locale-independent age-gate marker (live probe 2026-08-31): present (= 1) on every
            /// age-gated response in en/ar/nl, absent from bot-check and ERROR responses.
            var desktopLegacyAgeGateReason: Int?
        }
        struct Format: Decodable {
            var itag: Int?
            var url: String?
        }
        struct StreamingData: Decodable {
            var expiresInSeconds: String?
            var hlsManifestUrl: String?
            var formats: [Format]?
            var adaptiveFormats: [Format]?
        }
        struct CaptionTrackWire: Decodable {
            var baseUrl: String?
            var languageCode: String?
            var name: Name?
            var kind: String?

            struct Name: Decodable {
                var runs: [Run]?
            }
            struct Run: Decodable {
                var text: String?
            }
        }
        struct CaptionsTracklistRenderer: Decodable {
            var captionTracks: [CaptionTrackWire]?
        }
        struct Captions: Decodable {
            var playerCaptionsTracklistRenderer: CaptionsTracklistRenderer?
        }
        struct VideoDetails: Decodable {
            var isLive: Bool?
            var isLiveContent: Bool?
        }
        struct ResponseContext: Decodable {
            var visitorData: String?
        }

        var responseContext: ResponseContext?
        var playabilityStatus: PlayabilityStatus
        var streamingData: StreamingData?
        var captions: Captions?
        var videoDetails: VideoDetails?
    }
}
