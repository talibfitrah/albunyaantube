import Foundation

/// The outcome of parsing a `player` InnerTube response body
/// (`extraction.md` §4, `ios-app-plan.md` §6.2 step 4).
public enum Playability: Sendable, Equatable {
    case ok(StreamingData)
    case unplayable
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
    public var isLiveNow: Bool
    public var captionTracks: [CaptionTrack]
    /// `videoDetails.title`: the uploader's own title, untranslated at hl=en, ar and nl (VISIONOS live probes 2026-08-23
    /// and 2026-10-17). Owner issue 1: the player shows this, never a list's translation.
    public var title: String?

    public init(
        hlsManifestURL: URL? = nil,
        itag18URL: URL? = nil,
        itag140URL: URL? = nil,
        expiresInSeconds: Int? = nil,
        isLive: Bool = false,
        isLiveNow: Bool = false,
        captionTracks: [CaptionTrack] = [],
        title: String? = nil
    ) {
        self.hlsManifestURL = hlsManifestURL
        self.itag18URL = itag18URL
        self.itag140URL = itag140URL
        self.expiresInSeconds = expiresInSeconds
        self.isLive = isLive
        self.isLiveNow = isLiveNow
        self.captionTracks = captionTracks
        self.title = title
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
            // responses). CF-G-16: match the probed value exactly -- a hypothetical 0 must fall
            // through to retryable `.botCheck`, not terminal `.ageGate` (the worse direction).
            if status == "AGE_CHECK_REQUIRED" || wire.playabilityStatus.desktopLegacyAgeGateReason == 1 {
                return .ageGate
            }
            // Live probe 2026-10-14 (spec §3): a PRIVATE video answers LOGIN_REQUIRED with a
            // `messages` array on both VISIONOS and ANDROID, in en and ar; the bot check (tokenless
            // en/ar, stale-token en, `player-botcheck.json`) never carries one. `reason` is localized
            // ("Please sign in" / "يرجى تسجيل الدخول"), so only the structure can decide. Terminal:
            // the source gated it -- read as a bot check it rotated the session and could trip the
            // app-wide cooldown.
            if wire.playabilityStatus.messages?.isNonEmpty == true {
                return .unavailable(reason: reason)
            }
            return .botCheck
        case "UNPLAYABLE":
            // Returned whole. VISIONOS refuses a made-for-kids video and a geo-locked one with the
            // same `{status, reason}` and a localized reason (live probe 2026-10-14), so nothing in
            // the body can say which it is: WHO refused decides (`StreamResolver.runPlayerRung`).
            return .unplayable
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
            isLiveNow: wire.videoDetails?.isLive == true,
            captionTracks: captionTracks(from: wire.captions),
            title: wire.videoDetails?.title
        )
    }

    /// Dubbed videos list one format per audio track for the same itag, dubs FIRST (live VISIONOS
    /// probe 2026-10-13: `ar.10` first, the original `en-US.4` last) -- so prefer the track YouTube
    /// flags `audioIsDefault` (the original), else the first match -- among the formats with a
    /// usable URL, so a cipher-only original never turns a playable itag into nil.
    private func url(forItag itag: Int, in formats: [Wire.Format]) -> URL? {
        let matches = formats.filter { $0.itag == itag && Self.httpsURL($0.url) != nil }
        let format = matches.first(where: { $0.audioTrack?.audioIsDefault == true }) ?? matches.first
        return Self.httpsURL(format?.url)
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
            /// Present on a private video's LOGIN_REQUIRED, absent from every bot check (live probe
            /// 2026-10-14) -- the locale-independent private marker.
            var messages: Messages?

            /// Only "present and non-empty, in any shape" matters (spec R7, ruling 2026-10-14): a bot
            /// check never carries `messages`, so an unexpected shape read as absent would turn a private
            /// video into a bot check. Never throws, because a failed decode would drop the whole
            /// response, which on an OK body is an outage. A number or bool counts as absent (never seen).
            struct Messages: Decodable {
                let isNonEmpty: Bool
                init(from decoder: Decoder) {
                    if let array = try? decoder.unkeyedContainer() {
                        isNonEmpty = !array.isAtEnd
                    } else if let object = try? decoder.container(keyedBy: AnyKey.self) {
                        isNonEmpty = !object.allKeys.isEmpty
                    } else if let string = try? decoder.singleValueContainer().decode(String.self) {
                        isNonEmpty = !string.isEmpty
                    } else {
                        isNonEmpty = false
                    }
                }
                private struct AnyKey: CodingKey {
                    let stringValue: String
                    var intValue: Int? { nil }
                    init?(stringValue: String) { self.stringValue = stringValue }
                    init?(intValue: Int) { nil }
                }
            }
        }
        struct Format: Decodable {
            var itag: Int?
            var url: String?
            var audioTrack: AudioTrack?

            struct AudioTrack: Decodable {
                var audioIsDefault: Bool?
            }
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
            /// Display-only, so read leniently (stage 9 I-C9c, the `messages` rule): a title that is not a string is no
            /// title, never a failed decode of the response every resolve needs.
            var title: String?

            private enum CodingKeys: String, CodingKey { case isLive, isLiveContent, title }

            init(from decoder: Decoder) throws {
                let container = try decoder.container(keyedBy: CodingKeys.self)
                isLive = try container.decodeIfPresent(Bool.self, forKey: .isLive)
                isLiveContent = try container.decodeIfPresent(Bool.self, forKey: .isLiveContent)
                title = try? container.decodeIfPresent(String.self, forKey: .title)
            }
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

/// One `adaptiveFormats` entry with the fields the `tv_samsung` upgrade reads (spec §4.2 Parser).
struct AdaptiveFormat: Sendable, Equatable {
    var itag: Int
    var url: String?
    var signatureCipher: String?
    var mimeType: String
    var bitrate: Int
    var averageBitrate: Int?
    var width: Int?
    var height: Int?
    var fps: Int?
    var initRange: ClosedRange<Int>?
    var indexRange: ClosedRange<Int>?
    var contentLength: Int?
    var isDrc: Bool
    var xtags: String?
    var audioTrackId: String?
    var audioTrackName: String?
    var audioIsDefault: Bool
    var hasDRM: Bool
}

extension PlayerResponseParser {
    /// `streamingData.adaptiveFormats` with byte ranges and track fields, for the TV rung only. A separate decode on
    /// purpose: no field this rung needs can ever break the VISIONOS/ANDROID parse above. Entries without an itag,
    /// mimeType or bitrate are dropped.
    func adaptiveFormats(_ body: Data) throws -> [AdaptiveFormat] {
        try JSONDecoder().decode(AdaptiveWire.self, from: body).streamingData?.adaptiveFormats?.compactMap(\.format) ?? []
    }
}

private struct AdaptiveWire: Decodable {
    struct Range: Decodable { var start: String; var end: String }
    struct Track: Decodable { var id: String?; var displayName: String?; var audioIsDefault: Bool? }
    /// Present in any shape counts: `drmFamilies` is never decoded, only noticed.
    struct Present: Decodable { init(from decoder: Decoder) {} }
    struct Format: Decodable {
        var itag: Int?
        var url: String?
        var signatureCipher: String?
        var mimeType: String?
        var bitrate: Int?
        var averageBitrate: Int?
        var width: Int?
        var height: Int?
        var fps: Int?
        var initRange: Range?
        var indexRange: Range?
        var contentLength: String?
        var isDrc: Bool?
        var xtags: String?
        var audioTrack: Track?
        var drmFamilies: Present?

        var format: AdaptiveFormat? {
            guard let itag, let mimeType, let bitrate else { return nil }
            return AdaptiveFormat(
                itag: itag, url: url, signatureCipher: signatureCipher, mimeType: mimeType, bitrate: bitrate,
                averageBitrate: averageBitrate, width: width, height: height, fps: fps,
                initRange: Self.closed(initRange), indexRange: Self.closed(indexRange),
                contentLength: contentLength.flatMap(Int.init), isDrc: isDrc ?? false, xtags: xtags,
                audioTrackId: audioTrack?.id, audioTrackName: audioTrack?.displayName,
                audioIsDefault: audioTrack?.audioIsDefault ?? false, hasDRM: drmFamilies != nil)
        }

        private static func closed(_ range: Range?) -> ClosedRange<Int>? {
            guard let range, let lower = Int(range.start), let upper = Int(range.end), lower <= upper else { return nil }
            return lower...upper
        }
    }
    struct StreamingData: Decodable { var adaptiveFormats: [Format]? }
    var streamingData: StreamingData?
}
