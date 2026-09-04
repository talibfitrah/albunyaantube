import FitrahAPI
import Foundation
import SwiftData

/// Ruling C13's device wipe: everything this device still holds for an account that has gone away.
/// Reached from exactly one place — `AccountSession.handleDeletion()` — so the admin-side 403
/// `ACCOUNT_DELETED` envelope and the user's own successful `DELETE /api/account/me` share it, and
/// neither can run it twice.
///
/// Two of the three Android defects the ruling names are refused here (the third, CF-G-5, is the
/// caller's):
///   * **CF-G-4** — `LocalAccountDataWiper.kt` starts deleting while the download worker is still
///     writing into the directory it is deleting. Steps 1-2 stop that work FIRST, and go through
///     the manager (Phase 3's Global Constraint: files are only ever removed through it).
///   * **CF-G-6** — Android never clears the search history, so the next person to use the device
///     is offered the deleted account's queries as suggestions. Step 4.
///
/// It takes the store PROTOCOLS, never the concrete classes, so `LocalAccountWiperTests` runs on a
/// spy manager and an in-memory container with no Firebase, no network and no files.
@MainActor struct LocalAccountWiper {
    private let offline: any OfflineSaving
    private let offlineStore: OfflineStore
    private let stores: [any UserScoped]
    private let modelContainer: ModelContainer
    private let searchHistory: any SearchHistoryStore
    private let defaults: UserDefaults

    init(offline: any OfflineSaving, offlineStore: OfflineStore, stores: [any UserScoped],
         modelContainer: ModelContainer, searchHistory: any SearchHistoryStore, defaults: UserDefaults) {
        self.offline = offline
        self.offlineStore = offlineStore
        self.stores = stores
        self.modelContainer = modelContainer
        self.searchHistory = searchHistory
        self.defaults = defaults
    }

    /// Ruling C13, in this ORDER.
    func wipe() async {
        // 1-2. CF-G-4. A save still running while the rest of this executes is a race with the
        //      filesystem, so the work stops before its files go — and both steps route through the
        //      manager, which owns every unlink this app performs.
        await offline.cancelAll()
        await offline.deleteAll(offlineStore.items.map(\.id))

        // 3. Every row, ALL userIds: this is a DEVICE wipe, not a per-user one. Android scopes its
        //    deletes to the signed-in uid, which leaves a previous account's library on a device
        //    whose owner has just erased theirs. A batch delete through a context of our own, so it
        //    does not depend on which store happens to be scoped to what.
        let context = ModelContext(modelContainer)
        try? context.delete(model: FavoriteVideo.self)
        try? context.delete(model: SavedPlaylist.self)
        try? context.delete(model: SubscribedChannel.self)
        try? context.save()
        // Those deletes went through a different context, so every store still holds the objects it
        // last fetched — SwiftUI would keep rendering rows whose backing model no longer exists.
        // Re-scoping to the anon sentinel is what makes each of them re-read (`UserScoped`), and it
        // is the correct end state anyway: this device now has no account.
        for store in stores { store.currentUserId = "" }

        // 4. CF-G-6.
        searchHistory.clear()

        // 5. The response cache and the decoded-thumbnail cache: an avatar or thumbnail loaded for
        //    the deleted account's library must not survive it in memory.
        URLCache.shared.removeAllCachedResponses()
        RemoteImage.purge()

        // 6. CF-A-9: the persisted `X-Device-Id` is what ties this install's public traffic
        //    together, so it goes too and the next request mints a new one
        //    (`LocalAccountDataWiper.kt:48-51`).
        defaults.removeObject(forKey: DeviceId.defaultsKey)
    }
}
