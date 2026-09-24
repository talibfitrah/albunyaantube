/// Where `RootView` sends the user once the splash decision is made. Phase 1 only knew about
/// the onboarding flag; phase 4 adds the signed-in/account-status branches from
/// `splash-onboarding.md:1.7`. Owner ruling 2026-09-24 (overrides D11 / RULING 31): sign-in is
/// forced before any content, as on Android — there is no guest shell.
nonisolated enum SplashDestination: Equatable {
    case onboarding
    case main
    case profileBootstrap
    case emailVerification
    /// The full-screen sign-in root: no tab bar, no dismiss, no way to content.
    case signIn
    /// Signed in, `/me` still in flight: a spinner (Android's splash awaits the same call).
    case awaitingAccount
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
    /// Spec §6 + §13 bullet 1, with sign-in forced (owner ruling 2026-09-24; Android `SplashRouter.kt`):
    ///   !onboardingCompleted                       -> onboarding
    ///   signed out                                 -> signIn
    ///   BLOCKED / DELETED                          -> signIn + terminal alert (which signs out)
    ///   password provider AND !emailVerified       -> emailVerification   (§13, ahead of status)
    ///   status == nil, `/me` in flight             -> awaitingAccount (a spinner)
    ///   status == nil, nothing in flight           -> signIn (a failed `/me` has already signed
    ///                                                 out; a refused sign-out is the exception)
    ///   unknown wire value                         -> main (Stage 3 / I5; Android maps it to BLOCKED)
    ///   PENDING_PROFILE                            -> profileBootstrap
    ///   ACTIVE                                     -> main
    ///
    /// The two terminal statuses are tested BEFORE §13 (plan review): routing a blocked or deleted
    /// account to verification parks it in a loop it can never leave, and dropping the session is
    /// the entire point of those rows.
    static func outcome(onboardingCompleted: Bool, signedIn: Bool,
                        hasPasswordProvider: Bool, isEmailVerified: Bool,
                        status: AccountStatus?, awaitingStatus: Bool) -> SplashOutcome {
        guard onboardingCompleted else { return SplashOutcome(destination: .onboarding) }
        guard signedIn else { return SplashOutcome(destination: .signIn) }

        switch status {
        case .blocked: return SplashOutcome(destination: .signIn, alert: .blocked)
        case .deleted: return SplashOutcome(destination: .signIn, alert: .deleted)
        // Stage 3 / I5: `.unknown` is signed in with a record — main shell, no sign-out and no
        // alert. A status this build cannot name is a reason to keep asking, never a reason to
        // terminate (or wall off) a session fleet-wide.
        case nil, .unknown, .active, .pendingProfile: break
        }

        if hasPasswordProvider, !isEmailVerified { return SplashOutcome(destination: .emailVerification) }
        switch status {
        case nil: return SplashOutcome(destination: awaitingStatus ? .awaitingAccount : .signIn)
        case .pendingProfile: return SplashOutcome(destination: .profileBootstrap)
        default: return SplashOutcome(destination: .main)
        }
    }
}
