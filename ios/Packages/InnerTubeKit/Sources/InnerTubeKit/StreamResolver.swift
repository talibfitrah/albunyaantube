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

    /// Recovery step-downs this session (`demote`): videoId -> the client whose stream would not
    /// play. That video's walks start at the rung AFTER it and try it last.
    /// ponytail: process-lifetime and unbounded -- one small entry per video that failed in AVPlayer.
    /// Bound it if a session ever demotes thousands.
    private var demoted: [String: ClientFamily] = [:]

    // MARK: HD upgrade state (spec §4.2 revision 3)

    /// R20: the backend's sts lookup and sig/n solve; nil keeps HD off.
    private let challengeSolver: ChallengeSolving?
    /// R42: the first HD googlevideo request comes no earlier than the `/player` response + this.
    private let hdFirstFetchDelay: Duration
    /// R23: the 30-minute player-info cache; a 409 clears it.
    private var hdPlayer: (player: SolverPlayer, at: Duration)?
    /// R23 / R42 / R44: while this lies ahead, `upgrade` makes no request at all.
    private var hdPausedUntil: Duration?
    /// R5 / R7 / R41: videos with no HD this session.
    /// ponytail: process-lifetime and unbounded, like `demoted` -- one small entry per video.
    private var hdDenied: Set<String> = []

    static let tvStrategy = "tvSamsungAdaptive"
    private static let playerInfoTTL: Duration = .seconds(30 * 60)
    /// R23: the solver skip window's ceiling (backend failures only).
    private static let hdWindowSeconds = 120
    /// ponytail: a fixed 30 min, unmeasured (spec R44, ruling (c)); tune once the bot-check duration per IP is measured.
    private static let botCheckPauseSeconds = 30 * 60

    /// R20: `AppContainerTests` pins that the live container's resolver can ask for HD and a fixture's cannot.
    public nonisolated var hasChallengeSolver: Bool { challengeSolver != nil }

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
        minPostSpacing: Duration = .milliseconds(500),
        challengeSolver: ChallengeSolving? = nil,
        hdFirstFetchDelay: Duration = .seconds(4)
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
        self.challengeSolver = challengeSolver
        self.hdFirstFetchDelay = hdFirstFetchDelay
    }

    /// - Parameter purpose: the resolve lane (`.player` vs `.prefetch`, ruling 16), for caller-side
    ///   rate-limiter lane coordination (`ExtractionRateLimiter`). It alters resolver behaviour in
    ///   ONE place: a `.cast` muxed walk never records the global bot-check cooldown (`Purpose.cast`),
    ///   and neither does any `requiresHLS` walk (below).
    /// - Parameter requiresMuxed: save-purpose walk (owner ruling 2026-09-01) — the caller needs a
    ///   single-file muxed stream (itag 18), so the HLS rung advances instead of returning, and the
    ///   walk bypasses both the manifest cache and the single-flight registry (see `resolve` body).
    /// - Parameter requiresHLS: the audio-only save's network walk (Cubic C2; fix r4 N-1, r5 N4-4: when
    ///   its plain walk answered without itag 140 -- after a step-down, or with VISIONOS failing at
    ///   walk time -- and for every walk the save must force), the muxed walk's mirror: the caller
    ///   needs VISIONOS's itag 140, which only the HLS answer carries, so the ANDROID rung advances
    ///   without a POST, wherever a step-down put it. Same bypass as `requiresMuxed`: the plain key
    ///   must not hand the player the HLS it stepped down from. At most one of the two. It never
    ///   records a bot-check trip (fix r5 N4-2, controller ruling): it runs when the plain answer lacks
    ///   itag 140 or the save must force a walk, and its trips fed the SHARED cooldown that then refused the
    ///   player's working ANDROID rung. The save fails instead, and the user retries it.
    public func resolve(
        _ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool,
        requiresMuxed: Bool = false, requiresHLS: Bool = false
    ) async throws -> Resolved {
        guard Self.isValidVideoId(videoId) else { throw ExtractionError.invalidVideoId }

        if requiresMuxed || requiresHLS {
            // Save-purpose walk, fully outside the shared structures:
            // - CACHE (keyed by videoId only, shape-blind): a cached `.hls` has no muxed format and a
            //   cached `.progressive` no itag 140; caching this walk's answer would degrade the
            //   player to 360p on its next resolve, or hand it back the HLS it stepped down from.
            //   Skipping both read and write means every such walk POSTs (every video save; an
            //   audio-only save whose plain answer lacks itag 140, or that must force a walk) —
            //   CF-D-9 exposure accepted; the per-walk trip dedupe caps it, and an HLS walk records
            //   no trip at all (N4-2).
            // - SINGLE-FLIGHT: joining a player walk would adopt the wrong shape, and a
            //   `forceRefresh` through the registry would cancel the user's own playback resolve
            //   (forbidden — a save walk must never cancel any in-flight job). Bypassing also
            //   means two concurrent muxed walks of one id both POST: `OfflineManager` is serial
            //   (one active save), but a save and a cast (`PlayerViewModel.castMedia`) can overlap
            //   -- two android POSTs, accepted, since neither result is cached.
            return try await performResolve(videoId, sourceChannelId: sourceChannelId, requiresMuxed: requiresMuxed,
                                            requiresHLS: requiresHLS,
                                            recordsBotCheck: purpose != .cast && !requiresHLS)
        }

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
            try await performResolve(videoId, sourceChannelId: sourceChannelId,
                                     requiresMuxed: false, requiresHLS: false)
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
            // `winner.task != task`: a job that dies with a cancellation while still registered
            // (a transport-level `URLError(.cancelled)` with no superseding forceRefresh, which
            // removes the loser from the registry BEFORE inserting the winner) must not re-await
            // itself forever.
            if let winner = inFlight[videoId], winner.task != task {
                return try await awaitJob(winner.task, videoId: videoId)
            }
            if let cached = await cache.get(videoId, now: wallClock.wallNow) { return cached }
            throw ExtractionError.cancelled
        }
    }

    /// The player's step-down (owner directive 2026-10-14): `failed`'s stream resolved but would not
    /// play -- V2Brp_esIVI's VISIONOS HLS segments answer AVPlayer 401 while ANDROID's itag 18
    /// plays. Every later walk for this video, this session, resumes the ladder at the NEXT rung
    /// (`walkOrder`), and the manifest cached from `failed` is dropped so a non-forced open cannot
    /// hand it back.
    public func demote(_ videoId: String, failed family: ClientFamily) async {
        demoted[videoId] = family
        await cache.remove(videoId)
    }

    // MARK: - the HD upgrade (spec §4.2 revision 3, R43)

    /// Internal marker: the TV answer offers no HD for this video (R5, R7). Never escapes `upgrade`.
    private struct HDNotOffered: Error {}
    /// Internal marker: the TV `/player` was bot-checked or refused over HTTP (R44). Never escapes `upgrade`.
    private struct HDBotCheck: Error {}

    /// One `tv_samsung` `/player` and one backend solve for the video whose 360p is on screen; returned no earlier than
    /// the `/player` response + `hdFirstFetchDelay` (R42). nil = stay on 360p. It never throws, because the caller is
    /// already playing, and it never touches the walk, the manifest cache or the global bot-check cooldown.
    /// Only our backend's unavailability opens R23's skip window and only a bot check the 30-minute pause (R44). A
    /// YouTube transport failure (a non-2xx, a `URLError`, the 8 s budget) and a cancellation leave no state, so the
    /// next play retries, as the walk would. What the answer itself rules out (a non-OK answer, no usable format, a
    /// defect in YouTube's data) is this video's alone (R5, R7, R41).
    public func upgrade(_ videoId: String) async -> Resolved? {
        guard let solver = challengeSolver, Self.isValidVideoId(videoId), !hdDenied.contains(videoId) else { return nil }
        if let until = hdPausedUntil, monotonicClock.now < until { return nil }
        // Stage 9 I-C9a: the app-wide bot-check cooldown suppresses all InnerTube traffic from this IP (§6.3). A cached
        // 360p can still play inside it, since the cache is read before `performResolve`'s own check.
        if await sessionStore.cooldownRemaining(now: wallClock.wallNow) != nil { return nil }
        let config = await remoteConfigStore.current()
        guard config.resolverOrder.contains(Self.tvStrategy), let context = config.clients["tvSamsung"] else { return nil }
        do {
            let (resolved, respondedAt) = try await Self.withTimeout(Self.rungBudget) {
                try await self.mintHD(videoId, solver: solver, context: context)
            }
            let wait = respondedAt + hdFirstFetchDelay - monotonicClock.now
            if wait > .zero { try await Task.sleep(for: wait) }
            return resolved
        } catch ChallengeSolverError.unknownPlayer {
            hdPlayer = nil                                                        // M14: no window
        } catch ChallengeSolverError.unavailable(let seconds) {
            pauseHD(seconds: min(max(seconds ?? Self.hdWindowSeconds, 0), Self.hdWindowSeconds))   // R22, R23
        } catch is HDBotCheck {
            pauseHD(seconds: Self.botCheckPauseSeconds)                           // R44
        } catch is CancellationError {
            // The player moved on; nothing about the source or the backend is known.
        } catch ExtractionError.transport {
            // YouTube non-2xx or the 8 s budget: retryable, as in the walk. (A solver timeout is `.unavailable`, above.)
        } catch is URLError {
            // `URLSessionTransport` maps nothing but `.cancelled`: a raw network failure to YouTube, retryable.
        } catch {
            hdDenied.insert(videoId)                                              // never the backend window
        }
        return nil
    }

    /// R41 (revision 4): the HD item failed before or after the swap, or never buffered. No HD for this video this
    /// session, and nothing else: the device-wide pause is for a bot check and a refused `sidx`.
    public func hdFailed(_ videoId: String) {
        hdDenied.insert(videoId)
    }

    /// R42 (revision 4): a `sidx` GET was still 403 after its retry -- a wrong solve or a refused IP. The same fixed
    /// 30-minute `tv_samsung` pause as a bot check (R44). Idempotent: the loader and the app may both report one refusal.
    public func hdRefused() {
        pauseHD(seconds: Self.botCheckPauseSeconds)
    }

    /// Never shortens a pause and never stacks one: `max(existing, now + seconds)`.
    private func pauseHD(seconds: Int) {
        let until = monotonicClock.now + .seconds(seconds)
        hdPausedUntil = max(hdPausedUntil ?? until, until)
    }

    private func mintHD(_ videoId: String, solver: ChallengeSolving, context: ClientContext) async throws -> (Resolved, Duration) {
        let started = monotonicClock.now
        let player: SolverPlayer
        if let cached = hdPlayer, started - cached.at < Self.playerInfoTTL {
            player = cached.player
        } else {
            player = try await Self.askSolver(.milliseconds(1500)) { try await solver.playerInfo() }
            hdPlayer = (player, monotonicClock.now)
        }
        let infoSpent = monotonicClock.now - started
        let (streaming, body, respondedAt) = try await postTV(videoId, context: context, sts: player.signatureTimestamp, canAdopt: true)
        guard let selection = TVAdaptive.select(try responseParser.adaptiveFormats(body)) else { throw HDNotOffered() }
        let challenges = TVAdaptive.challenges(selection)
        let solveBudget = max(min(.milliseconds(1500), .seconds(2) - infoSpent), .milliseconds(1))   // R22
        let answers = try await Self.askSolver(solveBudget) {
            try await solver.solve(playerId: player.id, n: challenges.n, s: challenges.s)
        }
        // R11: a challenge left unanswered is the backend's failure (the window). Past this check every `RewriteError` is
        // a defect in YouTube's data, which `upgrade` keeps to this video.
        guard challenges.n.allSatisfy({ answers.n[$0] != nil }), challenges.s.allSatisfy({ answers.s[$0] != nil }) else {
            throw ChallengeSolverError.unavailable(retryAfterSeconds: nil)
        }
        let model = try TVAdaptive.synthetic(selection, answers: answers)
        let now = wallClock.wallNow
        // VOD only: a live stream has no byte ranges, so `select` never picks it (R7). `isLive` and `isLiveNow` stay
        // false; a future live HD path must not wrap this one. No `title`: TVHTML5 answers carry no `videoDetails.title`,
        // so the app keeps the title it adopted from the 360p walk.
        let resolved = Resolved(
            stream: .hls(url: URL(string: "\(SyntheticHLSLoader.scheme)://\(UUID().uuidString)/master.m3u8")!, isLive: false,
                         audioOnlyURL: model.audio.first?.url, captionTracks: streaming.captionTracks, synthetic: model),
            client: .tvSamsung, userAgent: context.userAgent ?? "", resolvedAt: now, expiresAt: expiry(streaming, from: now))
        return (resolved, respondedAt)
    }

    /// A solver call under its R22 budget. Its timeout, and any error reaching our backend that the conformer did not
    /// type, is the backend's unavailability (R23); a cancellation stays one.
    private static func askSolver<T: Sendable>(
        _ budget: Duration, _ call: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        do {
            return try await withTimeout(budget, call)
        } catch let error as ChallengeSolverError {
            throw error
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw ChallengeSolverError.unavailable(retryAfterSeconds: nil)
        }
    }

    /// The TV `/player` (R4). R6/R44: a non-OK answer bringing a DIFFERENT visitorData is adopted and retried once (the
    /// tokenless bootstrap); a bot check bringing none, or an HTTP 403/429, is `HDBotCheck` -- no rotation, no cooldown,
    /// the stored visitorData kept. Any other non-OK answer is `HDNotOffered` (R5: TVHTML5 never decides a verdict).
    private func postTV(_ videoId: String, context: ClientContext, sts: Int, canAdopt: Bool) async throws
        -> (StreamingData, Data, Duration) {
        let sent = await sessionStore.visitorData(for: .tvSamsung)
        let request = requestBuilder.build(videoId: videoId, family: .tvSamsung, context: context, visitorData: sent,
                                           locale: locale, signatureTimestamp: sts)
        let body: Data
        do {
            body = try await sendPlayerPost(request)
        } catch is RateLimited {
            throw HDBotCheck()
        } catch ExtractionError.botCheck {
            throw HDBotCheck()
        }
        let respondedAt = monotonicClock.now
        let parsed = try responseParser.parse(body)
        if case .ok(let streaming) = parsed.playability {
            if let visitor = parsed.visitorData { await sessionStore.setVisitorData(visitor, for: .tvSamsung) }
            return (streaming, body, respondedAt)
        }
        if canAdopt, let visitor = parsed.visitorData, visitor != sent {
            await sessionStore.setVisitorData(visitor, for: .tvSamsung)
            return try await postTV(videoId, context: context, sts: sts, canAdopt: false)
        }
        if case .botCheck = parsed.playability { throw HDBotCheck() }
        throw HDNotOffered()
    }

    // MARK: - the ladder

    private enum RungResult: Sendable {
        case resolved(Resolved)
        case advance
    }

    private func performResolve(
        _ videoId: String, sourceChannelId: String?, requiresMuxed: Bool, requiresHLS: Bool,
        recordsBotCheck: Bool = true
    ) async throws -> Resolved {
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
        var sawBotCheck = false
        // True once any rung failed on OUR side (a bot check, transport error, rung timeout, decode
        // error: whatever reaches the generic catch). Gates the held verdict below.
        var sawOurFailure = false
        // A refusal by ANDROID held back in a demoted walk (F4); see the `AndroidRefusal` catch below.
        var heldVerdict: ExtractionError?
        let demotedFamily = demoted[videoId]

        for strategy in Self.walkOrder(config.resolverOrder, after: demotedFamily) {
            let outcome: RungResult
            do {
                // The 8 s budget (§6.6 "8 s budget before demotion") covers the whole rung —
                // a bot-check rung is POST + (adopt | rotate) + retry POST, which per-POST would allow ~16 s.
                outcome = try await Self.withTimeout(Self.rungBudget) {
                    try await self.runRung(strategy, videoId: videoId, config: config, requiresMuxed: requiresMuxed,
                                           requiresHLS: requiresHLS)
                }
            } catch let error as ExtractionError where error.terminal {
                // A source verdict (private, members-only, removed, age gate, ...): the source
                // ANSWERED, so the network path works. No trip, even if an earlier rung saw a bot
                // check (Task 1 review M4); only a walk that fails without a verdict records one.
                throw error
            } catch is AndroidRefusal {
                // The source's own verdict (spec R6), terminal at once -- EXCEPT in a demoted walk,
                // where it now opens the ladder: VISIONOS served this very video moments ago, so
                // "the source no longer serves it" is not yet proven (owner rule: a video plays
                // unless the source no longer serves it). Hold the verdict, try the wrapped rungs,
                // and throw it if none plays and none failed on our side (see the walk's end). Without
                // a demotion this is the old terminal throw.
                let verdict = ExtractionError.unavailable(videoId: videoId)
                guard demotedFamily != nil else { throw verdict }
                heldVerdict = verdict
                continue
            } catch is CancellationError {
                // A superseded/cancelled job stops here; it must not walk on down the ladder.
                // No trip either: the superseding walk sees the bot check itself if it's real.
                throw CancellationError()
            } catch {
                // Owner ruling 2026-09-01: successful walk ends clean — mirrors Android's
                // player-lane never-trip. A bot check only NOTES here; the (per-walk single,
                // review F2a) trip is recorded at walk end, and only when the walk fails overall.
                if case ExtractionError.botCheck = error { sawBotCheck = true }
                sawOurFailure = true
                lastError = error
                continue
            }
            switch outcome {
            case .resolved(let resolved):
                return await succeed(resolved, videoId: videoId, saveWalk: requiresMuxed || requiresHLS)
            case .advance:
                continue
            }
        }
        // A held source verdict ends the walk like any other: no trip, and not the retryable error --
        // unless a wrapped rung failed on OUR side. ANDROID's word is doubted in a demoted walk
        // (VISIONOS just served this video), so a bot check, transport error or rung timeout on the
        // rung that could have confirmed it proves nothing about the source: the walk ends as any
        // failed one does (the retryable `lastError`; the trip below only if a bot check was seen).
        // A wrapped rung that merely advanced (answered, but has nothing to play) leaves the verdict.
        if let heldVerdict, !sawOurFailure { throw heldVerdict }
        if sawBotCheck, recordsBotCheck { await sessionStore.recordBotCheck() }
        throw lastError
    }

    /// A resolved stream is a real fetch: it carries a URL with a TTL worth caching and proves the
    /// session is healthy.
    private func succeed(_ resolved: Resolved, videoId: String, saveWalk: Bool) async -> Resolved {
        // A save walk's result must never land under the plain videoId key: the cache is
        // shape-blind, and the player's next resolve would degrade to a muxed walk's 360p
        // progressive, or get back the HLS an HLS walk fetched past a step-down.
        // The fetch still counts as a healthy session (`recordSuccess`) — it is a real one.
        if !saveWalk {
            await cache.put(resolved, videoId: videoId, now: wallClock.wallNow)
        }
        await sessionStore.recordSuccess()
        return resolved
    }

    private func runRung(
        _ strategy: String, videoId: String, config: RemoteConfig, requiresMuxed: Bool, requiresHLS: Bool
    ) async throws -> RungResult {
        switch strategy {
        case "visionosHLS":
            // A muxed-required walk advances without POSTing: this rung's HLS answer carries no
            // muxed format (adaptive-only), so the POST would be a wasted player call.
            if requiresMuxed { return .advance }
            return try await runPlayerRung(family: .visionos, videoId: videoId, config: config, expectHLS: true, canRotate: true)
        case "androidItag18":
            // The mirror image: an HLS-required walk wants the itag 140 only VISIONOS carries.
            if requiresHLS { return .advance }
            return try await runPlayerRung(family: .android, videoId: videoId, config: config, expectHLS: false, canRotate: true)
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
        let body: Data
        do {
            body = try await sendPlayerPost(request)
        } catch is RateLimited {
            // CF-G-15: a 429 is an IP/QPS-level block, not a token problem -- rotating a healthy
            // visitorData here burns the 10-min rotation slot for nothing. Surface `.botCheck`
            // unrotated; the walk-end recording still notes it if the walk fails overall.
            throw ExtractionError.botCheck
        } catch ExtractionError.botCheck {
            // Cubic r3 #4: an HTTP-level 403 block. Same handling as a parsed LOGIN_REQUIRED
            // bot check below, minus the visitor bootstrap (a non-200 body carries no
            // `responseContext.visitorData` to adopt): rotate once and retry the rung, else
            // surface `.botCheck` so `performResolve` NOTES it -- the (per-walk single) cooldown
            // trip is recorded at walk end, and only when the walk fails overall (ruling 2026-09-01).
            if canRotate, await sessionStore.rotate(family) {
                return try await runPlayerRung(
                    family: family, videoId: videoId, config: config, expectHLS: expectHLS, canRotate: false)
            }
            throw ExtractionError.botCheck
        }

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
                    client: family, userAgent: userAgent, resolvedAt: now, expiresAt: expiry(streaming, from: now),
                    isLiveNow: streaming.isLiveNow, title: streaming.title))
            }
            if !expectHLS, let itag18 = streaming.itag18URL {
                return .resolved(Resolved(
                    stream: .progressive(url: itag18, label: "360p"),
                    client: family, userAgent: userAgent, resolvedAt: now, expiresAt: expiry(streaming, from: now),
                    title: streaming.title))
            }
            return .advance
        case .unplayable:
            // VISIONOS refuses made-for-kids AND source-gated videos with the same shape (live probe
            // 2026-10-14: a kids video and a geo-locked one are both `{status, reason}`, reason
            // localized), so its refusal decides nothing: walk on. ANDROID serves made-for-kids
            // (43/43 in the 2026-10-14 spike), so its UNPLAYABLE is the source's own verdict --
            // members-only (J-AuO9TNt5I), geo-locked -- and the app says "not available", never the
            // retryable error (owner directive 2026-10-14, spec R6).
            guard family == .android else { return .advance }
            throw AndroidRefusal()
        case .ageGate:
            // Terminal, not a hand-off (owner directive 2026-08-27). `ageRestricted` is already one
            // of ruling 14's terminal states and the app maps it onto its single "not playable"
            // surface.
            throw ExtractionError.ageRestricted
        case .botCheck:
            // Session (re)bootstrap. A bot-checked response carries a freshly-minted
            // `responseContext.visitorData` whether the request went out tokenless (a family's first
            // call; verified live 2026-08-24 on `xc7keR2piUM`) or under a STALE token (live probe
            // 2026-10-14: a bogus `visitorData` came back with a different, fresh one). Adopting it
            // discards the old session; ONE retry under it is the recovery (owner directive
            // 2026-10-14). Adoption is not slot-limited (only rotation is), so even the retry keeps
            // the fresh token for the next walk (CF-G-15). Rotation below is the fallback for a bot
            // check that hands over nothing new.
            if let visitor = parsed.visitorData, visitor != visitorData {
                await sessionStore.setVisitorData(visitor, for: family)
                if canRotate {
                    return try await runPlayerRung(
                        family: family, videoId: videoId, config: config, expectHLS: expectHLS, canRotate: false)
                }
            }
            if canRotate, await sessionStore.rotate(family) {
                return try await runPlayerRung(
                    family: family, videoId: videoId, config: config, expectHLS: expectHLS, canRotate: false)
            }
            // No `recordBotCheck()` here: `performResolve` records it, deduped to one trip per
            // ladder walk (review F2a) -- both rungs tripping is one incident, not two.
            throw ExtractionError.botCheck
        case .liveOffline(let startsAt):
            throw ExtractionError.liveOffline(startsAt: startsAt)
        case .unavailable(let reason):
            throw Self.terminalError(reason: reason, videoId: videoId)
        }
    }

    // MARK: - network

    /// Internal marker for an HTTP 429: routed to `.botCheck` WITHOUT the rotation a 403 gets
    /// (CF-G-15). Never escapes `runPlayerRung`.
    private struct RateLimited: Error {}

    /// Internal marker: ANDROID answered UNPLAYABLE. `performResolve` turns it into the terminal
    /// `.unavailable` verdict, or holds it in a demoted walk. Never escapes `performResolve`.
    private struct AndroidRefusal: Error {}

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

        do {
            let response = try await transport.send(request)
            // Cubic r3 #4: a raw 429/403 (non-JSON body) IS a bot block -- surfaced as a Wire-decode
            // failure it read as a generic rung failure, so the ladder walked on and rotation /
            // cooldown never engaged. CF-G-15 splits them: 403 -> `.botCheck` (rotate-once path),
            // 429 -> `RateLimited` (surfaced as `.botCheck` upstream, but never rotates -- the
            // token is healthy, the IP budget is not). Other non-200s are retryable transport
            // failures, never terminal.
            switch response.status {
            case 200..<300: return response.body
            case 403: throw ExtractionError.botCheck
            case 429: throw RateLimited()
            default: throw ExtractionError.transport("HTTP \(response.status)")
            }
        } catch let error as URLError where error.code == .cancelled {
            // Cubic #1: `URLSessionTransport` does no error mapping, so a request torn down by task
            // cancellation surfaces `URLError(.cancelled)`, not `CancellationError`. Normalized HERE,
            // the resolver's one transport call site, so the ladder loop and the single-flight
            // adoption path (`awaitJob`) see a single cancellation currency -- otherwise a cancelled
            // force-refresh job walks the ladder as if the rung failed and superseded awaiters get a
            // raw URLError instead of adopting the winner.
            throw CancellationError()
        }
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

    private func expiry(_ streaming: StreamingData, from now: Date) -> Date? {
        streaming.expiresInSeconds.map { now.addingTimeInterval(TimeInterval($0)) }
    }

    // MARK: - helpers

    private static func isValidVideoId(_ id: String) -> Bool {
        // ^[a-zA-Z0-9_-]{11} (NewPipeExtractorClient.kt:1045); YouTube IDs are exactly 11.
        let allowed = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")
        return id.count == 11 && id.allSatisfy(allowed.contains)
    }

    /// `ladder` rotated to start at the rung after the one `failed` serves, wrapping round: the failed
    /// rung is tried LAST, never dropped -- a live stream has no itag 18, so its only rung must stay
    /// reachable after a step-down. An unknown client, or none, leaves the ladder as it is. A muxed
    /// walk needs no exception: its VISIONOS rung advances without a POST wherever it sits.
    static func walkOrder(_ ladder: [String], after failed: ClientFamily?) -> [String] {
        guard let failed, let index = ladder.firstIndex(where: { family(of: $0) == failed }) else { return ladder }
        return Array(ladder[(index + 1)...] + ladder[...index])
    }

    /// The client each known strategy POSTs as (`runRung`).
    static func family(of strategy: String) -> ClientFamily? {
        switch strategy {
        case "visionosHLS": return .visionos
        case "androidItag18": return .android
        case "tvSamsungAdaptive": return .tvSamsung
        default: return nil
        }
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
        case .tvSamsung: return "tvSamsung"
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
