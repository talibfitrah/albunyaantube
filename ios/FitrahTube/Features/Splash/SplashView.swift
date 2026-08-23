import SwiftUI

/// Android's `SplashFragment` animation timeline (`splash-onboarding.md:63-118`), pure so the
/// phase lookup and the completion gate are both testable without waiting on real time
/// (`SplashTimelineTests`). `transitions` is the single source of truth for both the test lookup
/// and `SplashView.run()`'s sleep chain.
nonisolated enum SplashTimeline {
    enum Phase: Int, Equatable, Comparable {
        case logo, name, tagline, spinner, held
        static func < (lhs: Phase, rhs: Phase) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    /// RULING 2: the logo is visible from t=0 (Android's own blank-first-600ms is a defect, not
    /// design). Millisecond offsets from `SplashFragment.kt:81-84` / the events table at
    /// `splash-onboarding.md:78-90`.
    static let transitions: [(phase: Phase, at: Duration)] = [
        (.logo, .zero),
        (.name, .milliseconds(600)),
        (.tagline, .milliseconds(1150)),
        (.spinner, .milliseconds(1550)),
        (.held, .milliseconds(1950)),
    ]

    /// `SPLASH_PRE_AWAIT_MS` = 600 + 400×3 + 150 + 800.
    static let preAwait: Duration = .milliseconds(2750)
    /// RULING 5: same +500 ms grace as Android's `UPDATE_AWAIT_GRACE_MS`.
    static let cap: Duration = .milliseconds(3250)

    static func phase(at elapsed: Duration) -> Phase {
        transitions.last { elapsed >= $0.at }?.phase ?? .logo
    }

    /// When routing may happen, given the warm-up work's finish time (`nil` = still in flight):
    /// `max(preAwait, workFinishedAt)`, capped at `cap`. Documents/tests the rule that
    /// `SplashView.run()`'s work/cap race implements at runtime -- see that method's comment.
    static func completionDelay(workFinishedAt: Duration?) -> Duration {
        guard let workFinishedAt else { return preAwait }
        return min(max(preAwait, workFinishedAt), cap)
    }
}

/// Android's `SplashFragment` (`splash-onboarding.md` §1). Phase 1 slice only: the animation
/// timeline plus the `categories.loadIfNeeded()` warm-up as "work" (brief's stand-in for phase 2's
/// remote-config fetch). Account status / update-required gating are phase 4 / phase 2 additions
/// per the contract's own iOS deviation notes (§1.7, §1.4c).
struct SplashView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.widthClass) private var widthClass

    let onComplete: () -> Void

    @State private var phase: SplashTimeline.Phase = .logo

    var body: some View {
        ZStack {
            Color.background.ignoresSafeArea()
            VStack(spacing: 0) {
                Spacer(minLength: 0)
                logoBlock
                Spacer(minLength: 0)
                ProgressView()
                    .tint(.brand)
                    .opacity(phase >= .spinner ? 1 : 0)
                    // No interpolator set on Android -> platform default (AccelerateDecelerate),
                    // easeInOut here, distinct from the name/tagline easeOut below. Keyed on the
                    // boolean this element actually animates on, not the whole `phase` -- keying
                    // on `phase` re-ran the animation on every unrelated step.
                    .animation(.easeInOut(duration: 0.4), value: phase >= .spinner)
                    .padding(.bottom, Spacing.xxl)
            }
        }
        .task { await run() }
        // Task 8 follow-up: `run()` below only re-checks `router.pendingRoute` between animation
        // steps (up to ~550ms apart), so a link arriving mid-sleep sat unnoticed until the next
        // checkpoint. `.onChange` reacts the instant Router's `@Observable` mutation happens,
        // regardless of where `run()` currently is in its sleep chain.
        .onChange(of: router.pendingRoute) { _, newValue in
            guard newValue != nil else { return }
            onComplete()
        }
    }

    // ponytail: approximates Android's `verticalBias 0.35` via a top/bottom Spacer split around
    // the content+spinner column rather than exact GeometryReader math -- fine for the acceptance
    // screenshots; revisit with a bias calculation if pixel parity to Android ever matters.
    private var logoBlock: some View {
        VStack(spacing: 0) {
            Image("logo")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: widthClass.pick(160, 220, 280), height: widthClass.pick(160, 220, 280))
                .accessibilityHidden(true)
            Text(String(localized: "app_name"))
                .font(.system(size: widthClass.pick(32, 40, 48), weight: .bold))
                .foregroundStyle(Color.textPrimary)
                .padding(.top, Spacing.lg(widthClass))
                .opacity(phase >= .name ? 1 : 0)
                .offset(y: offsetY(shown: phase >= .name))
                .animation(.easeOut(duration: 0.4), value: phase >= .name) // DecelerateInterpolator ~ easeOut
            Text(String(localized: "splash_tagline"))
                .font(TypeScale.subtitle)
                .foregroundStyle(Color.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, Spacing.sm)
                .padding(.horizontal, Spacing.xl(widthClass))
                .opacity(phase >= .tagline ? 1 : 0)
                .offset(y: offsetY(shown: phase >= .tagline))
                .animation(.easeOut(duration: 0.4), value: phase >= .tagline)
        }
    }

    /// 30 pt slide, dropped under Reduce Motion -- "static... fades only", same total dwell
    /// (spec §7 `2026-08-23-ios-app-design.md:169`; task-8 brief global constraints).
    private func offsetY(shown: Bool) -> CGFloat {
        (shown || reduceMotion) ? 0 : 30
    }

    private func run() async {
        await withCappedWork(
            grace: SplashTimeline.cap - SplashTimeline.preAwait,
            work: { await container.categories.loadIfNeeded() },
            duringWork: animate
        )
        // Single `onComplete()` call site for every path. Skipped when the view is already gone
        // (fix round: A-M8) -- `.onChange(of: router.pendingRoute)` above ends the splash the
        // instant a link lands, which cancels this `.task`; calling back into a torn-down view
        // was harmless only because `onComplete` happens to be idempotent.
        if !Task.isCancelled { onComplete() }
    }

    /// The animation timeline plus the POST_ANIMATION_DELAY hold. Returns `false` when the splash
    /// must end right now without waiting for the warm-up -- a deep link arrived (RULING: a
    /// pending deep link means `.onOpenURL` fired before the shell existed and `Router.open` held
    /// it in `pendingRoute`, so the animation is skipped entirely), or the view was torn down.
    private func animate() async -> Bool {
        guard router.pendingRoute == nil else { return false }

        var elapsed = Duration.zero
        for step in SplashTimeline.transitions.dropFirst() {
            try? await Task.sleep(for: step.at - elapsed)
            guard !Task.isCancelled, router.pendingRoute == nil else { return false }
            elapsed = step.at
            phase = step.phase
        }
        try? await Task.sleep(for: SplashTimeline.preAwait - elapsed) // POST_ANIMATION_DELAY hold
        return !Task.isCancelled && router.pendingRoute == nil
    }
}

/// RULING 5's cap (`SplashTimeline.cap`), as the structured-concurrency shape the rule actually
/// needs: `work` starts immediately as a real **child** task so it warms up alongside `duringWork`
/// (the splash animation), and once that returns it gets at most `grace` more before `cancelAll()`
/// abandons it.
///
/// The child-task shape is load-bearing (gate A-C1 / codex-P1). The previous version started the
/// warm-up as an unstructured `Task` and awaited `workTask.value` from inside the group: that await
/// is not cancellable (`Task<Void, Never>.value` has no throwing path), so `cancelAll()` could never
/// reach it, and `withTaskGroup` waits for *every* child before returning -- so a stalled categories
/// fetch held the splash on screen until URLSession's own 20 s request timeout (or the 120 s
/// resource timeout), silently ignoring the 3.25 s cap. Proven by `SplashTimelineTests`.
///
/// `work` deliberately starts *before* `duringWork` rather than only at the grace point: the
/// reviewer's minimal patch moved the whole fetch inside the race, which enforces the cap but cuts
/// the warm-up budget from 3.25 s to 0.5 s and makes it useless in practice.
@MainActor
func withCappedWork(grace: Duration,
                    work: @escaping @MainActor () async -> Void,
                    duringWork: () async -> Bool) async {
    await withTaskGroup(of: Void.self) { group in
        group.addTask { await work() }
        defer { group.cancelAll() }
        guard await duringWork() else { return }
        group.addTask { try? await Task.sleep(for: grace) }
        await group.next() // whichever finishes first: the warm-up, or the grace clock
    }
}

#if DEBUG
#Preview {
    SplashView(onComplete: {})
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    SplashView(onComplete: {})
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
