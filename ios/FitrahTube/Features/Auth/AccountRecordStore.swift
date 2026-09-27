import Foundation

/// The last `/me` record this device saw, so an offline launch routes on the last known status
/// (owner 2026-09-24). It holds PII (email, date of birth, phone), so it is a file in Application
/// Support, never `UserDefaults`: protected until first unlock (the Keychain class Firebase's own
/// session uses, so a background relaunch reads both or neither) and excluded from backups. One
/// record, stamped with the uid it belongs to; any other uid reads nothing.
@MainActor final class AccountRecordStore {
    private struct Stored: Codable {
        let uid: String
        let me: AccountMe
    }

    private let url: URL

    init(url: URL) { self.url = url }

    func load(uid: String) -> AccountMe? {
        guard let data = try? Data(contentsOf: url),
              let stored = try? JSONDecoder().decode(Stored.self, from: data), stored.uid == uid else { return nil }
        return stored.me
    }

    func save(_ me: AccountMe, uid: String) {
        // ponytail: a failed write only costs the next offline launch its record (the wall).
        guard let data = try? JSONEncoder().encode(Stored(uid: uid, me: me)) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var url = url
        try? url.setResourceValues(excluded)
    }

    /// nil removes whatever is there. A uid spares only a record that provably names another
    /// account: one that cannot be read or decoded (an older format) goes too, never left as PII.
    func clear(uid: String? = nil) {
        if let uid, let data = try? Data(contentsOf: url),
           let stored = try? JSONDecoder().decode(Stored.self, from: data), stored.uid != uid { return }
        try? FileManager.default.removeItem(at: url)
    }
}
