import Foundation

/// The three on-disk shapes a save can land as: HLS movpkg (engine dormant until hardware
/// proves it — `OfflineEngineSupport`), progressive itag 18 video, itag 140 audio-only.
nonisolated enum OfflineFileKind: String, Sendable {
    case movpkg, mp4, m4a
}

/// File layout + storage math, pure. The manager (Task 4) owns the actual `FileManager`
/// calls, directory creation, and `isExcludedFromBackup` (pinned in Task 7).
enum OfflineStorage {
    /// `Application Support/offline/` — owner ruling: app-container storage only.
    nonisolated static func directoryURL(base: URL) -> URL {
        base.appending(path: "offline", directoryHint: .isDirectory)
    }

    /// The stored `OfflineItem.localPath` is exactly this relative name, never absolute
    /// (the container path changes across reinstalls).
    nonisolated static func fileName(itemId: String, kind: OfflineFileKind) -> String {
        "\(itemId).\(kind.rawValue)"
    }

    nonisolated static func fileURL(relativePath: String, base: URL) -> URL {
        directoryURL(base: base).appending(path: relativePath)
    }

    /// Per-item accounting for the footer. No quota — device storage is the natural limit
    /// (`DownloadStorage.kt:61-67`).
    static func usedBytes(items: [OfflineItem]) -> Int64 {
        items.reduce(0) { $0 + $1.bytesWritten }
    }

    /// The Saved screen's storage footer — "%lld saved • %@ used • %@ available", digits and
    /// byte units localized (RTL-safe numerals via `Format.localizedFormat`).
    static func footer(count: Int, used: Int64, available: Int64, locale: Locale) -> String {
        Format.localizedFormat("offline_footer_format", locale: locale, Int64(count),
                               byteText(used, locale: locale), byteText(available, locale: locale))
    }

    nonisolated static func byteText(_ bytes: Int64, locale: Locale) -> String {
        bytes.formatted(.byteCount(style: .file).locale(locale))
    }
}
