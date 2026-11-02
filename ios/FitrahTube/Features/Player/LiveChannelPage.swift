import Foundation
import InnerTubeKit

/// Owner 2026-11-02: live-channel mode finds a channel's new live on the phone, not from our server's YouTube reads.
/// YouTube's `/channel/{id}/live` page carries `<link rel="canonical">` = the live's watch URL, or the channel's own URL
/// when nothing is live (live probe 2026-11-02, NL). Curation stays the backend's: a live read here goes to
/// `GET /v1/live/channels/{id}?live=`, which answers as always (404 for a channel not served live) plus this id only if
/// it passes curation. Of that answer only the page's live counts; it feeds `LiveChannelRecovery`.
nonisolated struct LiveChannelPage: Sendable {
    struct Unreadable: Error {}

    /// The desktop UA the app already sends YouTube (`remote-config-default.json`'s visionos client); the probe got the
    /// same page under it.
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    /// ponytail: the player gives the whole lookup 10 s (`liveChannelTrigger`); the page gets half, so a page that never
    /// answers still leaves the backend 5 s. Tune both together.
    static let timeout: Duration = .seconds(5)
    /// The page is ~1.3 MB with the canonical near 0.8 MB (probe 2026-11-02); the cap only stops a runaway body.
    static let maxBodyBytes = 4 << 20
    /// NewPipeExtractor 0.26.5 `YoutubeParsingHelper.getCookieHeader()` with consent not accepted (its default; Android
    /// never changes it), the header Android sends on this request: an EU consent interstitial would hide the canonical.
    static let consentCookie = "SOCS=CAE="
    /// Android's `DETAILS_MAX_CHARS` (UTF-16 units): how far after `"videoDetails":{` its `"shortDescription"` may end.
    static let detailsMaxChars = 64 * 1024

    private let transport: BoundedHTTPTransport
    private let sleep: @Sendable (Duration) async throws -> Void

    init(transport: BoundedHTTPTransport = URLSessionTransport(),
         sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.transport = transport
        self.sleep = sleep
    }

    /// `backend(live)` is the catalog's `liveChannel(id:live:)` for this channel. `notFound` hears the not-live plain
    /// call's 404 (Cubic K3), which lands after this returns.
    func lives(channelId: String,
               backend: @escaping @Sendable (_ live: String?) async throws -> LiveChannelLives,
               notFound: @escaping @Sendable () async -> Void = {}) async throws -> LiveChannelLives {
        let page: [LiveChannelLives.Live]
        do {
            page = try await read(channelId)
        } catch {
            try Task.checkCancellation()        // a cancelled lookup is no answer: never charge the backend for it
            return try await backend(nil)       // no answer from the page: the backend as before
        }
        guard let live = page.first else {          // not live: an answer
            // Fire-and-forget: the plain call makes the backend refresh its snapshot of the channel, so an ended live
            // leaves the LIVE category. Unstructured, never awaited; its answer and every error are ignored, except a 404
            // (Cubic K3): the channel is not a live entry, so `notFound` lets the running incident give up at once.
            Task.detached {
                do { _ = try await backend(nil) } catch is LiveChannelNotFound { await notFound() } catch {}
            }
            return LiveChannelLives(lives: [])
        }
        // Fail-closed: every backend error goes up as is (404 stops; 503/5xx/transport is no answer, and the mode retries),
        // so a live is never served past curation. Only the page's live counts: a stale ended id the backend still lists
        // must not block the switch.
        return LiveChannelLives(lives: try await backend(live.id).lives.filter { $0.id == live.id }, partial: true)
    }

    private func read(_ channelId: String) async throws -> [LiveChannelLives.Live] {
        guard channelId.wholeMatch(of: /UC[A-Za-z0-9_-]{22}/) != nil,
              let url = URL(string: "https://www.youtube.com/channel/\(channelId)/live") else { throw Unreadable() }
        let request = HTTPRequest(method: "GET", url: url,
                                  headers: ["User-Agent": Self.userAgent, "Accept-Language": "en", "Cookie": Self.consentCookie],
                                  body: nil)
        let response = try await withThrowingTaskGroup(of: HTTPResponse.self) { [transport, sleep] group in
            group.addTask { try await transport.send(request, maxBodyBytes: Self.maxBodyBytes) }
            group.addTask { try await sleep(Self.timeout); throw URLError(.timedOut) }
            defer { group.cancelAll() }
            return try await group.next()!
        }
        guard (200..<300).contains(response.status),
              let lives = Self.parse(String(decoding: response.body, as: UTF8.self), channelId: channelId)
        else { throw Unreadable() }
        return lives
    }

    /// `[live]` for a watch canonical whose player response `videoDetails` names that video, this channel and
    /// `"isLive":true` (so a scheduled stream's waiting room is not live), `[]` for this channel's own canonical (not live),
    /// nil for anything else (a consent or bot-check page, another channel, garbage): no answer. Android's rule
    /// (`LiveChannelResolver.parse`).
    static func parse(_ html: String, channelId: String) -> [LiveChannelLives.Live]? {
        guard let canonical = value(in: html, after: #"<link rel="canonical" href=""#) else { return nil }
        if canonical == "https://www.youtube.com/channel/\(channelId)" { return [] }
        let watch = "https://www.youtube.com/watch?v="
        guard canonical.hasPrefix(watch), case let id = String(canonical.dropFirst(watch.count)), TitlesClient.isVideoId(id),
              let details = videoDetails(in: html),
              [#""videoId":"\#(id)""#, #""channelId":"\#(channelId)""#, #""isLive":true"#].allSatisfy({ details.contains($0) })
        else { return nil }
        let title = value(in: html, after: #"<meta property="og:title" content=""#)
            ?? value(in: html, after: #"<meta name="title" content=""#) ?? ""
        return [.init(id: id, title: decoded(title))]
    }

    /// `"videoDetails":{` up to its `"shortDescription"` (always after `channelId`), which must end within
    /// `detailsMaxChars` of the start; else nil.
    private static func videoDetails(in html: String) -> Substring? {
        guard let start = html.range(of: #""videoDetails":{"#)?.lowerBound else { return nil }
        let limit = html.utf16.index(start, offsetBy: detailsMaxChars, limitedBy: html.endIndex) ?? html.endIndex
        guard let end = html[start..<limit].range(of: #""shortDescription""#)?.lowerBound else { return nil }
        return html[start..<end]
    }

    private static func value(in html: String, after marker: String) -> String? {
        guard let start = html.range(of: marker)?.upperBound, let end = html[start...].firstIndex(of: "\"") else { return nil }
        return String(html[start..<end])
    }

    /// The entities an attribute can hold: named, `&#NNN;` and `&#xHH;`. `&amp;` last, so `&amp;#39;` stays `&#39;`.
    private static func decoded(_ text: String) -> String {
        let named = [("&quot;", "\""), ("&lt;", "<"), ("&gt;", ">")].reduce(text) { $0.replacingOccurrences(of: $1.0, with: $1.1) }
        let numeric = named.replacing(/&#(?:x([0-9A-Fa-f]+)|([0-9]+));/) { match in
            let code = match.1.flatMap { UInt32($0, radix: 16) } ?? match.2.flatMap { UInt32($0) }
            return code.flatMap(Unicode.Scalar.init).map { String(Character($0)) } ?? String(match.0)
        }
        return numeric.replacingOccurrences(of: "&amp;", with: "&")
    }
}
