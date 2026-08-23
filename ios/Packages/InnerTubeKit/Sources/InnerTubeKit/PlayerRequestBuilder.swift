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
        let body = Body(
            context: Body.Context(
                client: Body.Context.Client(
                    clientName: context.clientName,
                    clientVersion: context.clientVersion,
                    deviceMake: context.deviceMake,
                    deviceModel: context.deviceModel,
                    osName: context.osName,
                    osVersion: context.osVersion,
                    androidSdkVersion: context.androidSdkVersion,
                    hl: locale.hl,
                    gl: locale.gl,
                    visitorData: visitorData
                )
            ),
            videoId: videoId,
            contentCheckOk: true,
            racyCheckOk: true
        )
        // Encoding a fixed Codable shape with .sortedKeys and no randomness never fails.
        let data = (try? Self.encoder.encode(body)) ?? Data()

        var headers: [String: String] = [
            "Content-Type": "application/json",
            "X-YouTube-Client-Name": String(context.clientNameId),
            "X-YouTube-Client-Version": context.clientVersion,
        ]
        // Web's context carries no User-Agent (Task-1 probe); URLSession's default is fine
        // for the browse/player POST — only the visionos/android segment fetch needs it.
        if let userAgent = context.userAgent {
            headers["User-Agent"] = userAgent
        }
        if let visitorData {
            headers["X-Goog-Visitor-Id"] = visitorData
        }

        return HTTPRequest(method: "POST", url: Self.requestURL, headers: headers, body: data)
    }

    private struct Body: Encodable {
        struct Context: Encodable {
            struct Client: Encodable {
                var clientName: String
                var clientVersion: String
                var deviceMake: String?
                var deviceModel: String?
                var osName: String?
                var osVersion: String?
                var androidSdkVersion: Int?
                var hl: String
                var gl: String
                var visitorData: String?
            }
            var client: Client
        }
        var context: Context
        var videoId: String
        var contentCheckOk: Bool
        var racyCheckOk: Bool
    }
}
