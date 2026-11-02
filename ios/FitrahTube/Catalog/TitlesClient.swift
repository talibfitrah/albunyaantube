import FitrahAPI
import Foundation
import InnerTubeKit

/// Owner issue 1(b): `GET /api/v1/titles` -- the original titles our backend holds for the video ids a channel or
/// playlist page lists. Browse runs at `hl=en` (`BrowseClient.send`), so YouTube machine-translates Arabic and Dutch
/// titles; this swaps the originals back in, once per page. Hand-written over `HTTPTransport` like `IndexClient` and
/// `PublicHeaders` (no regeneration), with the same `X-Device-Id` and no Bearer: a public read, off the auth stack.
/// Every failure is silent: the rows keep their list titles. A page whose every chunk fails or misses the cap quiets
/// the client for `quiet`, so a down server costs one slow page a minute, never a retry storm.
actor TitlesClient {
    static let batchSize = 50 // PublicContentController.MAX_TITLE_IDS
    static let quiet: Duration = .seconds(60)
    /// Not private: `AppContainerTests` pins that a fixture container's is canned.
    nonisolated let transport: HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId
    private let timeout: Duration
    /// Monotonic (fix round 1, B7): a wall-clock change cannot stretch or skip the quiet minute.
    private let now: @Sendable () -> ContinuousClock.Instant
    private var quietUntil: ContinuousClock.Instant?

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL, deviceId: DeviceId,
         timeout: Duration = .milliseconds(1500), now: @escaping @Sendable () -> ContinuousClock.Instant = { .now }) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
        self.timeout = timeout // ponytail: a 1.5 s cap (brief §2); raise it if field RTT to the API is higher
        self.now = now
    }

    private nonisolated enum Answer: Sendable {
        case titles([String: String])
        case failed
        case timedOut
    }

    /// `items` with each title the server knows swapped for the original; every other item unchanged.
    func retitle(_ items: [VideoItem]) async -> [VideoItem] {
        var seen = Set<String>()
        let ids = items.map(\.id).filter { Self.isVideoId($0) && seen.insert($0).inserted }
        guard !ids.isEmpty, quietUntil.map({ now() >= $0 }) ?? true else { return items }
        let chunks = stride(from: 0, to: ids.count, by: Self.batchSize).map { Array(ids[$0..<min($0 + Self.batchSize, ids.count)]) }
        // Fix round 1, B6: every chunk is its own child under the one cap, and whatever answered in time is kept
        // (Android `OriginalTitles` parity); a failed chunk costs only its own ids.
        let titles = await withTaskGroup(of: Answer.self) { group -> [String: String]? in
            for chunk in chunks { group.addTask { await self.fetch(chunk) } }
            group.addTask { [timeout] in try? await Task.sleep(for: timeout); return .timedOut }
            var merged: [String: String]? // nil until a chunk answers
            var left = chunks.count
            while left > 0, let answer = await group.next() {
                if case .timedOut = answer { break }
                left -= 1
                if case .titles(let map) = answer { merged = (merged ?? [:]).merging(map) { first, _ in first } }
            }
            group.cancelAll() // the cap's sleep, or the requests still out when it fired
            return merged
        }
        guard let titles else {
            // Every chunk failed, or none answered within the cap. A cancelled caller (the user backed out
            // mid-ask, fix round 1 B5) is not the server failing, so it leaves the client willing to ask.
            if !Task.isCancelled { quietUntil = now() + Self.quiet }
            return items
        }
        return items.map { item in
            guard let title = titles[item.id] else { return item }
            var item = item
            item.title = title
            return item
        }
    }

    /// `.failed` on any failure: a thrown error, a non-200 (400, 503 while the index builds), a body that is not a map.
    private func fetch(_ ids: [String]) async -> Answer {
        var url = baseURL.appending(path: "api/v1/titles")
        url.append(queryItems: [URLQueryItem(name: "ids", value: ids.joined(separator: ","))])
        guard let response = try? await transport.send(HTTPRequest(method: "GET", url: url, headers: ["X-Device-Id": deviceId.value], body: nil)),
              response.status == 200,
              let map = try? JSONDecoder().decode([String: String].self, from: response.body)
        else { return .failed }
        return .titles(map)
    }

    nonisolated static func isVideoId(_ id: String) -> Bool {
        id.count == 11 && id.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }
}
