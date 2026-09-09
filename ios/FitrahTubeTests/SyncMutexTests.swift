import Foundation
import SwiftData
import Synchronization
import Testing
@testable import FitrahTube

/// Phase 4 Task 23, Step 0 — the exclusion, pinned BEFORE the logic that rests on it.
///
/// `SyncManager` is an `actor`, and an `actor` alone does NOT serialise `bind`/`pull`/`push`:
/// every `await` inside an isolated method is a reentrancy point, so a second `syncNow()` runs its
/// pull while the first is parked in the network — exactly the interleave Android's two separate
/// mutexes produced (`SyncManager.kt:44-56`: pull reads the server `updatedAt`, a concurrent push
/// writes, pull persists a stale cursor tail). The real mutex is an explicit `inFlight` flag plus a
/// FIFO of `CheckedContinuation`, released in `defer` on EVERY exit path including cancellation.
///
/// Hand-rolled exclusions fail in three ways and this suite is one test per way: barging (two
/// callers inside at once), deadlock (a second entrant while the first is held), and a stranded
/// flag (a cancelled caller that never releases). No sleeping anywhere — the `Gate` rendezvous
/// actor holds the first caller inside its critical section and the suite's `Task.yield()` idiom
/// gives the second every chance to barge.
@Suite(.perTest)
struct SyncMutexTests {

    private static let uid = "uid-a"

    private func manager(_ client: ScriptedSyncClient,
                         sleep: @escaping @Sendable (Duration) async -> Void = { _ in }) -> SyncManager {
        SyncManager(client: client, modelContainer: AppContainer.makeModelContainer(inMemory: true),
                    backoff: SyncBackoff(random: { $0.lowerBound }), sleep: sleep)
    }

    /// Barging. The first `syncNow` is held inside its critical section, mid-pull; the second is
    /// started and given 500 scheduler turns to overtake it. A reentrant actor lets it straight in
    /// and the pull count reads 2 while the first caller has not returned.
    ///
    /// "One pull" is one pull AT A TIME: serialisation, not coalescing — there is no dedupe in the
    /// interface and Android has none either, so both callers do eventually pull.
    @Test func twoConcurrentSyncNowCallsProduceOnePullAtATime() async throws {
        let gate = Gate()
        let client = ScriptedSyncClient(pulls: [.page(.empty), .page(.empty)],
                                        hook: { call, index in
                                            if call == .pull && index == 1 { await gate.block() }
                                        })
        let manager = self.manager(client)

        let first = Task { await manager.syncNow(uid: Self.uid) }
        await gate.waitUntilBlocked()
        let second = Task { await manager.syncNow(uid: Self.uid) }
        for _ in 0..<500 where client.calls.count == 1 { await Task.yield() }

        #expect(client.calls == [.pull],
                "the second syncNow entered while the first was still inside its critical section")
        #expect(client.peakConcurrency == 1)

        await gate.release()
        await first.value
        await second.value
        #expect(client.calls == [.pull, .pull], "the queued caller never ran")
        #expect(client.peakConcurrency == 1)
    }

    /// Deadlock. `unbind()` takes the SAME exclusion (`SyncManager.kt:614-628` / cubic R8 P2 — an
    /// unlocked `unbind` races the `pendingRetry` cancel-then-assign and lets a retry survive
    /// sign-out), so it necessarily waits behind an in-flight pull. Waiting is correct; never
    /// returning is not. Both tasks must complete, and the retry queued before the pull must be
    /// dead afterwards — a retry that fires post-sign-out pushes the previous user's dirty rows
    /// under the new user's bearer.
    @Test func unbindDuringAnInFlightPullCancelsThePendingRetryWithoutDeadlocking() async throws {
        let container = AppContainer.makeModelContainer(inMemory: true)
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "xc7keR2piUM", title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid, dirty: true))
        try context.save()

        let pullGate = Gate(), retryGate = Gate()
        let client = ScriptedSyncClient(pulls: [.page(.empty)],
                                        puts: [.reply(500, nil)],
                                        hook: { call, _ in if call == .pull { await pullGate.block() } })
        let manager = SyncManager(client: client, modelContainer: container,
                                  backoff: SyncBackoff(random: { $0.lowerBound }),
                                  sleep: { _ in await retryGate.block() })

        // A transient push queues the retry; it parks in the injected sleep.
        await manager.pushDirty(uid: Self.uid)
        await retryGate.waitUntilBlocked()
        #expect(client.calls == [.put(.favorites, "xc7keR2piUM")])

        let pull = Task { await manager.pullAll(uid: Self.uid) }
        await pullGate.waitUntilBlocked()
        let unbind = Task { await manager.unbind() }
        for _ in 0..<500 where client.calls.count == 2 { await Task.yield() }

        await pullGate.release()
        await pull.value
        await unbind.value                       // no deadlock: it completes once the pull lets go

        await retryGate.release()                // the cancelled retry wakes and must do nothing
        for _ in 0..<500 where client.calls.count == 2 { await Task.yield() }
        #expect(client.calls == [.put(.favorites, "xc7keR2piUM"), .pull],
                "the retry cancelled by unbind pushed anyway")
    }

    /// A stranded flag. A caller cancelled while suspended INSIDE the critical section still has to
    /// run its `defer`; if the flag is cleared only on the happy path, one cancellation wedges every
    /// later sync for the life of the process — and the symptom is silence, not a crash.
    @Test func aCallerCancelledMidAwaitDoesNotStrandTheExclusion() async throws {
        let gate = Gate()
        let client = ScriptedSyncClient(pulls: [.page(.empty), .page(.empty)],
                                        hook: { call, index in
                                            if call == .pull && index == 1 { await gate.block() }
                                        })
        let manager = self.manager(client)

        let cancelled = Task { await manager.syncNow(uid: Self.uid) }
        await gate.waitUntilBlocked()
        cancelled.cancel()
        await gate.release()
        await cancelled.value

        await manager.syncNow(uid: Self.uid)     // hangs forever if `inFlight` was stranded
        #expect(client.calls == [.pull, .pull])
    }
}

// MARK: - The scripted `SyncTransporting`

/// Task 22 declared `SyncTransporting` as "the seam Task 23's tests script" — so this double sits at
/// that seam, not at `HTTPTransport`: `SyncClient`'s query names, paths and cursor-id validator are
/// `SyncClientTests`' subject and re-proving them through every manager test would couple the two.
///
/// Shared by `SyncMutexTests` and `SyncManagerTests` (one target, no import needed). Responses are
/// consumed in order and running the queue dry THROWS — a silent repeat would let an unexpected
/// extra request pass unnoticed, which is exactly what `ScriptedTransport` refuses for the same
/// reason.
final class ScriptedSyncClient: SyncTransporting {

    enum Call: Equatable, Sendable {
        case pull
        case put(SyncEntityType, String)
        case delete(SyncEntityType, String)
    }

    enum PullReply: Sendable {
        case page(SyncResponse)
        case failure(any Error & Sendable)
    }

    enum PutReply: Sendable {
        case reply(Int, SyncRowEcho?)
        case failure(any Error & Sendable)
    }

    enum Failure: Error, Equatable { case exhausted(String) }

    private struct State {
        var pulls: [PullReply]
        var puts: [PutReply]
        var deletes: [Int]
        var calls: [Call] = []
        var bodies: [Data] = []
        var inFlight = 0
        var peak = 0
    }

    private let state: Mutex<State>
    /// Awaited INSIDE each method, after the call is recorded and before it is answered, with this
    /// call's 1-based index — so a test can park exactly one request while it is genuinely in flight.
    private let hook: (@Sendable (Call, Int) async -> Void)?

    init(pulls: [PullReply] = [], puts: [PutReply] = [], deletes: [Int] = [],
         hook: (@Sendable (Call, Int) async -> Void)? = nil) {
        state = Mutex(State(pulls: pulls, puts: puts, deletes: deletes))
        self.hook = hook
    }

    var calls: [Call] { state.withLock { $0.calls } }
    /// Every PUT body, in order — what proves a synthesised `channelUrl` actually reached the wire.
    var bodies: [Data] { state.withLock { $0.bodies } }
    /// Peak simultaneous calls. 1 is the whole point of the exclusion.
    var peakConcurrency: Int { state.withLock { $0.peak } }

    func pull(cursors: [String: Int], ids: [String: String?]) async throws -> SyncResponse {
        let (reply, index) = enter(.pull)
        await run(.pull, index)
        leave()
        switch reply {
        case .none: throw Failure.exhausted("pull")
        case .some(.failure(let error)): throw error
        case .some(.page(let page)): return page
        }
    }

    func put(_ type: SyncEntityType, id: String, body: Data) async throws -> (status: Int, dto: SyncRowEcho?) {
        let call = Call.put(type, id)
        let (reply, index): (PutReply?, Int) = state.withLock {
            $0.calls.append(call)
            $0.bodies.append(body)
            $0.inFlight += 1
            $0.peak = max($0.peak, $0.inFlight)
            return ($0.puts.isEmpty ? nil : $0.puts.removeFirst(), $0.calls.count)
        }
        await run(call, index)
        leave()
        switch reply {
        case .none: throw Failure.exhausted("put \(type.rawValue)/\(id)")
        case .some(.failure(let error)): throw error
        case .some(.reply(let status, let echo)): return (status, echo)
        }
    }

    func delete(_ type: SyncEntityType, id: String) async throws -> Int {
        let call = Call.delete(type, id)
        let (status, index): (Int?, Int) = state.withLock {
            $0.calls.append(call)
            $0.inFlight += 1
            $0.peak = max($0.peak, $0.inFlight)
            return ($0.deletes.isEmpty ? nil : $0.deletes.removeFirst(), $0.calls.count)
        }
        await run(call, index)
        leave()
        guard let status else { throw Failure.exhausted("delete \(type.rawValue)/\(id)") }
        return status
    }

    private func enter(_ call: Call) -> (PullReply?, Int) {
        state.withLock {
            $0.calls.append(call)
            $0.inFlight += 1
            $0.peak = max($0.peak, $0.inFlight)
            return ($0.pulls.isEmpty ? nil : $0.pulls.removeFirst(), $0.calls.count)
        }
    }

    /// ONE suspension point even with no hook, so simultaneous callers actually overlap and
    /// `peakConcurrency` can exceed 1 — a `peak == 1` assertion is worthless otherwise.
    private func run(_ call: Call, _ index: Int) async {
        await Task.yield()
        await hook?(call, index)
    }

    private func leave() { state.withLock { $0.inFlight -= 1 } }
}

extension SyncResponse {
    /// The exhausted page: three empty item lists and no cursor — one `pullAll` iteration, then stop.
    static var empty: SyncResponse { .page() }

    static func page(subscriptions: [SubscriptionSyncDTO] = [], playlists: [PlaylistSyncDTO] = [],
                     favorites: [FavoriteSyncDTO] = [],
                     subscriptionsCursor: Int? = nil, subscriptionsCursorId: String? = nil,
                     playlistsCursor: Int? = nil, playlistsCursorId: String? = nil,
                     favoritesCursor: Int? = nil, favoritesCursorId: String? = nil) -> SyncResponse {
        SyncResponse(
            subscriptions: SyncPage(items: subscriptions, nextCursor: subscriptionsCursor,
                                    nextCursorId: subscriptionsCursorId),
            playlists: SyncPage(items: playlists, nextCursor: playlistsCursor,
                                nextCursorId: playlistsCursorId),
            favorites: SyncPage(items: favorites, nextCursor: favoritesCursor,
                                nextCursorId: favoritesCursorId))
    }
}
