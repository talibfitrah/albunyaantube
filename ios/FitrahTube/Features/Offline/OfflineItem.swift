import Foundation
import SwiftData

/// Spec §11's download status set, verbatim (Android `DownloadStatus` parity). Stored on
/// `OfflineItem.status` as the raw string — SwiftData-friendly, and an unknown raw from a
/// future version reads as nil instead of trapping.
nonisolated enum OfflineStatus: String, CaseIterable, Sendable {
    case queued, running, paused, completed, failed, cancelled
}

/// `OfflineItem` was declared inside the version that introduced it (`7471ea99`); V5 aliases that
/// FROZEN shape. V6 (CF-A-50, Task 41) re-declares it below with an owner column, V7 (offline review
/// P1) with a channel column, and `OfflineItem` at file scope is the V7 type (`FavoriteVideo.swift`).
extension FavoritesSchemaV4 {
    /// One saved copy of one video (Phase 3 Task 3; spec §11 item shape + `resumeData`).
    ///
    /// `#Unique` on `videoId` alone: ONE saved copy per video — a re-save at a different quality
    /// upserts over the old row. Deviation from Android's `playlistId|quality|videoId` dedupe,
    /// which exists for the bulk playlist saves this phase defers (fork F).
    @Model final class OfflineItem {
        #Unique<OfflineItem>([\.videoId])

        /// UUID string — the background task re-attach key (the `taskDescription` stem, Task 4) and the
        /// saved file's name stem (`OfflineStorage.fileName`). Not SwiftData's `persistentModelID`:
        /// this one is stable across store rebuilds and legal inside a `taskDescription`.
        var id: String
        var videoId: String
        var title: String
        var channelName: String?
        var thumbnailUrl: String?
        var qualityLabel: String
        var audioOnly: Bool
        /// Raw of `OfflineStatus`.
        var status: String
        var bytesWritten: Int64
        var totalBytes: Int64?
        /// Raw of Task 4's error-code set (`DownloadErrorCode.kt:12-39` minus the FFmpeg codes).
        var errorCode: String?
        /// Relative to `OfflineStorage.directoryURL(base:)` — never absolute; the app container
        /// path changes across reinstalls and updates.
        var localPath: String?
        /// `URLSessionDownloadTask` resume data captured on pause/failure (Task 4).
        var resumeData: Data?
        var createdAt: Date
        var completedAt: Date?

        init(videoId: String, title: String, channelName: String?, thumbnailUrl: String?,
             qualityLabel: String, audioOnly: Bool, id: String = UUID().uuidString,
             status: String = OfflineStatus.queued.rawValue, bytesWritten: Int64 = 0,
             totalBytes: Int64? = nil, errorCode: String? = nil, localPath: String? = nil,
             resumeData: Data? = nil, createdAt: Date = Date(), completedAt: Date? = nil) {
            self.videoId = videoId
            self.title = title
            self.channelName = channelName
            self.thumbnailUrl = thumbnailUrl
            self.qualityLabel = qualityLabel
            self.audioOnly = audioOnly
            self.id = id
            self.status = status
            self.bytesWritten = bytesWritten
            self.totalBytes = totalBytes
            self.errorCode = errorCode
            self.localPath = localPath
            self.resumeData = resumeData
            self.createdAt = createdAt
            self.completedAt = completedAt
        }
    }
}

/// CF-A-50 (Task 41): the V4 shape plus `userId`, the OWNER — the account signed in when the save
/// was made, `""` for the guest (the `FavoriteVideo` convention). Ownership is for DELETION only:
/// `LocalAccountWiper.wipeRows(of:)` pays a departed account's offline debt by uid, rows AND
/// files. The Saved library stays device-wide (`OfflineStore.items`), and `#Unique` stays on
/// `videoId` alone — one saved copy per video, whoever saved it. Rows written before V6 migrate
/// with `userId == ""`: nothing recorded who saved them, so they are the guest's.
extension FavoritesSchemaV6 {
    @Model final class OfflineItem {
        #Unique<OfflineItem>([\.videoId])

        var id: String
        var videoId: String
        var title: String
        var channelName: String?
        var thumbnailUrl: String?
        var qualityLabel: String
        var audioOnly: Bool
        var status: String
        var bytesWritten: Int64
        var totalBytes: Int64?
        var errorCode: String?
        var localPath: String?
        var resumeData: Data?
        var createdAt: Date
        var completedAt: Date?
        /// The PROPERTY initializer is the migration default: without it the V5 -> V6 stage is
        /// refused (`NSCocoaErrorDomain 134110`, "missing attribute values on mandatory destination
        /// attribute") and `makeModelContainer`'s recovery path rebuilds the store empty. The V5
        /// columns (`SavedPlaylist.playlistUrl` …) carry theirs the same way.
        var userId: String = ""

        init(videoId: String, title: String, channelName: String?, thumbnailUrl: String?,
             qualityLabel: String, audioOnly: Bool, id: String = UUID().uuidString,
             status: String = OfflineStatus.queued.rawValue, bytesWritten: Int64 = 0,
             totalBytes: Int64? = nil, errorCode: String? = nil, localPath: String? = nil,
             resumeData: Data? = nil, createdAt: Date = Date(), completedAt: Date? = nil,
             userId: String = "") {
            self.videoId = videoId
            self.title = title
            self.channelName = channelName
            self.thumbnailUrl = thumbnailUrl
            self.qualityLabel = qualityLabel
            self.audioOnly = audioOnly
            self.id = id
            self.status = status
            self.bytesWritten = bytesWritten
            self.totalBytes = totalBytes
            self.errorCode = errorCode
            self.localPath = localPath
            self.resumeData = resumeData
            self.createdAt = createdAt
            self.completedAt = completedAt
            self.userId = userId
        }
    }
}

/// Offline review P1: the V6 shape plus `channelId` — the channel playback's own availability check
/// asks about for this video (`PlayerArgs.channelId`), stamped at save time so `begin`, `retry` and
/// the sweep ask the gate the same question the Save button did. Rows written before V7 migrate with
/// nil: nothing recorded it, so they keep the video-only check.
extension FavoritesSchemaV7 {
    @Model final class OfflineItem {
        #Unique<OfflineItem>([\.videoId])

        var id: String
        var videoId: String
        var title: String
        var channelName: String?
        var thumbnailUrl: String?
        var qualityLabel: String
        var audioOnly: Bool
        var status: String
        var bytesWritten: Int64
        var totalBytes: Int64?
        var errorCode: String?
        var localPath: String?
        var resumeData: Data?
        var createdAt: Date
        var completedAt: Date?
        var userId: String = ""
        /// The PROPERTY initializer is the migration default, exactly as `userId`'s is at V6: the
        /// V6 -> V7 stage needs it (`NSCocoaErrorDomain 134110` otherwise).
        var channelId: String? = nil

        init(videoId: String, title: String, channelName: String?, thumbnailUrl: String?,
             qualityLabel: String, audioOnly: Bool, id: String = UUID().uuidString,
             status: String = OfflineStatus.queued.rawValue, bytesWritten: Int64 = 0,
             totalBytes: Int64? = nil, errorCode: String? = nil, localPath: String? = nil,
             resumeData: Data? = nil, createdAt: Date = Date(), completedAt: Date? = nil,
             userId: String = "", channelId: String? = nil) {
            self.videoId = videoId
            self.title = title
            self.channelName = channelName
            self.thumbnailUrl = thumbnailUrl
            self.qualityLabel = qualityLabel
            self.audioOnly = audioOnly
            self.id = id
            self.status = status
            self.bytesWritten = bytesWritten
            self.totalBytes = totalBytes
            self.errorCode = errorCode
            self.localPath = localPath
            self.resumeData = resumeData
            self.createdAt = createdAt
            self.completedAt = completedAt
            self.userId = userId
            self.channelId = channelId
        }
    }
}

extension OfflineItem {
    /// Clamped 0…1, nil while there is no known total to be determinate about. ONE clamp for the
    /// Saved row's bar and the player toolbar's ring (both rendered the same arithmetic).
    var progressFraction: Double? {
        guard let totalBytes, totalBytes > 0 else { return nil }
        return min(max(Double(bytesWritten) / Double(totalBytes), 0), 1)
    }
}
