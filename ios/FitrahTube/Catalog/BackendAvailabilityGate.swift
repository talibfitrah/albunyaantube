import Foundation
import InnerTubeKit

/// FitrahAPI-backed `AvailabilityGate` (CF-B3, `extraction.md` §5.2): a HEAD probe against the
/// public video/channel endpoint, run once at the start of every resolve job before
/// `StreamResolver` spends a `player` POST on content the backend has already pulled.
///
/// `/api/v1/videos/{id}` and `/api/v1/channels/{id}` are GET-only in `api-specification.yaml`,
/// but Spring MVC auto-serves HEAD for any `@GetMapping` (same route, no body) -- so a real HEAD
/// reaches the backend without going through the OpenAPI-generated `Client`, which only emits the
/// methods declared in the spec. Reusing InnerTubeKit's own `HTTPTransport` (already public, 15 s
/// timeout) avoids inventing a second transport seam.
///
/// The browse lists use a GET instead (`exclusions`): since jar #15 the channel and playlist bodies carry the
/// admin's exclusions, which the lists built on the phone from YouTube must drop. Same 410 and fail-open rules.
/// `AppContainer.browseGate` injects the signed transport so a PERSONAL item's grantee gets its lists.
struct BackendAvailabilityGate: AvailabilityGate {
    private let transport: HTTPTransport
    private let baseURL: URL
    /// I6 (B1 final review): this HEAD runs BEFORE `StreamResolver`'s per-rung 8 s budget and is
    /// not covered by it, so a backend that accepts the connection and then stalls held the user
    /// on "Loading…" for the transport's own 15 s timeout before rung 1 even started. 3 s is well
    /// past a healthy HEAD and well inside spec §6.6's 8 s demotion budget. Injectable so the
    /// timeout can be tested without a 3 s test.
    private let timeout: Duration
    private let now: @Sendable () -> Date
    /// A reference: every copy of this gate shares one memo (`AppContainer.browseGate`).
    private let memo = ExclusionMemo()
    /// The signed-in account's uid, nil for none. The memo is keyed by it: a PERSONAL item's lists are its grantee's
    /// and never answer the next account to sign in, or a guest.
    private let owner: @Sendable () async -> String?

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL, timeout: Duration = .seconds(3),
         now: @escaping @Sendable () -> Date = { Date() }, owner: @escaping @Sendable () async -> String? = { nil }) {
        self.transport = transport
        self.baseURL = baseURL
        self.timeout = timeout
        self.now = now
        self.owner = owner
    }

    /// `visible`'s bound on one page's channel asks, and on how many run at once.
    // ponytail: items of a channel past the 8 most frequent show unfiltered; raise it if mixed playlists need more.
    static let maxChannelAsks = 8
    static let maxConcurrentAsks = 4

    /// §5.2 semantics: `sourceChannelId` present -> check the channel (channel-sourced videos
    /// aren't individually registered); else check the video. 2xx/404 -> available; 410 ->
    /// unavailable (admin-blocked/rejected/archived, a hard stop); a thrown transport/HTTP error
    /// fails open so offline users can still play a cached manifest.
    func verify(videoId: String, sourceChannelId: String?) async throws -> Bool {
        await probe(sourceChannelId.map { "api/v1/channels/\($0)" } ?? "api/v1/videos/\(videoId)")
    }

    // MARK: - Exclusions (jar #15)

    /// A channel's 410 gate and lists, for its screens and the Me feed. nil = 410, the catalog pulled it. Otherwise
    /// fail-open: a failed fetch answers the last good lists, else none. `fresh` = a list's first page, so the next
    /// open shows a new exclusion; a later page reuses an answer younger than `ExclusionMemo.maxAge`.
    func exclusions(channelId: String, fresh: Bool) async -> Exclusions? {
        await answer("api/v1/channels/\(channelId)", fresh: fresh)
    }

    /// The playlist's, same semantics. A 404 is a playlist the backend does not list (`registered == false`).
    func exclusions(playlistId: String, fresh: Bool) async -> Exclusions? {
        await answer("api/v1/playlists/\(playlistId)", fresh: fresh)
    }

    /// The signed transport signs as whoever is signed in when it sends, so an account change during the fetch makes
    /// the answer someone else's: it is a failure, never kept under this key.
    private func answer(_ path: String, fresh: Bool) async -> Exclusions? {
        let asker = await owner() ?? ""
        return await memo.answer("\(asker)|\(path)", fresh: fresh, now: now()) {
            let fetched = await fetch(path)
            return (await owner() ?? "") == asker ? fetched : .failed
        }
    }

    /// A playlist page minus its exclusions; nil = the playlist is gone. One the backend does not list -- a channel's
    /// own playlist, usually -- takes each item's channel's exclusions instead: their videos are hidden, a 410
    /// channel's items are hidden, and a channel that excluded this playlist makes it gone (a deep link to it). Those
    /// asks are bounded: the `maxChannelAsks` channels with the most items on the page, `maxConcurrentAsks` at a time.
    func visible(_ items: [VideoItem], in playlist: Exclusions, playlistId: String, fresh: Bool) async -> [VideoItem]? {
        var hidden = playlist.videoIds
        var goneChannels = Set<String>()
        var gone = false
        if !playlist.registered {
            let counts = Dictionary(items.compactMap(\.channelId).map { ($0, 1) }, uniquingKeysWith: +)
            let asked = counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
                .prefix(Self.maxChannelAsks).map(\.key)
            await withTaskGroup(of: (String, Exclusions?).self) { group in
                func absorb(_ answer: (String, Exclusions?)?) {
                    guard let (channelId, lists) = answer else { return }
                    guard let lists else { goneChannels.insert(channelId); return }
                    hidden.formUnion(lists.videoIds)
                    if lists.playlistIds.contains(playlistId) { gone = true }
                }
                for (n, channelId) in asked.enumerated() {
                    if n >= Self.maxConcurrentAsks { absorb(await group.next()) }
                    group.addTask { (channelId, await exclusions(channelId: channelId, fresh: fresh)) }
                }
                while let answer = await group.next() { absorb(answer) }
            }
        }
        if gone { return nil }
        return items.filter { !hidden.contains($0.id) && !goneChannels.contains($0.channelId ?? "") }
    }

    private func fetch(_ path: String) async -> ExclusionMemo.Fetched {
        let request = HTTPRequest(method: "GET", url: baseURL.appending(path: path), headers: [:], body: nil)
        guard let response = try? await send(request) else { return .failed }
        switch response.status {
        case 410: return .gone
        case 404: return .lists(Exclusions(registered: false))
        case 200..<300:
            guard let dto = try? JSONDecoder().decode(ListsDTO.self, from: response.body) else { return .failed }
            return .lists(Exclusions(videoIds: Set(dto.excludedVideoIds ?? []), playlistIds: Set(dto.excludedPlaylistIds ?? [])))
        default: return .failed
        }
    }

    /// The two lists jar #15 added; everything else in the detail DTO is ignored.
    private struct ListsDTO: Decodable {
        var excludedVideoIds: [String]?
        var excludedPlaylistIds: [String]?
    }

    private func probe(_ path: String) async -> Bool {
        let request = HTTPRequest(method: "HEAD", url: baseURL.appending(path: path), headers: [:], body: nil)
        guard let response = try? await send(request) else { return true }
        return response.status != 410
    }

    /// `StreamResolver.withTimeout` is `private` to InnerTubeKit, so this is the same race in the
    /// smallest form that reaches here: whichever finishes first wins, and an expiry throws -- which
    /// `probe`'s `try?` turns into the fail-open answer and `fetch`'s into `.failed`, same as any transport error.
    /// The 3 s also caps the browse lists' exclusions GET, which the list's YouTube fetch runs alongside.
    private func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let transport = self.transport
        let timeout = self.timeout
        return try await withThrowingTaskGroup(of: HTTPResponse.self) { group in
            group.addTask { try await transport.send(request) }
            group.addTask {
                try await Task.sleep(for: timeout)
                throw CancellationError()
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
    }
}

/// What an admin excluded from one channel or playlist (jar #15). `registered` is false on a 404: the backend
/// does not list this source (a channel's own playlist, usually).
nonisolated struct Exclusions: Sendable, Equatable {
    var videoIds: Set<String> = []
    var playlistIds: Set<String> = []
    var registered = true
}

/// `BackendAvailabilityGate`'s memo, one entry per route. An ask while that route is in flight joins it (the
/// channel screen asks for its header and its Videos tab at once). A failure is held only briefly and never
/// replaces a good answer, so it is never kept as "nothing excluded".
actor ExclusionMemo {
    nonisolated enum Fetched: Sendable { case gone, lists(Exclusions), failed }

    static let maxAge: TimeInterval = 300
    static let failureHold: TimeInterval = 30

    /// The last good answer per route; a nil answer is a 410.
    private var good: [String: (answer: Exclusions?, at: Date)] = [:]
    private var failedAt: [String: Date] = [:]
    private var inFlight: [String: Task<Fetched, Never>] = [:]

    func answer(_ path: String, fresh: Bool, now: Date,
                fetch: @escaping @Sendable () async -> Fetched) async -> Exclusions? {
        if let running = inFlight[path] { return settle(path, await running.value) }
        if !fresh {
            if let hit = good[path], now.timeIntervalSince(hit.at) < Self.maxAge { return hit.answer }
            if let failed = failedAt[path], now.timeIntervalSince(failed) < Self.failureHold { return settle(path, .failed) }
        }
        let task = Task { await fetch() }
        inFlight[path] = task
        let fetched = await task.value
        inFlight[path] = nil
        switch fetched {
        case .failed: failedAt[path] = now
        case .gone: good[path] = (nil, now); failedAt[path] = nil
        case .lists(let lists): good[path] = (lists, now); failedAt[path] = nil
        }
        return settle(path, fetched)
    }

    /// A failure fails open: the last good lists, never a remembered 410. With none, nothing is known, so
    /// `registered: false` -- a channel's own playlist still takes its channels' lists (`visible`).
    private func settle(_ path: String, _ fetched: Fetched) -> Exclusions? {
        switch fetched {
        case .gone: nil
        case .lists(let lists): lists
        case .failed: good[path]?.answer ?? Exclusions(registered: false)
        }
    }
}
