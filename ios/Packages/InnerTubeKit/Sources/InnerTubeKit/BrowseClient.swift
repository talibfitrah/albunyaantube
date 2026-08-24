import Foundation

/// One page of browse results plus the opaque continuation token for the next one, if any
/// (spec §9, `ios-app-plan.md` §6.7).
public struct BrowsePage<T: Sendable>: Sendable {
    public var items: [T]
    public var nextContinuation: String?

    public init(items: [T], nextContinuation: String?) {
        self.items = items
        self.nextContinuation = nextContinuation
    }
}

/// A video/live-stream tile as it appears in a channel or playlist listing. Fields match what
/// the `lockupViewModel` renderer (YouTube's current browse UI model, captured live 2026-08-24 —
/// the spec's `richItemRenderer`/`playlistVideoRenderer` wrap this same model, not the older
/// `videoRenderer` shape) actually exposes: `channelName`/`channelId` are present on
/// playlist-style listings (each item carries its uploader) but not on a channel's own tabs
/// (the channel is already known there — `BrowseClient` backfills `channelId` in that case).
public struct VideoItem: Sendable, Equatable {
    public var id: String
    public var title: String
    public var channelName: String?
    public var channelId: String?
    public var durationSeconds: Int?
    public var viewCountText: String?
    public var publishedText: String?
    public var thumbnailURL: URL?

    public init(
        id: String, title: String, channelName: String? = nil, channelId: String? = nil,
        durationSeconds: Int? = nil, viewCountText: String? = nil, publishedText: String? = nil,
        thumbnailURL: URL? = nil
    ) {
        self.id = id
        self.title = title
        self.channelName = channelName
        self.channelId = channelId
        self.durationSeconds = durationSeconds
        self.viewCountText = viewCountText
        self.publishedText = publishedText
        self.thumbnailURL = thumbnailURL
    }
}

/// A channel's header (spec §9: "channel header, ... About from the header").
public struct ChannelHeader: Sendable, Equatable {
    public var id: String
    public var name: String
    public var subscriberText: String?
    public var avatarURL: URL?
    public var bannerURL: URL?

    public init(id: String, name: String, subscriberText: String? = nil, avatarURL: URL? = nil, bannerURL: URL? = nil) {
        self.id = id
        self.name = name
        self.subscriberText = subscriberText
        self.avatarURL = avatarURL
        self.bannerURL = bannerURL
    }
}

/// The three channel tabs served by `channel(id).browse(params:)`, distinct from `channelVideos`
/// (which uses the `VLUU…` uploads-playlist trick instead — `ios-app-plan.md` §6.7).
public enum ChannelTab: Sendable {
    case live
    case shorts
    case playlists

    /// Opaque per-tab `params` tokens, captured live 2026-08-24 from a real channel's tab
    /// endpoints (`channel-detail.md`). Forwarded verbatim — nothing in this app decodes them.
    var params: String {
        switch self {
        case .live: return "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"
        case .shorts: return "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"
        case .playlists: return "EglwbGF5bGlzdHPyBgQKAkIA"
        }
    }
}

/// Errors surfaced by a `browse` call. Degraded-mode fallback (approved playlists + Atom feed)
/// on `.botCheck` is the caller's responsibility (Plan C), not this client's.
public enum BrowseError: Error, Sendable, Equatable {
    case botCheck
    case malformed
}

/// Builds the `POST youtubei/v1/browse` request for a channel/playlist page or its continuation.
/// Sibling to `PlayerRequestBuilder`, sharing its context assembly via `InnerTubeContext`.
public struct BrowseRequestBuilder: Sendable {
    private static let requestURL = URL(string: "https://youtubei.googleapis.com/youtubei/v1/browse?prettyPrint=false")!
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    public init() {}

    /// `browseId`/`params` are sent on the first page; a `continuation` call omits both and
    /// carries only the token (this is what a real continuation request looks like on the wire).
    public func build(
        browseId: String?, params: String?, continuation: String?,
        context: ClientContext, visitorData: String?, locale: InnerTubeLocale
    ) -> HTTPRequest {
        let body = Body(
            context: Body.Context(client: InnerTubeContext.client(context: context, visitorData: visitorData, locale: locale)),
            browseId: browseId,
            params: params,
            continuation: continuation
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
        var browseId: String?
        var params: String?
        var continuation: String?
    }
}

/// InnerTube `browse` on the WEB client context: channel header, tabs, and playlist items
/// (spec §9, `ios-app-plan.md` §6.7). Every network call goes through the injected
/// `HTTPTransport`; parsing is tolerant of missing fields (YouTube's browse UI-model JSON) and
/// throws `BrowseError.botCheck` when the response is an interstitial rather than content.
public actor BrowseClient {
    private let transport: HTTPTransport
    private let remoteConfigStore: RemoteConfigStore
    private let sessionStore: SessionStore
    private let requestBuilder: BrowseRequestBuilder
    private let locale: InnerTubeLocale

    public init(
        transport: HTTPTransport,
        remoteConfigStore: RemoteConfigStore,
        sessionStore: SessionStore,
        locale: InnerTubeLocale,
        requestBuilder: BrowseRequestBuilder = BrowseRequestBuilder()
    ) {
        self.transport = transport
        self.remoteConfigStore = remoteConfigStore
        self.sessionStore = sessionStore
        self.locale = locale
        self.requestBuilder = requestBuilder
    }

    public func channelHeader(_ id: String) async throws -> ChannelHeader {
        let body = try await send(browseId: id, params: nil, continuation: nil)
        return try Self.parseHeader(body)
    }

    /// Uploads via the `VLUU…` uploads-playlist browseId (stable across pages — the trick
    /// Android uses because channel-tab continuations are unreliable past 1-2 pages,
    /// `channel-detail.md`). Each item already carries its own channel byline in this shape.
    public func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
        let browseId = continuation == nil ? Self.uploadsPlaylistBrowseId(for: id) : nil
        let body = try await send(browseId: browseId, params: nil, continuation: continuation)
        return try Self.parsePage(body)
    }

    // ponytail: `.shorts` (`shortsLockupViewModel`) and `.playlists` (`gridRenderer`-wrapped
    // tiles with an item-count badge, not a duration) use wire shapes `VideoItem` doesn't model;
    // this returns an empty page for them today rather than mis-parsing. `.live` (plain
    // `lockupViewModel`, same as channelVideos) is fully supported. Upgrade: dedicated
    // ShortsItem/PlaylistTile parsing once a consumer needs those two tabs.
    public func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem> {
        let browseId = continuation == nil ? id : nil
        let params = continuation == nil ? tab.params : nil
        let body = try await send(browseId: browseId, params: params, continuation: continuation)
        var page = try Self.parsePage(body)
        // Channel-tab items carry no byline (the channel is implicit); backfill from the known id.
        page.items = page.items.map { item in
            guard item.channelId == nil else { return item }
            var item = item
            item.channelId = id
            return item
        }
        return page
    }

    public func playlistItems(_ playlistId: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
        let browseId = continuation == nil ? "VL" + playlistId : nil
        let body = try await send(browseId: browseId, params: nil, continuation: continuation)
        return try Self.parsePage(body)
    }

    // MARK: - network

    private func send(browseId: String?, params: String?, continuation: String?) async throws -> Data {
        guard let context = await remoteConfigStore.current().clients["web"] else {
            throw BrowseError.malformed
        }
        let visitorData = await sessionStore.visitorData(for: .web)
        let request = requestBuilder.build(
            browseId: browseId, params: params, continuation: continuation,
            context: context, visitorData: visitorData, locale: locale)
        let response = try await transport.send(request)
        return response.body
    }

    private static func uploadsPlaylistBrowseId(for channelId: String) -> String {
        "VLUU" + channelId.dropFirst(2)
    }

    // MARK: - parsing

    private static func parsePage(_ data: Data) throws -> BrowsePage<VideoItem> {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw BrowseError.malformed
        }
        if detectBotCheck(json) { throw BrowseError.botCheck }

        var items: [VideoItem] = []
        var nextContinuation: String?
        for raw in itemsArray(json) {
            if let token = continuationToken(raw) {
                nextContinuation = token
                continue
            }
            if let lockup = lockupViewModel(raw), let item = videoItem(lockup) {
                items.append(item)
            }
        }
        return BrowsePage(items: items, nextContinuation: nextContinuation)
    }

    private static func parseHeader(_ data: Data) throws -> ChannelHeader {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw BrowseError.malformed
        }
        if detectBotCheck(json) { throw BrowseError.botCheck }
        guard let id = dig(json, "metadata", "channelMetadataRenderer", "externalId") as? String,
            let name = dig(json, "metadata", "channelMetadataRenderer", "title") as? String
        else {
            throw BrowseError.malformed
        }

        let headerVM = dig(json, "header", "pageHeaderRenderer", "content", "pageHeaderViewModel")
        let rows = (dig(headerVM, "metadata", "contentMetadataViewModel", "metadataRows") as? [[String: Any]]) ?? []
        var subscriberText: String?
        for row in rows {
            guard let parts = row["metadataParts"] as? [[String: Any]] else { continue }
            for part in parts {
                guard let text = dig(part, "text", "content") as? String, text.lowercased().contains("subscriber") else { continue }
                subscriberText = text
            }
        }

        let avatarURL = largestImageURL(
            dig(headerVM, "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image", "sources") as? [[String: Any]])
        let bannerURL = largestImageURL(dig(headerVM, "banner", "imageBannerViewModel", "image", "sources") as? [[String: Any]])

        return ChannelHeader(id: id, name: name, subscriberText: subscriberText, avatarURL: avatarURL, bannerURL: bannerURL)
    }

    /// Finds the flat array of content items in a browse response, whichever of the two shapes
    /// it is: a continuation append (`onResponseReceivedActions`), a playlist-style initial page
    /// (`sectionListRenderer.itemSectionRenderer`), or a channel-tab grid initial page
    /// (`richGridRenderer`).
    private static func itemsArray(_ json: [String: Any]) -> [[String: Any]] {
        if let actions = json["onResponseReceivedActions"] as? [[String: Any]] {
            for action in actions {
                if let items = dig(action, "appendContinuationItemsAction", "continuationItems") as? [[String: Any]] {
                    return items
                }
            }
        }
        guard let tabs = dig(json, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? [[String: Any]] else {
            return []
        }
        for tab in tabs {
            guard let content = dig(tab, "tabRenderer", "content") as? [String: Any] else { continue }
            if let sections = dig(content, "sectionListRenderer", "contents") as? [[String: Any]] {
                for section in sections {
                    if let items = dig(section, "itemSectionRenderer", "contents") as? [[String: Any]] {
                        return items
                    }
                }
            }
            if let items = dig(content, "richGridRenderer", "contents") as? [[String: Any]] {
                return items
            }
        }
        return []
    }

    /// A playlist-style item is `{"lockupViewModel": {...}}` directly; a channel-tab grid item
    /// is `{"richItemRenderer": {"content": {"lockupViewModel": {...}}}}`.
    private static func lockupViewModel(_ item: [String: Any]) -> [String: Any]? {
        if let lockup = item["lockupViewModel"] as? [String: Any] { return lockup }
        return dig(item, "richItemRenderer", "content", "lockupViewModel") as? [String: Any]
    }

    private static func continuationToken(_ item: [String: Any]) -> String? {
        dig(item, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token") as? String
    }

    private static func videoItem(_ lockup: [String: Any]) -> VideoItem? {
        guard let id = lockup["contentId"] as? String else { return nil }
        let title = dig(lockup, "metadata", "lockupMetadataViewModel", "title", "content") as? String ?? ""
        let rows =
            (dig(lockup, "metadata", "lockupMetadataViewModel", "metadata", "contentMetadataViewModel", "metadataRows")
                as? [[String: Any]]) ?? []

        // Two rows = [channel byline, stats] (playlist-style listings); one row = [stats] only
        // (a channel's own tabs, where the channel is already known).
        var channelName: String?
        var channelId: String?
        let statsRow: [String: Any]?
        if rows.count >= 2, let bylineParts = rows[0]["metadataParts"] as? [[String: Any]], let byline = bylineParts.first {
            channelName = dig(byline, "text", "content") as? String
            let commandRuns = dig(byline, "text", "commandRuns") as? [[String: Any]]
            channelId = commandRuns?.first.flatMap { dig($0, "onTap", "innertubeCommand", "browseEndpoint", "browseId") as? String }
            statsRow = rows[1]
        } else {
            statsRow = rows.first
        }

        var viewCountText: String?
        var publishedText: String?
        if let parts = statsRow?["metadataParts"] as? [[String: Any]] {
            let texts = parts.compactMap { dig($0, "text", "content") as? String }
            viewCountText = texts.first
            publishedText = texts.count > 1 ? texts[1] : nil
        }

        let thumbnailURL = largestImageURL(dig(lockup, "contentImage", "thumbnailViewModel", "image", "sources") as? [[String: Any]])

        return VideoItem(
            id: id, title: title, channelName: channelName, channelId: channelId,
            durationSeconds: durationSeconds(lockup), viewCountText: viewCountText,
            publishedText: publishedText, thumbnailURL: thumbnailURL)
    }

    /// Reads the thumbnail's bottom-overlay badge text (e.g. "11:08") — the only place a
    /// duration appears on this renderer. Non-timestamp badges ("LIVE", "Members only") don't
    /// parse as `mm:ss`/`hh:mm:ss` and are skipped rather than mis-read.
    private static func durationSeconds(_ lockup: [String: Any]) -> Int? {
        let overlays = (dig(lockup, "contentImage", "thumbnailViewModel", "overlays") as? [[String: Any]]) ?? []
        for overlay in overlays {
            guard let badges = dig(overlay, "thumbnailBottomOverlayViewModel", "badges") as? [[String: Any]] else { continue }
            for badge in badges {
                if let text = dig(badge, "thumbnailBadgeViewModel", "text") as? String, let seconds = parseDurationText(text) {
                    return seconds
                }
            }
        }
        return nil
    }

    private static func parseDurationText(_ text: String) -> Int? {
        let components = text.split(separator: ":")
        let numbers = components.compactMap { Int($0) }
        guard !numbers.isEmpty, numbers.count == components.count else { return nil }
        return numbers.reversed().enumerated().reduce(0) { $0 + $1.element * Int(pow(60.0, Double($1.offset))) }
    }

    private static func largestImageURL(_ sources: [[String: Any]]?) -> URL? {
        let best = sources?.max { (($0["width"] as? Int) ?? 0) < (($1["width"] as? Int) ?? 0) }
        return (best?["url"] as? String).flatMap(URL.init(string:))
    }

    /// InnerTube's standard interstitial convention for `browse`/`search`/`next` (distinct from
    /// `player`'s `playabilityStatus`): a top-level `alerts[]` array of renderers whose text
    /// names the block.
    private static func detectBotCheck(_ json: [String: Any]) -> Bool {
        guard let alerts = json["alerts"] as? [[String: Any]] else { return false }
        for alert in alerts {
            for value in alert.values {
                guard let renderer = value as? [String: Any] else { continue }
                let text = (dig(renderer, "text", "simpleText") as? String) ?? (dig(renderer, "text", "content") as? String)
                if let text, text.lowercased().contains("bot") {
                    return true
                }
            }
        }
        return false
    }

    /// Minimal dictionary-path walker over `JSONSerialization`'s `[String: Any]` tree —
    /// YouTube's browse UI-model JSON is deeply nested and only a handful of fields are read, so
    /// a full Codable struct tree would be mostly boilerplate for fields never used.
    private static func dig(_ obj: Any?, _ path: String...) -> Any? {
        var current = obj
        for key in path {
            guard let dict = current as? [String: Any] else { return nil }
            current = dict[key]
        }
        return current
    }
}
