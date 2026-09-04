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
        let wiper = LocalAccountWiper(offline: offline, offlineStore: offlineStore,
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

        await fixture.wiper.wipe()

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

        await fixture.wiper.wipe()

        #expect(fixture.count(FavoriteVideo.self) == 0)
        #expect(fixture.count(SavedPlaylist.self) == 0)
        #expect(fixture.count(SubscribedChannel.self) == 0)
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

        await fixture.wiper.wipe()

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

        await fixture.wiper.wipe()

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

        await fixture.wiper.wipe()

        #expect(fixture.defaults.data(forKey: feedKey) == nil, "the deleted account's cached feed survived")
        #expect(fixture.defaults.data(forKey: stateKey) == nil,
                "the key naming a channel the deleted account subscribed to survived")
        #expect(fixture.defaults.object(forKey: cooldownKey) == nil, "the uid's verification latch survived")
        #expect(fixture.defaults.string(forKey: "settings_theme") == "dark",
                "the sweep took a key that has nothing to do with the account")
    }

    /// CF-A-9: the persisted `X-Device-Id` is what ties this install's public traffic together, so
    /// it goes with the account and the next request mints a new one (`LocalAccountDataWiper.kt:48-51`).
    @Test func theDeviceIdIsForgottenSoTheNextRequestMintsANewOne() async throws {
        let fixture = makeFixture(); defer { fixture.tearDown() }
        let before = DeviceId.persisted(in: fixture.defaults)
        #expect(fixture.defaults.string(forKey: DeviceId.defaultsKey) == before.value)

        await fixture.wiper.wipe()

        #expect(fixture.defaults.string(forKey: DeviceId.defaultsKey) == nil)
        #expect(DeviceId.persisted(in: fixture.defaults).value != before.value)
    }
}
