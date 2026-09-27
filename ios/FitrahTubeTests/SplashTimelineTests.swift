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

    // MARK: withCappedWork -- gate A-C1/codex-P1: the cap must actually cap

    /// The defect this exists to catch: awaiting an unstructured `Task.value` inside the group is
    /// not cancellable, so `cancelAll()` never reached the warm-up and `withTaskGroup` blocked on
    /// it -- the same probe the review ran returned after 5.33 s against a 0.50 s cap. Real time,
    /// deliberately: the bug is a timing bug and nothing shorter than a clock can observe it.
    @Test func hungWorkIsAbandonedAtTheGrace() async {
        let clock = ContinuousClock()
        let started = clock.now
        await withCappedWork(grace: .milliseconds(200),
                             work: { try? await Task.sleep(for: .seconds(5)) },
                             duringWork: { true })
        #expect(clock.now - started < .seconds(2)) // 5 s work, 0.2 s grace
    }

    /// Work that finishes during `duringWork` must not add the grace on top -- `group.next()`
    /// returns its already-buffered result immediately.
    @Test func workFinishedDuringTheAnimationDoesNotWaitOutTheGrace() async {
        let clock = ContinuousClock()
        let started = clock.now
        await withCappedWork(grace: .seconds(5), work: {}, duringWork: { true })
        #expect(clock.now - started < .seconds(2))
    }

    /// A deep link arriving mid-animation (`duringWork` returns false) abandons the warm-up
    /// instead of waiting out the grace.
    @Test func deepLinkDuringTheAnimationSkipsTheGraceEntirely() async {
        let clock = ContinuousClock()
        let started = clock.now
        await withCappedWork(grace: .seconds(5),
                             work: { try? await Task.sleep(for: .seconds(5)) },
                             duringWork: { false })
        #expect(clock.now - started < .seconds(2))
    }
}
