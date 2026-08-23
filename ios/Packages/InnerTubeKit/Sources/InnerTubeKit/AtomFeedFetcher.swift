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
        let cached = readCache(channelId)

        var headers = ["Accept": "application/atom+xml"]
        if let etag = cached?.etag { headers["If-None-Match"] = etag }
        if let lastModified = cached?.lastModified { headers["If-Modified-Since"] = lastModified }

        var components = URLComponents(url: Self.feedURL, resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "channel_id", value: channelId)]
        let request = HTTPRequest(method: "GET", url: components.url!, headers: headers, body: nil)

        let response = try await transport.send(request)

        if response.status == 304 {
            return cached?.items.map(\.videoItem) ?? []
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

    private func headerValue(_ headers: [String: String], _ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    // MARK: - per-channel cache (conditional-GET validators + parsed items)

    private struct CachedItem: Codable {
        var id: String
        var title: String
        var publishedText: String?

        init(_ item: VideoItem) {
            id = item.id
            title = item.title
            publishedText = item.publishedText
        }

        var videoItem: VideoItem { VideoItem(id: id, title: title, publishedText: publishedText) }
    }

    private struct Cache: Codable {
        var etag: String?
        var lastModified: String?
        var items: [CachedItem]
    }

    private func cacheKey(_ channelId: String) -> String { "InnerTubeKit.AtomFeedFetcher.\(channelId)" }

    private func readCache(_ channelId: String) -> Cache? {
        guard let data = keyValueStore.get(cacheKey(channelId)) else { return nil }
        return try? JSONDecoder().decode(Cache.self, from: data)
    }

    private func writeCache(_ channelId: String, _ cache: Cache) {
        guard let data = try? JSONEncoder().encode(cache) else { return }
        keyValueStore.set(cacheKey(channelId), data)
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
    private(set) var items: [VideoItem] = []
    private var inEntry = false
    private var currentElement = ""
    private var videoId: String?
    private var title: String?
    private var published: String?

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
                items.append(VideoItem(id: id, title: t, publishedText: published?.trimmingCharacters(in: .whitespacesAndNewlines)))
            }
        }
        currentElement = ""
    }
}
