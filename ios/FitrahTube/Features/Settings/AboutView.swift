import SwiftUI

/// Android's 7-tap-in-3s developer gesture (`favorites-settings-about.md:279-288`), extracted as a
/// pure state machine so `SettingsRowsTests` can drive it with synthetic timestamps instead of
/// real time. `now` is injected per call (Android's `SystemClock.elapsedRealtime()`, a monotonic
/// clock immune to wall-clock changes) rather than read internally -- the iOS equivalent is
/// `ProcessInfo.processInfo.systemUptime`, supplied by the caller (`AboutView.handleVersionTap`).
nonisolated struct TapGate {
    enum Outcome: Equatable {
        case silent
        case stepsAway(Int)
        case unlocked
    }

    private static let threshold = 7
    private static let timeout: TimeInterval = 3

    private(set) var count = 0
    private var lastTapTime: TimeInterval?

    init() {}

    /// "the counter resets to 0 before incrementing" on a >3s gap; `lastTapTime` updates on every
    /// tap regardless. Tap 7 resets the counter back to 0 as it fires `.unlocked`.
    mutating func tap(now: TimeInterval) -> Outcome {
        if let lastTapTime, now - lastTapTime > Self.timeout {
            count = 0
        }
        lastTapTime = now
        count += 1

        if count >= Self.threshold {
            count = 0
            return .unlocked
        }
        if count >= Self.threshold - 3 { // taps 4, 5, 6
            return .stepsAway(Self.threshold - count)
        }
        return .silent
    }
}

/// `about_version_format` = "Version %1$@ (%2$@)" (task-13 brief) -- pulled out so
/// `SettingsRowsTests` can assert the exact substitution.
nonisolated enum AboutVersionText {
    static func format(version: String, build: String) -> String {
        String(format: String(localized: "about_version_format"), arguments: [version, build])
    }
}

struct AboutLink: Identifiable {
    let titleKey: String
    let url: URL
    var id: String { titleKey }
}

/// Internal, not `private` on the view, so `SettingsRowsTests` can pin every host.
///
/// Android's ANDROID-ABOUT-URL-01 choices, mirrored (`AboutFragment.kt` `setupLinks`): every link
/// used to point at `albunyaan.tube`, which has no DNS record. The legal pages are the backend's
/// `LegalPagesController`, on the host the share links already use. There is NO website row: no
/// public site exists (`fitrahtube.com` 404s, `app.fitrahtube.com/` 403s — probed 2026-09-21), and
/// Android hides the row by owner decision for the same reason. No GitHub row either (Phase 6
/// OQ-1 (a)): the app links only to fitrahtube.com.
nonisolated enum AboutLinks {
    static let legal: [AboutLink] = [
        AboutLink(titleKey: "about_privacy_policy", url: URL(string: "https://app.fitrahtube.com/privacy")!),
        AboutLink(titleKey: "about_terms_of_service", url: URL(string: "https://app.fitrahtube.com/terms")!),
        AboutLink(titleKey: "about_open_source_licenses", url: URL(string: "https://app.fitrahtube.com/licenses")!),
    ]
}

/// Android's `AboutFragment` (`favorites-settings-about.md:258-288`). Links open via `Link`,
/// SwiftUI's native equivalent of Android's `ACTION_VIEW` external-browser intent.
struct AboutView: View {
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var tapGate = TapGate()
    @State private var stepsAwayMessage: BannerMessage?
    @State private var showDeveloperDialog = false

    var body: some View {
        Form {
            Section {
                appInfoCard
            }
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)

            Section(String(localized: "about_legal")) {
                ForEach(AboutLinks.legal) { linkRow($0) }
            }
            // Phase 3 Task 8 (spec §10's "known ceiling, documented in the About -> Help text"):
            // a cast stream URL is bound to the phone's public IP, so the receiver can only fetch
            // it from behind the same IPv4 NAT. Stated as the user-visible condition (same Wi-Fi,
            // not cellular, not IPv6-only) rather than as the mechanism.
            Section {
                Text(String(localized: "cast_help_network"))
                    .font(.footnote)
                    .foregroundStyle(Color.textSecondary)
            }
        }
        .navigationTitle(String(localized: "about_title"))
        .navigationBarTitleDisplayMode(.inline)
        .transientBanner($stepsAwayMessage)
        .sheet(isPresented: $showDeveloperDialog) { DeveloperDialog() }
        .task {
            #if DEBUG
            // Acceptance-screenshot hook (task-13): the Developer dialog otherwise only opens
            // after 7 real taps on the version text within 3s, which `simctl launch` can't
            // perform -- same technique as FavoritesView's `-fitrah-show-clear-all-confirm`.
            if LaunchArguments.debug.contains("-fitrah-show-developer-dialog") {
                showDeveloperDialog = true
            }
            #endif
        }
    }

    private var appInfoCard: some View {
        VStack(spacing: Spacing.sm) {
            // "iOS: use the real app icon here" (contract §3.1) -- reuses the same asset SplashView
            // already renders for this exact purpose, in lieu of reading CFBundleIcons at runtime.
            Image("logo")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 80, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: Radius.thumbnail))
                .accessibilityHidden(true)
            Text(String(localized: "app_name"))
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Color.textPrimary)
            Text(versionText)
                .font(.subheadline)
                .foregroundStyle(Color.textSecondary)
                .onTapGesture { handleVersionTap() }
                .accessibilityAddTraits(.isButton)
            Text(String(localized: "splash_tagline"))
                .font(.subheadline)
                .foregroundStyle(Color.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(Spacing.lg(widthClass))
        .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
    }

    private func linkRow(_ link: AboutLink) -> some View {
        Link(destination: link.url) {
            HStack {
                Text(String(localized: String.LocalizationValue(link.titleKey)))
                    .foregroundStyle(Color.textPrimary)
                Spacer()
                Image(systemName: "chevron.forward")
                    .font(.caption)
                    .foregroundStyle(Color.textMuted)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
    }

    private var versionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        return AboutVersionText.format(version: version, build: build)
    }

    private func handleVersionTap() {
        switch tapGate.tap(now: ProcessInfo.processInfo.systemUptime) {
        case .silent:
            break
        case .stepsAway(let remaining):
            // `dev_settings_steps_away` carries one/other plural variations in all three locales,
            // and `String(localized:)` cannot select a category without the count embedded in the
            // `LocalizationValue` -- at best the singular was never chosen, at worst the raw
            // `%#@…@` token rendered. Passing a locale also gets the app locale's digits rather
            // than the system's (gate B1-minor-16).
            stepsAwayMessage = BannerMessage(
                text: Format.localizedFormat("dev_settings_steps_away", locale: locale, Int64(remaining)))
        case .unlocked:
            showDeveloperDialog = true
        }
    }
}

#if DEBUG
#Preview {
    NavigationStack { AboutView() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { AboutView() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
