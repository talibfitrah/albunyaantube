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

    /// RFC 3986 makes scheme and host case-insensitive and Foundation preserves whatever case was
    /// written, so `ALBUNYAANTUBE://video/abc` and `https://APP.FITRAHTUBE.COM/watch/abc` used to
    /// be silently dropped -- iOS matches `CFBundleURLSchemes` case-insensitively when *routing*,
    /// so the app was launched and then discarded the URL (gate A-M2/cso observation).
    static func route(for url: URL) -> Route? {
        switch url.scheme?.lowercased() {
        case "albunyaantube":
            let segments = pathComponents(of: url)
            // Exactly one component, mirroring the Universal Link branch's own `count == 2` guard.
            // `albunyaantube://video/a%2Fb` decodes to ["a", "b"] and used to silently become the
            // *wrong* video "a"; `…/abc/extra` silently ignored the trailing junk (gate A-M3).
            guard let kind = url.host()?.lowercased(), segments.count == 1 else { return nil }
            return route(kind: kind, id: segments[0])
        case "https":
            guard url.host()?.lowercased() == universalLinkHost else { return nil }
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
        guard isValidID(id) else { return nil }
        switch kind {
        case "video": return .player(PlayerArgs(videoId: id))
        case "channel": return .channel(id: id, name: nil, avatarURL: nil)
        case "playlist": return .playlist(id: id, title: nil, category: nil, count: nil)
        case "shorts": return .shorts(PlayerArgs(videoId: id))
        default: return nil
        }
    }

    private static let allowedIDCharacters = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")

    /// `^[A-Za-z0-9_-]{1,64}$`, applied at the trust boundary where all four kinds converge
    /// (gate A-I4 / cso-F1). Any web page or installed app can navigate to `albunyaantube://…`,
    /// and the ids reach `Route` verbatim: `..`, an embedded NUL, a `?`, or a 64 KB string are all
    /// interpolated into InnerTube request bodies and `PlayerArgs.videoId` into playback URLs, so
    /// an unchecked id is a path-traversal / C-string-truncation / query-injection primitive and
    /// the screens that consume it never revisit this file. Covers every real YouTube and
    /// Firestore document id.
    private static func isValidID(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 64 && id.allSatisfy(allowedIDCharacters.contains)
    }

    private static func pathComponents(of url: URL) -> [String] {
        url.pathComponents.filter { $0 != "/" }
    }
}
