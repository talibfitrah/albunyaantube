import Foundation
import SwiftUI

@main
struct FitrahTubeApp: App {
    // Release must always build the live container -- a Release binary should never be able to
    // serve fake data even if `-fitrah-fake-container` somehow ended up in its arguments.
    #if DEBUG
    // `sharedFake`, not `fake()` (gate wave-2 W8): `fake()` reuses the shared "fitrahtube.fake"
    // defaults suite *without* wiping it -- the hazard `AppContainer.fake()`'s own doc warns
    // callers about -- so onboarding/settings/filter/search-history state leaked from one UI-test
    // or screenshot run into the next. `sharedFake` wipes the suite once, at creation.
    @State private var container = ProcessInfo.processInfo.arguments.contains("-fitrah-fake-container")
        ? AppContainer.sharedFake
        : AppContainer.live()
    #else
    @State private var container = AppContainer.live()
    #endif

    // Owned at the app scope, not inside MainShellView, so a deep link that arrives before the
    // shell exists -- e.g. tapped while Onboarding is still showing -- has somewhere to land
    // (RULING 4: held in `pendingRoute`, applied once `Router.shellDidAppear()` runs).
    @State private var router = Router()

    @Environment(\.scenePhase) private var scenePhase
    // CF-B4: last time `remoteConfig.refresh()` fired, so returning to the foreground doesn't
    // re-fetch on every scene-phase flicker.
    @State private var lastRemoteConfigRefresh: Date?
    private static let remoteConfigRefreshSpacing: TimeInterval = 15 * 60

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.container, container)
                .environment(\.router, router)
                .onOpenURL { router.open($0) }
                .task {
                    openDebugDeepLinkIfRequested()
                    selectDebugTabIfRequested()
                    pushDebugRouteIfRequested()
                    showDebugBannerIfRequested()
                    seedDebugFavoritesIfRequested()
                    refreshRemoteConfigIfDue()
                }
                .onChange(of: scenePhase) { _, newPhase in
                    if newPhase == .active { refreshRemoteConfigIfDue() }
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
        if let last = lastRemoteConfigRefresh, now.timeIntervalSince(last) < Self.remoteConfigRefreshSpacing { return }
        lastRemoteConfigRefresh = now
        Task { await container.innerTube.remoteConfig.refresh() }
    }

    /// Debug-only launch hook (`-fitrah-deeplink <url>`, two argv tokens): exercises the exact
    /// same `Router.open(URL)` that `.onOpenURL` calls, so deep links are reproducible via
    /// `xcrun simctl launch` -- unlike `simctl openurl`, which routes through iOS's "Open in
    /// AppName?" system confirmation and needs a real tap to proceed.
    private func openDebugDeepLinkIfRequested() {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
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
        let args = ProcessInfo.processInfo.arguments
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
    /// `-fitrah-route player [videoId]|search|categories|subcategories <parentId> <parentName>|featured [categoryId] [categoryName]`
    /// (`-` for a nil `featured` arg). `player` is also reachable via `-fitrah-deeplink`, but this is
    /// the one-token form the screenshot rig's other routes already use.
    private func pushDebugRouteIfRequested() {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
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
                title: "Understanding Tawakkul: Trusting Allah in Every Situation",
                channelName: "Sample Channel",
                description: "A short reminder on tawakkul, with a link for further reading: "
                    + "https://example.com/tawakkul and a second note after it.",
                durationSeconds: 754, viewCount: 12_700_000
            )))
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
        let args = ProcessInfo.processInfo.arguments
        guard let flagIndex = args.firstIndex(of: "-fitrah-banner"), args.indices.contains(flagIndex + 1) else { return }
        router.pendingBanner = BannerMessage(text: args[flagIndex + 1])
        #endif
    }

    /// Debug-only launch hook (task-12 acceptance screenshots): `-fitrah-seed-favorites` favorites
    /// 3 sample videos on launch. There's no player yet (phase 2) to favorite a real video from,
    /// so this is the only way to get the Favorites screen / Me-tab favorites section populated
    /// for a screenshot.
    private func seedDebugFavoritesIfRequested() {
        #if DEBUG
        guard ProcessInfo.processInfo.arguments.contains("-fitrah-seed-favorites") else { return }
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
}
