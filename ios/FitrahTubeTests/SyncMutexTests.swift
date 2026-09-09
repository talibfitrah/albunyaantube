import Foundation
import SwiftData
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
    /// interface and Android has none either, so both callers do eventually pull. **Task 23 review
    /// M5:** that is the reading the brief's step 0 asked for AT THIS LAYER, and the second
    /// `calls == [.pull, .pull]` below is what asserts it rather than coalescing. Coalescing exists,
    /// one layer up and per uid, in `AppContainer.pushDirtySoon(uid:)` (Task 24) — do not build a
    /// second copy of it in the manager.
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

    /// **Task 23 review M1.** `acquire()` is a `withCheckedContinuation` and is NOT
    /// cancellation-aware, so a caller cancelled while QUEUED on the exclusion stays parked and is
    /// then handed the critical section by `release()` — and drains. The real instance is the push
    /// retry: it clears its own `Task.isCancelled` guard the moment its sleep returns, then parks
    /// on `acquire()` behind an in-flight pull, and the pull's own terminal arm calls
    /// `unbindLocked()` from INSIDE the exclusion. The retry then wakes into a drain under a bearer
    /// that is already gone. `unbindDuringAnInFlightPull…` covers only the parked-in-SLEEP ordering,
    /// where the retry's own guard is what stops it.
    ///
    /// Cancelling the queued caller directly pins the guard itself rather than one route to it: it
    /// is the same suspension, the same handoff, and it cannot go green by accident, since a push
    /// cancelled before it ever reaches `acquire()` has no other guard on the path either.
    @Test func aPushCancelledWhileQueuedOnTheExclusionDoesNotDrainAfterTheHandoff() async throws {
        let container = AppContainer.makeModelContainer(inMemory: true)
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "xc7keR2piUM", title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid, dirty: true))
        try context.save()

        let pullGate = Gate()
        let client = ScriptedSyncClient(pulls: [.page(.empty)],
                                        hook: { call, _ in if call == .pull { await pullGate.block() } })
        let manager = SyncManager(client: client, modelContainer: container,
                                  backoff: SyncBackoff(random: { $0.lowerBound }), sleep: { _ in })

        let pull = Task { await manager.pullAll(uid: Self.uid) }
        await pullGate.waitUntilBlocked()
        let push = Task { await manager.pushDirty(uid: Self.uid) }
        // The queued push makes no call of its own, so the suite's yield idiom runs its full budget
        // and gives it every chance to reach `acquire()` before the cancel.
        for _ in 0..<500 where client.calls.count == 1 { await Task.yield() }

        push.cancel()
        await pullGate.release()
        await pull.value
        await push.value

        #expect(client.calls == [.pull], "the cancelled push drained once the exclusion was handed over")
        let row = try #require(try ModelContext(container).fetch(FetchDescriptor<FavoriteVideo>()).first)
        #expect(row.dirty, "the drain that must not have happened cleared the row's dirt")
    }
}
