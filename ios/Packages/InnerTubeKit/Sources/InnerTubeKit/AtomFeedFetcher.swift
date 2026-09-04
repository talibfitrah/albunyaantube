import Foundation

/// Errors surfaced by `AtomFeedFetcher.latest(_:)` (a 304 is not an error — it's the
/// conditional-GET fast path, handled internally).
public enum AtomFeedError: Error, Sendable, Equatable {
    case httpError(Int)
}

/// Degraded-mode channel feed (spec §9; `ios-app-plan.md` §6.7/§6.9): YouTube's per-channel Atom
/// feed (`https://www.youtube.com/feeds/videos.xml?channel_id=...`) returns the 15 newest
/// uploads without executing anti-bot JS, so it backs channel-page degraded mode when `browse`
/// is bot-checked and the Me tab's subscribed-channel feed. Ports `AtomChannelFeedFetcher.kt:12-60`
/// / `AtomFeedParser.kt`. Android hands ETag/Last-Modified back to a separate repository layer
/// that holds onto the last-known items across a 304; that layer doesn't exist on iOS yet, so
/// this actor persists both the conditional-GET validators *and* the parsed items itself, keyed
/// per channel in the injected `KeyValueStore` — a 304 replays the same list it returned last time.
///
/// Conditional-GET reality check (probed live 5x, 2026-08-23): `feeds/videos.xml` sends neither
/// `ETag` nor `Last-Modified` — only `Cache-Control: max-age=900` — and ignores `If-Modified-Since`
/// on request. The `If-None-Match`/`If-Modified-Since` machinery above is therefore dormant against
/// the real endpoint today (kept: harmless, and would activate if YouTube adds validators later).
/// Do not read this as "stays under per-IP limits via 304s" — that isn't happening; the actual
/// throttle, if one is needed, would have to honor `max-age=900` via a stored fetch timestamp.
public actor AtomFeedFetcher {
    private static let maxItems = 15
    private static let feedURL = URL(string: "https://www.youtube.com/feeds/videos.xml")!

    private let transport: HTTPTransport
    private let keyValueStore: KeyValueStore

    public init(transport: HTTPTransport, keyValueStore: KeyValueStore) {
        self.transport = transport
        self.keyValueStore = keyValueStore
    }

    public func latest(_ channelId: String) async throws -> [VideoItem] {
        let stored = readCache(channelId)

        var headers = ["Accept": "application/atom+xml"]
        if let etag = stored?.etag { headers["If-None-Match"] = etag }
        if let lastModified = stored?.lastModified { headers["If-Modified-Since"] = lastModified }

        var components = URLComponents(url: Self.feedURL, resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "channel_id", value: channelId)]
        let request = HTTPRequest(method: "GET", url: components.url!, headers: headers, body: nil)

        let response = try await transport.send(request)

        if response.status == 304 {
            return stored?.items.map(\.videoItem) ?? []
        }
        guard response.status == 200 else {
            throw AtomFeedError.httpError(response.status)
        }

        let items = Array(Self.parse(response.body).prefix(Self.maxItems))
        writeCache(
            channelId,
            Cache(
                etag: headerValue(response.headers, "ETag"),
                lastModified: headerValue(response.headers, "Last-Modified"),
                items: items.map(CachedItem.init)))
        return items
    }

    /// The per-channel cache with NO network call -- what the Me feed renders between refreshes,
    /// and what it shows while a refresh is in flight. Empty for a channel never fetched.
    /// Actor-isolated (callers write `await fetcher.cached(id)`) and declared here rather than in an
    /// extension so it can reach the file-private `readCache`.
    public func cached(_ channelId: String) -> [VideoItem] {
        readCache(channelId)?.items.map(\.videoItem) ?? []
    }

    private func headerValue(_ headers: [String: String], _ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    // MARK: - per-channel cache (conditional-GET validators + parsed items)

    private struct CachedItem: Codable {
        var id: String
        var title: String
        var publishedText: String?
        var thumbnailURL: String?
        // Persisted, not re-derived: `publishedText` is already humanized by the time it lands here,
        // so a replay that dropped this field would hand the Me feed a list it cannot sort.
        var publishedAt: Date?

        init(_ item: VideoItem) {
            id = item.id
            title = item.title
            publishedText = item.publishedText
            thumbnailURL = item.thumbnailURL?.absoluteString
            publishedAt = item.publishedAt
        }

        var videoItem: VideoItem {
            VideoItem(
                id: id, title: title, publishedText: publishedText,
                thumbnailURL: thumbnailURL.flatMap(URL.init(string:)), publishedAt: publishedAt)
        }
    }

    private struct Cache: Codable {
        var etag: String?
        var lastModified: String?
        var items: [CachedItem]
    }

    /// `public` so the app's account wiper can sweep every channel's cache without re-spelling the
    /// prefix: the key NAMES enumerate which channels a device subscribed to, so they go with the
    /// account that followed them (`LocalAccountWiper`).
    public static let cacheKeyPrefix = "InnerTubeKit.AtomFeedFetcher."

    private func cacheKey(_ channelId: String) -> String { "\(Self.cacheKeyPrefix)\(channelId)" }

    private func readCache(_ channelId: String) -> Cache? {
        guard let data = keyValueStore.get(cacheKey(channelId)) else { return nil }
        return try? JSONDecoder().decode(Cache.self, from: data)
    }

    private func writeCache(_ channelId: String, _ cache: Cache) {
        guard let data = try? JSONEncoder().encode(cache) else { return }
        keyValueStore.set(cacheKey(channelId), data)
    }

    /// gstack R4: degraded-mode rows render `publishedText` verbatim (RULING 48), so the Atom
    /// `<published>` ISO 8601 instant must be humanized at the producer to match the browse path's
    /// "7 days ago" shape. An unparseable value degrades to nil -- no subtitle beats raw ISO.
    ///
    /// The parser calls this so each entry's ISO string is parsed exactly ONCE and the resulting
    /// `Date` feeds both `publishedText` and `VideoItem.publishedAt`.
    ///
    /// `public` because the Me feed calls it too, and has to: the humanized string is what the
    /// per-channel cache persists, so a cached row's `publishedText` froze at write time and would
    /// render "2 days ago" forever. A feed row humanizes from the exact `publishedAt` at RENDER
    /// time instead; `publishedText` stays the browse/degraded path's field.
    public static func humanizePublished(from date: Date?, now: Date = Date(), locale: Locale = .autoupdatingCurrent) -> String? {
        guard let date else { return nil }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = locale
        formatter.unitsStyle = .full
        return formatter.localizedString(for: date, relativeTo: now)
    }

    // MARK: - XML parsing

    /// Parses `<entry>` elements for `yt:videoId` / `title` / `published` with Foundation
    /// `XMLParser` (no namespace processing — YouTube's feed elements come through as qualified
    /// names like `"yt:videoId"`). Defensive like `AtomFeedParser.kt`: a parse error mid-stream
    /// returns whatever entries were already collected rather than throwing.
    private static func parse(_ data: Data) -> [VideoItem] {
        let delegate = AtomParserDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return delegate.items
    }
}

private final class AtomParserDelegate: NSObject, XMLParserDelegate {
    // One formatter for the whole document rather than one per entry: `XMLParser` drives this
    // delegate synchronously on a single thread, so the instance is never shared.
    private let isoFormatter = ISO8601DateFormatter()
    private(set) var items: [VideoItem] = []
    private var inEntry = false
    private var currentElement = ""
    private var videoId: String?
    private var title: String?
    private var published: String?
    private var thumbnailURL: URL?

    func parser(
        _ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
        qualifiedName qName: String?, attributes attributeDict: [String: String]
    ) {
        currentElement = elementName
        if elementName == "entry" {
            inEntry = true
            videoId = nil
            title = nil
            published = nil
            thumbnailURL = nil
        } else if elementName == "media:thumbnail", inEntry, thumbnailURL == nil {
            thumbnailURL = attributeDict["url"].flatMap(URL.init(string:))
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard inEntry else { return }
        switch currentElement {
        case "yt:videoId": videoId = (videoId ?? "") + string
        case "title": title = (title ?? "") + string
        case "published": published = (published ?? "") + string
        default: break
        }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "entry" {
            inEntry = false
            if let id = videoId?.trimmingCharacters(in: .whitespacesAndNewlines), !id.isEmpty,
                let t = title?.trimmingCharacters(in: .whitespacesAndNewlines)
            {
                let trimmedPublished = published?.trimmingCharacters(in: .whitespacesAndNewlines)
                let publishedAt = trimmedPublished.flatMap { isoFormatter.date(from: $0) }
                items.append(
                    VideoItem(
                        id: id, title: t,
                        publishedText: AtomFeedFetcher.humanizePublished(from: publishedAt),
                        thumbnailURL: thumbnailURL,
                        publishedAt: publishedAt))
            }
        }
        currentElement = ""
    }
}
