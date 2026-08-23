import SwiftUI

/// Android's `onboarding/OnboardingPage.kt:13-28` -- a static 3-item list, no VM, no remote
/// content. Icons are SF Symbols per RULINGS.md contradiction 6 / `strings-assets.md:732-770`
/// (compass -> `safari`, not the table's own `play.circle` suggestion).
nonisolated struct OnboardingPage {
    let icon: String
    let titleKey: String
    let descriptionKey: String

    static let all: [OnboardingPage] = [
        OnboardingPage(icon: "safari", titleKey: "onboarding_page1_title", descriptionKey: "onboarding_page1_desc"),
        OnboardingPage(icon: "headphones", titleKey: "onboarding_page2_title", descriptionKey: "onboarding_page2_desc"),
        OnboardingPage(icon: "arrow.down.circle.fill", titleKey: "onboarding_page3_title", descriptionKey: "onboarding_page3_desc"),
    ]
}

/// `OnboardingFragment.kt:79-85`: "Get Started" only on the last page, "Next" otherwise. Pure so
/// it's testable without a `View` (`OnboardingTests`).
nonisolated enum OnboardingCTA {
    static func isLastPage(currentPage: Int, pageCount: Int) -> Bool {
        currentPage >= pageCount - 1
    }

    static func titleKey(currentPage: Int, pageCount: Int) -> String {
        isLastPage(currentPage: currentPage, pageCount: pageCount) ? "onboarding_get_started" : "onboarding_continue"
    }
}

/// RULING 3 (non-dismissible until Skip/Get started) is structural here: `RootView` swaps this in
/// as its whole root view, never as a sheet, so there is no dismiss gesture to disable --
/// `.interactiveDismissDisabled()` was a no-op and is gone.
///
/// Android's `OnboardingFragment` (`splash-onboarding.md` §2). Skip and Get Started are
/// behaviourally identical (`:59`) and both persist the flag before anything dismisses
/// (`navigateToMain()` ordering bug fix, `:324-330`) -- here that's just `container.settings`'s
/// synchronous `UserDefaults` write completing before this method returns.
struct OnboardingView: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @State private var currentPage = Self.initialPage

    private let pages = OnboardingPage.all

    #if DEBUG
    /// Acceptance artefact hook (task 8): `-fitrah-onboarding-page <n>` jumps straight to a page
    /// for a screenshot, since this sandbox has no reliable way to synthesize a swipe/tap
    /// (`RootView`'s `-fitrah-gallery-section` is the same pattern). Absent/unparsable -> page 0.
    private static var initialPage: Int {
        let args = ProcessInfo.processInfo.arguments
        guard let flagIndex = args.firstIndex(of: "-fitrah-onboarding-page"), args.indices.contains(flagIndex + 1),
              let page = Int(args[flagIndex + 1]) else { return 0 }
        return page
    }
    #else
    private static let initialPage = 0
    #endif

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $currentPage) {
                ForEach(Array(pages.enumerated()), id: \.offset) { index, page in
                    OnboardingPageContent(page: page).tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never)) // custom dots below, not the system ones
            .accessibilityLabel(String(localized: "onboarding_carousel_content"))

            dots.padding(.bottom, Spacing.lg(widthClass))
            primaryCTA.padding(.horizontal, Spacing.lg(widthClass)).padding(.bottom, Spacing.md(widthClass))
            skipButton.padding(.bottom, Spacing.lg(widthClass))
        }
        .background(Color.background.ignoresSafeArea())
    }

    // MARK: Dots (RULINGS.md contradiction 7: inactive dot is a token, not Android's hardcoded #CCCCCC)

    private var dots: some View {
        HStack(spacing: 0) {
            ForEach(pages.indices, id: \.self) { index in
                Circle()
                    .fill(index == currentPage ? Color.brand : Color.textMuted)
                    .frame(width: 8, height: 8)
                    .padding(6) // Android's per-dot 6dp margin -> 12dp gap between dots
            }
        }
        .accessibilityHidden(true) // currentPage is announced via the pager's own label/CTA text
    }

    // MARK: Primary CTA (capsule, brand fill, `onBrand` label -- RULING fixing Android's white-on-dark-mint AA fail)

    private var primaryCTA: some View {
        Button(action: advance) {
            Text(String(localized: String.LocalizationValue(OnboardingCTA.titleKey(currentPage: currentPage, pageCount: pages.count))))
                .font(TypeScale.body(widthClass)).fontWeight(.semibold)
                .foregroundStyle(Color.onBrand)
                .frame(maxWidth: 400)
                .frame(height: widthClass.pick(56, 60, 64))
                .background(Color.brand, in: Capsule())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }

    // MARK: Skip (TextButton, identical action to the last page's CTA)

    private var skipButton: some View {
        Button(action: finish) {
            Text(String(localized: "onboarding_skip"))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textSecondary)
        }
        .buttonStyle(.plain)
    }

    private func advance() {
        if OnboardingCTA.isLastPage(currentPage: currentPage, pageCount: pages.count) {
            finish()
        } else {
            currentPage += 1
        }
    }

    private func finish() {
        container.settings.onboardingCompleted = true
    }
}

/// `page_onboarding_item.xml` -- icon in a circle, title, description, packed vertically and
/// centred. Metrics: icon plate 160/160/180 with 80/80/90 icon, title 28/32/36sp, body 16/18/20sp
/// (task-8 brief global constraints; `strings-assets.md:814-828`).
private struct OnboardingPageContent: View {
    let page: OnboardingPage
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            Circle()
                .fill(Color.settingsIconBackground)
                .frame(width: widthClass.pick(160, 160, 180), height: widthClass.pick(160, 160, 180))
                .overlay {
                    Image(systemName: page.icon)
                        .font(.system(size: widthClass.pick(80, 80, 90)))
                        .foregroundStyle(Color.brand)
                        .accessibilityHidden(true) // decorative -- the pager already carries one a11y label
                }
            Text(String(localized: String.LocalizationValue(page.titleKey)))
                .font(.system(size: widthClass.pick(28, 32, 36), weight: .bold))
                .foregroundStyle(Color.textPrimary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 600)
                .padding(.top, Spacing.xl(widthClass))
            Text(String(localized: String.LocalizationValue(page.descriptionKey)))
                .font(.system(size: widthClass.pick(16, 18, 20)))
                .foregroundStyle(Color.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 600)
                .padding(.top, Spacing.md(widthClass))
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Spacing.lg(widthClass))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    OnboardingView().environment(\.container, .sharedFake)
}

#Preview("RTL") {
    OnboardingView()
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
