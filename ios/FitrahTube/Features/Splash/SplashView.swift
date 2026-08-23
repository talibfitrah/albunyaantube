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
                    // easeInOut here, distinct from the name/tagline easeOut below.
                    .animation(.easeInOut(duration: 0.4), value: phase)
                    .padding(.bottom, Spacing.xxl)
            }
        }
        .task { await run() }
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
                .animation(.easeOut(duration: 0.4), value: phase) // DecelerateInterpolator ~ easeOut
            Text(String(localized: "splash_tagline"))
                .font(TypeScale.subtitle)
                .foregroundStyle(Color.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, Spacing.sm)
                .padding(.horizontal, Spacing.xl(widthClass))
                .opacity(phase >= .tagline ? 1 : 0)
                .offset(y: offsetY(shown: phase >= .tagline))
                .animation(.easeOut(duration: 0.4), value: phase)
        }
    }

    /// 30 pt slide, dropped under Reduce Motion -- "static... fades only", same total dwell
    /// (spec §7 `2026-08-23-ios-app-design.md:169`; task-8 brief global constraints).
    private func offsetY(shown: Bool) -> CGFloat {
        (shown || reduceMotion) ? 0 : 30
    }

    private func run() async {
        let workTask = Task { await container.categories.loadIfNeeded() }
        // RULING (deep link skips animation entirely): a pending deep link means `.onOpenURL`
        // already fired before the shell existed and `Router.open` held it in `pendingRoute`
        // (`Router.swift`) -- the same signal `shellDidAppear()` later consumes. Re-checked after
        // every phase step below in case one arrives mid-animation.
        guard router.pendingRoute == nil else { onComplete(); return }

        var elapsed = Duration.zero
        for step in SplashTimeline.transitions.dropFirst() {
            try? await Task.sleep(for: step.at - elapsed)
            elapsed = step.at
            guard router.pendingRoute == nil else { onComplete(); return }
            phase = step.phase
        }
        try? await Task.sleep(for: SplashTimeline.preAwait - elapsed) // POST_ANIMATION_DELAY hold
        guard router.pendingRoute == nil else { onComplete(); return }

        // RULING 5: same rule `SplashTimeline.completionDelay` documents/tests, implemented here
        // as a race (Android's `withTimeoutOrNull(500) { updateInfoDeferred.await() }`) rather
        // than computed from a known finish time, since the work's finish time isn't knowable
        // without waiting for it.
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await workTask.value }
            group.addTask { try? await Task.sleep(for: SplashTimeline.cap - SplashTimeline.preAwait) }
            await group.next()
            group.cancelAll()
        }
        onComplete()
    }
}

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
