import Foundation
import Testing
@testable import FitrahTube

/// Phase 3 Task 3: the pure offline engine — spec §16's "state machine transitions and action
/// matrix, expiry sweep" pins, plus the cellular gate and the storage math. No network, no
/// AVFoundation, no `URLSession` anywhere here; the glue that executes these decisions is
/// Task 4's `OfflineManager`.
@Suite(.perTest)
struct OfflineEngineTests {
    // MARK: - State machine (spec §11 status set)

    @Test func theLegalLifecycleTransitionsAreAccepted() {
        // queued → running → completed
        #expect(OfflineStateMachine.transition(from: .queued, on: .start) == .running)
        #expect(OfflineStateMachine.transition(from: .running, on: .complete) == .completed)
        // running → paused → running
        #expect(OfflineStateMachine.transition(from: .running, on: .pause) == .paused)
        #expect(OfflineStateMachine.transition(from: .paused, on: .resume) == .running)
        // running → failed → queued on retry
        #expect(OfflineStateMachine.transition(from: .running, on: .fail) == .failed)
        #expect(OfflineStateMachine.transition(from: .failed, on: .retry) == .queued)
        // Resolve can fail before the engine ever starts (Task 4's embed outcome).
        #expect(OfflineStateMachine.transition(from: .queued, on: .fail) == .failed)
        // Cubic R4-7: a gate pause landing between the `.running` transition and `engine.start`
        // leaves a PAUSED row whose resolve then fails. Without this arm `fail()` wrote neither
        // status nor error code and the row sat at "Paused" as if nothing had happened.
        #expect(OfflineStateMachine.transition(from: .paused, on: .fail) == .failed)
        // A queued row's button reads Resume (DownloadsAdapter parity); resuming one starts it.
        #expect(OfflineStateMachine.transition(from: .queued, on: .resume) == .running)
    }

    @Test func anythingCancelsExceptCompleted() {
        for from: OfflineStatus in [.queued, .running, .paused, .failed] {
            #expect(OfflineStateMachine.transition(from: from, on: .cancel) == .cancelled)
        }
        #expect(OfflineStateMachine.transition(from: .completed, on: .cancel) == nil)
        // A cancelled row can come back through Retry.
        #expect(OfflineStateMachine.transition(from: .cancelled, on: .retry) == .queued)
    }

    @Test func illegalTransitionsReturnNil() {
        // completed is terminal (deletable, but deletion is not a transition)
        #expect(OfflineStateMachine.transition(from: .completed, on: .start) == nil)
        #expect(OfflineStateMachine.transition(from: .completed, on: .retry) == nil)
        // can't skip running
        #expect(OfflineStateMachine.transition(from: .queued, on: .complete) == nil)
        // a paused task can't finish or fail without running first
        #expect(OfflineStateMachine.transition(from: .paused, on: .complete) == nil)
        #expect(OfflineStateMachine.transition(from: .cancelled, on: .pause) == nil)
    }

    // MARK: - Action matrix (`DownloadsAdapter.kt:96-125`)

    @Test func theActionMatrixMatchesAndroidsRowAnatomy() {
        #expect(OfflineStateMachine.actions(for: .running) == [.pause, .cancel])
        #expect(OfflineStateMachine.actions(for: .paused) == [.resume, .cancel])
        #expect(OfflineStateMachine.actions(for: .queued) == [.resume, .cancel])
        #expect(OfflineStateMachine.actions(for: .failed) == [.retry, .remove])
        #expect(OfflineStateMachine.actions(for: .cancelled) == [.retry, .remove])
    }

    /// The owner ruling's no-share/no-export invariant, enforced by construction: a completed
    /// row offers Open and Delete and NOTHING else — no share, no export, no Files hand-off.
    @Test func aCompletedRowOffersOpenAndDeleteAndNothingElse() {
        #expect(OfflineStateMachine.actions(for: .completed) == [.open, .delete])
    }

    // MARK: - Cellular gate (reconciliation note 6, §16 "cellular gate")

    @Test func theCellularGateBlocksOnlyWifiOnlyPlusCellular() {
        #expect(OfflineStateMachine.allowedToRun(wifiOnly: false, isOnCellular: false))
        #expect(OfflineStateMachine.allowedToRun(wifiOnly: false, isOnCellular: true))
        #expect(OfflineStateMachine.allowedToRun(wifiOnly: true, isOnCellular: false))
        #expect(OfflineStateMachine.allowedToRun(wifiOnly: true, isOnCellular: true) == false)
    }

    // MARK: - Sweep (`DownloadExpiryPolicy.kt:23-28` TTL + reconciliation note 7 revalidation)

    private let now = Date(timeIntervalSince1970: 1_756_600_000)
    private let day: TimeInterval = 86_400

    @Test func expiredUnderGraceIsKept() {
        // 30 days + 30 minutes: past TTL, inside the 1 h grace — kept.
        let completedAt = now.addingTimeInterval(-(30 * day + 1_800))
        #expect(OfflineSweep.decide(completedAt: completedAt, now: now, gate: .allowed) == .keep)
    }

    @Test func expiredPastGraceIsDeletedRegardlessOfTheGateAnswer() {
        let completedAt = now.addingTimeInterval(-(30 * day + 2 * 3_600))
        for gate: GateAnswer in [.allowed, .notAllowed, .gone, .unreachable] {
            #expect(OfflineSweep.decide(completedAt: completedAt, now: now, gate: gate) == .deleteExpired)
        }
    }

    @Test func aCatalogRemovalDeletes() {
        // 410/404 → the ruling's auto-delete on catalog removal.
        #expect(OfflineSweep.decide(completedAt: now.addingTimeInterval(-day), now: now, gate: .gone) == .deleteRemoved)
    }

    @Test func aGateFlipDeletes() {
        // fork C: an admin turning `offlineAllowed` off is the same-day remedy path.
        #expect(OfflineSweep.decide(completedAt: now.addingTimeInterval(-day), now: now, gate: .notAllowed) == .deleteGateRevoked)
    }

    @Test func anUnreachableGateKeepsTheCopy() {
        // fail-open: never mass-delete a library because the phone was offline.
        #expect(OfflineSweep.decide(completedAt: now.addingTimeInterval(-day), now: now, gate: .unreachable) == .keep)
    }

    @Test func freshAndAllowedKeeps() {
        #expect(OfflineSweep.decide(completedAt: now.addingTimeInterval(-day), now: now, gate: .allowed) == .keep)
    }

    // MARK: - Storage (file layout + footer math)

    @Test func theRelativePathRoundTripsAcrossBases() {
        #expect(OfflineStorage.fileName(itemId: "ABC123", kind: .mp4) == "ABC123.mp4")
        #expect(OfflineStorage.fileName(itemId: "X", kind: .movpkg) == "X.movpkg")
        #expect(OfflineStorage.fileName(itemId: "X", kind: .m4a) == "X.m4a")

        let name = OfflineStorage.fileName(itemId: "ABC123", kind: .mp4)
        let base1 = URL(fileURLWithPath: "/container-a/Library/Application Support", isDirectory: true)
        #expect(OfflineStorage.directoryURL(base: base1).lastPathComponent == "offline")
        #expect(OfflineStorage.fileURL(relativePath: name, base: base1).path().hasSuffix("/offline/ABC123.mp4"))
        // `localPath` is stored relative, never absolute: the same row resolves cleanly after
        // the app container moves (reinstall/update).
        let base2 = URL(fileURLWithPath: "/container-b/Library/Application Support", isDirectory: true)
        let rebased = OfflineStorage.fileURL(relativePath: name, base: base2)
        #expect(rebased.path().hasPrefix("/container-b/"))
        #expect(rebased.lastPathComponent == "ABC123.mp4")
    }

    @Test func usedBytesSumsTheRows() {
        let a = OfflineItem(videoId: "vidA", title: "A", channelName: nil, thumbnailUrl: nil,
                            qualityLabel: "360p", audioOnly: false)
        a.bytesWritten = 100
        let b = OfflineItem(videoId: "vidB", title: "B", channelName: nil, thumbnailUrl: nil,
                            qualityLabel: "360p", audioOnly: true)
        b.bytesWritten = 250
        #expect(OfflineStorage.usedBytes(items: [a, b]) == 350)
        #expect(OfflineStorage.usedBytes(items: []) == 0)
    }

    @Test func theFooterRendersThroughFormatWithLocaleAwareNumerals() {
        let en = OfflineStorage.footer(count: 3, used: 1_500_000, available: 2_000_000_000,
                                       locale: Locale(identifier: "en"))
        #expect(en.contains("3"))
        #expect(en.contains("saved"))
        #expect(en.lowercased().contains("download") == false)

        // RTL-safe numerals: the count renders in Eastern Arabic digits under `ar`.
        let ar = OfflineStorage.footer(count: 3, used: 1_500_000, available: 2_000_000_000,
                                       locale: Locale(identifier: "ar"))
        #expect(ar.contains("٣"), "Arabic footer must use Eastern Arabic numerals: \(ar)")
        #expect(ar.contains("saved") == false, "Arabic footer must not fall back to English: \(ar)")
    }
}
