import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct RouterTests {
    @Test func reselectAtRootSignalsScrollToTop() {
        let router = Router()
        #expect(router.reselect(.home) == .scrollToTop)
    }

    @Test func reselectOnPushedScreenPopsToRoot() {
        let router = Router()
        router.push(.search)
        #expect(router.paths[.home] == [.search])

        #expect(router.reselect(.home) == .popToRoot)
        #expect(router.paths[.home] == [])
    }

    @Test func pushAppendsToSelectedTabsPathOnly() {
        let router = Router()
        router.selectedTab = .channels
        router.push(.categories)

        #expect(router.paths[.channels] == [.categories])
        #expect(router.paths[.home] == [])
    }

    @Test func popToRootClearsOnlyThatTabsPath() {
        let router = Router()
        router.push(.search)
        router.selectedTab = .videos
        router.push(.categories)

        router.popToRoot(.home)

        #expect(router.paths[.home] == [])
        #expect(router.paths[.videos] == [.categories])
    }

    @Test func openBeforeShellReadyHoldsPendingRoute() {
        let router = Router()
        router.open(URL(string: "albunyaantube://video/abc123")!)

        #expect(router.pendingRoute == .player(PlayerArgs(videoId: "abc123")))
        #expect(router.paths[.home] == [])
    }

    @Test func shellDidAppearAppliesPendingRouteOnce() {
        let router = Router()
        router.open(URL(string: "albunyaantube://video/abc123")!)

        router.shellDidAppear()
        #expect(router.pendingRoute == nil)
        #expect(router.paths[.home] == [.player(PlayerArgs(videoId: "abc123"))])

        // A second shellDidAppear() (e.g. the view reappearing) must not re-apply/duplicate it.
        router.shellDidAppear()
        #expect(router.paths[.home] == [.player(PlayerArgs(videoId: "abc123"))])
    }

    @Test func openAfterShellIsReadyAppliesImmediately() {
        let router = Router()
        router.shellDidAppear()

        router.open(URL(string: "albunyaantube://channel/chan1")!)

        #expect(router.pendingRoute == nil)
        #expect(router.paths[.home] == [.channel(id: "chan1", name: nil, avatarURL: nil)])
    }

    @Test func invalidDeepLinkIsIgnored() {
        let router = Router()
        router.open(URL(string: "mailto:test@example.com")!)

        #expect(router.pendingRoute == nil)
        #expect(router.paths[.home] == [])
    }
}
