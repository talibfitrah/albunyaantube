/// Where `RootView` sends the user once the splash decision is made. Phase 1 only knows about
/// the onboarding flag; phase 4 adds the signed-in/account-status branches from
/// `splash-onboarding.md:1.7` (guest routing per spec §6 -- iOS never forces sign-in).
nonisolated enum SplashDestination: Equatable {
    case onboarding
    case main
}

nonisolated enum SplashRouter {
    static func destination(onboardingCompleted: Bool) -> SplashDestination {
        onboardingCompleted ? .main : .onboarding
    }
}
