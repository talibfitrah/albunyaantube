import Foundation
import Testing
@testable import FitrahTube

/// Phase 4 Task 21: the §16 "`SyncManager` merge matrix", as pure values with no actor, no
/// transport and no store behind them. Every row here is a production incident on Android that the
/// iOS port must not re-earn: the stalled cursor that pinned the app on the splash screen, the
/// null-body push that re-pushed forever, the tombstone that resurrected a newer row.
@Suite(.perTest)
struct SyncDecisionTests {

    // MARK: - bind (`SyncManager.kt:78-96`)

    @Test func noBindingRowMerges() {
        #expect(SyncDecisions.bind(binding: nil, uid: "uid-a") == .merge)
    }

    @Test func theSameUidWithTheMergeDonePullsThenPushes() {
        #expect(SyncDecisions.bind(binding: (userId: "uid-a", initialMergeDone: true), uid: "uid-a") == .pullThenPush)
    }

    /// A prior merge crashed mid-way: re-enter it rather than start pulling over half-merged rows.
    @Test func theSameUidWithAnUnfinishedMergeMergesAgain() {
        #expect(SyncDecisions.bind(binding: (userId: "uid-a", initialMergeDone: false), uid: "uid-a") == .merge)
    }

    /// The account switch carries the PREVIOUS uid, because the atomic transaction tags this
    /// device's anonymous rows to it before wiping them -- tagging them to the NEW uid is how
    /// Android transferred one user's local data to another (R-final5/R-final6).
    @Test func aDifferentUidSwitchesAccountCarryingThePreviousUid() {
        #expect(SyncDecisions.bind(binding: (userId: "uid-a", initialMergeDone: true), uid: "uid-b")
                == .switchAccount(previousUid: "uid-a"))
        #expect(SyncDecisions.bind(binding: (userId: "uid-a", initialMergeDone: false), uid: "uid-b")
                == .switchAccount(previousUid: "uid-a"))
    }

    // MARK: - rowAction, the 8-row truth table (`SyncManager.kt:208-267`)

    @Test func aTombstoneNewerThanTheLocalRowApplies() {
        #expect(SyncDecisions.rowAction(serverDeleted: true, serverUpdatedAt: 200, localExists: true,
                                        localDirty: false, localUpdatedAt: 100) == .applyTombstone)
    }

    /// The monotonicity guard: an older tombstone can never resurrect a newer row.
    @Test func aTombstoneOlderThanTheLocalRowIsSkippedAsStale() {
        #expect(SyncDecisions.rowAction(serverDeleted: true, serverUpdatedAt: 100, localExists: true,
                                        localDirty: false, localUpdatedAt: 200) == .skipStaleTombstone)
        // Task 21 review / M2 -- the ninth reachable combination, and the intersection of the two
        // rules deviation 4 had to arbitrate between: `dirty` does not save a row from a NEWER
        // tombstone (above), and it does not resurrect it from a STALE one either. The guard is the
        // timestamp, alone.
        #expect(SyncDecisions.rowAction(serverDeleted: true, serverUpdatedAt: 100, localExists: true,
                                        localDirty: true, localUpdatedAt: 200) == .skipStaleTombstone)
    }

    /// Room's predicate is `updated_at < :ts`, strictly -- an equal timestamp is not progress.
    @Test func aTombstoneEqualToTheLocalTimestampIsSkippedAsStale() {
        #expect(SyncDecisions.rowAction(serverDeleted: true, serverUpdatedAt: 100, localExists: true,
                                        localDirty: false, localUpdatedAt: 100) == .skipStaleTombstone)
    }

    /// `dirty` does NOT protect a row from a newer tombstone: `applyTombstone` sets `dirty = 0`
    /// itself. Only the non-deleted branch defers to the pending push.
    @Test func aNewerTombstoneOverridesEvenADirtyLocalRow() {
        #expect(SyncDecisions.rowAction(serverDeleted: true, serverUpdatedAt: 200, localExists: true,
                                        localDirty: true, localUpdatedAt: 100) == .applyTombstone)
    }

    /// Nothing to tombstone. Room's UPDATE is a no-op here; the decision says the same thing.
    @Test func aTombstoneForARowThatDoesNotExistLocallyIsSkipped() {
        #expect(SyncDecisions.rowAction(serverDeleted: true, serverUpdatedAt: 200, localExists: false,
                                        localDirty: false, localUpdatedAt: 0) == .skipStaleTombstone)
    }

    @Test func aServerRowWithNoLocalCounterpartApplies() {
        #expect(SyncDecisions.rowAction(serverDeleted: false, serverUpdatedAt: 200, localExists: false,
                                        localDirty: false, localUpdatedAt: 0) == .applyRow)
    }

    /// A clean local row loses to the server even when its own timestamp is higher: local writes
    /// never bump `updatedAt` (it is server-stamped on push success), so a newer local stamp can
    /// only have come from the server in the first place.
    @Test func aServerRowAgainstACleanLocalRowAppliesWhateverTheTimestamps() {
        #expect(SyncDecisions.rowAction(serverDeleted: false, serverUpdatedAt: 100, localExists: true,
                                        localDirty: false, localUpdatedAt: 200) == .applyRow)
    }

    /// `dirty == true` alone is the conflict signal (R-final P2). The push resolves it next.
    @Test func aDirtyLocalRowSkipsTheServerRow() {
        #expect(SyncDecisions.rowAction(serverDeleted: false, serverUpdatedAt: 900, localExists: true,
                                        localDirty: true, localUpdatedAt: 100) == .skipDirty)
    }

    // MARK: - page: the stalled-cursor guard (`SyncManager.kt:326-363`)

    @Test func aMintedCursorThatAdvancedContinuesTheLoop() {
        #expect(SyncDecisions.page(mintedCursor: true,
                                   cursorsBefore: ["subscriptions": 100], cursorsAfter: ["subscriptions": 200],
                                   idsBefore: ["subscriptions": "a"], idsAfter: ["subscriptions": "b"]) == .advance)
    }

    /// THE GUARD. A server that hands back the cursor it was queried with cannot make progress;
    /// looping on it ran ~3 req/s, starved the shared HTTP client and pinned the app on the splash
    /// screen. Stop instead of spinning.
    @Test func aMintedCursorThatDidNotAdvanceIsStalled() {
        #expect(SyncDecisions.page(mintedCursor: true,
                                   cursorsBefore: ["subscriptions": 100], cursorsAfter: ["subscriptions": 100],
                                   idsBefore: ["subscriptions": "a"], idsAfter: ["subscriptions": "a"]) == .stalled)
    }

    /// Normal exhaustion -- every type returned a null cursor. NOT the same condition as stalled,
    /// and the two must not be conflated: one is done, the other is broken.
    @Test func noMintedCursorIsExhaustion() {
        #expect(SyncDecisions.page(mintedCursor: false,
                                   cursorsBefore: ["subscriptions": 100], cursorsAfter: ["subscriptions": 100],
                                   idsBefore: ["subscriptions": "a"], idsAfter: ["subscriptions": "a"]) == .exhausted)
    }

    /// A page break inside a group of rows sharing one millisecond advances only the tiebreaker.
    /// That is progress: the next request starts after that document.
    @Test func advancingOnlyTheLastDocIdStillCountsAsAdvanced() {
        #expect(SyncDecisions.page(mintedCursor: true,
                                   cursorsBefore: ["favorites": 100], cursorsAfter: ["favorites": 100],
                                   idsBefore: ["favorites": "a"], idsAfter: ["favorites": "b"]) == .advance)
    }

    // MARK: - push (`SyncManager.kt:575-617`)

    @Test func aSuccessWithABodyIsOk() {
        #expect(SyncDecisions.push(status: 200, hasBody: true) == .ok)
        #expect(SyncDecisions.push(status: 201, hasBody: true) == .ok)
    }

    /// R-final7 P0. `OK` here meant `clearDirty` never ran, the row stayed dirty, and the next
    /// cycle re-pushed it -- forever. Transient keeps the row dirty AND advances the backoff.
    @Test func aSuccessWithNoBodyIsTransientNotOk() {
        #expect(SyncDecisions.push(status: 200, hasBody: false) == .transientFailure)
        #expect(SyncDecisions.push(status: 204, hasBody: false) == .transientFailure)
    }

    @Test func a404IsOkBecauseTheDeleteIsIdempotent() {
        #expect(SyncDecisions.push(status: 404, hasBody: false) == .ok)
    }

    @Test func a401OrA403AbortsTheDrain() {
        #expect(SyncDecisions.push(status: 401, hasBody: false) == .authFailed)
        #expect(SyncDecisions.push(status: 403, hasBody: true) == .authFailed)
    }

    /// The server says the payload is bad; retrying the same bytes fails the same way. Dropping
    /// dirty (with a local warning) is what stops one malformed row blocking pulls forever.
    @Test func a400A409OrA422IsAPermanentFailure() {
        for status in [400, 409, 422] {
            #expect(SyncDecisions.push(status: status, hasBody: true) == .permanentFailure)
        }
    }

    @Test func every5xx429AndUnknownStatusIsTransient() {
        for status in [429, 500, 502, 503, 0] {
            #expect(SyncDecisions.push(status: status, hasBody: true) == .transientFailure)
        }
    }

    // MARK: - pull (Task 22 review / I1; added by Task 23)

    /// A revoked or blocked account answers 401/403 forever. Without this arm a generic retry is an
    /// unbounded loop against a server that will never say yes — `SyncManager` stops the run and
    /// unbinds, and Part A's session handling owns what happens to the account.
    @Test func a401Or403PullIsTerminalAndNeverRetried() {
        #expect(SyncDecisions.pull(status: 401) == .terminal)
        #expect(SyncDecisions.pull(status: 403) == .terminal)
    }

    /// 400 is the REQUEST's fault — a cursor id the server rejects. The same bytes cannot succeed,
    /// so the cursor is dropped (with the log line) rather than sent again.
    @Test func a400PullIsPermanentAndDropsTheCursorInsteadOfRetrying() {
        #expect(SyncDecisions.pull(status: 400) == .permanent)
    }

    /// Everything else takes the bounded ladder and then gives up for THIS run, keeping the cursor:
    /// 5xx, 429, and `nil` — which is both a transport error and a response the decoder refused
    /// (`SyncPage.items` is a required key, so one page omitting it fails the whole decode).
    @Test func everyOtherPullFailureIncludingATransportErrorIsTransient() {
        for status in [nil, 0, 404, 429, 500, 502, 503] {
            #expect(SyncDecisions.pull(status: status) == .transient)
        }
    }

    // MARK: - SyncBackoff (`SyncBackoff.kt:18-35`)

    /// 1 s doubling to a 60 s cap; the RNG stub takes the low edge of the equal-jitter window, so
    /// the waits are exactly half the base and cap at 30 s.
    @Test func theScheduleDoublesFromOneSecondAndCapsAtHalfOfSixtySeconds() {
        var backoff = SyncBackoff(random: { $0.lowerBound })
        let waits = (0..<8).map { _ in backoff.next() }
        #expect(waits == [.milliseconds(500), .seconds(1), .seconds(2), .seconds(4),
                          .seconds(8), .seconds(16), .seconds(30), .seconds(30)])
    }

    /// Equal jitter: the window is [base/2, base], so the high edge is exactly twice the low edge
    /// at every step -- which is also what keeps a fleet-wide outage from reconnecting in lockstep.
    @Test func theJitterWindowRunsFromHalfTheBaseToTheBase() {
        var low = SyncBackoff(random: { $0.lowerBound })
        var high = SyncBackoff(random: { $0.upperBound })
        for _ in 0..<8 { #expect(low.next() * 2 == high.next()) }
    }

    @Test func resetReturnsTheScheduleToTheBase() {
        var backoff = SyncBackoff(random: { $0.lowerBound })
        _ = backoff.next()
        _ = backoff.next()
        #expect(backoff.next() == .seconds(2))
        backoff.reset()
        #expect(backoff.next() == .milliseconds(500))
    }
}
