import Testing
@testable import FitrahTube

/// `splash-onboarding.md:225-338`, RULINGS contradiction 6 (SF Symbols) + 3 (non-dismissible).
@Suite(.perTest)
struct OnboardingTests {
    // MARK: OnboardingPage.all -- exactly 3 pages, exact icons/keys

    @Test func exactlyThreePages() {
        #expect(OnboardingPage.all.count == 3)
    }

    @Test func pageIconsMatchStringsAssetsSFSymbols() {
        // strings-assets.md:732-770 -- compass -> safari, headphones -> headphones,
        // download_circle -> arrow.down.circle.fill (RULINGS.md contradiction 6).
        #expect(OnboardingPage.all[0].icon == "safari")
        #expect(OnboardingPage.all[1].icon == "headphones")
        #expect(OnboardingPage.all[2].icon == "arrow.down.circle.fill")
    }

    @Test func pageStringKeysMatchAndroidKeysVerbatim() {
        #expect(OnboardingPage.all[0].titleKey == "onboarding_page1_title")
        #expect(OnboardingPage.all[0].descriptionKey == "onboarding_page1_desc")
        #expect(OnboardingPage.all[1].titleKey == "onboarding_page2_title")
        #expect(OnboardingPage.all[1].descriptionKey == "onboarding_page2_desc")
        #expect(OnboardingPage.all[2].titleKey == "onboarding_page3_title")
        #expect(OnboardingPage.all[2].descriptionKey == "onboarding_page3_desc")
    }

    // MARK: OnboardingCTA.titleKey -- "Get Started" only on the last page (:79-85)

    @Test func firstPageShowsContinue() {
        #expect(OnboardingCTA.titleKey(currentPage: 0, pageCount: 3) == "onboarding_continue")
    }

    @Test func middlePageShowsContinue() {
        #expect(OnboardingCTA.titleKey(currentPage: 1, pageCount: 3) == "onboarding_continue")
    }

    @Test func lastPageShowsGetStarted() {
        #expect(OnboardingCTA.titleKey(currentPage: 2, pageCount: 3) == "onboarding_get_started")
    }

    @Test func isLastPage() {
        #expect(OnboardingCTA.isLastPage(currentPage: 0, pageCount: 3) == false)
        #expect(OnboardingCTA.isLastPage(currentPage: 1, pageCount: 3) == false)
        #expect(OnboardingCTA.isLastPage(currentPage: 2, pageCount: 3) == true)
    }
}
