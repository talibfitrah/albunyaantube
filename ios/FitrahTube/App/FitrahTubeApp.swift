import Foundation
import InnerTubeKit
import SwiftUI

@main
struct FitrahTubeApp: App {
    // B4 (fork C): exists solely for `supportedInterfaceOrientationsFor` -- see `OrientationLock`.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    // Release must always build the live container -- a Release binary should never be able to
    // serve fake data even if `-fitrah-fake-container` somehow ended up in its arguments.
    #if DEBUG
    // `sharedFake`, not `fake()` (gate wave-2 W8): `fake()` reuses the shared "fitrahtube.fake"
    // defaults suite *without* wiping it -- the hazard `AppContainer.fake()`'s own doc warns
    // callers about -- so onboarding/settings/filter/search-history state leaked from one UI-test
    // or screenshot run into the next. `sharedFake` wipes the suite once, at creation.
    @State private var container = LaunchArguments.debug.contains("-fitrah-fake-container")
        ? AppContainer.sharedFake
        : AppContainer.live(baseURL: debugAPIBaseURL ?? AppConfig.apiBaseURL)

    /// Plan C Task 6 live rig: `-fitrah-api-base-url <url>` points the LIVE container at a backend
    /// other than the xcconfig's (Debug is `localhost:8080`; the acceptance leg needs production).
    private static var debugAPIBaseURL: URL? {
        let args = LaunchArguments.debug
        guard let i = args.firstIndex(of: "-fitrah-api-base-url"), args.indices.contains(i + 1) else { return nil }
        return AppConfig.validate(args[i + 1])
    }

    #else
    @State private var container = AppContainer.live()
    #endif

    /// DEBUG `-fitrah-stdout <path>`: every DEBUG `print` (IndexClient statuses, the remote-config
    /// refresh, InnerTubeKit's bot-check trips) lands in a file the live XCUITest can read -- the
    /// app's own stdout is invisible from a UI-test run and `simctl spawn … log` needs approval.
    init() {
        #if DEBUG
        let args = LaunchArguments.debug
        if let i = args.firstIndex(of: "-fitrah-stdout"), args.indices.contains(i + 1),
           freopen(args[i + 1], "a", stdout) != nil {
            setvbuf(stdout, nil, _IOLBF, 0)
        }
        #endif
        // Cubic P1: the background-events relaunch seam — see `AppContainer.current`.
        AppContainer.current = container
        // Phase 4 Task 2: an eager warm-up ONLY. `container` above is a stored property, and Swift
        // evaluates stored-property initializers before this body runs — so `AppContainer.live()`
        // has already gone by, and the auth builder inside it calls `configureIfPossible()` itself.
        // Never gate a container member on "configure already ran": on the real launch path it has
        // not. This line just moves the one-time cost off the first sign-in tap.
        FirebaseBootstrap.configureIfPossible()
    }

    // Owned at the app scope, not inside MainShellView, so a deep link that arrives before the
    // shell exists -- e.g. tapped while Onboarding is still showing -- has somewhere to land
    // (RULING 4: held in `pendingRoute`, applied once `Router.shellDidAppear()` runs).
    @State private var router = Router()

    @Environment(\.scenePhase) private var scenePhase
    // CF-B4: last time `remoteConfig.refresh()` fired, so returning to the foreground doesn't
    // re-fetch on every scene-phase flicker.
    @State private var lastRemoteConfigRefresh: Date?
    private static let remoteConfigRefreshSpacing: TimeInterval = 15 * 60
    // Spec D3 "update required" gate: set after every remote-config refresh, never persisted --
    // a later config that LOWERS `minAppVersion` un-blocks on the next refresh (or relaunch)
    // without a reinstall.
    @State private var updateRequired = false
    // Phase 4 Task 24: last time the foreground sync fired. Its OWN timestamp, not
    // `lastRemoteConfigRefresh`: the launch `.task` above consumes that one before the session has
    // loaded, and sharing it would silence the first foreground sync of every launch.
    @State private var lastSync: Date?

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.container, container)
                .environment(\.router, router)
                // Google Sign-In gets first refusal: its OAuth callback comes back on the reversed
                // client id scheme, not `albunyaantube://`, and it returns false for everything it
                // did not start — so every deep link still reaches `DeepLinkParser` unchanged.
                .onOpenURL { if !FirebaseBootstrap.handleOpenURL($0) { router.open($0) } }
                .task {
                    openDebugDeepLinkIfRequested()
                    selectDebugTabIfRequested()
                    pushDebugRouteIfRequested()
                    showDebugBannerIfRequested()
                    await awaitFakeAccountIfSignedIn()
                    seedDebugFavoritesIfRequested()
                    seedDebugSubscriptionsIfRequested()
                    seedDebugOfflineItemsIfRequested()
                    seedDebugSubmissionsIfRequested()
                    refreshRemoteConfigIfDue()
                    // Phase 3 Task 4: re-bind rows to background tasks that outlived the last
                    // launch (creates the session, so a relaunch-for-events gets its delegate).
                    Task { await container.offlineManager.reattach() }
                }
                .onChange(of: scenePhase) { _, newPhase in
                    guard newPhase == .active else { return }
                    refreshRemoteConfigIfDue()
                    // Stage 5 / C2.1 + C4.2: NOTHING else re-reads `/me`. `AccountSession.refresh()`
                    // fires only on an auth transition, on `SignInViewModel.land()` and on the
                    // bootstrap submit — so an email change completed in the mail app never landed
                    // on the profile, and a launch whose `/me` failed rendered a signed-in user as a
                    // guest until relaunch. One attempt, coalesced by the session, so a scene-phase
                    // flicker costs at most one in-flight request.
                    //
                    // Unstructured on purpose: a `.task`-scoped caller that LEADS the coalescer
                    // decides the session's state for every other observer (`refresh`'s doc).
                    //
                    // Stage 9 / P2b: `refreshIfSignedIn`, so a GUEST foreground sends nothing.
                    Task { await container.session.refreshIfSignedIn(maxAttempts: 1) }
                    // Task 24, riding this SAME arm rather than a second `onChange(of: scenePhase)`
                    // -- two observers of one value have no defined order, and the sync's guard
                    // reads the session the line above refreshes.
                    syncOnForegroundIfDue()
                }
                // Task 24: connectivity restored -> push the dirty rows
                // (`AlBunyaanApplication.kt:206-215`). `NetworkMonitor` is `@Observable`, so this
                // fires on the same de-duped transitions the offline banner reads -- never once per
                // `NWPathMonitor` callback.
                .onChange(of: container.network.isOnline) { _, isOnline in
                    container.connectivityChanged(isOnline: isOnline)
                }
                // Overlay, not a branch replacing RootView: the refresh task/onChange above keep
                // firing underneath, which is what lets a lowered `minAppVersion` un-block live.
                .overlay {
                    if updateRequired { UpdateRequiredView() }
                }
        }
    }

    /// CF-B4: `RemoteConfigStore.refresh()` must run on launch and on every return to the
    /// foreground, or `current()` serves InnerTubeKit's bundled default forever. `scenePhase`
    /// becoming `.active` covers both launch and foreground -- the ≥15 min spacing below is what
    /// keeps this to "launch + foreground", not every phase change. Fire-and-forget: never blocks
    /// the UI on a network round trip.
    private func refreshRemoteConfigIfDue() {
        let now = Date()
        let due = Self.isRemoteConfigRefreshDue(
            now: now, last: lastRemoteConfigRefresh, spacing: Self.remoteConfigRefreshSpacing)
        guard due else { return }
        lastRemoteConfigRefresh = now
        // Task 7 (reconciliation note 7): the revalidation sweep shares this hook's cadence —
        // launch + `willEnterForeground` (the `DownloadExpiryPolicy.kt:23-28` cadence), spaced by
        // the SAME due-decision above so a scene-phase flicker never re-fires it. Fire-and-forget
        // and fail-open: an unreachable gate keeps every row (`OfflineSweep.decide`).
        Task { await container.offlineManager.sweep() }
        Task {
            // `refresh()` is the launch path's one remaining network call, and the screenshot rig
            // must make none. `current()` still runs — it reads the persisted last-known-good or
            // InnerTubeKit's bundled default, no transport — so the kill-switch and the
            // forced-update decision below behave exactly as they do live.
            //
            // `isFixture` is DEBUG-only (there is no fixture container in Release), so Release has
            // only the `due` half to consult.
            #if DEBUG
            let shouldFetch = Self.shouldFetchRemoteConfig(isFixture: container.isFixture, due: due)
            #else
            let shouldFetch = due
            #endif
            if shouldFetch {
                await container.innerTube.remoteConfig.refresh()
            }
            let config = await container.innerTube.remoteConfig.current()
            // Cubic P3-1: the kill-switch flipping back ON is observed by nothing — a row saved
            // during an off-window stays queued (or, gate-parked, `.paused`) until the next launch.
            // The refresh above is where the new config lands, so the queue is kicked right after
            // it: a no-op when there is nothing to move or the switch is still off (`begin`
            // re-consults it). What that kick has to do lives on the manager, in
            // `kickAfterConfigRefresh` — review I1.
            //
            // Its OWN Task (fix round 1, the `sweep()` idiom above): the kick awaits
            // `begin` → a real InnerTube resolve whenever a row IS startable — precisely the case
            // this kick exists for — which inline would put spec D3's forced-update decision
            // below behind a network round trip.
            Task { await container.offlineManager.kickAfterConfigRefresh() }
            // Spec D3: re-evaluated after EVERY refresh, from the served config alone (fetched,
            // else persisted last-known-good, else bundled default) -- so a lowered minAppVersion
            // un-blocks and the first launch after a blocking publish blocks even if this
            // refresh's fetch failed.
            updateRequired = Self.isUpdateRequired(
                appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                config: config)
            #if DEBUG
            print("RemoteConfig: refreshed featuredCategoryId=\(config.featuredCategoryId ?? "nil") resolverOrder=\(config.resolverOrder) updateRequired=\(updateRequired)")
            #endif
        }
    }

    /// The launch path's ONE remaining network call, as a decision instead of an inline `if` so the
    /// skip is pinned: the screenshot rig must make no network call at all. `current()` runs either
    /// way (persisted last-known-good, else InnerTubeKit's bundled default, no transport), so the
    /// kill-switch and forced-update decisions are unaffected.
    static func shouldFetchRemoteConfig(isFixture: Bool, due: Bool) -> Bool { due && !isFixture }

    /// Phase 4 Task 24: pull + push on every return to the foreground
    /// (`AlBunyaanApplication.kt:167-185`), fire-and-forget like the refresh above.
    private func syncOnForegroundIfDue() {
        let now = Date()
        guard let uid = container.session.syncableUid,
              Self.shouldSyncOnForeground(uid: uid, now: now, last: lastSync,
                                          spacing: Self.remoteConfigRefreshSpacing) else { return }
        lastSync = now
        Task { await container.sync.syncNow(uid: uid) }
    }

    /// The foreground rule, whole, so the glue above is one `guard` -- the `isRemoteConfigRefreshDue`
    /// idiom, testable without a running scene.
    ///
    /// `uid` is `AccountSession.syncableUid`, which is nil for a guest, for a `/me` still in flight
    /// and for a terminal verdict being handled; the spacing half IS `isRemoteConfigRefreshDue`,
    /// because "the same >=15 min due-decision as the remote-config refresh" means the same
    /// decision, not a second copy of it. Only the timestamp differs.
    static func shouldSyncOnForeground(uid: String?, now: Date, last: Date?, spacing: TimeInterval) -> Bool {
        guard uid != nil else { return false }
        return isRemoteConfigRefreshDue(now: now, last: last, spacing: spacing)
    }

    /// CF-B1-13: the spacing decision, extracted so it is testable without a running scene.
    static func isRemoteConfigRefreshDue(now: Date, last: Date?, spacing: TimeInterval) -> Bool {
        guard let last else { return true }
        return now.timeIntervalSince(last) >= spacing
    }

    /// Spec D3's "update required" decision, extracted (same pattern as `isRemoteConfigRefreshDue`)
    /// so `UpdateGateTests` drives it without a running scene. Fails open: a missing
    /// `CFBundleShortVersionString` never blocks, and `requiresUpdate`'s numeric-segment compare
    /// treats an unparseable `minAppVersion` as 0-segments, which never exceeds a real version.
    static func isUpdateRequired(appVersion: String?, config: RemoteConfig) -> Bool {
        guard let appVersion else { return false }
        return config.requiresUpdate(appVersion: appVersion)
    }

    /// Debug-only launch hook (`-fitrah-deeplink <url>`, two argv tokens): exercises the exact
    /// same `Router.open(URL)` that `.onOpenURL` calls, so deep links are reproducible via
    /// `xcrun simctl launch` -- unlike `simctl openurl`, which routes through iOS's "Open in
    /// AppName?" system confirmation and needs a real tap to proceed.
    private func openDebugDeepLinkIfRequested() {
        #if DEBUG
        let args = LaunchArguments.debug
        if let flagIndex = args.firstIndex(of: "-fitrah-deeplink"),
           args.indices.contains(flagIndex + 1),
           let url = URL(string: args[flagIndex + 1]) {
            router.open(url)
        }
        #endif
    }

    /// Debug-only launch hook (`-fitrah-tab <home|channels|me|playlists|videos>`): lands directly
    /// on a tab for `xcrun simctl launch` screenshot scripts -- there's no tap-gesture equivalent
    /// in `simctl`, and `-fitrah-deeplink` only reaches item-detail routes, not tab selection.
    private func selectDebugTabIfRequested() {
        #if DEBUG
        let args = LaunchArguments.debug
        guard let flagIndex = args.firstIndex(of: "-fitrah-tab"), args.indices.contains(flagIndex + 1) else { return }
        switch args[flagIndex + 1] {
        case "home": router.selectedTab = .home
        case "channels": router.selectedTab = .channels
        case "me": router.selectedTab = .me
        case "playlists": router.selectedTab = .playlists
        case "videos": router.selectedTab = .videos
        default: break
        }
        #endif
    }

    /// Debug-only launch hook (task-11 acceptance screenshots): `.search`/`.categories` have no
    /// deep-link URL (`DeepLinkParser` only covers video/channel/playlist/shorts) and no
    /// `simctl` tap-gesture equivalent exists, so this pushes the route directly onto the
    /// currently-selected tab's stack -- same technique as `-fitrah-tab`.
    /// `-fitrah-route player [videoId] [playlistId] [targetVideoId]|shorts [videoId]|playlist [playlistId] [title]|channel [channelId] [name]|search|categories|subcategories <parentId> <parentName>|featured [categoryId] [categoryName]|favorites|settings|about|offline|profile`
    /// (`-` for a nil arg). `player` is also reachable via `-fitrah-deeplink`, but this is
    /// the one-token form the screenshot rig's other routes already use. B5 Task 4: the optional
    /// `playlistId`/`targetVideoId` (plus the `-fitrah-shuffled` flag) are the queue launch
    /// contract, so the rig can reach Up Next, deep start and shuffle with no Plan C screen.
    private func pushDebugRouteIfRequested() {
        #if DEBUG
        let args = LaunchArguments.debug
        guard let flagIndex = args.firstIndex(of: "-fitrah-route"), args.indices.contains(flagIndex + 1) else { return }
        func arg(_ offset: Int) -> String? {
            let index = flagIndex + offset
            guard args.indices.contains(index) else { return nil }
            return args[index] == "-" ? nil : args[index]
        }
        switch args[flagIndex + 1] {
        case "player":
            // Task 8: title/channel/description/views so the metadata-panel screenshot has
            // something real to show -- the plain `videoId`-only args used to leave the whole
            // panel empty.
            router.push(.player(PlayerArgs(
                videoId: arg(2) ?? "fixture-video",
                playlistId: arg(3),
                title: "Understanding Tawakkul: Trusting Allah in Every Situation",
                channelName: "Sample Channel",
                description: "A short reminder on tawakkul, with a link for further reading: "
                    + "https://example.com/tawakkul and a second note after it.",
                durationSeconds: 754, viewCount: 12_700_000,
                shuffled: args.contains("-fitrah-shuffled"), targetVideoId: arg(4)
            )))
        case "shorts":
            // B4 task 3 screenshot rig: a channel-attributed short (the deep-link form carries an
            // id only, which hides the channel row by design -- `ShortsOverlay.showsChannelRow`).
            router.push(.shorts(PlayerArgs(
                videoId: arg(2) ?? "fixture-video",
                title: "A short reminder on tawakkul",
                channelName: "Sample Channel",
                channelId: "UCsample"
            )))
        case "playlist":
            // Plan C Task 4: `-fitrah-route playlist [playlistId] [title]` -- the fake catalog's
            // lists are all videos, so this is the rig's only way onto PlaylistDetailScreen.
            router.push(.playlist(id: arg(2) ?? "fixture-playlist", title: arg(3), category: nil, count: nil))
        case "channel":
            // Plan C Task 5: `-fitrah-route channel [channelId] [name]`, same reason as `playlist`.
            router.push(.channel(id: arg(2) ?? "UCfixturechannel", name: arg(3), avatarURL: nil))
        case "search":
            router.push(.search)
        case "categories":
            router.push(.categories)
        case "subcategories":
            router.push(.categories)
            router.push(.subcategories(parentId: arg(2) ?? "", parentName: arg(3) ?? ""))
        case "featured":
            router.push(.featured(categoryId: arg(2), categoryName: arg(3)))
        case "favorites":
            router.push(.favorites)
        case "settings":
            router.push(.settings)
        case "about":
            router.push(.about)
        case "offline":
            // Phase 3 Task 6: the Saved screen, for the `phase3-saved` screenshot case.
            router.push(.offline)
        case "profile":
            // Phase 4 Task 19: the Profile screen is reached by tapping the signed-in Me tab's
            // kebab, which the rig cannot do, and it has no deep-link URL. No `signIn` arm beside
            // it: that row is dropped (see `ScreenshotTests.phase4Screens`), and an arm no rig
            // case reaches is a branch nothing proves.
            router.push(.profile)
        case "importFromYouTube":
            // Phase 4 Task 30: the import review screen is reached by tapping the signed-in Me
            // tab's kebab, which the rig cannot do, and it has no deep-link URL. Only useful
            // alongside `-fitrah-seed-import-review`, which is what gets the flow past
            // `.authorizing` — without it the screen photographs its error arm.
            router.push(.importFromYouTube)
        default:
            break
        }
        #endif
    }

    /// Debug-only launch hook (task-11 acceptance screenshots): shows the category-filter-applied
    /// `TransientBanner` directly, without needing a real tap through Categories/Subcategories --
    /// same overlay (`Router.pendingBanner`, shown by `MainShellView`) a real pick would set.
    /// `-fitrah-banner "<text>"`.
    private func showDebugBannerIfRequested() {
        #if DEBUG
        let args = LaunchArguments.debug
        guard let flagIndex = args.firstIndex(of: "-fitrah-banner"), args.indices.contains(flagIndex + 1) else { return }
        router.pendingBanner = BannerMessage(text: args[flagIndex + 1])
        #endif
    }

    /// Task 13. The two seeds below write through PER-USER stores, and `AccountSession.start()`
    /// (`RootView`'s task) is what re-scopes those stores from the `""` anon sentinel to the
    /// signed-in uid. Seeding first put every row under the WRONG uid: the re-scope then hid all of
    /// them, and the `me-signed-in` screenshot row photographed an empty state rather than the
    /// screen it exists to capture. Found by the rig, not by inspection.
    ///
    /// A bounded `Task.yield()` loop, never a sleep — the fixture transport has one canned `/me`
    /// and `sessionSleep` is `noSleep`, so this settles in a handful of yields; the bound is what
    /// keeps a fixture that can never load from parking the launch path
    /// (`AccountSessionTests.awaitAccount*`).
    ///
    /// Fix round 1 / M2: `-fitrah-fake-container` is part of the guard, not an assumption. Only the
    /// fixture container has a canned `/me` queued, so `-fitrah-fake-auth` on a Debug launch
    /// against the LIVE container has nothing that can land and would spend the whole bound.
    private func awaitFakeAccountIfSignedIn() async {
        #if DEBUG
        guard LaunchArguments.debug.contains("-fitrah-fake-container"),
              AppContainer.FakeAuth.fromLaunchArguments().accountJSON != nil else { return }
        await container.session.awaitAccount(bound: 2000)
        #endif
    }

    /// Debug-only launch hook (task-12 acceptance screenshots): `-fitrah-seed-favorites` favorites
    /// 3 sample videos on launch. There's no player yet (phase 2) to favorite a real video from,
    /// so this is the only way to get the Favorites screen / Me-tab favorites section populated
    /// for a screenshot.
    private func seedDebugFavoritesIfRequested() {
        #if DEBUG
        guard LaunchArguments.debug.contains("-fitrah-seed-favorites") else { return }
        for index in 1...3 {
            let item = ContentItem(
                id: "seed-favorite-\(index)", type: .video, title: "Seeded Favorite \(index)", category: nil,
                description: nil, thumbnailURL: nil, durationSeconds: 300 + index * 60, uploadedDaysAgo: nil,
                viewCount: nil, channelTitle: "Sample Channel", subscribers: nil, videoCount: nil, itemCount: nil
            )
            // Seed, not toggle (gate wave-2 W8): against a persistent store a second launch with
            // the flag soft-deleted the three rows instead of ensuring them, so every other
            // screenshot run showed an empty Favorites screen.
            guard !container.favorites.isFavorite(item.id) else { continue }
            try? container.favorites.toggle(item)
        }
        #endif
    }

    /// Plan C Task 6 screenshot rig: `-fitrah-seed-subscriptions` fills the guest cap (RULING 27,
    /// `SwiftDataSubscriptionsStore.cap`) with seeded channels, so the channel screen's Subscribe
    /// tap is the 31st and shows `me_subscription_cap_reached`. Seed, not toggle -- same reason
    /// as `seedDebugFavoritesIfRequested`.
    private func seedDebugSubscriptionsIfRequested() {
        #if DEBUG
        guard LaunchArguments.debug.contains("-fitrah-seed-subscriptions") else { return }
        for index in 1...SwiftDataSubscriptionsStore.cap {
            let id = "UCseed\(index)"
            guard !container.subscriptions.isSubscribed(id) else { continue }
            try? container.subscriptions.toggle(id: id, name: "Seeded Channel \(index)", avatarURL: nil)
        }
        #endif
    }

    /// Phase 3 Task 6 screenshot rig: `-fitrah-seed-offline` inserts one Saved row per
    /// `OfflineStatus` (the full action matrix on one screen). Rows only, through the store —
    /// no files, no manager. Seed, not toggle — same reason as `seedDebugFavoritesIfRequested`.
    private func seedDebugOfflineItemsIfRequested() {
        #if DEBUG
        guard LaunchArguments.debug.contains("-fitrah-seed-offline") else { return }
        for (index, status) in OfflineStatus.allCases.enumerated() {
            let videoId = "seed-offline-\(index)"
            guard container.offlineStore.item(videoId: videoId) == nil else { continue }
            let item = OfflineItem(
                videoId: videoId, title: "Seeded Lecture \(index + 1) (\(status.rawValue))",
                channelName: "Sample Channel", thumbnailUrl: nil,
                qualityLabel: "360p", audioOnly: false, status: status.rawValue,
                bytesWritten: Int64(index + 1) * 3_000_000,
                totalBytes: status == .queued ? nil : 18_000_000,
                errorCode: status == .failed ? "NETWORK" : nil,
                completedAt: status == .completed ? Date() : nil)
            try? container.offlineStore.insert(item)
        }
        #endif
    }

    /// Phase 4 Task 30 screenshot rig: `-fitrah-seed-submissions` writes AWAITING rows — one
    /// channel, one playlist, one video — through the three stores, which is what makes the Me
    /// tab's Pending tab appear and gives the import review screen something to show. Rows only,
    /// through the stores, so `refresh()` picks them up exactly as an import's do. Seed, not
    /// toggle — same reason as `seedDebugFavoritesIfRequested`.
    ///
    /// The ids are the approved fixture ids, never a music-video id (owner directive).
    private func seedDebugSubmissionsIfRequested() {
        #if DEBUG
        guard LaunchArguments.debug.contains("-fitrah-seed-submissions") else { return }
        let at = Date()
        let channel = "UCmMcOjsVehVlEOteyrhjI2Q"
        let playlist = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
        let video = "xc7keR2piUM"
        if !container.subscriptions.containsAny(channel) {
            try? container.subscriptions.importChannel(
                id: channel, title: "Seeded Awaiting Channel", avatarUrl: nil,
                approvalStatus: ImportProvenance.awaiting, at: at)
        }
        if !container.savedPlaylists.containsAny(playlist) {
            try? container.savedPlaylists.importPlaylist(
                id: playlist, title: "Seeded Awaiting Playlist", thumbnailUrl: nil,
                uploaderName: nil, approvalStatus: ImportProvenance.awaiting, at: at)
        }
        if !container.favorites.containsAny(video) {
            try? container.favorites.importVideo(
                id: video, title: "Seeded Awaiting Lecture", channelName: "",
                thumbnailUrl: nil, durationSeconds: 0,
                approvalStatus: ImportProvenance.awaiting, at: at)
        }
        #endif
    }
}

/// Spec D3's blocking "update required" surface: full-screen, opaque, no dismiss and no bypass --
/// it sits over everything and swallows every touch until a refreshed config (or an updated
/// build) clears `updateRequired`.
// TODO: add an "Update" button linking to the App Store listing once the app has a store id.
struct UpdateRequiredView: View {
    var body: some View {
        EmptyStateView(
            systemImage: "arrow.down.circle.fill",
            title: String(localized: "app_update_required_title"),
            message: String(localized: "app_update_required_message")
        )
        .background(Color.background.ignoresSafeArea())
    }
}

#if DEBUG
#Preview("Update required") { UpdateRequiredView() }
#endif
