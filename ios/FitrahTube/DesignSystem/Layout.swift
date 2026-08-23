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
        return ((container - 2 * margin - CGFloat(visible - 1) * gap) / CGFloat(visible)) * 0.98
    }
}

extension View {
    /// `true` once the scrollable content fits its container without scrolling -- large screens
    /// (tablet/TV) can show a full page of items with nothing to scroll, so a scroll-position
    /// listener alone never fires `loadMore()` there (CLAUDE.md pagination rule). Callers pair
    /// this with `.onAppear` on the last row and an in-flight guard.
    func onContentFits(_ fits: @escaping (Bool) -> Void) -> some View {
        onScrollGeometryChange(for: Bool.self) { geometry in
            geometry.contentSize.height <= geometry.containerSize.height
        } action: { _, fitsNow in
            fits(fitsNow)
        }
    }
}
