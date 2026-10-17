import Foundation

/// Locale injected into an InnerTube request (ruling 19 — no `Locale.current`
/// read inside the engine; the app supplies device locale / storefront).
public struct InnerTubeLocale: Sendable, Equatable {
    public var hl: String
    public var gl: String

    public init(hl: String, gl: String) {
        self.hl = hl
        self.gl = gl
    }
}

/// Builds the `POST youtubei/v1/player` request for one videoId + client
/// family (spec §6.2 step 2, §6.3). Deterministic (`.sortedKeys`, no pretty
/// printing) so identical inputs produce byte-identical bodies — the
/// "byte-identical contexts" rule that turned 0/40 into playable.
public struct PlayerRequestBuilder: Sendable {
    private static let requestURL = URL(
        string: "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false")!
    private static let tvRequestURL = URL(string: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")!
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    public init() {}

    /// - Parameter signatureTimestamp: TVHTML5 only (spec R4): the solver's sts, sent in `playbackContext` so the
    ///   minted URLs match the player version the backend solves with. Ignored for every other family.
    public func build(
        videoId: String, family: ClientFamily, context: ClientContext, visitorData: String?, locale: InnerTubeLocale,
        signatureTimestamp: Int? = nil
    ) -> HTTPRequest {
        // Never mix a family with the wrong client context under one visitor (§6.3) — a caller
        // passing e.g. `family: .android` with the `web` ClientContext would silently mint a
        // stream whose family/userAgent/visitor bookkeeping is for the wrong client. This only
        // ever catches an INTERNAL wiring mistake (dev-time); a mismatched *remote* config is
        // filtered out upstream by `RemoteConfigStore.sanitized()` before it ever reaches here,
        // so this stays debug-only rather than crashing release builds on bad served data.
        assert(
            context.clientName == family.expectedClientName,
            "PlayerRequestBuilder: family \(family) expects clientName \(family.expectedClientName), got \(context.clientName)")
        var client = InnerTubeContext.client(context: context, visitorData: visitorData, locale: locale)
        var headers = InnerTubeContext.headers(context: context, visitorData: visitorData, locale: locale)
        var url = Self.requestURL
        var playbackContext: Body.PlaybackContext?
        // R4: gated by FAMILY -- every context carries a userAgent, so a nil test would not gate it. The only shape measured
        // (5/5 OK, 5/5 206 after solving): www.youtube.com, Origin, the body userAgent and the playbackContext.
        if family == .tvSamsung {
            client.userAgent = context.userAgent
            headers["Origin"] = "https://www.youtube.com"
            url = Self.tvRequestURL
            playbackContext = Body.PlaybackContext(contentPlaybackContext: .init(
                html5Preference: "HTML5_PREF_WANTS", signatureTimestamp: signatureTimestamp))
        }
        let body = Body(context: Body.Context(client: client), videoId: videoId, playbackContext: playbackContext,
                        contentCheckOk: true, racyCheckOk: true)
        // Encoding a fixed Codable shape with .sortedKeys and no randomness never fails.
        let data = (try? Self.encoder.encode(body)) ?? Data()
        return HTTPRequest(method: "POST", url: url, headers: headers, body: data)
    }

    private struct Body: Encodable {
        struct Context: Encodable {
            var client: InnerTubeContext.Client
        }
        struct PlaybackContext: Encodable {
            struct Content: Encodable {
                var html5Preference: String
                var signatureTimestamp: Int?
            }
            var contentPlaybackContext: Content
        }
        var context: Context
        var videoId: String
        var playbackContext: PlaybackContext?
        var contentCheckOk: Bool
        var racyCheckOk: Bool
    }
}
