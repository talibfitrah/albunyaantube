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

private struct AboutLink: Identifiable {
    let titleKey: String
    let url: URL
    var id: String { titleKey }
}

/// Android's `AboutFragment` (`favorites-settings-about.md:258-288`). URLs are Android's verbatim
/// (RULING 34); links open via `Link`, SwiftUI's native equivalent of Android's `ACTION_VIEW`
/// external-browser intent.
struct AboutView: View {
    @Environment(\.widthClass) private var widthClass

    @State private var tapGate = TapGate()
    @State private var stepsAwayMessage: BannerMessage?
    @State private var showDeveloperDialog = false

    private let links: [AboutLink] = [
        AboutLink(titleKey: "about_website", url: URL(string: "https://albunyaan.tube")!),
        AboutLink(titleKey: "about_github", url: URL(string: "https://github.com/albunyaan/albunyaan-tube")!),
    ]
    private let legal: [AboutLink] = [
        AboutLink(titleKey: "about_privacy_policy", url: URL(string: "https://albunyaan.tube/privacy")!),
        AboutLink(titleKey: "about_terms_of_service", url: URL(string: "https://albunyaan.tube/terms")!),
        AboutLink(titleKey: "about_open_source_licenses", url: URL(string: "https://albunyaan.tube/licenses")!),
    ]

    var body: some View {
        Form {
            Section {
                appInfoCard
            }
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)

            Section(String(localized: "about_links")) {
                ForEach(links) { linkRow($0) }
            }
            Section(String(localized: "about_legal")) {
                ForEach(legal) { linkRow($0) }
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
            if ProcessInfo.processInfo.arguments.contains("-fitrah-show-developer-dialog") {
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
            let format = String(localized: "dev_settings_steps_away")
            stepsAwayMessage = BannerMessage(text: String(format: format, Int64(remaining)))
        case .unlocked:
            showDeveloperDialog = true
        }
    }
}

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
