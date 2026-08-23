import Testing
@testable import FitrahTube

@Suite(.perTest)
struct LayoutRulesTests {
    @Test func listColumnsByWidthClass() {
        // RULINGS.md #14: spec §7's "2/3/4" is corrected to "1/3/4" (single-column rows on phone).
        #expect(GridRules.listColumns(.compact) == 1)
        #expect(GridRules.listColumns(.regular) == 3)
        #expect(GridRules.listColumns(.large) == 4)
    }

    @Test func videoColumnsClampsLowerBoundAtTwo() {
        // floor(359/180) = 1 -> clamped up to the min(2, ...) floor.
        #expect(GridRules.videoColumns(width: 359) == 2)
        // floor(360/180) = 2 exactly -- already at the floor, not clamped.
        #expect(GridRules.videoColumns(width: 360) == 2)
    }

    @Test func videoColumnsClampsUpperBoundAtEight() {
        // floor(1440/180) = 8 exactly -- already at the ceiling, not clamped.
        #expect(GridRules.videoColumns(width: 1440) == 8)
        // floor(1441/180) = 8 (truncation) -- stays at the ceiling one point past the boundary.
        #expect(GridRules.videoColumns(width: 1441) == 8)
    }

    @Test func videoColumnsMidRangeFloorsWidthDividedBy180() {
        #expect(GridRules.videoColumns(width: 720) == 4)
        #expect(GridRules.videoColumns(width: 899) == 4)
        #expect(GridRules.videoColumns(width: 900) == 5)
    }

    @Test func carouselVisibleByTypeAndWidthClass() {
        #expect(GridRules.carouselVisible(.video, .compact) == 2)
        #expect(GridRules.carouselVisible(.video, .regular) == 3)
        #expect(GridRules.carouselVisible(.video, .large) == 5)

        #expect(GridRules.carouselVisible(.channel, .compact) == 2)
        #expect(GridRules.carouselVisible(.channel, .regular) == 4)
        #expect(GridRules.carouselVisible(.channel, .large) == 6)

        #expect(GridRules.carouselVisible(.playlist, .compact) == 2)
        #expect(GridRules.carouselVisible(.playlist, .regular) == 3)
        #expect(GridRules.carouselVisible(.playlist, .large) == 5)
    }

    @Test func carouselCardWidthAppliesThe098Factor() {
        // Phone bucket, 2 visible video cards: margin 16, gap 12 (shell-home.md card-widths table).
        let width = GridRules.carouselCardWidth(container: 390, margin: 16, gap: 12, visible: 2)
        let expected = ((390 - 2 * 16 - 1 * 12) / 2) * 0.98
        #expect(abs(width - expected) < 0.001)
        #expect(abs(width - 169.54) < 0.001)
    }

    @Test func carouselCardWidthLargeBucketFiveVisible() {
        // sw720 bucket: margin 32, gap 20, 5 visible.
        let width = GridRules.carouselCardWidth(container: 1200, margin: 32, gap: 20, visible: 5)
        let expected = ((1200 - 2 * 32 - 4 * 20) / 5) * 0.98
        #expect(abs(width - expected) < 0.001)
    }

    @Test func carouselCardWidthReturnsZeroForNonPositiveVisible() {
        #expect(GridRules.carouselCardWidth(container: 390, margin: 16, gap: 12, visible: 0) == 0)
        #expect(GridRules.carouselCardWidth(container: 390, margin: 16, gap: 12, visible: -1) == 0)
    }
}
