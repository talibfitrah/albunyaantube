import Foundation
import SwiftUI
import Testing
@testable import FitrahTube

/// Phase 3 Task 6: the Saved screen's pure seams. Sort (alphabetical by title) is already pinned
/// by `OfflineStoreTests.itemsAreSortedAlphabeticallyByTitle` and the ar-numerals footer by
/// `OfflineEngineTests.theFooterRendersThroughFormatWithLocaleAwareNumerals` — the screen renders
/// `store.items` and `OfflineStorage.footer` verbatim, so neither is re-tested here.
@Suite(.perTest)
struct SavedScreenTests {

    // MARK: - The ONE action matrix

    /// The row's action set delegates to Task 3's `OfflineStateMachine.actions(for:)` — no second
    /// status switch in the view. Editing the matrix must move the rows with it.
    @Test func rowActionsComeFromTheOneMatrix() {
        for status in OfflineStatus.allCases {
            #expect(SavedScreen.rowActions(for: status) == OfflineStateMachine.actions(for: status))
        }
    }

    // MARK: - Action dispatch (every mutation through the manager, never FileManager)

    @Test func eachRowActionCallsItsManagerMethodExactlyOnce() async {
        let cases: [(OfflineAction, String)] = [
            (.pause, "pause"), (.resume, "resume"), (.cancel, "cancel"), (.retry, "retry"),
            (.remove, "delete"), (.delete, "delete"),
        ]
        for (action, expected) in cases {
            let spy = SpyOfflineManager()
            await SavedRowAction.perform(action, id: "row-1", manager: spy, open: {})
            let calls = await spy.calls
            #expect(calls == [Call(method: expected, id: "row-1")], "\(action) dispatched \(calls)")
        }
    }

    @Test func openRunsTheOpenClosureAndNeverTouchesTheManager() async {
        let spy = SpyOfflineManager()
        var opened = false
        await SavedRowAction.perform(.open, id: "row-1", manager: spy, open: { opened = true })
        #expect(opened)
        #expect(await spy.calls.isEmpty)
    }

    // MARK: - Settings Clear (spy manager, once per item — CF-B3-11's confirm fires this)

    @Test func clearAllDeletesThroughTheManagerOncePerItem() async {
        let spy = SpyOfflineManager()
        await OfflineClearAll.run(ids: ["a", "b", "c"], manager: spy)
        #expect(await spy.calls == [Call(method: "delete", id: "a"),
                                    Call(method: "delete", id: "b"),
                                    Call(method: "delete", id: "c")])
    }

    // MARK: - Row captions (status/error keys, running → "saving")

    @Test func statusCaptionsUseTheOfflineStatusKeys() {
        let expected: [OfflineStatus: String] = [
            .queued: "offline_status_queued", .running: "offline_status_saving",
            .paused: "offline_status_paused", .completed: "offline_status_completed",
            .failed: "offline_status_failed", .cancelled: "offline_status_cancelled",
        ]
        for (status, key) in expected {
            #expect(SavedRowText.statusKey(status) == key)
        }
    }

    /// ErrorCode raw → key; NOT_SAVEABLE gets the refusal copy (WHAT, never why); an unknown or
    /// missing raw falls back to the generic key rather than rendering a raw code.
    @Test func errorCaptionsMapEveryErrorCode() {
        let expected: [(String?, String)] = [
            ("HTTP_403", "offline_error_403"), ("HTTP_429", "offline_error_429"),
            ("NETWORK", "offline_error_network"), ("NO_STREAM", "offline_error_no_stream"),
            ("INVALID_INPUT", "offline_error_invalid"), ("NOT_SAVEABLE", "offline_not_saveable"),
            ("UNKNOWN", "offline_error_unknown"), ("SOME_FUTURE_CODE", "offline_error_unknown"),
            (nil, "offline_error_unknown"),
        ]
        for (raw, key) in expected {
            #expect(SavedRowText.errorKey(raw) == key, "\(raw ?? "nil")")
        }
    }

    /// Every key this screen renders resolves to real copy — `String(localized:)` silently
    /// returns the key itself when the catalog entry is missing (the SettingsRowsTests idiom).
    @Test func everySavedScreenKeyResolvesToRealCopy() {
        var keys = ["offline_saved_title", "offline_empty_state", "offline_footer_format",
                    "offline_action_pause", "offline_action_resume", "offline_action_remove",
                    "offline_action_open", "offline_action_delete", "cancel", "retry"]
        keys += OfflineStatus.allCases.map(SavedRowText.statusKey)
        keys += ["HTTP_403", "HTTP_429", "NETWORK", "NO_STREAM", "INVALID_INPUT", "NOT_SAVEABLE", nil]
            .map(SavedRowText.errorKey)
        for key in keys {
            #expect(String(localized: String.LocalizationValue(key)) != key, "missing catalog entry for \(key)")
        }
    }

    @Test func actionLabelsReuseTheAndroidGenericsForCancelAndRetry() {
        #expect(SavedRowAction.labelKey(.cancel) == "cancel")
        #expect(SavedRowAction.labelKey(.retry) == "retry")
        #expect(SavedRowAction.labelKey(.pause) == "offline_action_pause")
        #expect(SavedRowAction.labelKey(.resume) == "offline_action_resume")
        #expect(SavedRowAction.labelKey(.remove) == "offline_action_remove")
        #expect(SavedRowAction.labelKey(.open) == "offline_action_open")
        #expect(SavedRowAction.labelKey(.delete) == "offline_action_delete")
    }

    // MARK: - Settings storage value

    @Test func storageValueRendersBothByteCountsThroughFormat() {
        let en = OfflineStorage.storageValue(used: 1_500_000, available: 2_000_000_000,
                                             locale: Locale(identifier: "en"))
        #expect(en.contains("used"))
        #expect(en.contains("available"))
        #expect(en.lowercased().contains("download") == false)
        let ar = OfflineStorage.storageValue(used: 1_500_000, available: 2_000_000_000,
                                             locale: Locale(identifier: "ar"))
        #expect(ar.contains("used") == false, "Arabic value must not fall back to English: \(ar)")
    }

    // MARK: - Task 5 fold-in 2: the save sheet's captured identity

    /// `.sheet(item:)` presents the args captured at tap time; its identity is the videoId, so a
    /// queue auto-advance mutating the toolbar's `args` in place can never re-aim an open sheet at
    /// the advanced-to video.
    @Test func theSaveSheetIdentityIsThePresentedVideoId() {
        var args = PlayerArgs(videoId: "xc7keR2piUM", title: "Lecture")
        let captured = args
        args = PlayerArgs(videoId: "advanced-to")   // swapArgs-style replacement of the toolbar's args
        #expect(captured.id == "xc7keR2piUM")
        #expect(args.id == "advanced-to")
    }

    // MARK: - Layout pin (the screenshots.sh substitute — simulator launch is owner-gated)

    /// The Task 5 `PlayerToolbarLayoutTests` idiom: `ImageRenderer` renders synchronously with
    /// deterministic pixels. Six seeded rows across every status, ar/RTL at `.accessibility3` —
    /// the screen must render (non-zero), and must grow taller than its empty state (proof the
    /// rows and footer actually mounted).
    @MainActor
    @Test func savedScreenRendersAllSixStatusesInRTLWithoutCollapsing() throws {
        let empty = try renderedHeight(seeded: false)
        let seeded = try renderedHeight(seeded: true)
        #expect(empty > 0)
        // Six rows, each carrying at least one ≥44 pt action button, plus the footer — anything
        // shorter means rows failed to mount.
        #expect(seeded >= 6 * 44)
        // And taller than the empty state (Task 6 review fold-in: the comment above promised
        // this; now the pin does too).
        #expect(seeded > empty)
    }

    @MainActor
    private func renderedHeight(seeded: Bool) throws -> CGFloat {
        let suite = "fitrahtube.saved-screen-tests"
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        defaults.removePersistentDomain(forName: suite)
        let container = AppContainer.fake(defaults: defaults)
        if seeded {
            for (index, status) in OfflineStatus.allCases.enumerated() {
                let item = OfflineItem(videoId: "seed-\(index)", title: "Lecture \(index)",
                                       channelName: "Channel", thumbnailUrl: nil,
                                       qualityLabel: "360p", audioOnly: false,
                                       status: status.rawValue, bytesWritten: 1_000_000,
                                       totalBytes: 4_000_000,
                                       errorCode: status == .failed ? "NETWORK" : nil)
                try container.offlineStore.insert(item)
            }
        }
        let screen = SavedScreen()
            .environment(\.container, container)
            .environment(\.locale, Locale(identifier: "ar"))
            .environment(\.layoutDirection, .rightToLeft)
            .dynamicTypeSize(.accessibility3)
        let renderer = ImageRenderer(content: screen)
        renderer.proposedSize = ProposedViewSize(width: 390, height: nil)
        return renderer.uiImage?.size.height ?? 0
    }
}

// MARK: - Spy

nonisolated struct Call: Equatable, Sendable {
    let method: String
    let id: String
}

/// Records every `OfflineSaving` call — proof the UI mutates through the manager and nothing else.
actor SpyOfflineManager: OfflineSaving {
    private(set) var calls: [Call] = []

    func save(videoId: String, quality: String, audioOnly: Bool, metadata: OfflineMetadata) async {
        calls.append(Call(method: "save", id: videoId))
    }
    func pause(_ id: String) async { calls.append(Call(method: "pause", id: id)) }
    func resume(_ id: String) async { calls.append(Call(method: "resume", id: id)) }
    func cancel(_ id: String) async { calls.append(Call(method: "cancel", id: id)) }
    func retry(_ id: String) async { calls.append(Call(method: "retry", id: id)) }
    func delete(_ id: String) async { calls.append(Call(method: "delete", id: id)) }
    func reattach() async { calls.append(Call(method: "reattach", id: "")) }
    func sweep() async { calls.append(Call(method: "sweep", id: "")) }
}
