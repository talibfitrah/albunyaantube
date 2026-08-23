import Testing
@testable import FitrahTube

@Suite(.perTest)
struct SplashRouterTests {
    @Test func onboardingNotCompletedRoutesToOnboarding() {
        #expect(SplashRouter.destination(onboardingCompleted: false) == .onboarding)
    }

    @Test func onboardingCompletedRoutesToMain() {
        #expect(SplashRouter.destination(onboardingCompleted: true) == .main)
    }
}
