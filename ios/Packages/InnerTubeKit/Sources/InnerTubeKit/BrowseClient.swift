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
/// The thumbnail's non-duration badge: an in-progress stream (`THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE`)
/// or a scheduled premiere/stream ("UPCOMING"). A finished stream carries a duration instead, so
/// `durationSeconds == nil` alone cannot tell live from upcoming (C T5 fix I2).
public enum VideoBadge: Sendable, Equatable {
    case live, upcoming
}

public struct VideoItem: Sendable, Equatable {
    public var id: String
    public var title: String
    public var channelName: String?
    public var channelId: String?
    public var durationSeconds: Int?
    public var viewCountText: String?
    public var publishedText: String?
    public var thumbnailURL: URL?
    public var badge: VideoBadge?
    /// The upload instant, when the producer had one. Only the Atom path sets it (`<published>` is
    /// an ISO 8601 timestamp); `BrowseClient` leaves it nil because YouTube hands browse a
    /// pre-rendered relative string ("7 days ago"), never an instant. Anything that has to *sort*
    /// or *bucket* by recency — the Me feed — needs this; anything that only displays uses
    /// `publishedText` — except cached Atom rows, whose `publishedText` froze at write time; render
    /// those from `publishedAt`.
    public var publishedAt: Date?

    public init(
        id: String, title: String, channelName: String? = nil, channelId: String? = nil,
        durationSeconds: Int? = nil, viewCountText: String? = nil, publishedText: String? = nil,
        thumbnailURL: URL? = nil, badge: VideoBadge? = nil, publishedAt: Date? = nil
    ) {
        self.id = id
        self.title = title
        self.channelName = channelName
        self.channelId = channelId
        self.durationSeconds = durationSeconds
        self.viewCountText = viewCountText
        self.publishedText = publishedText
        self.thumbnailURL = thumbnailURL
        self.badge = badge
        self.publishedAt = publishedAt
    }
}

/// A playlist tile as it appears on a channel's Playlists tab. Captured live 2026-08-29
/// (`browse-channel-playlists.json`): a plain `lockupViewModel` with
/// `contentType: LOCKUP_CONTENT_TYPE_PLAYLIST`, whose thumbnail badge is an item count
/// ("99 videos"), not a duration. `channelName` is nil on a channel's own tab (the channel is
/// implicit) and reserved for playlist listings that carry a byline.
public struct PlaylistTile: Sendable, Equatable {
    public var id: String
    public var title: String
    public var thumbnailURL: URL?
    public var itemCountText: String?
    public var channelName: String?

    public init(id: String, title: String, thumbnailURL: URL? = nil, itemCountText: String? = nil, channelName: String? = nil) {
        self.id = id
        self.title = title
        self.thumbnailURL = thumbnailURL
        self.itemCountText = itemCountText
        self.channelName = channelName
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

/// The three video-shaped channel tabs served by `channel(id).browse(params:)`, distinct from
/// `channelPlaylists` (a different item type, so its own method). `.videos` replaced the `VLUU…`
/// uploads-playlist browseId (`ios-app-plan.md` §6.7, ruling 3) on 2026-08-30: live, that
/// playlist stopped at 200 rows (100 + 100, then no continuation) on a 3.1K-video channel, while
/// this tab's `richGridRenderer` continuation kept paging 30/page past 300. The tab lists
/// long-form uploads only -- Shorts and streams live on their own tabs, as on YouTube.
public enum ChannelTab: Sendable {
    case videos
    case live
    case shorts

    /// Opaque per-tab `params` tokens, captured live 2026-08-24 from a real channel's tab
    /// endpoints (`channel-detail.md`). Forwarded verbatim — nothing in this app decodes them.
    var params: String {
        switch self {
        case .videos: return "EgZ2aWRlb3PyBgQKAjoA"
        case .live: return "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"
        case .shorts: return "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"
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
        try Self.parseHeader(await send(browseId: id, params: nil, continuation: nil))
    }

    /// The Videos tab -- the one uploads path (ruling 3), kept as its own entry point so the app
    /// has a single call site whatever the tab mechanics behind it.
    public func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
        try await channelTab(id, tab: .videos, continuation: continuation)
    }

    /// `.videos` and `.live` are plain `lockupViewModel` grids; `.shorts` is a
    /// `shortsLockupViewModel` grid (captured live 2026-08-29, `browse-channel-shorts.json`) that
    /// `videoItem(_:)` maps onto the same `VideoItem` — a Short has an id, title, thumbnail and a
    /// view-count line and no duration badge. Still unmodelled: community/posts tabs, which
    /// NewPipe cannot extract either (brief §0.2).
    public func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem> {
        let browseId = continuation == nil ? id : nil
        let params = continuation == nil ? tab.params : nil
        var page = try Self.parsePage(await send(browseId: browseId, params: params, continuation: continuation))
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
        return try Self.parsePage(await send(browseId: browseId, params: nil, continuation: continuation))
    }

    /// The channel's Playlists tab: `gridRenderer`-wrapped playlist lockups with an item-count
    /// badge. Its own method because a `PlaylistTile` is not a `VideoItem`.
    public func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile> {
        let browseId = continuation == nil ? id : nil
        let params = continuation == nil ? Self.playlistsTabParams : nil
        return try Self.parsePlaylistPage(await send(browseId: browseId, params: params, continuation: continuation))
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
        // Cubic r3 #5 (defensive): an HTTP-level browse block. Unobserved live -- real
        // interstitials arrive as 200 + `alerts[]` (see `detectBotCheck`) -- but a raw 429/403
        // body is not JSON, so without this it surfaced as `.malformed` and the degraded fallback
        // (keyed on `.botCheck`) never engaged. Other non-200s stay `.malformed` (`BrowseError`
        // has no transport case; either way the caller retries/degrades, never terminal).
        switch response.status {
        case 200..<300: break
        case 429, 403: throw BrowseError.botCheck
        default: throw BrowseError.malformed
        }
        // Adopt `responseContext.visitorData` from EVERY response that carries one — including a
        // bot-check interstitial, which is the bootstrap trip (Plan A: the first tokenless call is
        // bot-checked and THAT response carries the token the next call succeeds with). This runs
        // before the parser gets to throw, which is what makes the interstitial's token stick.
        if let json = (try? JSONSerialization.jsonObject(with: response.body)) as? [String: Any],
            let visitor = Self.dig(json, "responseContext", "visitorData") as? String, !visitor.isEmpty
        {
            await sessionStore.setVisitorData(visitor, for: .web)
        }
        return response.body
    }

    // The rotate-on-stale path was DELETED (Cubic #8 = CF-CL-2, closed): it wiped the visitorData
    // the same interstitial just handed over (`send` adopts from EVERY response, bot checks
    // included) and burnt the 10-minute rotation slot -- and it never fired in ~14 live launches.
    // Adoption alone leaves the session in the correct state: the interstitial's fresh token rides
    // on the next call.
    //
    // Deliberately no `recordBotCheck()` here either: the shared cooldown ladder is consulted by
    // `StreamResolver` before every resolve, so escalating it from browse would let one
    // bot-checked listing page silence playback for an hour (24 h on the fourth trip). Android's
    // cooldown exempts the player for the same reason; a bot-checked browse goes degraded
    // instead (reconciliation note 3, CF-C2).

    /// The Playlists tab's opaque `params` token, captured live 2026-08-24 (`channel-detail.md`).
    /// Forwarded verbatim — nothing in this app decodes it.
    static let playlistsTabParams = "EglwbGF5bGlzdHPyBgQKAkIA"

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

    private static func parsePlaylistPage(_ data: Data) throws -> BrowsePage<PlaylistTile> {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw BrowseError.malformed
        }
        if detectBotCheck(json) { throw BrowseError.botCheck }

        var items: [PlaylistTile] = []
        var nextContinuation: String?
        for raw in itemsArray(json) {
            if let token = continuationToken(raw) {
                nextContinuation = token
                continue
            }
            if let lockup = lockupViewModel(raw), let tile = playlistTile(lockup) {
                items.append(tile)
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

    /// Finds the flat array of content items in a browse response, whichever of the shapes it
    /// is: a continuation append (`onResponseReceivedActions`), a playlist-style initial page
    /// (`sectionListRenderer.itemSectionRenderer`), a channel-tab grid initial page
    /// (`richGridRenderer`), or the Playlists tab (captured 2026-08-29), whose `itemSectionRenderer`
    /// holds a single `gridRenderer` wrapping the tiles.
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
                        if let grid = items.first.flatMap({ dig($0, "gridRenderer", "items") as? [[String: Any]] }) {
                            return grid
                        }
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
    /// is `{"richItemRenderer": {"content": {"lockupViewModel": {...}}}}`, and a Shorts-tab grid
    /// item wraps `shortsLockupViewModel` the same way.
    private static func lockupViewModel(_ item: [String: Any]) -> [String: Any]? {
        if let lockup = item["lockupViewModel"] as? [String: Any] { return lockup }
        if let lockup = dig(item, "richItemRenderer", "content", "lockupViewModel") as? [String: Any] { return lockup }
        return dig(item, "richItemRenderer", "content", "shortsLockupViewModel") as? [String: Any]
    }

    private static func continuationToken(_ item: [String: Any]) -> String? {
        dig(item, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token") as? String
    }

    private static func videoItem(_ lockup: [String: Any]) -> VideoItem? {
        if let short = shortsItem(lockup) { return short }
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
            publishedText: publishedText, thumbnailURL: thumbnailURL, badge: badge(lockup))
    }

    /// The `shortsLockupViewModel` variant (captured 2026-08-29): id and thumbnail live under the
    /// tap endpoint, title and view count under `overlayMetadata`; there is no duration badge.
    private static func shortsItem(_ lockup: [String: Any]) -> VideoItem? {
        let reel = dig(lockup, "onTap", "innertubeCommand", "reelWatchEndpoint")
        guard let id = dig(reel, "videoId") as? String else { return nil }
        return VideoItem(
            id: id,
            title: dig(lockup, "overlayMetadata", "primaryText", "content") as? String ?? "",
            viewCountText: dig(lockup, "overlayMetadata", "secondaryText", "content") as? String,
            thumbnailURL: largestImageURL(dig(reel, "thumbnail", "thumbnails") as? [[String: Any]]))
    }

    private static func playlistTile(_ lockup: [String: Any]) -> PlaylistTile? {
        guard let id = lockup["contentId"] as? String else { return nil }
        let thumbnail = dig(lockup, "contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel")
        var itemCountText: String?
        for overlay in (dig(thumbnail, "overlays") as? [[String: Any]]) ?? [] {
            for badge in (dig(overlay, "thumbnailOverlayBadgeViewModel", "thumbnailBadges") as? [[String: Any]]) ?? [] {
                if let text = dig(badge, "thumbnailBadgeViewModel", "text") as? String { itemCountText = text }
            }
        }
        return PlaylistTile(
            id: id,
            title: dig(lockup, "metadata", "lockupMetadataViewModel", "title", "content") as? String ?? "",
            thumbnailURL: largestImageURL(dig(thumbnail, "image", "sources") as? [[String: Any]]),
            itemCountText: itemCountText)
    }

    /// Reads the thumbnail's bottom-overlay badge text (e.g. "11:08") — the only place a
    /// duration appears on this renderer. Non-timestamp badges ("LIVE", "Members only") don't
    /// parse as `mm:ss`/`hh:mm:ss` and are skipped rather than mis-read.
    private static func durationSeconds(_ lockup: [String: Any]) -> Int? {
        for badge in thumbnailBadges(lockup) {
            if let text = badge["text"] as? String, let seconds = parseDurationText(text) { return seconds }
        }
        return nil
    }

    /// Same badge walk as `durationSeconds`: the live style is locale-independent; "UPCOMING" is
    /// the `hl=en` text (CF-C-13: confirm the scheduled variant's style/text against a live channel).
    private static func badge(_ lockup: [String: Any]) -> VideoBadge? {
        for badge in thumbnailBadges(lockup) {
            let style = badge["badgeStyle"] as? String ?? ""
            let text = (badge["text"] as? String ?? "").uppercased()
            if style.hasSuffix("_LIVE") || text == "LIVE" { return .live }
            if text == "UPCOMING" { return .upcoming }
        }
        return nil
    }

    /// Every `thumbnailBadgeViewModel` under the thumbnail's bottom overlay.
    private static func thumbnailBadges(_ lockup: [String: Any]) -> [[String: Any]] {
        let overlays = (dig(lockup, "contentImage", "thumbnailViewModel", "overlays") as? [[String: Any]]) ?? []
        return overlays.flatMap { overlay in
            ((dig(overlay, "thumbnailBottomOverlayViewModel", "badges") as? [[String: Any]]) ?? [])
                .compactMap { $0["thumbnailBadgeViewModel"] as? [String: Any] }
        }
    }

    private static func parseDurationText(_ text: String) -> Int? {
        let components = text.split(separator: ":")
        let numbers = components.compactMap { Int($0) }
        // ss / mm:ss / hh:mm:ss only, each group bounded: 12+ groups made `Int(pow(60, n))` trap
        // and an `Int.max` group overflowed the multiply.
        guard (1...3).contains(numbers.count), numbers.count == components.count,
            numbers.allSatisfy({ (0..<100_000).contains($0) })
        else { return nil }
        return numbers.reduce(0) { $0 * 60 + $1 }
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
        // Locale probe 2026-08-31 (Cubic #7): the alert text is localized -- an ar/nl session never
        // contains the English interstitial copy, so a text match missed every non-English bot
        // check. The structured, locale-independent discriminator is the alert's renderer + type:
        // the interstitial is an `alertWithButtonRenderer` (it carries the sign-in button) with
        // `type == "ERROR"`; informational notices are plain `alertRenderer`s and never match.
        return alerts.contains { alert in
            ((alert["alertWithButtonRenderer"] as? [String: Any])?["type"] as? String) == "ERROR"
        }
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
