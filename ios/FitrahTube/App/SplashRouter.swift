/// Where `RootView` sends the user once the splash decision is made. Phase 1 only knew about
/// the onboarding flag; phase 4 adds the signed-in/account-status branches from
/// `splash-onboarding.md:1.7` (guest routing per spec §6 -- iOS never forces sign-in).
nonisolated enum SplashDestination: Equatable {
    case onboarding
    case main
    case profileBootstrap
    case emailVerification
}

/// The whole launch decision: where to go, whether the session must be dropped first, and the
/// terminal event to surface once it has been. `signOut`/`alert` travel with the destination
/// because the blocked/deleted rows are all three at once, and a caller that read only the
/// destination would silently keep a dead session alive.
nonisolated struct SplashOutcome: Equatable {
    var destination: SplashDestination
    var signOut: Bool = false
    var alert: AccountStatusEvent?
}

nonisolated enum SplashRouter {
    /// Phase 1's callers. Kept as the onboarding-only leg of `outcome`.
    static func destination(onboardingCompleted: Bool) -> SplashDestination {
        onboardingCompleted ? .main : .onboarding
    }

    /// Spec §6 + §13 bullet 1, with the forced sign-in removed (D11 / RULING 31):
    ///   !onboardingCompleted                       -> onboarding
    ///   signed out                                 -> main (guest)
    ///   BLOCKED / DELETED                          -> sign out -> main (guest) + terminal alert
    ///   password provider AND !emailVerified       -> emailVerification   (§13, ahead of status)
    ///   status == nil (network)                    -> main (guest; caller retries fetchMe)
    ///   PENDING_PROFILE                            -> profileBootstrap
    ///   ACTIVE                                     -> main
    ///
    /// The two terminal statuses are tested BEFORE §13 (plan review): routing a blocked or deleted
    /// account to verification parks it in a loop it can never leave, and dropping the session is
    /// the entire point of those rows.
    static func outcome(onboardingCompleted: Bool, signedIn: Bool,
                        hasPasswordProvider: Bool, isEmailVerified: Bool,
                        status: AccountStatus?) -> SplashOutcome {
        guard onboardingCompleted else { return SplashOutcome(destination: .onboarding) }
        guard signedIn else { return SplashOutcome(destination: .main) }

        switch status {
        case .blocked: return SplashOutcome(destination: .main, signOut: true, alert: .blocked)
        case .deleted: return SplashOutcome(destination: .main, signOut: true, alert: .deleted)
        case nil, .active, .pendingProfile: break
        }

        if hasPasswordProvider, !isEmailVerified { return SplashOutcome(destination: .emailVerification) }
        return SplashOutcome(destination: status == .pendingProfile ? .profileBootstrap : .main)
    }
}
