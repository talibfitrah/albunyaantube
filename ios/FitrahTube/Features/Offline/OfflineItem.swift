import Foundation
import SwiftData

/// Spec §11's download status set, verbatim (Android `DownloadStatus` parity). Stored on
/// `OfflineItem.status` as the raw string — SwiftData-friendly, and an unknown raw from a
/// future version reads as nil instead of trapping.
nonisolated enum OfflineStatus: String, CaseIterable, Sendable {
    case queued, running, paused, completed, failed, cancelled
}

/// One saved copy of one video (Phase 3 Task 3; spec §11 item shape + `resumeData`).
///
/// `#Unique` on `videoId` alone: ONE saved copy per video — a re-save at a different quality
/// upserts over the old row. Deviation from Android's `playlistId|quality|videoId` dedupe,
/// which exists for the bulk playlist saves this phase defers (fork F).
@Model final class OfflineItem {
    #Unique<OfflineItem>([\.videoId])

    /// UUID string — the background task re-attach key (`taskDescription`, Task 4) and the
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
