import Foundation
import InnerTubeKit
import Synchronization
import Testing
@testable import FitrahTube

/// Task 16: the glue between `AtomFeedFetcher` (Task 14), `WeekBucket`/`MeFeedRefreshGate`
/// (Task 15) and the Me screen.
///
/// Every case drives a **real** `AtomFeedFetcher` over `ScriptedTransport` + `MemoryKV`, never a
/// fetcher protocol: `AtomFeedFetcher` is a concrete `public actor` and scripting the transport
/// gives the same control for zero production surface. `now` and the `Calendar` are injected with
/// an explicit `TimeZone` and `firstWeekday` (the Task 12 lesson — `Calendar.current` on an
/// Arabic/Gulf device is the Islamic region calendar).
///
/// Concurrency note: the fan-out is concurrent, so one `ScriptedTransport` queue cannot be mapped
/// to particular channels. Every case that needs per-channel identity therefore refreshes ONE
/// channel at a time (which also exercises `.skipFresh` on the channels already fetched); the
/// multi-channel cases either assert over the whole set or use identical responses.
@Suite(.perTest)
@MainActor
struct MeFeedRepositoryTests {

    // MARK: - Fixture

    /// Friday 2027-01-15 08:00 UTC. Monday-first weeks: week 0 opens Mon 2027-01-11, week 1 opens
    /// Mon 2027-01-04, week 2 opens Mon 2026-12-28.
    private static let now = ISO8601DateFormatter().date(from: "2027-01-15T08:00:00Z")!

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        calendar.firstWeekday = 2
        return calendar
    }

    private static let week0 = "2027-01-14T10:00:00Z"
    private static let week0Older = "2027-01-12T10:00:00Z"
    private static let week1 = "2027-01-06T10:00:00Z"
    private static let week2 = "2026-12-30T10:00:00Z"

    private static let alpha = "UCchannelAlpha"
    private static let beta = "UCchannelBeta"

    /// One `<entry>`; `published == nil` omits the element entirely, which is what a feed with a
    /// malformed or missing `<published>` degrades to (`VideoItem.publishedAt == nil`).
    private func entry(_ id: String, _ published: String?) -> String {
        let publishedTag = published.map { "<published>\($0)</published>" } ?? ""
        return "<entry><yt:videoId>\(id)</yt:videoId><title>Video \(id)</title>\(publishedTag)"
            + "<media:thumbnail url=\"https://i.ytimg.com/vi/\(id)/hqdefault.jpg\"/></entry>"
    }

    private func feed(_ entries: String...) -> HTTPResponse {
        let xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<feed xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\" xmlns=\"http://www.w3.org/2005/Atom\">"
            + entries.joined() + "</feed>"
        return HTTPResponse(status: 200, headers: [:], body: Data(xml.utf8))
    }

    /// `sleep` defaults to a NO-OP, not to the production clock: every case but the stagger one
    /// would otherwise really wait out `250 ms × k` per fan-out, and this gate has no wall-clock
    /// sleeps in it.
    private func makeRepo(_ transport: ScriptedTransport,
                          feedCache: MemoryKV = MemoryKV(),
                          refreshState: MemoryKV = MemoryKV(),
                          deadline: MeFeedRepository.Deadline? = nil,
                          sleep: @escaping MeFeedRepository.Sleep = { _ in }) -> MeFeedRepository {
        MeFeedRepository(atom: AtomFeedFetcher(transport: transport, keyValueStore: feedCache),
                         refreshState: refreshState, now: { Self.now }, calendar: Self.calendar,
                         deadline: deadline ?? MeFeedRepository.withDeadline, sleep: sleep)
    }

    private func allItems(_ repo: MeFeedRepository) -> [String] {
        repo.weeks.flatMap { $0.items.map(\.id) }
    }

    // MARK: - Cache-first

    @Test func aChannelInsideTheTTLIsServedFromTheCacheWithZeroSends() async {
        let transport = ScriptedTransport([feed(entry("vid-0", Self.week0))])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)
        #expect(transport.sent.count == 1)
        #expect(allItems(repo) == ["vid-0"])

        // Second refresh inside `MeFeedRefreshGate.ttl`: the gate says `.skipFresh`, so the feed is
        // rebuilt from `cached(_:)` and NOTHING reaches the transport. The queue is empty by now,
        // so a send would have thrown `exhausted` and shown up as a `.transport` outcome.
        await repo.refresh(channelIds: [Self.alpha], force: false)
        #expect(transport.sent.count == 1)
        #expect(allItems(repo) == ["vid-0"])
        #expect(repo.state(for: Self.alpha)?.consecutiveErrorCount == 0)
    }

    @Test func forcingARefreshBypassesTheTTL() async {
        let transport = ScriptedTransport([
            feed(entry("vid-0", Self.week0)),
            feed(entry("vid-0", Self.week0), entry("vid-1", Self.week0Older)),
        ])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)
        await repo.refresh(channelIds: [Self.alpha], force: true)

        #expect(transport.sent.count == 2)
        #expect(allItems(repo) == ["vid-0", "vid-1"])
    }

    // MARK: - Bucketing

    @Test func anItemWithNoUploadInstantIsDroppedRatherThanBucketedIntoThisWeek() async {
        let transport = ScriptedTransport([feed(entry("vid-dated", Self.week0), entry("vid-undated", nil))])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)

        // The whole point: a nil `publishedAt` has no week, and week 0 is NOT the default.
        #expect(repo.weeks.count == 1)
        #expect(repo.weeks.first?.index == 0)
        #expect(allItems(repo) == ["vid-dated"])
    }

    @Test func weeksAscendAndItemsInsideAWeekAreNewestFirst() async {
        let transport = ScriptedTransport([
            feed(entry("vid-w0-older", Self.week0Older), entry("vid-w0", Self.week0),
                 entry("vid-w1", Self.week1), entry("vid-w2", Self.week2))
        ])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)
        repo.loadMoreWeeks()
        repo.loadMoreWeeks()

        // Compared whole rather than per index: a subscript on an empty `weeks` traps, and a test
        // that crashes the process hides every other failure in the run.
        #expect(repo.weeks.map(\.index) == [0, 1, 2])
        #expect(repo.weeks.map { $0.items.map(\.id) }
                == [["vid-w0", "vid-w0-older"], ["vid-w1"], ["vid-w2"]])
    }

    // MARK: - F4: no deep paging

    @Test func reachedEndIsTrueOnceTheDeepestNonEmptyWeekHasBeenEmitted() async {
        let transport = ScriptedTransport([
            feed(entry("vid-w0", Self.week0), entry("vid-w1", Self.week1), entry("vid-w2", Self.week2))
        ])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)
        #expect(repo.weeks.map(\.index) == [0])
        #expect(repo.reachedEnd == false)

        repo.loadMoreWeeks()
        #expect(repo.weeks.map(\.index) == [0, 1])
        #expect(repo.reachedEnd == false)

        repo.loadMoreWeeks()
        #expect(repo.weeks.map(\.index) == [0, 1, 2])
        // F4: the cache ran out, so there is nothing deeper to page to.
        #expect(repo.reachedEnd)

        repo.loadMoreWeeks()
        #expect(repo.weeks.map(\.index) == [0, 1, 2])
        #expect(transport.sent.count == 1)
    }

    // MARK: - The chip filter

    @Test func aChipFilterRebucketsWithoutRefetching() async {
        let transport = ScriptedTransport([
            feed(entry("alpha-w0", Self.week0)),
            feed(entry("beta-w0", Self.week0Older)),
        ])
        let repo = makeRepo(transport)

        // One channel at a time so each response lands on a known channel; the second call finds
        // alpha fresh and fetches beta alone.
        await repo.refresh(channelIds: [Self.alpha], force: false)
        await repo.refresh(channelIds: [Self.alpha, Self.beta], force: false)
        #expect(transport.sent.count == 2)
        #expect(allItems(repo).sorted() == ["alpha-w0", "beta-w0"])

        await repo.rebucket(filter: Self.alpha)
        #expect(allItems(repo) == ["alpha-w0"])
        #expect(transport.sent.count == 2)

        await repo.rebucket(filter: nil)
        #expect(allItems(repo).sorted() == ["alpha-w0", "beta-w0"])
        #expect(transport.sent.count == 2)
    }

    /// Fix round 1 / I1: `rebucket` accumulates rows across `await atom.cached(_:)` hops, so a chip
    /// tap landing inside that window used to be overwritten by the OLDER in-flight rebucket —
    /// the previous filter's rows republished under the new chip, which is the exact render
    /// `setFilter`'s synchronous clear exists to prevent.
    ///
    /// Deterministic without a clock: `Task {}` inherits this main actor and the main actor's job
    /// queue is FIFO, so the single `Task.yield()` below runs the late rebucket up to its first
    /// `await` and no further — the tap that follows is synchronous, and the rebucket's
    /// continuation can only be appended behind it.
    @Test func aLateRebucketNeverPublishesThePreviousFiltersRows() async {
        let transport = ScriptedTransport([
            feed(entry("alpha-w0", Self.week0)),
            feed(entry("beta-w0", Self.week0Older)),
        ])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)
        await repo.refresh(channelIds: [Self.alpha, Self.beta], force: false)
        #expect(allItems(repo).sorted() == ["alpha-w0", "beta-w0"])

        // An UNFILTERED rebucket, suspended inside its `cached(_:)` loop (its own `setFilter(nil)`
        // is a no-op — the filter is already nil — so nothing about the tap below is undone).
        let late = Task { await repo.rebucket(filter: nil) }
        await Task.yield()

        // ...and then the user taps a chip.
        repo.setFilter(Self.beta)
        await late.value

        #expect(repo.weeks.isEmpty,
                "a superseded rebucket must not republish the previous filter's rows")
    }

    @Test func setFilterClearsTheLoadedWeeksSynchronously() async {
        let transport = ScriptedTransport([
            feed(entry("vid-w0", Self.week0), entry("vid-w1", Self.week1))
        ])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)
        repo.loadMoreWeeks()
        #expect(repo.weeks.count == 2)
        #expect(repo.reachedEnd)

        // No await: the clear happens before the filter is flipped, so the render between the tap
        // and the rebucket reads as loading rather than "no results" (`MeViewModel.kt:377-395`).
        repo.setFilter(Self.beta)
        #expect(repo.weeks.isEmpty)
        #expect(repo.reachedEnd == false)
    }

    // MARK: - Subscription changes

    @Test func aSubscriptionCountChangeResetsTheLoadedWeeks() async {
        let transport = ScriptedTransport([
            feed(entry("alpha-w0", Self.week0), entry("alpha-w1", Self.week1), entry("alpha-w2", Self.week2)),
            feed(entry("beta-w0", Self.week0Older)),
        ])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)
        repo.loadMoreWeeks()
        repo.loadMoreWeeks()
        #expect(repo.weeks.count == 3)

        // A NEW subscription: the count moved, so the deep-loaded weeks reset to the first one.
        await repo.refresh(channelIds: [Self.alpha, Self.beta], force: false)
        #expect(repo.weeks.map(\.index) == [0])

        repo.loadMoreWeeks()
        repo.loadMoreWeeks()
        #expect(repo.weeks.count == 3)

        // Same count -> no reset. (Deliberately the UNFILTERED count: a filter is not a
        // subscription change and must not throw away the loaded weeks.)
        await repo.refresh(channelIds: [Self.alpha, Self.beta], force: false)
        #expect(repo.weeks.count == 3)
    }

    // MARK: - Fan-out

    @Test func atMostMaxConcurrentChannelsAreInFlight() async {
        let ids = (0..<8).map { "UCchannel\($0)" }
        let transport = ScriptedTransport(ids.map { _ in feed(entry("vid-0", Self.week0)) })
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: ids, force: false)

        #expect(transport.sent.count == 8)
        #expect(transport.peakConcurrency <= MeFeedRefreshGate.maxConcurrent)
        #expect(transport.peakConcurrency >= 1)
    }

    // MARK: - Failure classification

    @Test func timeoutsCancellationsAndOfflineNeverEscalateButOtherTransportFailuresDo() async {
        let transport = ScriptedTransport([
            .failing(URLError(.timedOut)),
            .failing(URLError(.cancelled)),
            .failing(URLError(.notConnectedToInternet)),
            .failing(URLError(.badServerResponse)),
        ])
        let repo = makeRepo(transport)

        // One channel per refresh so each scripted failure lands on a known channel.
        for (index, code) in ["timedOut", "cancelled", "offline", "badServerResponse"].enumerated() {
            await repo.refresh(channelIds: ["UCfail\(index)-\(code)"], force: false)
        }

        // Task 15's `.transport` walks the 5xx ladder, so a mapping that called ambient network
        // jitter `.transport` would route around "a timeout never escalates".
        for index in 0..<3 {
            let state = repo.state(for: "UCfail\(index)-\(["timedOut", "cancelled", "offline"][index])")
            #expect(state?.consecutiveErrorCount == 0)
            #expect(state?.backoffUntil == nil)
            #expect(state?.lastAttemptAt == Self.now)
        }
        let transportFailure = repo.state(for: "UCfail3-badServerResponse")
        #expect(transportFailure?.consecutiveErrorCount == 1)
        #expect(transportFailure?.backoffUntil == Self.now + MeFeedRefreshGate.serverErrorBackoffs[0])
    }

    @Test func anHTTPErrorIsRecordedWithItsStatus() async {
        let transport = ScriptedTransport([HTTPResponse(status: 429, headers: [:], body: Data())])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)

        let state = repo.state(for: Self.alpha)
        #expect(state?.consecutiveErrorCount == 1)
        #expect(state?.backoffUntil == Self.now + MeFeedRefreshGate.rateLimitedBackoffs[0])
    }

    @Test func aChannelThatMissesThePerChannelDeadlineIsRecordedAsATimeout() async {
        let ids = [Self.alpha, Self.beta, "UCchannelGamma"]
        let transport = ScriptedTransport(ids.map { _ in feed(entry("vid-0", Self.week0)) })
        // Exactly ONE channel misses its deadline; the other two run to completion. Injected
        // rather than raced against a zero sleep: the outcome of a scheduler race is not something
        // a gate can depend on. WHICH channel loses is the scheduler's business — that a deadline
        // does not stop the fan-out (dispatcher addendum (f)) is not, and a seam that fails every
        // channel could never have shown it.
        let attempts = Mutex(0)
        let repo = makeRepo(transport, deadline: { work in
            let isFirst = attempts.withLock { (count: inout Int) -> Bool in
                count += 1
                return count == 1
            }
            if isFirst { return nil }
            return await work()
        })

        await repo.refresh(channelIds: ids, force: false)

        // The two that ran sent and rendered; the one that timed out escalated nothing.
        #expect(transport.sent.count == 2)
        let timedOut = ids.filter { repo.state(for: $0)?.lastSuccessfulFetchAt == nil }
        #expect(timedOut.count == 1)
        for id in ids {
            let state = repo.state(for: id)
            #expect(state?.lastAttemptAt == Self.now)
            #expect(state?.consecutiveErrorCount == 0)
            #expect(state?.backoffUntil == nil)
        }
        for id in timedOut {
            #expect(repo.state(for: id)?.lastErrorMessage
                    == "timeout after \(MeFeedRefreshGate.perChannelTimeout)")
        }
        #expect(repo.weeks.map(\.index) == [0])
        #expect(repo.lastError == nil, "a partial success is not a failed refresh")
    }

    /// Fix round 1 / M5: the 250 ms start stagger, through the same kind of injected seam the
    /// deadline uses. Four channels launch at 0 / 250 / 500 / 750 ms so YouTube sees a paced
    /// request stream instead of `maxConcurrent` simultaneous requests (`MeFeedRepository.kt:770`).
    @Test func theInitialBurstIsStaggeredByTwoHundredAndFiftyMilliseconds() async {
        let ids = (0..<4).map { "UCchannel\($0)" }
        let transport = ScriptedTransport(ids.map { _ in feed(entry("vid-0", Self.week0)) })
        let delays = Mutex<[Duration]>([])
        let repo = makeRepo(transport, sleep: { duration in delays.withLock { $0.append(duration) } })

        await repo.refresh(channelIds: ids, force: false)

        // Sorted: four concurrent children record these, so the ORDER they land in is the
        // scheduler's business — the spacing is what is being pinned.
        #expect(delays.withLock { $0.sorted() }
                == [.zero, .milliseconds(250), .milliseconds(500), .milliseconds(750)])
        #expect(transport.sent.count == 4)
    }

    // MARK: - Persistence

    @Test func anUndecodableRefreshStateBlobReadsAsNoStateAndTheChannelIsFetched() async {
        let refreshState = MemoryKV()
        refreshState.set(MeFeedRepository.stateKey(Self.alpha), Data("{not a state}".utf8))
        let transport = ScriptedTransport([feed(entry("vid-0", Self.week0))])
        let repo = makeRepo(transport, refreshState: refreshState)

        #expect(repo.state(for: Self.alpha) == nil)

        // A blob that cannot be decoded must not freeze the channel: it reads as "never fetched".
        await repo.refresh(channelIds: [Self.alpha], force: false)
        #expect(transport.sent.count == 1)
        #expect(repo.state(for: Self.alpha)?.lastSuccessfulFetchAt == Self.now)
    }

    // MARK: - The user-visible error

    @Test func aRefreshWhereNothingSucceededShowsTheRefreshErrorAndASuccessClearsIt() async {
        let transport = ScriptedTransport([
            HTTPResponse(status: 500, headers: [:], body: Data()),
            feed(entry("vid-0", Self.week0)),
        ])
        let repo = makeRepo(transport)

        await repo.refresh(channelIds: [Self.alpha], force: false)
        #expect(repo.lastError == String(localized: "me_refresh_error"))

        await repo.refresh(channelIds: [Self.alpha], force: true)
        #expect(repo.lastError == nil)
        #expect(allItems(repo) == ["vid-0"])
    }
}
