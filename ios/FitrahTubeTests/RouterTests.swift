import Foundation
import Observation
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

    // task-7b: `select(_:)` is the shared tap handler both the bottom `TabView` and the leading
    // `NavigationRailView` call -- one function, so the two bars can never disagree on reselect
    // semantics.
    @Test func selectDifferentTabSwitchesSelection() {
        let router = Router()
        router.select(.channels)
        #expect(router.selectedTab == .channels)
    }

    @Test func selectSameTabAtRootSignalsScrollToTop() {
        let router = Router()
        router.select(.home)
        #expect(router.selectedTab == .home)
        #expect(router.scrollToTopSignal?.tab == .home)
    }

    @Test func selectSameTabOnPushedScreenPopsToRoot() {
        let router = Router()
        router.push(.search)
        #expect(router.paths[.home] == [.search])

        router.select(.home)
        #expect(router.paths[.home] == [])
        #expect(router.scrollToTopSignal == nil)
    }

    /// Task 8 follow-up: `SplashView` now reacts to `router.pendingRoute` via `.onChange`, not by
    /// polling between animation steps. `.onChange(of:)` is powered by exactly this Observation
    /// primitive (`withObservationTracking`) -- this proves a `pendingRoute` mutation is observable
    /// the moment it happens, not just at the next poll checkpoint, which is what makes "a link
    /// arrives mid-animation -> completes immediately" true instead of "-> completes within ~550ms".
    @Test func pendingRouteMutationIsObservableTheMomentItHappens() {
        let router = Router()
        // `onChange` below is `@Sendable`; this test runs single-threaded and mutates it
        // synchronously (Observation invokes `onChange` inline with the mutating access, not on a
        // background queue), so `nonisolated(unsafe)` is the accurate annotation, not a lock.
        nonisolated(unsafe) var observedChange = false
        withObservationTracking {
            _ = router.pendingRoute
        } onChange: {
            observedChange = true
        }

        router.open(URL(string: "albunyaantube://video/abc123")!)

        #expect(observedChange)
    }

    /// Task 27 fix round / M7. A submit made from the Suggest screen has to reach My Submissions,
    /// which is a SIBLING route with its own ViewModel — the **+** ON that screen already refreshes,
    /// because it is the screen showing the result. A TOKEN, not a flag, for `scrollToTopSignal`'s
    /// reason: `.onChange` needs a value that actually changes, so two consecutive submits are two
    /// refreshes rather than one silently dropped.
    @Test func everySubmissionMadeElsewhereIsItsOwnRefreshToken() {
        let router = Router()
        var seen = [router.submissionsToken]
        router.submissionsChanged()
        seen.append(router.submissionsToken)
        router.submissionsChanged()
        seen.append(router.submissionsToken)

        #expect(Set(seen).count == 3, "a Bool would collapse the second submit into no refresh")
    }
}
