import Foundation

/// The three share URL builders and the message body (`ShareLinks.kt:8-73`, `PlayerFragment.kt:3317-3355`).
/// Every URL is the app's own `https://app.fitrahtube.com/api/...` -- owner directive 2026-08-27:
/// never a youtube.com / youtu.be link anywhere. The body omits the URL: `ShareLink(item:subject:message:)`
/// already hands it over as its own activity item (reconciliation note 5).
nonisolated enum ShareLinks {
    enum Target: Hashable, Sendable {
        case video(String)
        case channel(String)
        case playlist(String)
    }

    private static let base = URL(string: "https://app.fitrahtube.com/api/")!
    /// `PlayerFragment.kt:3317-3323`: two lines of title, then "...".
    private static let maxVideoTitle = 160

    static func video(_ id: String) -> URL { url(for: .video(id)) }
    static func channel(_ id: String) -> URL { url(for: .channel(id)) }
    static func playlist(_ id: String) -> URL { url(for: .playlist(id)) }

    static func url(for target: Target) -> URL {
        switch target {
        case .video(let id): base.appending(path: "watch").appending(path: id)
        case .channel(let id): base.appending(path: "channel").appending(path: id)
        case .playlist(let id): base.appending(path: "playlist").appending(path: id)
        }
    }

    static func message(for target: Target, title: String, locale: Locale) -> String {
        let headline: String
        let lineKey: String
        switch target {
        case .video:
            headline = title.count > maxVideoTitle ? String(title.prefix(maxVideoTitle - 3)) + "..." : title
            lineKey = "share_watch_in_app"
        case .channel:
            headline = title
            lineKey = "share_channel_in_app"
        case .playlist:
            headline = title
            lineKey = "share_playlist_in_app"
        }
        return [headline, Format.localizedFormat(lineKey, locale: locale), Format.localizedFormat("share_app_promo", locale: locale)]
            .joined(separator: "\n\n")
    }
}
