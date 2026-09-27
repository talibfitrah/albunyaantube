import Foundation

/// `SuggestContentViewModel.kt:76-112`. Turns a string the user pasted into something the BACKEND
/// search can be scoped by.
///
/// **This is not a link to YouTube.** Nothing here opens, renders or navigates to what it reads —
/// it extracts a type and an id, which then travel to `GET api/admin/youtube/search` on this app's
/// own backend (owner directive 2026-08-27: no button, link or redirect to YouTube anywhere).
///
/// Only `http`/`https`; hosts `youtu.be`, `youtube.com`, `youtube-nocookie.com` and their
/// subdomains; PRECEDENCE `v` -> `list` -> `/channel/<id>` -> `/shorts/<id>` -> `@handle`. Anything
/// else — a different scheme, a different host, a YouTube URL that names no content — falls through
/// to a plain `(ALL, rawQuery)` search carrying the RAW text, so what the user typed is what gets
/// searched.
nonisolated enum YouTubeURLParser {
    enum Parsed: Equatable {
        case video(String), playlist(String), channel(String), handle(String), query(String)
    }

    /// The three accepted hosts. Matched as `host == name || host.hasSuffix("." + name)` — a DOT
    /// boundary, never `contains`: `https://evil.com/youtube.com/watch?v=…` has host `evil.com`, and
    /// a substring test against the whole URL (or a suffix test with no dot) would let any site
    /// have its links parsed as YouTube's.
    private static let hosts = ["youtu.be", "youtube.com", "youtube-nocookie.com"]

    static func parse(_ raw: String) -> Parsed {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host?.lowercased(),
              let matched = hosts.first(where: { host == $0 || host.hasSuffix(".\($0)") })
        else { return .query(raw) }

        // Path segments, empties dropped — `/shorts/abc/` and `//shorts//abc` read alike.
        let segments = components.path.split(separator: "/").map(String.init)

        // The short host carries the id in the PATH and nothing else (`:83-86`).
        if matched == "youtu.be" {
            return segments.first.map(Parsed.video) ?? .query(raw)
        }

        // `queryItems` percent-DECODES, which is Android's `URLDecoder.decode` leg (`:120`). An
        // empty value is treated as absent: `?v=` must not become a search for a video with no id.
        let params = (components.queryItems ?? []).reduce(into: [String: String]()) { table, item in
            guard let value = item.value, !value.isEmpty, table[item.name] == nil else { return }
            table[item.name] = value
        }
        if let video = params["v"] { return .video(video) }
        if let list = params["list"] { return .playlist(list) }
        if let channel = segment(after: "channel", in: segments) { return .channel(channel) }
        if let short = segment(after: "shorts", in: segments) { return .video(short) }
        if let handle = segments.first(where: { $0.hasPrefix("@") }) { return .handle(handle) }
        return .query(raw)
    }

    private static func segment(after marker: String, in segments: [String]) -> String? {
        guard let index = segments.firstIndex(of: marker), index + 1 < segments.count else { return nil }
        return segments[index + 1]
    }
}

extension YouTubeURLParser.Parsed {
    /// `resolveQuery` (`:73-74`): what the backend is actually asked for. A handle resolves to a
    /// CHANNEL search FOR THE HANDLE (`:105`) rather than to an id, because a handle is not one.
    var resolved: (type: SuggestType, query: String) {
        switch self {
        case .video(let id): (.videos, id)
        case .playlist(let id): (.playlists, id)
        case .channel(let id): (.channels, id)
        case .handle(let handle): (.channels, handle)
        case .query(let raw): (.all, raw)
        }
    }

    /// The registry target this parse names, or nil when it names none. A `.query` is not a target
    /// (there is no id in it) and neither is a `.handle`: the registry keys on a YouTube id, and
    /// Android's own submit sheet refuses handles for the same reason (`SubmitContentBottomSheet
    /// .kt:167-168`, "only UCxxx channel IDs are supported").
    var submitTarget: SubmitTarget? {
        switch self {
        case .video(let id): SubmitTarget(type: .videos, youtubeId: id)
        case .playlist(let id): SubmitTarget(type: .playlists, youtubeId: id)
        case .channel(let id): SubmitTarget(type: .channels, youtubeId: id)
        case .handle, .query: nil
        }
    }
}

/// What `SubmitContentSheet` posts: the registry collection and the id inside it. One type, because
/// the two always travel together — `POST api/admin/registry/{type}` puts one in the PATH and the
/// other in the BODY, and a mismatched pair submits a video into the channels collection.
nonisolated struct SubmitTarget: Equatable, Sendable {
    let type: SubmissionType
    let youtubeId: String
}

extension SuggestItem {
    /// The same target, reached from a search result instead of from a pasted URL — so the sheet
    /// has ONE thing to submit whichever entry point opened it.
    ///
    /// A HIT's own type is never `.all`: `YouTubeSearchClient.item` drops a row whose `contentType`
    /// it cannot name, `"ALL"` included, precisely so this mapping needs no guess. The `.all` arm
    /// is therefore unreachable and answers nil rather than picking a collection.
    var submitTarget: SubmitTarget? {
        switch type {
        case .channels: SubmitTarget(type: .channels, youtubeId: youtubeId)
        case .playlists: SubmitTarget(type: .playlists, youtubeId: youtubeId)
        case .videos: SubmitTarget(type: .videos, youtubeId: youtubeId)
        case .all: nil
        }
    }
}
