import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftData
import Testing
@testable import FitrahTube

/// Task 30 (fork F14): the two Me-tab surfaces that only become honest once an `AWAITING` row can
/// exist — the one-time import offer and the Content/Pending tabs.
///
/// No Firebase, no network, no clock: three SwiftData stores in memory and `FakeAuthClient`.
@MainActor
@Suite(.perTest)
struct MeAwaitingTabsTests {

    /// The approved fixture ids, and nothing else (dispatcher fixture rule).
    private enum Fixture {
        static let channel = "UCmMcOjsVehVlEOteyrhjI2Q"
        static let playlist = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
        static let video = "xc7keR2piUM"
    }

    private static let now = Date(timeIntervalSince1970: 1_756_800_000)

    private struct Stores {
        var favorites: SwiftDataFavoritesStore
        var subscriptions: SwiftDataSubscriptionsStore
        var playlists: SwiftDataSavedPlaylistsStore
    }

    private func stores(uid: String = "uid-1") throws -> Stores {
        let container = try ModelContainer(
            for: FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let favorites = SwiftDataFavoritesStore(modelContainer: container, onDirty: { _ in })
        let subscriptions = SwiftDataSubscriptionsStore(modelContainer: container, onDirty: { _ in })
        let playlists = SwiftDataSavedPlaylistsStore(modelContainer: container, onDirty: { _ in })
        for store in [favorites as any UserScoped, subscriptions, playlists] { store.currentUserId = uid }
        return Stores(favorites: favorites, subscriptions: subscriptions, playlists: playlists)
    }

    private func settings() -> UserDefaultsSettingsStore {
        UserDefaultsSettingsStore(
            defaults: UserDefaults(suiteName: "MeAwaitingTabsTests.\(UUID().uuidString)")!)
    }

    /// `MeViewModelTests.makeSession`'s shape — the same canned `/me` body and the same
    /// yield-until-loaded wait. A GUEST simply never signs in, so `session.user` stays nil.
    private func session(signedIn: Bool) async throws -> AccountSession {
        let auth = FakeAuthClient(state: .signedOut)
        let transport = ScriptedTransport([
            .json(200, #"{"uid":"u1","email":"a@b.test","status":"active","role":"user"}"#)
        ])
        let session = AccountSession(
            auth: auth,
            account: AccountClient(transport: transport,
                                   baseURL: URL(string: "https://api.test/")!,
                                   deviceId: DeviceId(value: "d1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { nil })
        guard signedIn else { return session }
        let running = Task { await session.start() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        for _ in 0..<500 where session.state.me == nil { await Task.yield() }
        running.cancel()
        return session
    }

    private func model(_ stores: Stores, session: AccountSession,
                       settings: any SettingsStore, canImport: Bool = true) -> MeViewModel {
        MeViewModel(session: session, favorites: stores.favorites,
                    subscriptions: stores.subscriptions, savedPlaylists: stores.playlists,
                    settings: settings, canImportFromYouTube: { canImport })
    }

    private func seedAwaiting(_ stores: Stores, channel: Bool = true, playlist: Bool = false,
                              video: Bool = false) throws {
        if channel {
            try stores.subscriptions.importChannel(id: Fixture.channel, title: "Alafasy",
                                                   avatarUrl: nil,
                                                   approvalStatus: ImportProvenance.awaiting,
                                                   at: Self.now)
        }
        if playlist {
            try stores.playlists.importPlaylist(id: Fixture.playlist, title: "Tafsir series",
                                                thumbnailUrl: nil, uploaderName: nil,
                                                approvalStatus: ImportProvenance.awaiting,
                                                at: Self.now)
        }
        if video {
            try stores.favorites.importVideo(id: Fixture.video, title: "Lecture", channelName: "",
                                             thumbnailUrl: nil, durationSeconds: 0,
                                             approvalStatus: ImportProvenance.awaiting, at: Self.now)
        }
    }

    // MARK: - Each store's awaiting rows

    /// The rows are scoped to `currentUserId`, skip tombstones, and are EMPTY for a store holding
    /// only APPROVED rows — which is the whole reason they cannot be derived from `items`, and
    /// equally the reason `items` cannot be derived from them.
    @Test func eachStoresAwaitingRowsAreScopedIgnoreTombstonesAndExcludeApproved() throws {
        let stores = try stores()
        try seedAwaiting(stores, channel: true, playlist: true, video: true)
        #expect(stores.subscriptions.awaitingItems.map(\.channelId) == [Fixture.channel])
        #expect(stores.playlists.awaitingItems.map(\.playlistId) == [Fixture.playlist])
        #expect(stores.favorites.awaitingItems.map(\.videoId) == [Fixture.video])
        // …and none of them is in `items`, which is fail-closed on `== "APPROVED"`.
        #expect(stores.subscriptions.items.isEmpty)
        #expect(stores.playlists.items.isEmpty)
        #expect(stores.favorites.items.isEmpty)

        // An APPROVED import is the other side of the same filter: in `items`, never awaiting.
        try stores.subscriptions.importChannel(id: "UCapproved00000000000001", title: "Approved",
                                               avatarUrl: nil,
                                               approvalStatus: ImportProvenance.approved, at: Self.now)
        #expect(stores.subscriptions.items.count == 1)
        #expect(stores.subscriptions.awaitingItems.count == 1, "the approved row is not awaiting")

        // A tombstone drops out of both.
        try stores.subscriptions.toggle(id: Fixture.channel, name: nil, avatarURL: nil)
        #expect(stores.subscriptions.awaitingItems.isEmpty)

        // Another user's rows are not this user's.
        stores.favorites.currentUserId = "uid-2"
        #expect(stores.favorites.awaitingItems.isEmpty)
        stores.favorites.currentUserId = "uid-1"
        #expect(stores.favorites.awaitingItems.count == 1)
    }

    /// The rows must be part of the same OBSERVATION `items` is, not a `fetchCount` on read: an
    /// import writes rows `items` does not contain, so if the count were computed lazily nothing
    /// observable would change and the Pending tab could never appear. Every write refreshes both.
    @Test func anImportWriteUpdatesTheAwaitingRowsWithoutAnyoneAskingForARefresh() throws {
        let stores = try stores()
        #expect(stores.favorites.awaitingItems.isEmpty)
        try seedAwaiting(stores, channel: false, video: true)
        #expect(stores.favorites.awaitingItems.count == 1, "the store refreshed itself")
        // Graduating the row to APPROVED moves it across, again with no explicit refresh.
        try stores.favorites.importVideo(id: Fixture.video, title: "Lecture", channelName: "Zad",
                                         thumbnailUrl: nil, durationSeconds: 0,
                                         approvalStatus: ImportProvenance.approved, at: Self.now)
        #expect(stores.favorites.awaitingItems.isEmpty)
        #expect(stores.favorites.items.count == 1)
    }

    // MARK: - The tabs

    /// `MeFragment.kt:352-368`: hidden ENTIRELY at zero, shown at one. With an empty queue a
    /// two-tab bar is permanent chrome over a screen with nothing to switch to.
    @Test func theTabBarIsAbsentAtZeroAwaitingRowsAndPresentAtOne() async throws {
        let stores = try stores()
        let model = model(stores, session: try await session(signedIn: true), settings: settings())
        #expect(model.awaitingCount == 0)
        #expect(model.showsTabs == false)

        try seedAwaiting(stores)
        #expect(model.awaitingCount == 1)
        #expect(model.showsTabs)
    }

    /// The count is the SUM of the three stores, and the rows are channels → playlists → videos,
    /// `AwaitingImportsAdapter.buildRows`' order (the import review screen's grouping).
    @Test func theCountIsTheSumAndTheRowsAreGroupedChannelsPlaylistsVideos() async throws {
        let stores = try stores()
        let model = model(stores, session: try await session(signedIn: true), settings: settings())
        try seedAwaiting(stores, channel: true, playlist: true, video: true)
        #expect(model.awaitingCount == 3)
        #expect(model.awaitingItems.map(\.id) == [Fixture.channel, Fixture.playlist, Fixture.video])
    }

    /// `MeFragment.kt:355-358`: when the last pending item clears while the user is on that tab,
    /// they fall back to Content rather than sitting on a list that no longer exists.
    @Test func theSelectionFallsBackToContentWhenTheQueueEmpties() async throws {
        let stores = try stores()
        let model = model(stores, session: try await session(signedIn: true), settings: settings())
        try seedAwaiting(stores)
        model.select(tab: .pending)
        #expect(model.selectedTab == .pending)

        try stores.subscriptions.toggle(id: Fixture.channel, name: nil, avatarURL: nil)
        #expect(model.awaitingCount == 0)
        #expect(model.showsTabs == false)
        #expect(model.selectedTab == .content, "an unreachable tab cannot stay selected")
    }

    /// `MeFragment.kt:387-406`. Repeated same-value selection is stable and a real switch lands;
    /// the one-line guard in `select(tab:)` saves an invalidation and pins nothing more (Task 30
    /// measured it vacuous — SwiftUI has no adapter swap; Part B gate ruling a).
    @Test func aSameTabReassignmentIsStableAndARealSwitchLands() async throws {
        let stores = try stores()
        let model = model(stores, session: try await session(signedIn: true), settings: settings())
        try seedAwaiting(stores)

        model.select(tab: .pending)
        model.select(tab: .pending)
        #expect(model.selectedTab == .pending, "repeated selection is stable")
        model.select(tab: .content)
        #expect(model.selectedTab == .content)
        model.select(tab: .pending)
        #expect(model.selectedTab == .pending, "the Pending tab is still reachable while the queue is non-empty")
    }

    /// Part B gate (stage 3 M-3). The fallback in `selectedTab` is a READ: with the stored value
    /// left at `.pending`, the NEXT awaiting row — a later import, a background pull — switched the
    /// user onto Pending with no tap. The screen calls `queueEmptied()` when the tab bar goes away.
    @Test func theNextAwaitingRowDoesNotReselectPendingOnceTheQueueEmptied() async throws {
        let stores = try stores()
        let model = model(stores, session: try await session(signedIn: true), settings: settings())
        try seedAwaiting(stores)
        model.select(tab: .pending)
        try stores.subscriptions.toggle(id: Fixture.channel, name: nil, avatarURL: nil)
        #expect(model.showsTabs == false)
        model.queueEmptied()

        try seedAwaiting(stores, channel: false, playlist: true, video: false)

        #expect(model.showsTabs)
        #expect(model.selectedTab == .content, "a stale stored selection switched the user onto Pending")
    }

    /// `MeFragment.kt:428-445`, and `:433` in particular: the flag is written BEFORE the dialog is
    /// presented, so a second arrival — a tab revisit, a relaunch while the first dialog is still
    /// up — cannot fire a second one. "At most once, ever" is the acceptance.
    @Test func theOfferFiresAtMostOnceEverAndMarksItselfBeforePresenting() async throws {
        let stores = try stores()
        let settings = settings()
        let model = model(stores, session: try await session(signedIn: true), settings: settings)

        #expect(settings.importOfferShown == false)
        #expect(model.consumeImportOffer())
        // Written BEFORE it answered — this is the whole of `:433`.
        #expect(settings.importOfferShown)
        #expect(model.consumeImportOffer() == false)
        #expect(model.consumeImportOffer() == false)

        // A fresh view model over the SAME persisted settings never offers again.
        let relaunched = self.model(stores, session: try await session(signedIn: true), settings: settings)
        #expect(relaunched.consumeImportOffer() == false)
    }

    /// A guest is never offered an import — there is no account to import into. Nor is an account
    /// with no Google grant to extend: the offer's one action pushes `Route.importFromYouTube`,
    /// which RULING 28 already keeps off that account's kebab for the same reason.
    @Test func theOfferIsNotMadeToAGuestOrToAnAccountThatCannotImport() async throws {
        let guestSettings = settings()
        let guest = model(try stores(), session: try await session(signedIn: false),
                          settings: guestSettings)
        #expect(guest.consumeImportOffer() == false)
        #expect(guestSettings.importOfferShown == false, "a guest must not burn the one offer")

        let appleSettings = settings()
        let apple = model(try stores(), session: try await session(signedIn: true),
                          settings: appleSettings, canImport: false)
        #expect(apple.consumeImportOffer() == false)
        #expect(appleSettings.importOfferShown == false,
                "an account that cannot import must not burn the one offer either")
    }

    // MARK: - Review: the channel page and the Pending tab must agree

    /// Never loaded — these two cases read only the SUBSCRIPTION state the view model resolves in
    /// its initialiser, so no page is ever fetched.
    private final class UnusedBrowse: BrowseSource, @unchecked Sendable {
        func isDegraded() async -> Bool { false }
        func channelHeader(_ id: String) async throws -> ChannelHeader { throw BrowseSourceError.unavailable }
        func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem> { throw BrowseSourceError.unavailable }
        func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem> { throw BrowseSourceError.unavailable }
        func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile> { throw BrowseSourceError.unavailable }
        func playlistItems(_ playlistId: String, continuation: String?) async throws -> BrowsePage<VideoItem> { throw BrowseSourceError.unavailable }
    }

    /// The Task 30 dispatcher's addendum. `SubscriptionsStore.isSubscribed` is unfiltered by design,
    /// so an AWAITING channel reads as subscribed on the channel page — while the Me tab hides it
    /// from the chip rail, the feed never fetches it, and it sits under Pending. "Subscribed" alone
    /// is a claim the rest of the app visibly does not honour, so the page carries the SAME
    /// `me_awaiting_pending_label` the Pending tab does.
    ///
    /// Unsubscribing stays available and stays honest: the tombstone drops the row out of
    /// `awaitingItems` too, so the badge cannot outlive the subscription it explains.
    @Test func anAwaitingChannelReadsTheSameOnTheChannelPageAndThePendingTab() async throws {
        let stores = try stores()
        try seedAwaiting(stores)
        let model = model(stores, session: try await session(signedIn: true), settings: settings())
        #expect(model.awaitingItems.map(\.id) == [Fixture.channel])

        let channel = ChannelDetailViewModel(
            channelId: Fixture.channel, name: "Alafasy", avatarURL: nil,
            browse: UnusedBrowse(), subscriptions: stores.subscriptions)
        // Both true at once: the row exists (so the button offers Unsubscribe) AND it is pending.
        #expect(channel.isSubscribed, "an AWAITING row is still a row — `isSubscribed` is unfiltered")
        #expect(channel.isAwaiting, "…and the page must say so, or it contradicts the Pending tab")
        // The chip rail, which is what "subscribed" normally buys you, shows nothing.
        #expect(model.chips.isEmpty)

        // Unsubscribing clears BOTH, on both screens.
        #expect(channel.toggleSubscribed() == nil)
        #expect(channel.isSubscribed == false)
        #expect(channel.isAwaiting == false)
        #expect(model.awaitingCount == 0)
        #expect(model.showsTabs == false)
    }

    /// An APPROVED subscription is subscribed and NOT pending — the badge is not simply "always on
    /// for an imported row".
    @Test func anApprovedChannelCarriesNoPendingBadge() async throws {
        let stores = try stores()
        try stores.subscriptions.importChannel(id: Fixture.channel, title: "Alafasy", avatarUrl: nil,
                                               approvalStatus: ImportProvenance.approved, at: Self.now)
        let channel = ChannelDetailViewModel(
            channelId: Fixture.channel, name: "Alafasy", avatarURL: nil,
            browse: UnusedBrowse(), subscriptions: stores.subscriptions)
        #expect(channel.isSubscribed)
        #expect(channel.isAwaiting == false)
    }

    /// Every key these two surfaces render resolves in all three locales, and neither plural-ish
    /// label loses its number.
    @Test func everyTabAndOfferKeyResolvesInEnglishArabicAndDutch() throws {
        let keys = ["me_awaiting_count", "me_awaiting_pending_label", "me_awaiting_section_title",
                    "me_tab_content", "me_tab_pending", "import_offer_title", "import_offer_message",
                    "import_offer_positive", "import_offer_negative"]
        let bundles = try ["en", "ar", "nl"].map { identifier -> (String, Bundle) in
            (identifier, try #require(Bundle.main.path(forResource: identifier, ofType: "lproj")
                .flatMap(Bundle.init(path:))))
        }
        for (identifier, bundle) in bundles {
            for key in keys {
                #expect(bundle.localizedString(forKey: key, value: nil, table: nil) != key,
                        "\(key) unresolved in \(identifier)")
            }
        }
        for (identifier, _) in bundles {
            for key in ["me_awaiting_count", "me_tab_pending"] {
                let rendered = Format.localizedFormat(key, locale: Locale(identifier: identifier),
                                                      Int64(3))
                #expect(!rendered.contains("%"), "\(identifier)/\(key): \(rendered)")
                #expect(rendered != key)
            }
        }
        #expect(Format.localizedFormat("me_tab_pending", locale: Locale(identifier: "en"), Int64(3))
            == "Pending (3)")
    }
}
