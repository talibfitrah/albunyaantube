import Foundation
import InnerTubeKit
import Observation

/// One week's worth of the Me feed, already bucketed. Only NON-EMPTY weeks are ever built, so a
/// header never renders over nothing (`WeekBucket`'s "week 0 can be one day long" note).
nonisolated struct WeekSection: Sendable, Equatable, Identifiable {
    var index: Int
    var items: [VideoItem]

    var id: Int { index }
}

/// The Me tab's subscribed-channel feed: fan out over the subscribed channels under
/// `MeFeedRefreshGate`, let `AtomFeedFetcher` persist what came back, then bucket its CACHE by week.
///
/// Two properties decide the whole shape:
///  - the fetcher owns the rows. `latest(_:)` writes the per-channel cache and this type never
///    keeps a second copy of it, so a refresh that half-fails still renders every channel that
///    ever succeeded — `rebucket` reads `cached(_:)`, which needs no network at all.
///  - ruling F4: there is no deep paging. `loadMoreWeeks()` walks further down the SAME bucketed
///    list; when the deepest non-empty week has been emitted the feed has `reachedEnd`, because
///    the 15-entry Atom window is all the data that exists.
///
/// Foreground only (ruling F6): the Me tab's `.task` bursts if stale and pull-to-refresh forces.
/// No `BGAppRefreshTask`, no `UIBackgroundModes`.
@MainActor @Observable final class MeFeedRepository {
    /// Runs one channel's attempt under `MeFeedRefreshGate.perChannelTimeout`; `nil` means the
    /// deadline won. Injected rather than raced against a fake sleep on purpose: a race decided by
    /// the scheduler is a flaky test on a host running at load average 200+, and what is worth
    /// pinning is that a deadline is recorded as `.timeout` and never escalates, not that
    /// `Task.sleep` can lose a race.
    typealias Deadline = @Sendable (
        @escaping @Sendable () async -> MeFeedRefreshGate.Outcome
    ) async -> MeFeedRefreshGate.Outcome?

    private let atom: AtomFeedFetcher
    private let states: any KeyValueStore
    private let now: () -> Date
    private let calendar: Calendar
    private let deadline: Deadline

    /// The loaded prefix of `allWeeks`, newest week first.
    private(set) var weeks: [WeekSection] = []
    /// True once the deepest non-empty week has been emitted — F4's stop signal.
    private(set) var reachedEnd = false
    /// `me_refresh_error`, or nil. Never a raw error description: `ChannelRefreshState`'s
    /// `lastErrorMessage` is the diagnostic field and is not user-visible (Task 15, deviation 4).
    private(set) var lastError: String?

    /// Every non-empty week from the last bucketing; `weeks` is its loaded prefix.
    private var allWeeks: [WeekSection] = []
    private var loadedWeekCount = 1
    private var channelIds: [String] = []
    private var filter: String?
    /// The UNFILTERED channel count at the last refresh (`MeViewModel.kt:181-195`).
    private var lastChannelCount: Int?

    init(atom: AtomFeedFetcher, refreshState: any KeyValueStore,
         now: @escaping () -> Date, calendar: Calendar,
         deadline: @escaping Deadline = MeFeedRepository.withDeadline) {
        self.atom = atom
        self.states = refreshState
        self.now = now
        self.calendar = calendar
        self.deadline = deadline
    }

    // MARK: - Refresh

    /// `force` bypasses TTL and backoff (pull-to-refresh); otherwise `MeFeedRefreshGate.decide` rules.
    ///
    /// `channelIds` is always the UNFILTERED subscription list — the chip filter is a rendering
    /// concern (`rebucket`), never a fetching one, and the loaded-week reset below keys off this
    /// list's size for exactly that reason (`MeViewModel.kt:181-195`: an AWAITING import that later
    /// graduates should also be able to reset the feed).
    func refresh(channelIds: [String], force: Bool) async {
        if lastChannelCount != channelIds.count { loadedWeekCount = 1 }
        lastChannelCount = channelIds.count
        self.channelIds = channelIds

        let at = now()
        let due = channelIds.filter { MeFeedRefreshGate.decide(state(for: $0), now: at, force: force) == .fetch }
        if due.isEmpty {
            lastError = nil
        } else {
            let atom = atom
            let deadline = deadline
            let outcomes = await Self.fanOut(due, limit: MeFeedRefreshGate.maxConcurrent) { id in
                await deadline { await Self.fetch(id, atom: atom) } ?? .timeout
            }
            let after = now()
            for (id, outcome) in outcomes {
                write(MeFeedRefreshGate.apply(outcome, to: state(for: id), now: after), for: id)
            }
            // One rule, deliberately coarse: the banner says "couldn't refresh your feed", which is
            // true exactly when nothing this round did. A partial success renders its rows and says
            // nothing — an error over a feed that just grew would be noise.
            lastError = outcomes.values.contains(.success) ? nil : String(localized: "me_refresh_error")
        }
        await rebucket(filter: filter)
    }

    /// Bounded fan-out: `limit` channels in flight, the next started as each finishes. `nonisolated`
    /// so the children run off the main actor — the only main-actor work is the bookkeeping above.
    private nonisolated static func fanOut(
        _ ids: [String], limit: Int,
        run: @escaping @Sendable (String) async -> MeFeedRefreshGate.Outcome
    ) async -> [String: MeFeedRefreshGate.Outcome] {
        await withTaskGroup(of: (String, MeFeedRefreshGate.Outcome).self) { group in
            var results: [String: MeFeedRefreshGate.Outcome] = [:]
            var next = 0
            while next < min(max(limit, 1), ids.count) {
                let id = ids[next]
                next += 1
                group.addTask { (id, await run(id)) }
            }
            while let (id, outcome) = await group.next() {
                results[id] = outcome
                if next < ids.count {
                    let id = ids[next]
                    next += 1
                    group.addTask { (id, await run(id)) }
                }
            }
            return results
        }
    }

    /// One channel's fetch, with every failure mapped onto a `MeFeedRefreshGate.Outcome`.
    ///
    /// The mapping is load-bearing, not cosmetic: `.transport` walks the 5xx backoff ladder
    /// (Task 15), so classifying ambient network jitter as `.transport` would route around the
    /// "a timeout never escalates" guarantee. A timed-out, cancelled or offline request is
    /// `.timeout` — it records the attempt and leaves the counter and the cooldown alone.
    ///
    /// The items are discarded: `latest(_:)` has already written them to the per-channel cache,
    /// and `rebucket` reads that cache. Keeping a second copy here is what would let the two
    /// disagree.
    private nonisolated static func fetch(_ id: String, atom: AtomFeedFetcher) async -> MeFeedRefreshGate.Outcome {
        do {
            _ = try await atom.latest(id)
            return .success
        } catch AtomFeedError.httpError(let status) {
            return .httpError(status)
        } catch is CancellationError {
            return .timeout
        } catch let error as URLError {
            switch error.code {
            case .timedOut, .cancelled, .notConnectedToInternet: return .timeout
            default: return .transport
            }
        } catch {
            return .transport
        }
    }

    // MARK: - Bucketing

    /// Re-buckets from `cached(_:)` with NO fetch — the chip filter and the `loadMoreWeeks` path.
    ///
    /// A filter naming something that is not a subscribed channel (a saved-playlist chip: the Me
    /// rail merges both) buckets nothing, which is the honest answer — the Atom feed is per
    /// channel, so a playlist's videos were never fetched.
    func rebucket(filter channelId: String?) async {
        setFilter(channelId)
        let ids = channelId.map { channelIds.contains($0) ? [$0] : [] } ?? channelIds

        var items: [VideoItem] = []
        for id in ids {
            items += await atom.cached(id)
        }

        let at = now()
        var buckets: [Int: [VideoItem]] = [:]
        for item in items {
            // A nil upload instant is DROPPED, never defaulted into week 0: the browse path leaves
            // `publishedAt` nil and an undated Atom entry degrades to nil too, so "no date" piling
            // into "This week" would be a lie the user cannot correct.
            guard let publishedAt = item.publishedAt,
                  let index = WeekBucket.weekIndexOf(publishedAt, now: at, calendar: calendar)
            else { continue }
            buckets[index, default: []].append(item)
        }
        allWeeks = buckets.keys.sorted().map { index in
            WeekSection(index: index, items: buckets[index]!.sorted {
                $0.publishedAt == $1.publishedAt ? $0.id < $1.id : ($0.publishedAt ?? .distantPast) > ($1.publishedAt ?? .distantPast)
            })
        }
        publish()
    }

    /// Clears the loaded weeks SYNCHRONOUSLY, then flips the filter (`MeViewModel.kt:377-395`).
    ///
    /// Without the clear there is one render in which the already-loaded week indices are matched
    /// against the NEW filter; a chip with nothing in those weeks paints an empty feed that is
    /// visually identical to "no results", so users tapped the chip a second time before the
    /// rebucket could repopulate. Clearing first makes that render explicitly a loading state.
    func setFilter(_ channelId: String?) {
        guard channelId != filter else { return }
        weeks = []
        allWeeks = []
        loadedWeekCount = 1
        reachedEnd = false
        filter = channelId
    }

    /// F4: one more week off the SAME bucketed list, never another request.
    func loadMoreWeeks() {
        guard !reachedEnd else { return }
        loadedWeekCount += 1
        publish()
    }

    private func publish() {
        weeks = Array(allWeeks.prefix(loadedWeekCount))
        reachedEnd = weeks.count == allWeeks.count
    }

    // MARK: - Per-channel refresh state

    /// The storage key for one channel's `ChannelRefreshState`. Internal so a test can plant a
    /// corrupt blob under it.
    nonisolated static func stateKey(_ channelId: String) -> String {
        "MeFeedRepository.refreshState.\(channelId)"
    }

    /// Internal, not private: the tests read back what a refresh recorded.
    ///
    /// A blob that will not decode reads as NO state — never as an error, and never as a state
    /// that happens to look backed off. `ChannelRefreshState` decodes strictly (Task 15, concern
    /// 3), so one bad write would otherwise freeze that channel's feed forever.
    func state(for channelId: String) -> ChannelRefreshState? {
        guard let data = states.get(Self.stateKey(channelId)) else { return nil }
        return try? JSONDecoder().decode(ChannelRefreshState.self, from: data)
    }

    private func write(_ state: ChannelRefreshState, for channelId: String) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        states.set(Self.stateKey(channelId), data)
    }

    // MARK: - Deadline

    /// The production `Deadline`: whichever of the fetch and `perChannelTimeout` finishes first.
    // ponytail: no 250 ms start stagger. `MeFeedRefreshGate.maxConcurrent` already bounds the
    // burst to four, and a fixed stagger would add 7.5 s to a 30-channel refresh plus a second
    // injected clock to every test. Add one here if the 429 ladder ever starts walking.
    nonisolated static func withDeadline(
        _ work: @escaping @Sendable () async -> MeFeedRefreshGate.Outcome
    ) async -> MeFeedRefreshGate.Outcome? {
        await withTaskGroup(of: MeFeedRefreshGate.Outcome?.self) { group in
            group.addTask { await work() }
            group.addTask {
                try? await Task.sleep(for: MeFeedRefreshGate.perChannelTimeout)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}
