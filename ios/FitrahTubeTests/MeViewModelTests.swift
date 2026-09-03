import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftData
import Testing
@testable import FitrahTube

/// Task 13, the signed-in Me shell (ruling C5). Everything here is LOCAL: the chip rail is the two
/// per-user stores merged, the favorites row is the favorites store capped, and the kebab is a pure
/// role decision. No feed (Tasks 14-16), no History (F10), no Content/Pending tabs (F14).
@Suite(.perTest)
@MainActor
struct MeViewModelTests {

    private static let base = URL(string: "https://api.fitrah.test/")!

    private func makeStores() -> (favorites: SwiftDataFavoritesStore,
                                  subscriptions: SwiftDataSubscriptionsStore,
                                  savedPlaylists: SwiftDataSavedPlaylistsStore) {
        // One container for all three: `AppContainer` shares one too, and `SavedPlaylist`'s and
        // `SubscribedChannel`'s `#Unique` macros need their own schema entries either way.
        let container = AppContainer.makeModelContainer(inMemory: true)
        return (SwiftDataFavoritesStore(modelContainer: container),
                SwiftDataSubscriptionsStore(modelContainer: container),
                SwiftDataSavedPlaylistsStore(modelContainer: container))
    }

    /// A session parked on whatever `/me` answers. `role` nil -> the session stays `.signedOut`,
    /// which is this app's "no account" (the container always holds a session, so a *nil* session
    /// is not expressible — `state.me == nil` is the same fact).
    private func makeSession(role: String?, status: String = "active") async throws -> AccountSession {
        let auth = FakeAuthClient(state: .signedOut)
        let body = #"{"uid":"u1","email":"a@b.test","status":"\#(status)","role":"\#(role ?? "user")"}"#
        let transport = ScriptedTransport([.json(200, body)])
        let session = AccountSession(
            auth: auth,
            account: AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "d1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in })
        guard role != nil else { return session }
        let running = Task { await session.start() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        for _ in 0..<500 where session.state.me == nil { await Task.yield() }
        running.cancel()
        return session
    }

    private func makeModel(session: AccountSession,
                           stores: (favorites: SwiftDataFavoritesStore,
                                    subscriptions: SwiftDataSubscriptionsStore,
                                    savedPlaylists: SwiftDataSavedPlaylistsStore)) -> MeViewModel {
        MeViewModel(session: session, favorites: stores.favorites,
                    subscriptions: stores.subscriptions, savedPlaylists: stores.savedPlaylists)
    }

    private func favorite(_ id: String) -> ContentItem {
        ContentItem(id: id, type: .video, title: "Video \(id)", category: nil, description: nil,
                    thumbnailURL: nil, durationSeconds: 120, uploadedDaysAgo: nil, viewCount: nil,
                    channelTitle: "Channel", subscribers: nil, videoCount: nil, itemCount: nil)
    }

    // MARK: - Chips

    /// Merged, never segregated (`MeViewModel.kt:406-411`): a playlist saved AFTER a channel sorts
    /// first. The two add times are set explicitly so the assertion is about the sort, not about
    /// how fast two `Date()`s follow each other.
    @Test func chipsMergeBothStoresSortedByAddTimeDescending() async throws {
        let stores = makeStores()
        try stores.subscriptions.toggle(id: "UCchannel", name: "Channel One",
                                        avatarURL: URL(string: "https://example.com/a.jpg"))
        try stores.savedPlaylists.toggle(id: "PLplaylist", title: "Playlist One",
                                         thumbnailURL: URL(string: "https://example.com/p.jpg"), itemCount: 4)
        stores.subscriptions.items[0].followedAt = Date(timeIntervalSince1970: 1_000)
        stores.savedPlaylists.items[0].addedAt = Date(timeIntervalSince1970: 2_000)

        let model = makeModel(session: try await makeSession(role: "user"), stores: stores)

        #expect(model.chips.map(\.id) == ["PLplaylist", "UCchannel"])
        #expect(model.chips.map(\.kind) == [.playlist, .channel])
        #expect(model.chips.map(\.title) == ["Playlist One", "Channel One"])
        #expect(model.chips.map(\.avatarURL) == [URL(string: "https://example.com/p.jpg"),
                                                  URL(string: "https://example.com/a.jpg")])
    }

    // MARK: - Chip filtering (pure)

    @Test func chipSelectionIsAPureFunctionOverTheMergedList() {
        let chips = [MeChipItem(id: "a", title: "A", avatarURL: nil, addedAt: .distantPast, kind: .channel)]
        #expect(MeViewModel.selection("a", in: chips) == "a")
        #expect(MeViewModel.selection(nil, in: chips) == nil)
        #expect(MeViewModel.selection("gone", in: chips) == nil)
        #expect(MeViewModel.selection("a", in: []) == nil)
    }

    /// The staleness this exists for: a chip unsubscribed on the channel screen must not leave the
    /// Me tab filtering by a row that no longer exists.
    @Test func aSelectionWhoseChipDisappearedResolvesToNil() async throws {
        let stores = makeStores()
        try stores.subscriptions.toggle(id: "UCchannel", name: "Channel One", avatarURL: nil)
        let model = makeModel(session: try await makeSession(role: "user"), stores: stores)

        model.setFilter("UCchannel")
        #expect(model.selectedChipId == "UCchannel")

        try stores.subscriptions.toggle(id: "UCchannel", name: nil, avatarURL: nil) // unsubscribe
        #expect(model.selectedChipId == nil)
    }

    // MARK: - Favorites row

    @Test func favoriteTilesCapAtTwentyOfTwentyFive() async throws {
        let stores = makeStores()
        for index in 1...25 { try stores.favorites.toggle(favorite("v\(index)")) }
        let model = makeModel(session: try await makeSession(role: "user"), stores: stores)

        #expect(stores.favorites.items.count == 25)
        #expect(MeViewModel.maxFavoriteTiles == 20)
        #expect(model.favoriteTiles.count == 20)
    }

    @Test func favoriteTilesAreEmptyWithNoFavorites() async throws {
        let model = makeModel(session: try await makeSession(role: "user"), stores: makeStores())
        #expect(model.favoriteTiles.isEmpty)
    }

    // MARK: - Role gate

    /// `MeFragment.kt:270-271` compares `ignoreCase = true`, and the backend can send "ADMIN".
    @Test func showsModeratorItemsIsTrueForAdminInAnyCase() async throws {
        for role in ["admin", "ADMIN", "Admin"] {
            let model = makeModel(session: try await makeSession(role: role), stores: makeStores())
            #expect(model.showsModeratorItems, "role \(role)")
        }
    }

    @Test func showsModeratorItemsIsTrueForModeratorInAnyCase() async throws {
        for role in ["moderator", "MODERATOR", "Moderator"] {
            let model = makeModel(session: try await makeSession(role: role), stores: makeStores())
            #expect(model.showsModeratorItems, "role \(role)")
        }
    }

    @Test func showsModeratorItemsIsFalseForAPlainUserOrABlankRole() async throws {
        for role in ["user", ""] {
            let model = makeModel(session: try await makeSession(role: role), stores: makeStores())
            #expect(!model.showsModeratorItems, "role \(role)")
        }
    }

    @Test func showsModeratorItemsIsFalseWithNoLoadedAccount() async throws {
        let model = makeModel(session: try await makeSession(role: nil), stores: makeStores())
        #expect(model.showsModeratorItems == false)
    }

    // MARK: - Kebab

    @Test func aPlainUsersKebabHasExactlyThreeItems() {
        #expect(MeKebabItem.items(isModerator: false) == [.profile, .importYouTube, .signOut])
    }

    @Test func aModeratorsKebabHasAllFiveItems() {
        #expect(MeKebabItem.items(isModerator: true)
            == [.profile, .mySubmissions, .suggestContent, .importYouTube, .signOut])
    }

    /// F10 / RULING 28: the ABSENCE is the requirement. A `.history` or `.recentlyWatched` case
    /// added here — even hidden — is a dead affordance waiting to be rendered.
    @Test func thereIsNoHistoryOrRecentlyWatchedKebabItem() {
        #expect(MeKebabItem.allCases.count == 5)
    }

    @Test func everyKebabTitleKeyResolvesInEnglishArabicAndDutch() throws {
        let keys = MeKebabItem.allCases.map(\.titleKey) + [
            "me_favorites", "me_empty_title", "me_empty_subtitle", "me_empty_cta",
            "offline_saved_title", "settings_account_header", "settings_account_signed_in_as",
            "settings_account_signed_in_default", "settings_account_sign_out",
            "settings_account_sign_out_confirm_title", "settings_account_sign_out_confirm_body",
            "settings_account_sign_out_confirm_action", "settings_account_sign_out_cancel",
        ]
        let bundles = try ["en", "ar", "nl"].map { locale in
            (locale, try #require(Bundle.main.path(forResource: locale, ofType: "lproj")
                .flatMap(Bundle.init(path:))))
        }
        for (locale, bundle) in bundles {
            for key in keys {
                let value = bundle.localizedString(forKey: key, value: nil, table: nil)
                #expect(value != key, "\(key) unresolved in \(locale)")
            }
        }
        // Task 12's rule applied to this task's keys: ten of them are en-only on Android
        // (`values/strings.xml`, no `values-ar`/`values-nl` sibling), so the converter's per-locale
        // read shipped the ENGLISH sentence as the Arabic value and Task 13 is what first RENDERS
        // them. Arabic is never the English sentence — Dutch legitimately can be ("Account"), so
        // only Arabic is pinned here.
        let english = bundles[0].1, arabic = bundles[1].1
        for key in keys {
            #expect(arabic.localizedString(forKey: key, value: nil, table: nil)
                != english.localizedString(forKey: key, value: nil, table: nil),
                    "\(key) carries the English value in Arabic")
        }
    }

    /// RULING 28 again, as arithmetic: only `.signOut` has a destination in Task 13, so only
    /// `.signOut` renders. Tasks 17/25/27/29 each widen `MeKebabItem.landed` and edit this test.
    @Test func onlySignOutHasADestinationAtTaskThirteen() async throws {
        let plain = makeModel(session: try await makeSession(role: "user"), stores: makeStores())
        #expect(plain.enabledKebabItems == [.signOut])
        let moderator = makeModel(session: try await makeSession(role: "admin"), stores: makeStores())
        #expect(moderator.enabledKebabItems == [.signOut])
    }

    /// The kebab's ONE live destination, end to end: sign out drops the session, and ruling C5's
    /// tab-root decision then renders the guest screen again.
    @Test func signingOutDropsTheSessionAndTheTabRootFallsBackToGuest() async throws {
        let session = try await makeSession(role: "user")
        let model = makeModel(session: session, stores: makeStores())
        #expect(MeTabRoot.showsSignedInScreen(for: session.state))

        model.signOut()

        #expect(session.state == .signedOut)
        #expect(MeTabRoot.showsSignedInScreen(for: session.state) == false)
    }
}
