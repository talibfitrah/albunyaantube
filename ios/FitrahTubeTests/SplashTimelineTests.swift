import Testing
@testable import FitrahTube

/// `splash-onboarding.md:65-118`, RULINGS 2 + 5. `SplashTimeline` is pure so the animation-phase
/// lookup and the completion gate are both testable without waiting on real (2.75s+) time.
@Suite(.perTest)
struct SplashTimelineTests {
    // MARK: phase(at:) -- RULING 2 (logo visible from t=0) + the exact events table

    @Test func logoVisibleFromZero() {
        #expect(SplashTimeline.phase(at: .zero) == .logo)
        #expect(SplashTimeline.phase(at: .milliseconds(599)) == .logo)
    }

    @Test func nameStartsAt600() {
        #expect(SplashTimeline.phase(at: .milliseconds(600)) == .name)
        #expect(SplashTimeline.phase(at: .milliseconds(1149)) == .name)
    }

    @Test func taglineStartsAt1150() {
        #expect(SplashTimeline.phase(at: .milliseconds(1150)) == .tagline)
        #expect(SplashTimeline.phase(at: .milliseconds(1549)) == .tagline)
    }

    @Test func spinnerStartsAt1550() {
        #expect(SplashTimeline.phase(at: .milliseconds(1550)) == .spinner)
        #expect(SplashTimeline.phase(at: .milliseconds(1949)) == .spinner)
    }

    @Test func heldFrom1950ThroughPreAwait() {
        #expect(SplashTimeline.phase(at: .milliseconds(1950)) == .held)
        #expect(SplashTimeline.phase(at: SplashTimeline.preAwait) == .held)
    }

    // MARK: completionDelay(workFinishedAt:) -- RULING 5: max(2750ms, work), capped at 3250ms

    @Test func preAwaitIs2750Milliseconds() {
        #expect(SplashTimeline.preAwait == .milliseconds(2750))
    }

    @Test func capIs3250Milliseconds() {
        #expect(SplashTimeline.cap == .milliseconds(3250))
    }

    @Test func workStillInFlightFallsBackToPreAwait() {
        #expect(SplashTimeline.completionDelay(workFinishedAt: nil) == SplashTimeline.preAwait)
    }

    @Test func fastWorkDoesNotShortenThePreAwaitFloor() {
        #expect(SplashTimeline.completionDelay(workFinishedAt: .milliseconds(500)) == SplashTimeline.preAwait)
    }

    @Test func slowWorkWithinTheCapExtendsCompletion() {
        #expect(SplashTimeline.completionDelay(workFinishedAt: .milliseconds(3000)) == .milliseconds(3000))
    }

    @Test func workPastTheCapIsClampedTo3250() {
        #expect(SplashTimeline.completionDelay(workFinishedAt: .milliseconds(4000)) == SplashTimeline.cap)
    }
}
