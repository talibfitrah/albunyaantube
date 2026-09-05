/// Where `RootView` sends the user once the splash decision is made. Phase 1 only knew about
/// the onboarding flag; phase 4 adds the signed-in/account-status branches from
/// `splash-onboarding.md:1.7` (guest routing per spec §6 -- iOS never forces sign-in).
nonisolated enum SplashDestination: Equatable {
    case onboarding
    case main
    case profileBootstrap
    case emailVerification
}

/// The whole launch decision: where to go, and the terminal event to surface. `alert` travels with
/// the destination because the blocked/deleted rows are both at once, and it is what `RootView.act`
/// routes through `session.handle` — the drop is a CONSEQUENCE of the event, not a second field.
/// Stage 8 / S1: there used to be a `signOut: Bool` beside it; fix round 2 / M8 removed its last
/// reader (`RootView.act`'s branch) and left it written on the two rows that no longer needed it.
nonisolated struct SplashOutcome: Equatable {
    var destination: SplashDestination
    var alert: AccountStatusEvent?
}

nonisolated enum SplashRouter {
    /// Spec §6 + §13 bullet 1, with the forced sign-in removed (D11 / RULING 31):
    ///   !onboardingCompleted                       -> onboarding
    ///   signed out                                 -> main (guest)
    ///   BLOCKED / DELETED                          -> main (guest) + terminal alert (which signs out)
    ///   password provider AND !emailVerified       -> emailVerification   (§13, ahead of status)
    ///   status == nil (network) / unknown wire     -> main (guest; the foreground refresh retries)
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
        case .blocked: return SplashOutcome(destination: .main, alert: .blocked)
        case .deleted: return SplashOutcome(destination: .main, alert: .deleted)
        // Stage 3 / I5: `.unknown` rides the `status == nil` row — signed in, guest shell, no
        // sign-out and no alert. A status this build cannot name is a reason to keep asking, never
        // a reason to terminate a session fleet-wide.
        case nil, .unknown, .active, .pendingProfile: break
        }

        if hasPasswordProvider, !isEmailVerified { return SplashOutcome(destination: .emailVerification) }
        return SplashOutcome(destination: status == .pendingProfile ? .profileBootstrap : .main)
    }
}
