import SwiftUI

/// Grid/carousel sizing rules ported from Android's column-count integers and
/// `HomeFragment.calculateCardWidths` (spec §7 "Grids";
/// `docs/superpowers/plans/2026-08-23-ios-phase1-research/shell-home.md` "Card widths (computed,
/// not fixed)"). `nonisolated` -- every function here is a pure, thread-safe transform, same
/// rationale as `Format` in Catalog/Formatting.swift.
nonisolated enum GridRules {
    /// Channels/playlists grid columns by width class. RULINGS.md #14 corrects spec §7's "2/3/4"
    /// to "1/3/4" -- Android renders these as single-column rows below sw600dp, not a 2-column grid.
    static func listColumns(_ w: WidthClass) -> Int { w.pick(1, 3, 4) }

    /// Videos tab columns: `max(2, min(8, floor(width/180)))` (shell-home.md Grids row).
    static func videoColumns(width: CGFloat) -> Int {
        max(2, min(8, Int(width / 180)))
    }

    /// Spec §14: "Dynamic Type everywhere, **single column at `.accessibility1+`**". A grid cell
    /// at an accessibility text size needs the whole width for its own title, so every grid
    /// collapses to one column there regardless of width class -- task-14
    /// (`screenshots/task-14/iphone-17/videos-en-light-a11y3-portrait.png` still showed two).
    static func columns(_ base: Int, dynamicTypeSize: DynamicTypeSize) -> Int {
        dynamicTypeSize.isAccessibilitySize ? 1 : base
    }

    /// Home carousel visible-card counts by content type and width class
    /// (`home_cards_visible_videos/channels/playlists`, shell-home.md "Card widths" table).
    static func carouselVisible(_ type: ContentType, _ w: WidthClass) -> Int {
        switch type {
        case .video: w.pick(2, 3, 5)
        case .channel: w.pick(2, 4, 6)
        case .playlist: w.pick(2, 3, 5)
        }
    }

    /// `((container − 2·margin − (visible−1)·gap) / visible) · 0.98` -- the 0.98 factor
    /// deliberately leaves a sliver of the next card visible as a scroll affordance (keep it,
    /// shell-home.md). Returns 0 when `visible <= 0` (Android parity: `HomeFragment.kt:97-100`).
    static func carouselCardWidth(container: CGFloat, margin: CGFloat, gap: CGFloat, visible: Int) -> CGFloat {
        guard visible > 0 else { return 0 }
        // `max(0, …)`: a zero-size container (first layout pass, or mid-transition) made this
        // negative -- (0 − 32 − 24)/3 × 0.98 = −18.3 -- and SwiftUI treats a negative frame
        // dimension as undefined (gate A-M5).
        return max(0, ((container - 2 * margin - CGFloat(visible - 1) * gap) / CGFloat(visible)) * 0.98)
    }

    /// `home_card_spacing` (shell-home.md "Card widths" table): the gap between adjacent carousel
    /// cards -- 12/16/20 pt by bucket. No existing consumer to stay aligned with (unlike the
    /// carousel's horizontal margin, which callers pass as `Spacing.md` to stay flush with
    /// `SectionHeader`'s own padding), so this is a precise port of Android's dimen.
    static func cardGap(_ w: WidthClass) -> CGFloat { w.pick(12, 16, 20) }
}

extension View {
    /// Reports whether the scrollable content currently fits its container without scrolling --
    /// large screens (tablet/TV) can show a full page of items with nothing to scroll, so a
    /// scroll-position listener alone never fires `loadMore()` there (CLAUDE.md pagination rule).
    /// Callers pair this with `.onAppear` on the last row, an in-flight guard, and a
    /// `PaginationGuard`.
    ///
    /// The derived value is the **fit margin**, not the Bool the caller wants (gate B1-C1):
    /// `onScrollGeometryChange` only runs its action when the derived value *changes*, and the
    /// Bool stays `true` for exactly the situation autofill exists to fix -- page 1 fits, autofill
    /// appends page 2, the content still fits, no transition, no second round. The list stopped
    /// half-filled with nothing to scroll and no way to load the rest. The margin changes on every
    /// append, so this now fires after every layout the way Android's post-`submitList` autofill
    /// check does.
    func onContentFits(_ fits: @escaping (Bool) -> Void) -> some View {
        onScrollGeometryChange(for: CGFloat.self) { geometry in
            geometry.contentSize.height - geometry.containerSize.height
        } action: { _, margin in
            fits(margin <= 0)
        }
    }
}
