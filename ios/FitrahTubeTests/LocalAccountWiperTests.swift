import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftData
import Synchronization
import Testing
@testable import FitrahTube

/// Ruling C13's device wipe. Two of the three Android defects the ruling names are pinned here —
/// the offline work is stopped and torn down BEFORE the local rows go (CF-G-4) and the search
/// history goes with them (CF-G-6, which Android's `LocalAccountDataWiper` misses entirely). The
/// third (CF-G-5, the detached cleanup) belongs to the caller and lives in `DeleteAccountTests`.
///
/// Fakes only: a `SpyOfflineManager` for the manager, an in-memory `ModelContainer` for the rows and
/// a per-test `UserDefaults` suite for the history and the device id. No files, no network, no clock.
@Suite(.perTest)
struct LocalAccountWiperTests {

    private struct Fixture {
        let wiper: LocalAccountWiper
        let container: ModelContainer
        let offline: SpyOfflineManager
        let offlineStore: OfflineStore
        let favorites: SwiftDataFavoritesStore
        let playlists: SwiftDataSavedPlaylistsStore
        let subscriptions: SwiftDataSubscriptionsStore
        let searchHistory: UserDefaultsSearchHistoryStore
        let defaults: UserDefaults
        let suiteName: String

        func tearDown() { defaults.removePersistentDomain(forName: suiteName) }

        /// Through a FRESH context every time: what actually persisted, never in-memory state.
        func count<T: PersistentModel>(_ type: T.Type) -> Int {
            (try? ModelContext(container).fetchCount(FetchDescriptor<T>())) ?? -1
        }

        /// WHOSE rows persisted, sorted — a count cannot tell "A's row went" from "B's row went".
        func owners<T: PersistentModel>(_ type: T.Type, _ userId: KeyPath<T, String>) -> [String] {
            ((try? ModelContext(container).fetch(FetchDescriptor<T>())) ?? []).map { $0[keyPath: userId] }.sorted()
        }
    }

    /// Two accounts' worth of rows. The wipe is a DEVICE wipe, not a per-user one, so the second
    /// uid is the point: Android scopes its deletes to the signed-in user and leaves the rest.
    private func makeFixture() -> Fixture {
        let suiteName = "LocalAccountWiperTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        let container = AppContainer.makeModelContainer(inMemory: true)
        let offline = SpyOfflineManager()
        let offlineStore = OfflineStore(modelContainer: container)
        let favorites = SwiftDataFavoritesStore(modelContainer: container)
        let playlists = SwiftDataSavedPlaylistsStore(modelContainer: container)
        let subscriptions = SwiftDataSubscriptionsStore(modelContainer: container)
        let searchHistory = UserDefaultsSearchHistoryStore(defaults: defaults)
        let wiper = LocalAccountWiper(offline: offline,
                                      stores: [favorites, playlists, subscriptions],
                                      modelContainer: container, searchHistory: searchHistory,
                                      defaults: defaults)
        return Fixture(wiper: wiper, container: container, offline: offline, offlineStore: offlineStore,
                       favorites: favorites, playlists: playlists, subscriptions: subscriptions,
                       searchHistory: searchHistory, defaults: defaults, suiteName: suiteName)
    }

    /// One row per entity per uid, written through a context of their own so the stores' contexts
    /// have to re-read to see them — the same asymmetry the wipe itself has.
    private func seedRows(_ fixture: Fixture, uids: [String] = ["fake-uid", "someone-else"]) throws {
        let context = ModelContext(fixture.container)
        for uid in uids {
            context.insert(FavoriteVideo(videoId: "xc7keR2piUM-\(uid)", title: "Lecture", channelName: "Alafasy",
                                         thumbnailUrl: nil, durationSeconds: 600, userId: uid))
            context.insert(SavedPlaylist(playlistId: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc-\(uid)", title: "Series",
                                         thumbnailUrl: nil, itemCount: 12, userId: uid))
            context.insert(SubscribedChannel(channelId: "UCmMcOjsVehVlEOteyrhjI2Q-\(uid)", title: "Alafasy",
                                             avatarUrl: nil, userId: uid))
        }
        try context.save()
    }

    // MARK: - CF-G-4: the offline work stops before the wipe touches anything

    /// The wipe deletes the directory the download engine writes into, so a save still running while
    /// it does is a race with the filesystem — Android's wiper starts deleting with the worker live.
    /// Steps 1-2 come first and go through the MANAGER (files AND rows), never `FileManager`.
    @Test func theOfflineWorkIsCancelledAndTornDownBeforeAnyLocalRowIsTouched() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture)
        let saved = makeOfflineItem("xc7keR2piUM")
        try fixture.offlineStore.insert(saved)
        // What the world still looked like at the moment the manager was asked to delete: if the
        // SwiftData clears had already run, this reads 0 instead of 2.
        let favoritesAtDeleteAll = Mutex<Int>(-1)
        let container = fixture.container
        await fixture.offline.setOnDeleteAll {
            let count = (try? ModelContext(container).fetchCount(FetchDescriptor<FavoriteVideo>())) ?? -1
            favoritesAtDeleteAll.withLock { $0 = count }
        }

        await fixture.wiper.wipe(unlessTakenOver: { false })

        #expect(await fixture.offline.calls == [Call(method: "cancelAll", id: ""),
                                                Call(method: "deleteAll", id: saved.id)],
                "cancel every save, then tear the whole batch down — in that order, through the manager")
        #expect(favoritesAtDeleteAll.withLock { $0 } == 2,
                "the local rows were cleared while a save was still being torn down")
    }

    // MARK: - The rows

    /// A DEVICE wipe: every favorite, saved playlist and subscription this device holds, whichever
    /// account they belong to. `LocalAccountDataWiper.kt` scopes its deletes to the signed-in uid,
    /// which leaves a previous account's library sitting on a device its owner has just erased.
    @Test func everySavedRowGoesIncludingOnesBelongingToADifferentAccount() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture)
        #expect(fixture.count(FavoriteVideo.self) == 2)

        await fixture.wiper.wipe(unlessTakenOver: { false })

        #expect(fixture.count(FavoriteVideo.self) == 0)
        #expect(fixture.count(SavedPlaylist.self) == 0)
        #expect(fixture.count(SubscribedChannel.self) == 0)
    }

    /// Task 23: the sync bookkeeping is per-account state and goes with the rest. Left behind, the
    /// next person to sign in on this device inherits the deleted account's cursors — a `SyncState`
    /// whose `lastCursor` is already past every row the new account has, so their first pull
    /// returns nothing and their library looks empty.
    @Test func theSyncCursorsAndTheAccountBindingGoWithTheRows() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        let context = ModelContext(fixture.container)
        context.insert(SyncState(entityType: "favorites", userId: "fake-uid", lastCursor: 1_700_000))
        context.insert(AccountBinding(userId: "fake-uid", initialMergeDone: true))
        try context.save()
        #expect(fixture.count(SyncState.self) == 1)

        await fixture.wiper.wipe(unlessTakenOver: { false })

        #expect(fixture.count(SyncState.self) == 0)
        #expect(fixture.count(AccountBinding.self) == 0)
    }

    // MARK: - CF-A-55 (c): a takeover inside the wipe's own awaits

    /// The caller's last takeover check is BEFORE `wipe()`, and the offline teardown is two awaits
    /// long: an account that signs in, binds and pulls inside it had its rows, its cursors and its
    /// binding deleted. The flag is raised from INSIDE the wiper's last await, so a check placed
    /// anywhere before that await reads false and this goes red — the position is the fact pinned.
    ///
    /// CF-A-55 (g): the abort skips ONLY the row step. The device-wide sweeps still run — the
    /// departed account's search history, the feed caches and per-channel state (their key names
    /// enumerate what it followed) and the device id used to pass to the newcomer permanently,
    /// because the debt the caller then pays (`wipeRows`) touches rows only. The newcomer's cost
    /// is seconds-old searches and a re-fetch; its own per-uid resend cooldown is NOT taken —
    /// that key names B, and A's own copy is `wipeRows`'s to remove.
    @Test func anAccountThatArrivesDuringTheOfflineTeardownKeepsItsRowsCursorsAndBindingButTheDeviceIsStillSwept() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture)
        let context = ModelContext(fixture.container)
        context.insert(SyncState(entityType: "favorites", userId: "someone-else", lastCursor: 1_700_000))
        context.insert(AccountBinding(userId: "someone-else", initialMergeDone: true))
        try context.save()
        fixture.searchHistory.add("tafsir")
        fixture.defaults.set("dev-1", forKey: DeviceId.defaultsKey)
        let feedKey = AtomFeedFetcher.cacheKeyPrefix + "UCmMcOjsVehVlEOteyrhjI2Q"
        let stateKey = MeFeedRepository.stateKey("UCmMcOjsVehVlEOteyrhjI2Q")
        let arrivedCooldownKey = EmailVerificationViewModel.lastSentKey(uid: "someone-else")
        fixture.defaults.set(Data(#"{"items":[]}"#.utf8), forKey: feedKey)
        fixture.defaults.set(Data("{}".utf8), forKey: stateKey)
        fixture.defaults.set(2.0, forKey: arrivedCooldownKey)
        fixture.defaults.set("dark", forKey: "settings_theme")
        fixture.favorites.currentUserId = "someone-else"
        let arrived = Mutex(false)
        await fixture.offline.setOnDeleteAll { arrived.withLock { $0 = true } }

        let error = await fixture.wiper.wipe(unlessTakenOver: { arrived.withLock { $0 } })

        #expect(error != nil, "a wipe that deleted nothing reported success, so its caller clears the marker")
        #expect(await fixture.offline.calls.map(\.method) == ["cancelAll", "deleteAll"],
                "the offline library has no owner and is gone by the time the check can be asked (CF-A-50)")
        #expect(fixture.count(FavoriteVideo.self) == 2)
        #expect(fixture.count(SavedPlaylist.self) == 2)
        #expect(fixture.count(SubscribedChannel.self) == 2)
        #expect(fixture.count(SyncState.self) == 1, "the arrived account's cursors were deleted")
        #expect(fixture.count(AccountBinding.self) == 1, "the arrived account's binding was deleted")
        #expect(fixture.favorites.currentUserId == "someone-else", "the arrived account's stores were re-scoped to the guest")
        // …and the sweeps still ran.
        #expect(fixture.searchHistory.entries.isEmpty, "the departed account's searches passed to the newcomer")
        #expect(fixture.defaults.data(forKey: feedKey) == nil, "the departed account's cached feed survived the abort")
        #expect(fixture.defaults.data(forKey: stateKey) == nil, "a key naming a channel the departed account followed survived")
        #expect(fixture.defaults.string(forKey: DeviceId.defaultsKey) == nil, "the departed account's device id passed to the newcomer")
        #expect(fixture.defaults.double(forKey: arrivedCooldownKey) == 2.0, "the abort-time sweep took the newcomer's own resend cooldown")
        #expect(fixture.defaults.string(forKey: "settings_theme") == "dark")
    }

    // MARK: - The uid-scoped delete (a marker owed to an account that no longer holds the device)

    /// Review I3. A's wipe failed, so its marker and its rows both survived; B has used the device
    /// since. The DEVICE wipe above can no longer run — it would take B's library. Dropping the
    /// marker instead left A's rows to `SyncManager.switchAccount`, which deletes the previous
    /// uid's rows on B's first bind — and strands them whenever that bind never runs or rolls back
    /// (B offline, B with no syncable uid). Every per-user row carries its owner, so the
    /// redemption is the durable backstop for that case: it deletes A's rows and ONLY A's — not B's, not the guest's (`""`), and none of the
    /// device-wide steps (the search history and the device id are B's and the guest's too).
    ///
    /// Both mismatch shapes: B signed in right now, and B signed out with nobody signed in.
    @Test(arguments: [false, true])
    func aMarkerOwedToAnAccountThatNoLongerHoldsTheDeviceDeletesOnlyThatAccountsRows(holderIsSignedIn: Bool) async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        let uids = ["uid-a", "uid-b", ""]
        try seedRows(fixture, uids: uids)
        let context = ModelContext(fixture.container)
        for uid in uids {
            context.insert(SyncState(entityType: "favorites", userId: uid, lastCursor: 1_700_000))
            context.insert(AccountBinding(userId: uid, initialMergeDone: true))
        }
        try context.save()
        fixture.searchHistory.add("tafsir")
        fixture.defaults.set("dev-1", forKey: DeviceId.defaultsKey)
        // Round 2 / item 4: the one `UserDefaults` key that NAMES an account. A's goes with A's
        // rows; B's is B's. (The prefix sweep that takes both is a device-wide step.)
        fixture.defaults.set(1.0, forKey: EmailVerificationViewModel.lastSentKey(uid: "uid-a"))
        fixture.defaults.set(2.0, forKey: EmailVerificationViewModel.lastSentKey(uid: "uid-b"))
        let marker = InMemoryDeletionMarker()
        marker.pendingUid = "uid-a"
        marker.lastSignedInUid = "uid-b"
        let holder = AuthUser(uid: "uid-b", email: "b@fitrah.test", isEmailVerified: true, providerIDs: ["password"])
        let deviceWipes = Mutex(0)
        let session = AccountSession(
            auth: FakeAuthClient(state: holderIsSignedIn ? .signedIn(holder) : .signedOut),
            account: AccountClient(transport: ScriptedTransport([]), baseURL: URL(string: "https://api.fitrah.test/")!,
                                   deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in },
            wipe: { _ in deviceWipes.withLock { $0 += 1 }; return nil },
            wipeRows: { [wiper = fixture.wiper] in await wiper.wipeRows(of: $0) }, marker: marker)

        await session.resumePendingDeletion()

        let survivors = ["", "uid-b"]
        #expect(fixture.owners(FavoriteVideo.self, \.userId) == survivors)
        #expect(fixture.owners(SavedPlaylist.self, \.userId) == survivors)
        #expect(fixture.owners(SubscribedChannel.self, \.userId) == survivors)
        #expect(fixture.owners(SyncState.self, \.userId) == survivors)
        // Round 3 / item 2: the binding is NOT A's row to take — see the test below.
        #expect(fixture.owners(AccountBinding.self, \.userId) == uids.sorted())
        #expect(deviceWipes.withLock { $0 } == 0, "a wipe owed to A erased the device of whoever held it since")
        #expect(fixture.searchHistory.entries == ["tafsir"], "the scoped delete ran a device-wide step")
        #expect(fixture.defaults.string(forKey: DeviceId.defaultsKey) == "dev-1")
        #expect(fixture.defaults.object(forKey: EmailVerificationViewModel.lastSentKey(uid: "uid-a")) == nil,
                "a key naming the deleted account outlived its rows")
        #expect(fixture.defaults.double(forKey: EmailVerificationViewModel.lastSentKey(uid: "uid-b")) == 2.0,
                "the scoped delete took a key belonging to the account that holds the device")
        #expect(marker.pendingUid == nil, "the debt was paid and the marker still claims it")
        #expect(marker.lastSignedInUid == "uid-b", "paying A's debt forgot who holds the device now")
    }

    /// Round 3 / item 2. The binding is what makes the NEXT account's first bind a
    /// `.switchAccount`, which tags the guest-era (`""`) rows to the PREVIOUS uid and deletes them.
    /// With no binding `SyncDecisions.bind` answers `.merge`, which tags those same rows to the NEW
    /// uid and pushes them — the guest-era library uploaded into B's server account, the transfer
    /// `SyncManager.switchAccount`'s own comment exists to prevent. The device wipe may take the
    /// binding because it takes every row, anonymous ones included; the scoped delete leaves the
    /// anonymous rows behind, so the binding is what still protects them.
    @Test func theScopedDeleteLeavesTheBindingSoTheNextAccountSwitchesRatherThanMerges() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture, uids: ["uid-a", ""])
        let context = ModelContext(fixture.container)
        context.insert(AccountBinding(userId: "uid-a", initialMergeDone: true))
        try context.save()

        #expect(await fixture.wiper.wipeRows(of: "uid-a") == nil)

        #expect(fixture.owners(FavoriteVideo.self, \.userId) == [""], "the positive control: A's rows did go")
        let binding = SyncStore.binding(fixture.container)
        #expect(binding?.userId == "uid-a")
        #expect(SyncDecisions.bind(binding: binding, uid: "uid-b") == .switchAccount(previousUid: "uid-a"),
                "B's first bind would MERGE the guest-era rows into B's account")
    }

    /// CF-A-50 (Task 41): the scoped delete pays the departed account's OFFLINE debt too — its
    /// rows AND files, through the manager (the only thing that unlinks) — and nobody else's.
    /// Before the owner column, A's downloads outlived A and sat in the next user's Saved library
    /// while the debt was booked as paid. Guest-owned (`""`) copies are not A's to take.
    @Test func theScopedDeleteTakesTheDepartedAccountsOfflineRowsAndFilesAndNobodyElses() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture, uids: ["uid-a", "uid-b"])
        let a = makeOfflineItem("xc7keR2piUM", userId: "uid-a")
        let b = makeOfflineItem("video-b", userId: "uid-b")
        let guest = makeOfflineItem("video-guest")
        for item in [a, b, guest] { try fixture.offlineStore.insert(item) }

        let error = await fixture.wiper.wipeRows(of: "uid-a")

        #expect(error == nil)
        #expect(await fixture.offline.calls == [Call(method: "deleteAll", id: a.id)],
                "A's saved file was not torn down through the manager, or somebody else's was")
        #expect(fixture.owners(FavoriteVideo.self, \.userId) == ["uid-b"], "the positive control: A's rows did go")
    }

    /// Task 41 review (MEDIUM): a fetch that THROWS must keep the debt. `try?`-swallowed, the ids
    /// read as none, the rows then deleted fine, `wipeRows` answered nil and the marker was
    /// redeemed — A's files on disk forever with nothing left to retry them. A throwing
    /// `ModelContext.fetch` is not injectable (the in-memory container never throws), so the
    /// provider is the seam: it throws, `wipeRows` reports it, and nothing is deleted.
    @Test func aScopedDeleteWhoseOfflineFetchThrowsReportsItAndPaysNothing() async throws {
        struct StoreFull: Error {}
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture, uids: ["uid-a"])
        let wiper = LocalAccountWiper(offline: fixture.offline,
                                      stores: [fixture.favorites], modelContainer: fixture.container,
                                      searchHistory: fixture.searchHistory, defaults: fixture.defaults,
                                      offlineIds: { _ in throw StoreFull() })

        let error = await wiper.wipeRows(of: "uid-a")

        #expect(error is StoreFull, "a failed offline fetch was swallowed and the debt booked as paid")
        #expect(await fixture.offline.calls.isEmpty, "the manager was asked to delete with no ids to delete")
        #expect(fixture.owners(FavoriteVideo.self, \.userId) == ["uid-a"], "the rows went while the offline half was never read — a retry then finds nothing to do")
    }

    /// Cubic r2 (P2): the DEVICE-WIDE arm has the same hole. It took its ids from
    /// `offlineStore.items`, which a failed fetch leaves EMPTY, so `deleteAll([])` succeeded, the
    /// wipe returned nil and the marker was redeemed with the departed account's files on disk.
    /// The same fail-closed id source as `wipeRows`, and the fetch error returns before ANY
    /// deletion; the takeover check keeps its place after the last await.
    ///
    /// Cubic r3 (P2): and the snapshot is taken AFTER `cancelAll()`, so a row a still-running save
    /// inserts inside that await cannot miss it. Cancelling first is free on this path: nothing is
    /// deleted, the caller keeps its marker, and the next launch retries the whole debt.
    @Test func aDeviceWipeWhoseOfflineFetchThrowsReportsItAndDeletesNothing() async throws {
        struct StoreFull: Error {}
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture)
        fixture.searchHistory.add("tafsir")
        let wiper = LocalAccountWiper(offline: fixture.offline,
                                      stores: [fixture.favorites], modelContainer: fixture.container,
                                      searchHistory: fixture.searchHistory, defaults: fixture.defaults,
                                      offlineIds: { _ in throw StoreFull() })

        let error = await wiper.wipe(unlessTakenOver: { false })

        #expect(error is StoreFull, "a failed offline fetch was swallowed and the device wipe booked as paid")
        #expect(await fixture.offline.calls == [Call(method: "cancelAll", id: "")],
                "the saves ran on past the fetch, or the manager was asked to delete with no ids to delete")
        #expect(fixture.count(FavoriteVideo.self) == 2, "rows went before the offline half was read")
        #expect(fixture.searchHistory.entries == ["tafsir"], "a sweep ran before the offline half was read")
    }

    /// `""` is not "nobody" here — it is the GUEST's scope, and `UserDefaultsDeletionMarker`'s
    /// getter reports a stored `""` as pending (a build before Stage 7 fix 2 / M2 could write one).
    /// Redeeming that by uid would erase the guest's library for a marker that names no account.
    @Test func theScopedDeleteNeverTakesTheGuestsRows() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture, uids: ["", "uid-b"])

        let error = await fixture.wiper.wipeRows(of: "")

        #expect(error == nil)
        #expect(fixture.owners(FavoriteVideo.self, \.userId) == ["", "uid-b"])
    }

    /// The rows are deleted through a context of the wiper's own, so every store still holds the
    /// objects it last fetched — SwiftUI would keep rendering rows whose backing model is gone.
    /// Re-scoping to the anon sentinel is what makes each store re-read.
    @Test func everyStoreReReadsSoNothingKeepsRenderingADeletedRow() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture)
        fixture.favorites.currentUserId = "fake-uid"
        fixture.playlists.currentUserId = "fake-uid"
        fixture.subscriptions.currentUserId = "fake-uid"
        #expect(fixture.favorites.items.count == 1)

        await fixture.wiper.wipe(unlessTakenOver: { false })

        #expect(fixture.favorites.items.isEmpty)
        #expect(fixture.playlists.items.isEmpty)
        #expect(fixture.subscriptions.items.isEmpty)
        #expect(fixture.favorites.currentUserId == "")
    }

    // MARK: - CF-G-6 and CF-A-9

    /// Android's wiper never touches the search history, so the next person to use the device is
    /// offered the deleted account's queries as suggestions.
    @Test func theSearchHistoryIsClearedToo() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        fixture.searchHistory.add("tafsir")
        fixture.searchHistory.add("seerah")
        #expect(fixture.searchHistory.entries.count == 2)

        await fixture.wiper.wipe(unlessTakenOver: { false })

        #expect(fixture.searchHistory.entries.isEmpty)
        #expect(fixture.defaults.stringArray(forKey: "search_history") == nil)
    }

    /// Fix round 1 / I1 + M6: the wipe cleared no `UserDefaults` key but the search history's, so
    /// every cached Atom feed (titles, ids, dates) and every per-channel refresh state survived a
    /// dialog that promises the account's subscriptions are erased — and the key NAMES alone
    /// enumerate exactly which channels it followed. The email cooldown latch (M6) is the third
    /// shape: uid-scoped, so it records that an account with that uid existed on this device.
    ///
    /// An unrelated key is seeded alongside them: a prefix sweep that takes the whole domain would
    /// be a different bug, not a fix.
    @Test func theCachedFeedsAndPerChannelStateGoTooAndNothingUnrelatedDoes() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        let channel = "UCmMcOjsVehVlEOteyrhjI2Q"
        let feedKey = AtomFeedFetcher.cacheKeyPrefix + channel
        let stateKey = MeFeedRepository.stateKey(channel)
        let cooldownKey = EmailVerificationViewModel.lastSentKey(uid: "fake-uid")
        fixture.defaults.set(Data(#"{"items":[]}"#.utf8), forKey: feedKey)
        fixture.defaults.set(Data("{}".utf8), forKey: stateKey)
        fixture.defaults.set(1.0, forKey: cooldownKey)
        fixture.defaults.set("dark", forKey: "settings_theme")

        await fixture.wiper.wipe(unlessTakenOver: { false })

        #expect(fixture.defaults.data(forKey: feedKey) == nil, "the deleted account's cached feed survived")
        #expect(fixture.defaults.data(forKey: stateKey) == nil,
                "the key naming a channel the deleted account subscribed to survived")
        #expect(fixture.defaults.object(forKey: cooldownKey) == nil, "the uid's verification latch survived")
        #expect(fixture.defaults.string(forKey: "settings_theme") == "dark",
                "the sweep took a key that has nothing to do with the account")
    }

    /// CF-A-9: the persisted `X-Device-Id` is what ties this install's public traffic together, so
    /// it goes with the account and the next request mints a new one (`LocalAccountDataWiper.kt:48-51`).
    ///
    /// Stage 3 / M5: asserted through the REAL request path. The shape this replaces re-called
    /// `DeviceId.persisted(in:)` itself, which is not what the app sends — every client captured
    /// its `DeviceId` at container construction and `value` was a stored `String`, so removing the
    /// defaults key changed nothing until relaunch and every request for the rest of the session
    /// still carried the deleted account's id. `DeviceId.value` now resolves per read, so the very
    /// next request off an ALREADY-BUILT client is the thing this asserts.
    @Test func theDeviceIdIsForgottenSoTheNextRequestMintsANewOne() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        let base = URL(string: "https://api.fitrah.test/")!
        let me = #"{"uid":"u","status":"active","role":"user"}"#
        let transport = ScriptedTransport([.json(200, me), .json(200, me)])
        // Built BEFORE the wipe, exactly as `AppContainer` builds it at launch.
        let client = AccountClient(transport: transport, baseURL: base,
                                   deviceId: .persisted(in: fixture.defaults))
        _ = try await client.me()
        let before = transport.sent.first?.headers["X-Device-Id"]
        #expect(before?.isEmpty == false)

        await fixture.wiper.wipe(unlessTakenOver: { false })
        _ = try await client.me()

        #expect(fixture.defaults.string(forKey: DeviceId.defaultsKey) != nil, "the next request re-minted")
        #expect(transport.sent.last?.headers["X-Device-Id"] != before,
                "the deleted account's traffic stayed linked for the rest of the session")
    }

    /// Stage 5 / C2.2: the four SwiftData calls were `try?`-swallowed and every test ran against an
    /// infallible in-memory store, so on a full or corrupt store the rows survived while the app
    /// hid them, signed out and announced the account erased. A healthy store still reports nil,
    /// and the later steps are unaffected — that is what the caller's marker keys off.
    @Test func aHealthyWipeReportsNoError() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture)

        let error = await fixture.wiper.wipe(unlessTakenOver: { false })

        #expect(error == nil)
    }

    /// R7-P2, the same class as C2.2 one layer out and the last swallowed step in this sequence.
    /// `OfflineManager.write` is `await MainActor.run { try? body(store) }` returning `Void`, so
    /// `deleteAll` could not report a row that refused to go — a full or corrupt store unlinked the
    /// FILES, kept the row, and still answered "everything went". `wipe()` then returned nil,
    /// `AccountSession.performDeletion` cleared `marker.pendingUid`, and no relaunch ever retried:
    /// the exact residue C2.2 exists to prevent, arriving through the one step C2.2 did not cover.
    ///
    /// The marker half is `DeleteAccountTests
    /// .aFailedWipeKeepsTheDeletionMarkerSoTheNextLaunchRetries` (a non-nil `wipe` return keeps
    /// `pendingUid`); what was missing is a non-nil return to give it.
    @Test func anOfflineRowThatRefusesToGoIsReportedSoTheMarkerSurvives() async throws {
        struct StoreFull: Error {}
        let fixture = makeFixture(); defer { fixture.tearDown() }
        try seedRows(fixture)
        try fixture.offlineStore.insert(makeOfflineItem("xc7keR2piUM"))
        await fixture.offline.setDeleteAllError(StoreFull())

        let error = await fixture.wiper.wipe(unlessTakenOver: { false })

        #expect(error is StoreFull,
                "the deletion marker was cleared while the saved rows were still on disk")
        // It does NOT stop at the first error: the independent later steps still run.
        #expect(fixture.searchHistory.entries.isEmpty)
        #expect(fixture.count(FavoriteVideo.self) == 0)
    }
}
