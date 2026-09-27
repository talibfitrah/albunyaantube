import Foundation

/// Shared `context.client` body fragment + standard InnerTube request headers, used by
/// every `youtubei/v1/*` POST (`player`, `browse`). Factored out of `PlayerRequestBuilder`
/// (Task 6, reviewed/closed — its public behaviour is unchanged, byte-identical bodies) since
/// `BrowseClient`'s WEB context assembly is the same shape (ponytail: reuse, don't redefine).
enum InnerTubeContext {
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

    static func client(context: ClientContext, visitorData: String?, locale: InnerTubeLocale) -> Client {
        Client(
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
    }

    /// `Web`'s context carries no `User-Agent` (Task-1 probe); only the visionos/android
    /// segment fetch needs it, so it's added only when the client context supplies one.
    ///
    /// `Accept-Language` is pinned to the injected `hl`: CFNetwork otherwise auto-injects one
    /// derived from the *device* locale, which varies per device and contradicts the body's `hl`
    /// — a per-device wobble in the byte-identical fingerprint §6.3 depends on.
    static func headers(context: ClientContext, visitorData: String?, locale: InnerTubeLocale) -> [String: String] {
        var headers: [String: String] = [
            "Content-Type": "application/json",
            "Accept-Language": locale.hl,
            "X-YouTube-Client-Name": String(context.clientNameId),
            "X-YouTube-Client-Version": context.clientVersion,
        ]
        if let userAgent = context.userAgent {
            headers["User-Agent"] = userAgent
        }
        if let visitorData {
            headers["X-Goog-Visitor-Id"] = visitorData
        }
        return headers
    }
}
