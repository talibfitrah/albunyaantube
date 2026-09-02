import AVFoundation
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Phase 3 Task 7: the owner ruling's storage constraints, pinned as TESTS, not comments
/// (plan Global Constraints). Saved media lives in the app sandbox and never leaves it.
@Suite(.perTest)
struct OfflineComplianceTests {
    /// Neither key may ever land in Info.plist: `UIFileSharingEnabled` mounts the container in
    /// Files/Finder, `LSSupportsOpeningDocumentsInPlace` lets other apps open files in place —
    /// either one exports saved media out of the sandbox. `Bundle.main` in this hosted test
    /// target IS the app bundle, so this reads the built plist, not the project template.
    @Test func theInfoPlistDeclaresNoFileSharingAndNoOpenInPlace() {
        #expect(Bundle.main.object(forInfoDictionaryKey: "UIFileSharingEnabled") == nil)
        #expect(Bundle.main.object(forInfoDictionaryKey: "LSSupportsOpeningDocumentsInPlace") == nil)
    }

    /// After a manager write, `Application Support/offline/` carries `isExcludedFromBackup`
    /// (owner ruling: saved media must not ride iCloud/iTunes backups off the device).
    @Test func theOfflineDirectoryIsExcludedFromBackupAfterAManagerWrite() throws {
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflineComplianceTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: base) }
        let directory = OfflineStorage.directoryURL(base: base)
        try OfflineManager.prepareDirectory(directory)
        let values = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey, .isDirectoryKey])
        #expect(values.isDirectory == true)
        #expect(values.isExcludedFromBackup == true)
    }

    /// The no-share/no-export invariant, enforced by construction: a completed row offers Open
    /// and Delete and NOTHING else (owner ruling — media files never leave the sandbox; no
    /// `ShareLink`/`UIActivityViewController` ever receives a saved file URL, because no surface
    /// exists to hand one to). Link sharing stays allowed and unchanged; this pins the FILE side.
    @Test func aCompletedRowOffersOpenAndDeleteAndNothingElse() {
        #expect(OfflineStateMachine.actions(for: .completed) == [.open, .delete])
    }

    /// Casting a saved file is blocked three ways over (the toolbar slot, the claim, and
    /// `CastMedia.make`'s http(s) guard) — but AVKit's stock transport carries its OWN route
    /// picker, and `allowsExternalPlayback` was written `true` for every player this app builds.
    /// That is the same export by another route: AirPlay pulls the decoded sandbox file to a device
    /// on the network. Offline playback is in-app only (screen mirroring is a device-level setting
    /// and is unaffected); a remote stream keeps the route it always had.
    ///
    /// The signal is the URL, not a new parameter: a saved item is exactly a `file://` resolve, and
    /// `assetOptions(userAgent:url:)` right below already splits local from remote on it.
    @Test func theOfflinePlayerNeverOffersItsSavedFileToAnExternalRoute() {
        let saved = Resolved(stream: .progressive(url: URL(filePath: "/tmp/fitrah-offline.m4a"), label: "360p"),
                             client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
        #expect(PlayerHostView.player(for: .rung2Progressive(saved), replacing: nil)?
            .allowsExternalPlayback == false)

        let streamed = Resolved(stream: .hls(url: URL(string: "https://example.invalid/v.m3u8")!, isLive: false,
                                             audioOnlyURL: nil, captionTracks: []),
                                client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
        #expect(PlayerHostView.player(for: .ready(streamed), replacing: nil)?
            .allowsExternalPlayback == true)
    }
}
