import Testing
@testable import FitrahTube

struct PlayerMetadataViewTests {
    @Test func showMoreIsHiddenWhenTheDescriptionAlreadyFits() {
        // CF-B1-10 / B1 task-8 M2: the toggle rendered unconditionally, so a two-line description
        // got a "Show more" that expanded nothing.
        #expect(DescriptionTruncation.needsToggle(fits: true, isExpanded: false) == false)
        #expect(DescriptionTruncation.needsToggle(fits: false, isExpanded: false))
        #expect(DescriptionTruncation.needsToggle(fits: true, isExpanded: true))   // still offer "Show less"
    }
}
