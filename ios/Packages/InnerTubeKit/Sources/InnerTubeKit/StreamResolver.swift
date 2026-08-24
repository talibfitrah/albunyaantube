import Foundation

/// Backend availability check run as the first step of a new resolve
/// (`extraction.md` §5.2). Real impl (Plan B) issues a HEAD against FitrahAPI
/// and maps 2xx/404 → available, 410 → unavailable; the resolver treats a throw
/// (HTTP/transport error) as fail-open. A test stub returns `true`.
public protocol AvailabilityGate: Sendable {
    func verify(videoId: String, sourceChannelId: String?) async throws -> Bool
}

/// Turns a videoId into a playable `Resolved` by walking `RemoteConfig.resolverOrder`
/// (`ios-app-plan.md` §6.2, spec §9). Cache-first, one in-flight task per videoId
/// (same-id callers join it), ≥500 ms spacing between `player` POSTs.
public actor StreamResolver {
    private static let rungBudget: Duration = .seconds(8)

    private let transport: HTTPTransport
    private let remoteConfigStore: RemoteConfigStore
    private let sessionStore: SessionStore
    private let cache: ManifestCache
    private let gate: AvailabilityGate
    private let requestBuilder: PlayerRequestBuilder
    private let responseParser: PlayerResponseParser
    private let monotonicClock: MonotonicClock
    private let wallClock: WallClock
    private let locale: InnerTubeLocale
    private let minPostSpacing: Duration

    /// Single-flight registry. `id` distinguishes a live entry from one a
    /// superseding `forceRefresh` replaced, so the loser's `defer` can't evict
    /// the winner (the generation-token discipline).
    private struct InFlight { let id: Int; let task: Task<Resolved, Error> }
    private var inFlight: [String: InFlight] = [:]
    private var nextJobId = 0

    /// Monotonic instant of the last `player` POST, for ≥500 ms spacing.
    private var lastPostInstant: Duration?

    public init(
        transport: HTTPTransport,
        remoteConfigStore: RemoteConfigStore,
        sessionStore: SessionStore,
        cache: ManifestCache,
        gate: AvailabilityGate,
        monotonicClock: MonotonicClock,
        wallClock: WallClock,
        locale: InnerTubeLocale,
        requestBuilder: PlayerRequestBuilder = PlayerRequestBuilder(),
        responseParser: PlayerResponseParser = PlayerResponseParser(),
        minPostSpacing: Duration = .milliseconds(500)
    ) {
        self.transport = transport
        self.remoteConfigStore = remoteConfigStore
        self.sessionStore = sessionStore
        self.cache = cache
        self.gate = gate
        self.requestBuilder = requestBuilder
        self.responseParser = responseParser
        self.monotonicClock = monotonicClock
        self.wallClock = wallClock
        self.locale = locale
        self.minPostSpacing = minPostSpacing
    }

    /// - Parameter purpose: the resolve lane (`.player` vs `.prefetch`, ruling 16). Reserved for
    ///   caller-side rate-limiter lane coordination (`ExtractionRateLimiter`); it does NOT alter
    ///   resolver behaviour today — kept in the signature to avoid a later break when it's wired.
    public func resolve(
        _ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool
    ) async throws -> Resolved {
        guard Self.isValidVideoId(videoId) else { throw ExtractionError.invalidVideoId }

        if !forceRefresh, let cached = await cache.get(videoId, now: wallClock.wallNow) {
            return cached
        }

        if forceRefresh {
            inFlight[videoId]?.task.cancel()
            inFlight[videoId] = nil
        } else if let existing = inFlight[videoId] {
            return try await awaitJob(existing.task, videoId: videoId)
        }

        nextJobId += 1
        let jobId = nextJobId
        let job = Task<Resolved, Error> { [self] in
            try await performResolve(videoId, sourceChannelId: sourceChannelId)
        }
        inFlight[videoId] = InFlight(id: jobId, task: job)
        defer {
            if inFlight[videoId]?.id == jobId { inFlight[videoId] = nil }
        }
        return try await awaitJob(job, videoId: videoId)
    }

    /// Awaits a resolve job. `extraction.md` §5.1: a `CancellationException` is rethrown only
    /// when the *awaiter's own* context is cancelled — a job cancelled by another caller's
    /// `forceRefresh` must not surface as a spurious cancellation to everyone waiting on it.
    ///
    /// Deviation from §5.1's "otherwise converted to null": `resolve` returns a non-optional
    /// `Resolved`, so a superseded awaiter adopts the superseding job's result (or the manifest
    /// it just cached) instead of a nil; only a lost race with no successor left is `.cancelled`.
    private func awaitJob(_ task: Task<Resolved, Error>, videoId: String) async throws -> Resolved {
        do {
            return try await task.value
        } catch is CancellationError {
            try Task.checkCancellation()
            if let winner = inFlight[videoId] { return try await awaitJob(winner.task, videoId: videoId) }
            if let cached = await cache.get(videoId, now: wallClock.wallNow) { return cached }
            throw ExtractionError.cancelled
        }
    }

    // MARK: - the ladder

    private enum RungResult: Sendable {
        case resolved(Resolved)
        case advance
        case jumpToOpenInYouTube
    }

    private func performResolve(_ videoId: String, sourceChannelId: String?) async throws -> Resolved {
        // Self-gate on the persisted, restart-surviving escalating cooldown (§6.3): if a prior
        // bot-check tripped it, suppress all API traffic until it elapses rather than hammering
        // the `player` endpoint on every launch. Terminal — no rung can clear it.
        let cooldownNow = wallClock.wallNow
        if let remaining = await sessionStore.cooldownRemaining(now: cooldownNow) {
            throw ExtractionError.cooldown(until: cooldownNow.addingTimeInterval(Self.seconds(remaining)))
        }

        // Availability gate first; a throw (HTTP/transport error) is fail-open.
        let available = (try? await gate.verify(videoId: videoId, sourceChannelId: sourceChannelId)) ?? true
        guard available else { throw ExtractionError.unavailable(videoId: videoId) }

        let config = await remoteConfigStore.current()
        var lastError: Error = ExtractionError.allRungsFailed

        for strategy in config.resolverOrder {
            let outcome: RungResult
            do {
                // The 8 s budget (§6.6 "8 s budget before demotion") covers the whole rung —
                // a bot-check rung is POST + rotate + retry POST, which per-POST would allow ~16 s.
                outcome = try await Self.withTimeout(Self.rungBudget) {
                    try await self.runRung(strategy, videoId: videoId, config: config)
                }
            } catch let error as ExtractionError where error.terminal {
                throw error
            } catch is CancellationError {
                // A superseded/cancelled job stops here; it must not walk on down the ladder.
                throw CancellationError()
            } catch {
                lastError = error
                continue
            }
            switch outcome {
            case .resolved(let resolved):
                return await succeed(resolved, videoId: videoId)
            case .advance:
                continue
            case .jumpToOpenInYouTube:
                return await succeed(makeOpenInYouTube(videoId), videoId: videoId)
            }
        }
        throw lastError
    }

    /// Only `.hls`/`.progressive` are a real fetch: they carry URLs with a TTL worth caching, and
    /// they are the only outcome that proves the session is healthy. Caching `embed`/`openInYouTube`
    /// would pin a user on the fallback for the full TTL after a transient bot check clears, and
    /// counting them as a clean fetch would fake a healthy session while the ladder bottomed out.
    private func succeed(_ resolved: Resolved, videoId: String) async -> Resolved {
        switch resolved.stream {
        case .hls, .progressive:
            await cache.put(resolved, videoId: videoId, now: wallClock.wallNow)
            await sessionStore.recordSuccess()
        case .embed, .openInYouTube:
            break
        }
        return resolved
    }

    private func runRung(_ strategy: String, videoId: String, config: RemoteConfig) async throws -> RungResult {
        switch strategy {
        case "visionosHLS":
            return try await runPlayerRung(family: .visionos, videoId: videoId, config: config, expectHLS: true, canRotate: true)
        case "androidItag18":
            return try await runPlayerRung(family: .android, videoId: videoId, config: config, expectHLS: false, canRotate: true)
        case "embed":
            return .resolved(makeEmbed(videoId))
        case "openInYouTube":
            return .resolved(makeOpenInYouTube(videoId))
        default:
            return .advance
        }
    }

    private func runPlayerRung(
        family: ClientFamily, videoId: String, config: RemoteConfig, expectHLS: Bool, canRotate: Bool
    ) async throws -> RungResult {
        guard let context = config.clients[Self.contextKey(family)] else { return .advance }
        let visitorData = await sessionStore.visitorData(for: family)
        let request = requestBuilder.build(
            videoId: videoId, family: family, context: context, visitorData: visitorData, locale: locale)
        let body = try await sendPlayerPost(request)

        let parsed = try responseParser.parse(body)

        switch parsed.playability {
        case .ok(let streaming):
            // §6.3: take `responseContext.visitorData` from a successful response and send it on
            // every later call under this family (both `context.client` and `X-Goog-Visitor-Id`).
            // Without this every POST goes out tokenless and the bot-check retry is a no-op.
            if let visitor = parsed.visitorData {
                await sessionStore.setVisitorData(visitor, for: family)
            }
            let now = wallClock.wallNow
            let userAgent = context.userAgent ?? ""
            if expectHLS, let hls = streaming.hlsManifestURL {
                return .resolved(Resolved(
                    stream: .hls(url: hls, isLive: streaming.isLive, audioOnlyURL: streaming.itag140URL, captionTracks: streaming.captionTracks),
                    client: family, userAgent: userAgent, resolvedAt: now, expiresAt: expiry(streaming, from: now)))
            }
            if !expectHLS, let itag18 = streaming.itag18URL {
                return .resolved(Resolved(
                    stream: .progressive(url: itag18, label: "360p"),
                    client: family, userAgent: userAgent, resolvedAt: now, expiresAt: expiry(streaming, from: now)))
            }
            return .advance
        case .unplayableKids:
            return .advance
        case .ageGate:
            return .jumpToOpenInYouTube
        case .botCheck:
            // Session bootstrap (Appendix A.1 `probe.py`: "a bare request establishes the session;
            // reuse responseContext.visitorData for everything after"). The very first call under a
            // family goes out tokenless and YouTube answers with a bot check that *carries* a
            // freshly-minted `responseContext.visitorData`; adopting it and retrying flips the same
            // rung to OK+HLS (verified live 2026-08-24 against `xc7keR2piUM`). This is session
            // establishment, not a rotation — rotating here would clear the token we were just
            // handed and burn the 10-minute rotation budget on the first play of every launch.
            if visitorData == nil, canRotate, let visitor = parsed.visitorData {
                await sessionStore.setVisitorData(visitor, for: family)
                return try await runPlayerRung(
                    family: family, videoId: videoId, config: config, expectHLS: expectHLS, canRotate: false)
            }
            if canRotate, await sessionStore.rotate(family) {
                return try await runPlayerRung(
                    family: family, videoId: videoId, config: config, expectHLS: expectHLS, canRotate: false)
            }
            await sessionStore.recordBotCheck()
            throw ExtractionError.botCheck
        case .liveOffline(let startsAt):
            throw ExtractionError.liveOffline(startsAt: startsAt)
        case .unavailable(let reason):
            throw Self.terminalError(reason: reason, videoId: videoId)
        }
    }

    // MARK: - network

    /// Enforces ≥`minPostSpacing` between `player` POSTs, then delegates to the injected
    /// transport. The 8 s budget wraps the whole rung, one level up.
    ///
    /// The slot is RESERVED synchronously (write `lastPostInstant` before the `await`): `Task.sleep`
    /// is a suspension point, so two concurrent distinct-id resolves would otherwise both read the
    /// same stale instant across the await and fire together, collapsing the throttle (I1). Reserving
    /// first makes each concurrent caller serialise ≥ spacing behind the previous reservation.
    private func sendPlayerPost(_ request: HTTPRequest) async throws -> Data {
        let now = monotonicClock.now
        let scheduled = lastPostInstant.map { max(now, $0 + minPostSpacing) } ?? now
        lastPostInstant = scheduled
        let wait = scheduled - now
        if wait > .zero { try await Task.sleep(for: wait) }

        return try await transport.send(request).body
    }

    private static func withTimeout<T: Sendable>(
        _ budget: Duration, _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(for: budget)
                throw ExtractionError.transport("rung budget exceeded")
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
    }

    // MARK: - result builders

    private func makeEmbed(_ videoId: String) -> Resolved {
        Resolved(stream: .embed(videoId: videoId), client: .web, userAgent: "", resolvedAt: wallClock.wallNow, expiresAt: nil)
    }

    private func makeOpenInYouTube(_ videoId: String) -> Resolved {
        // videoId is validated to 11 URL-safe chars, so this URL always parses.
        let url = URL(string: "https://www.youtube.com/watch?v=\(videoId)")!
        return Resolved(stream: .openInYouTube(url: url), client: .web, userAgent: "", resolvedAt: wallClock.wallNow, expiresAt: nil)
    }

    private func expiry(_ streaming: StreamingData, from now: Date) -> Date? {
        streaming.expiresInSeconds.map { now.addingTimeInterval(TimeInterval($0)) }
    }

    // MARK: - helpers

    private static func isValidVideoId(_ id: String) -> Bool {
        // ^[a-zA-Z0-9_-]{11} (NewPipeExtractorClient.kt:1045); YouTube IDs are exactly 11.
        let allowed = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")
        return id.count == 11 && id.allSatisfy(allowed.contains)
    }

    private static func seconds(_ duration: Duration) -> TimeInterval {
        let c = duration.components
        return Double(c.seconds) + Double(c.attoseconds) / 1e18
    }

    private static func contextKey(_ family: ClientFamily) -> String {
        switch family {
        case .visionos: return "visionos"
        case .android: return "android"
        case .web: return "web"
        }
    }

    /// Maps a non-branching `UNPLAYABLE`/error reason to its terminal error
    /// (ruling 14: age-restricted / geo-blocked / private / removed are distinct,
    /// non-retryable states); anything else is a generic `.unavailable`.
    static func terminalError(reason: String, videoId: String) -> ExtractionError {
        let reason = reason.lowercased()
        if reason.contains("private") { return .private }
        if reason.contains("removed") || reason.contains("deleted") || reason.contains("terminated") || reason.contains("no longer available") {
            return .removed
        }
        if reason.contains("country") || reason.contains("region") || reason.contains("not available in") { return .geoBlocked }
        if reason.contains("age") { return .ageRestricted }
        return .unavailable(videoId: videoId)
    }
}
