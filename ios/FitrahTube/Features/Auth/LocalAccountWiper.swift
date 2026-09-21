import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftData

/// Ruling C13's device wipe: everything this device still holds for an account that has gone away.
/// `wipe(unlessTakenOver:)` is reached from two places, both in `AccountSession`:
/// `handleDeletion()` — so the admin-side 403 `ACCOUNT_DELETED` envelope and the user's own
/// successful `DELETE /api/account/me` share it, and its latch keeps either from running it twice
/// — and `resumePendingDeletion()`, which redeems an interrupted one at launch. Both fall back to
/// the uid-scoped `wipeRows(of:)` whenever the device is not provably the departed account's.
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
    ///
    /// Returns the FIRST error it hit, or nil when everything went (Stage 5 / C2.2). The three
    /// deletes and the save used to be `try?`-swallowed and every test ran against an infallible
    /// in-memory store, so on a full, corrupt or unavailable persistent store the rows survived
    /// while the app hid them, signed out and announced the account erased. The caller
    /// (`AccountSession.performDeletion`) keeps its durable marker set on a non-nil return, so the
    /// next launch tries again.
    ///
    /// It does NOT stop at the first error: the later steps (search history, the defaults sweep,
    /// the caches, the device id) are independent of SwiftData and must still run — a store that
    /// refused its deletes is no reason to leave the deleted account's search suggestions and
    /// device id behind.
    ///
    /// The ONE early return: `takenOver` is asked after the offline teardown — the last await —
    /// and when it answers true nothing below runs and a non-nil error is returned, so the caller
    /// keeps its marker and pays by uid. No default, for `wipeRows`'s reason in `AccountSession`:
    /// a call site that forgot the check would compile into a silent "never taken over".
    @discardableResult
    func wipe(unlessTakenOver takenOver: () -> Bool) async -> Error? {
        var firstError: Error?
        // 1-2. CF-G-4. A save still running while the rest of this executes is a race with the
        //      filesystem, so the work stops before its files go — and both steps route through the
        //      manager, which owns every unlink this app performs.
        await offline.cancelAll()
        // R7-P2: the offline rows are the FIRST thing this deletes and were the one step that could
        // not report a failure, so a full or corrupt store left the saved library on disk while the
        // caller cleared its durable marker and announced the account erased.
        firstError = await offline.deleteAll(offlineStore.items.map(\.id))

        // CF-A-55 (c): the LAST await is above and nothing below suspends, so the answer cannot go
        // stale before the deletes — the caller's own check is two awaits old by now, and an
        // account that signed in, bound and pulled inside them lost its rows, cursors and binding
        // here. `CancellationError` as in `AppContainer`'s released-container arm: the work did
        // not run. (The offline library above is already gone — `OfflineItem` has no owner, CF-A-50.)
        if takenOver() { return firstError ?? CancellationError() }

        // 3. Every row, ALL userIds: this is a DEVICE wipe, not a per-user one. Android scopes its
        //    deletes to the signed-in uid, which leaves a previous account's library on a device
        //    whose owner has just erased theirs. A batch delete through a context of our own, so it
        //    does not depend on which store happens to be scoped to what.
        let context = ModelContext(modelContainer)
        func attempt(_ work: () throws -> Void) {
            do { try work() } catch { firstError = firstError ?? error }
        }
        attempt { try context.delete(model: FavoriteVideo.self) }
        attempt { try context.delete(model: SavedPlaylist.self) }
        attempt { try context.delete(model: SubscribedChannel.self) }
        // Task 23: the sync bookkeeping is per-account state too. Left behind, the next person to
        // sign in on this device inherits the deleted account's cursors — `bind` reads a binding
        // for a uid that no longer exists and a `SyncState` whose `lastCursor` is already past
        // every row the new account has, so their first pull returns nothing.
        attempt { try context.delete(model: SyncState.self) }
        attempt { try context.delete(model: AccountBinding.self) }
        attempt { try context.save() }
        // Those deletes went through a different context, so every store still holds the objects it
        // last fetched — SwiftUI would keep rendering rows whose backing model no longer exists.
        // Re-scoping to the anon sentinel is what makes each of them re-read (`UserScoped`), and it
        // is the correct end state anyway: this device now has no account.
        for store in stores { store.currentUserId = "" }

        // 4. CF-G-6.
        searchHistory.clear()

        // 4b. Fix round 1 / I1 + M6. One key PER SUBSCRIBED CHANNEL under each of the first two
        //     prefixes, so their NAMES alone enumerate what the deleted account followed — and the
        //     Atom blobs hold the titles, ids and dates of its feed. The third records that an
        //     account with that uid was verified from this device. All three land in this same
        //     suite (`AppContainer` builds every `UserDefaultsKeyValueStore` on it), so ONE sweep
        //     covers them; the prefixes are the constants their own writers spell.
        let prefixes = [AtomFeedFetcher.cacheKeyPrefix, MeFeedRepository.stateKeyPrefix,
                        EmailVerificationViewModel.lastSentKeyPrefix]
        for key in defaults.dictionaryRepresentation().keys
        where prefixes.contains(where: key.hasPrefix) {
            defaults.removeObject(forKey: key)
        }

        // 5. The response cache and the decoded-thumbnail cache: an avatar or thumbnail loaded for
        //    the deleted account's library must not survive it in memory.
        URLCache.shared.removeAllCachedResponses()
        RemoteImage.purge()

        // 6. CF-A-9: the persisted `X-Device-Id` is what ties this install's public traffic
        //    together, so it goes too and the next request mints a new one
        //    (`LocalAccountDataWiper.kt:48-51`).
        defaults.removeObject(forKey: DeviceId.defaultsKey)

        return firstError
    }

    /// The NARROW counterpart, for a wipe owed to an account that no longer holds this device
    /// (`AccountSession.resumePendingDeletion`'s by-uid arm, review I3): that account's own rows
    /// in FOUR per-user models, and nothing else. Every other step of `wipe()` above is
    /// device-wide — the search history, the defaults sweep, the caches, the device id and the
    /// offline library (`OfflineItem` carries no owner) now belong to whoever has used the device
    /// since — and `stores` are left alone because re-scoping them would un-scope that account.
    ///
    /// NOT `AccountBinding` (round 3 / item 2). The binding is what makes the next account's first
    /// bind a `.switchAccount`, which tags the guest-era (`""`) rows to the PREVIOUS uid and deletes
    /// them; with no binding `SyncDecisions.bind` answers `.merge`, which tags them to the NEW uid
    /// and pushes — the guest-era library uploaded into the next account. `wipe()` may take the
    /// binding because it takes every row, anonymous ones included; this leaves them behind.
    ///
    /// Returns the error it hit, so the caller keeps its marker and retries. UNLIKE `wipe()` it is
    /// one `do` block and STOPS at the first throw rather than attempting every step. It is NOT
    /// atomic: `delete(model:where:)` is a batch delete against the STORE — probed, it is visible
    /// to a fresh context before `save()` and `rollback()` does not undo it — so a throw part-way
    /// leaves the earlier models' rows already gone. Every step is a delete, so the retry the kept
    /// marker buys is idempotent.
    func wipeRows(of uid: String) -> Error? {
        // `""` is the GUEST's scope, not "nobody": THIS arm never deletes the guest's rows for a
        // marker that names no account (a build before Stage 7 fix 2 / M2 could store one). It
        // claims nothing about the device-wide arm, which such a marker can still reach.
        guard !uid.isEmpty else { return nil }
        // The one `UserDefaults` key that NAMES this account (step 4b's third prefix, for this uid
        // only — the sweep itself is device-wide). Independent of SwiftData, so it goes first.
        defaults.removeObject(forKey: EmailVerificationViewModel.lastSentKey(uid: uid))
        let context = ModelContext(modelContainer)
        do {
            try context.delete(model: FavoriteVideo.self, where: #Predicate { $0.userId == uid })
            try context.delete(model: SavedPlaylist.self, where: #Predicate { $0.userId == uid })
            try context.delete(model: SubscribedChannel.self, where: #Predicate { $0.userId == uid })
            try context.delete(model: SyncState.self, where: #Predicate { $0.userId == uid })
            try context.save()
            return nil
        } catch {
            return error
        }
    }
}
