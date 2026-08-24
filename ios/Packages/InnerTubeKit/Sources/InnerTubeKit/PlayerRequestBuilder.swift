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
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    public init() {}

    public func build(
        videoId: String, family: ClientFamily, context: ClientContext, visitorData: String?, locale: InnerTubeLocale
    ) -> HTTPRequest {
        // Never mix a family with the wrong client context under one visitor (§6.3) — a caller
        // passing e.g. `family: .android` with the `web` ClientContext would silently mint a
        // stream whose family/userAgent/visitor bookkeeping is for the wrong client.
        precondition(
            context.clientName == family.expectedClientName,
            "PlayerRequestBuilder: family \(family) expects clientName \(family.expectedClientName), got \(context.clientName)")
        let body = Body(
            context: Body.Context(client: InnerTubeContext.client(context: context, visitorData: visitorData, locale: locale)),
            videoId: videoId,
            contentCheckOk: true,
            racyCheckOk: true
        )
        // Encoding a fixed Codable shape with .sortedKeys and no randomness never fails.
        let data = (try? Self.encoder.encode(body)) ?? Data()
        let headers = InnerTubeContext.headers(context: context, visitorData: visitorData, locale: locale)

        return HTTPRequest(method: "POST", url: Self.requestURL, headers: headers, body: data)
    }

    private struct Body: Encodable {
        struct Context: Encodable {
            var client: InnerTubeContext.Client
        }
        var context: Context
        var videoId: String
        var contentCheckOk: Bool
        var racyCheckOk: Bool
    }
}
