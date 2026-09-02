import Foundation
import InnerTubeKit

/// Phase 3 Task 7 (reconciliation note 5): offline playback is a resolver stub, not a player
/// fork. `PlayerViewModel` accepts any `StreamResolving`; this one answers from the store row
/// and `FileManager` alone — it holds no transport, no client, no `URLSession`, so it performs
/// ZERO network calls by construction. Idempotent: recovery re-resolves route back here and get
/// the same answer (`forceRefresh` is accepted and ignored, like `kind:`).
///
/// `expiresAt: nil` means the TTL-refresh path never fires (`Resolved.isExpired` and
/// `PlayerViewModel.shouldPreemptivelyReResolve` both guard on a non-nil `expiresAt`).
/// A missing row or file throws `ExtractionError.unavailable`, which `PlayerViewModel.map`
/// lands on the player's existing `.contentUnavailable` surface — no new state, no new copy.
struct OfflineResolver: StreamResolving {
    let store: OfflineStore
    /// The `OfflineItem.id` from `PlayerArgs.offlineItemId` — the row is looked up per resolve,
    /// so a sweep deleting it mid-session honestly turns the next re-resolve into `.unavailable`.
    let itemId: String
    /// `AppContainer.offlineBase` in production — the same value `makeOfflineManager` writes
    /// under; tests point it at a temp directory. No default on purpose: the compiler forces
    /// every construction seam to pass the manager's base, so the two can never drift apart.
    let base: URL

    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool,
                 requiresMuxed: Bool) async throws -> Resolved {
        guard let item = store.item(id: itemId), let localPath = item.localPath else {
            throw ExtractionError.unavailable(videoId: videoId)
        }
        let localURL = OfflineStorage.fileURL(relativePath: localPath, base: base)
        guard FileManager.default.fileExists(atPath: localURL.path()) else {
            throw ExtractionError.unavailable(videoId: videoId)
        }
        let stream: ResolvedStream
        switch OfflineFileKind(rawValue: localURL.pathExtension) {
        case .movpkg:
            // The HLS engine's on-disk shape (dormant until hardware proves it, Task 4).
            // `.visionos` because that is the client family whose HLS shape this app plays.
            stream = .hls(url: localURL, isLive: false, audioOnlyURL: nil, captionTracks: [])
        case .mp4, .m4a:
            // An audio-only m4a rides `.progressive` too: the player renders no video for it
            // (acceptable — the metadata area carries the screen, note 5).
            stream = .progressive(url: localURL, label: item.qualityLabel)
        case nil:
            throw ExtractionError.unavailable(videoId: videoId)
        }
        return Resolved(stream: stream, client: .visionos, userAgent: "",
                        resolvedAt: Date(), expiresAt: nil)
    }
}
