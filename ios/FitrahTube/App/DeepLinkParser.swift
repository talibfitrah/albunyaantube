import Foundation

/// Parses the two deep-link surfaces spec §6 lists: the custom scheme and the Universal Link
/// hosts (`AndroidManifest.xml:52-129`; `2026-08-23-ios-app-design.md` §6).
///
/// - Custom scheme: `albunyaantube://{video|channel|playlist|shorts}/{id}`
/// - Universal Link: `https://app.fitrahtube.com/{watch|channel|playlist}/{id}`
///   and the same three paths under `/api/...`. `shorts` has no Universal Link shape (Android
///   only reaches it via in-app navigation, never a verified https host).
nonisolated enum DeepLinkParser {
    private static let universalLinkHost = "app.fitrahtube.com"

    static func route(for url: URL) -> Route? {
        switch url.scheme {
        case "albunyaantube":
            guard let kind = url.host, let id = firstPathComponent(of: url) else { return nil }
            return route(kind: kind, id: id)
        case "https":
            guard url.host == universalLinkHost else { return nil }
            var segments = pathComponents(of: url)
            if segments.first == "api" { segments.removeFirst() }
            guard segments.count == 2, segments[0] != "shorts" else { return nil }
            let kind = segments[0] == "watch" ? "video" : segments[0]
            return route(kind: kind, id: segments[1])
        default:
            return nil
        }
    }

    private static func route(kind: String, id: String) -> Route? {
        switch kind {
        case "video": .player(PlayerArgs(videoId: id))
        case "channel": .channel(id: id, name: nil, avatarURL: nil)
        case "playlist": .playlist(id: id, title: nil, category: nil, count: nil)
        case "shorts": .shorts(id: id)
        default: nil
        }
    }

    private static func pathComponents(of url: URL) -> [String] {
        url.pathComponents.filter { $0 != "/" }
    }

    private static func firstPathComponent(of url: URL) -> String? {
        pathComponents(of: url).first
    }
}
