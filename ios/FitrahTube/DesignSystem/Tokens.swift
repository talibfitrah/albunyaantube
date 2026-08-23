import SwiftUI
import UIKit

// MARK: - Colors (Android values/colors.xml ↔ values-night/colors.xml)

private extension Color {
    /// Dynamic color from two hex values (0xRRGGBB or 0xAARRGGBB).
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(argb: dark) : UIColor(argb: light)
        })
    }
}

extension UIColor {
    convenience init(argb: UInt32) {
        // Heuristic: 0x00RRGGBB (fully transparent, non-zero RGB) is indistinguishable from opaque 0xRRGGBB; no token uses that.
        let hasAlpha = argb > 0xFFFFFF
        let a = hasAlpha ? CGFloat((argb >> 24) & 0xFF) / 255 : 1
        self.init(
            red: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: a
        )
    }
}

extension Color {
    static let brand = Color(light: 0x275E4B, dark: 0x35C491)            // primary_green — text-safe in both modes
    static let accent = Color(light: 0x35C491, dark: 0x35C491)           // primary_variant — fills only
    static let onBrand = Color(light: 0xFFFFFF, dark: 0x0A1F18)          // label on a brand/accent fill (Android filter_chip selected text)
    static let surfaceVariant = Color(light: 0xE3E9E7, dark: 0x1A2E27)
    static let background = Color(light: 0xF5F5F5, dark: 0x121212)       // background_gray
    static let homeSurface = Color(light: 0xF5F6F8, dark: 0x0F1512)
    static let homeCard = Color(light: 0xFFFFFF, dark: 0x1A231F)
    static let categoryPill = Color(light: 0xE8F5F0, dark: 0x12352B)
    static let textPrimary = Color(light: 0x1A1A1A, dark: 0xF1F5F9)
    static let textSecondary = Color(light: 0x6B7280, dark: 0x9CB3A7)
    static let textMuted = Color(light: 0x9CA3AF, dark: 0x74847C)
    static let accentRed = Color(light: 0xD32F2F, dark: 0xEF5350)
    static let durationChip = Color(light: 0xCC000000, dark: 0xCC000000)
    static let videoCountChip = Color(light: 0xCC275E4B, dark: 0xCC35C491)
    static let errorBackground = Color(light: 0xFFF3E0, dark: 0x3D2A1A)
    static let errorText = Color(light: 0xE65100, dark: 0xFFCC80)
    static let errorIcon = Color(light: 0xFF6F00, dark: 0xFFB74D)
    static let skeleton = Color(light: 0xE0E0E0, dark: 0x2A2A2A)
    // Fix round 1, finding #5: light `skeletonShimmer` used to equal `background` (both
    // 0xF5F5F5), so the shimmer phase in `SkeletonListView`/`SkeletonGrid` (StateViews.swift)
    // rendered rows invisible against the page every other second. Dark mode was never affected
    // (0x383838 vs. 0x121212 background -- already distinct); only the light value changes here.
    static let skeletonShimmer = Color(light: 0xFFFFFF, dark: 0x383838)
    static let navInactive = Color(light: 0x757575, dark: 0xB0B0B0)
    static let divider = Color(light: 0x1A000000, dark: 0x1AFFFFFF)
    // Material red/blue 800, not 500 (gate wave-2 W8): `Badge` prints white caption2-bold on these
    // fills, and white on #F44336 is 4.0:1 / on #2196F3 is 3.1:1 -- both under WCAG AA's 4.5:1.
    // Darkening the fill is what actually fixes it: white here measures 5.6:1 on both, in both
    // appearances. (Swapping the *text* to `onBrand` would not: it is white in light mode, and its
    // dark-mode near-black on a red 500 fill is 3.1:1.)
    static let liveBadge = Color(light: 0xC62828, dark: 0xC62828)
    static let upcomingBadge = Color(light: 0x1565C0, dark: 0x1565C0)
    static let heroOverlay = Color(light: 0x40000000, dark: 0x66000000)
    static let settingsIconBackground = Color(light: 0xF0F0F0, dark: 0x2A3530)
    static let submissionPending = Color(light: 0xFFA000, dark: 0xFFA000)
    static let submissionApproved = Color(light: 0x43A047, dark: 0x43A047)
    static let submissionRejected = Color(light: 0xE53935, dark: 0xE53935)
    static let submissionChanges = Color(light: 0x1E88E5, dark: 0x1E88E5)
}

// MARK: - Width class (Android layout/ ↔ layout-sw600dp/ ↔ layout-sw720dp/)

nonisolated enum WidthClass: Equatable {
    case compact, regular, large

    /// `.compact` < 600 pt mirrors Android's sw600dp threshold directly. `.large` begins at
    /// 1000 pt by design (spec §7: "regular ≥1000 pt approximating sw720"), not at Android's
    /// 720 dp -- pt and dp aren't the same physical unit, so 1000 pt is a deliberately chosen
    /// threshold approximating the sw720dp tablet/TV bucket, not a unit-for-unit port of it.
    init(width: CGFloat) {
        switch width {
        case ..<600: self = .compact
        case ..<1000: self = .regular
        default: self = .large
        }
    }

    /// Android sw600dp/sw720dp bucket on the smallest width; does not flip on rotation.
    init(size: CGSize) { self.init(width: min(size.width, size.height)) }

    /// Selects the value for this width class -- the shared three-way branch every token table
    /// below picks from.
    func pick<T>(_ compact: T, _ regular: T, _ large: T) -> T {
        switch self { case .compact: compact; case .regular: regular; case .large: large }
    }
}

extension EnvironmentValues {
    /// Set by the root view from its width (Android layout / sw600dp / sw720dp buckets).
    @Entry var widthClass: WidthClass = .compact
}

// MARK: - Spacing (dimens.xml + sw600dp/sw720dp overrides, `.large` at the 1000 pt threshold
// above -- see WidthClass), points

enum Spacing {
    static let xxs: CGFloat = 2
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let xxl: CGFloat = 48

    static func md(_ w: WidthClass) -> CGFloat { w.pick(16, 20, 24) }
    static func lg(_ w: WidthClass) -> CGFloat { w.pick(24, 32, 40) }
    static func xl(_ w: WidthClass) -> CGFloat { w.pick(32, 48, 64) }
    static func xxxl(_ w: WidthClass) -> CGFloat { w.pick(96, 112, 128) }

    /// `home_horizontal_margin` (shell-home.md:227) -- distinct from `md`'s 16/20/24: Home's
    /// left/right screen edge uses its own dimen, 16/24/32.
    static func homeHorizontalMargin(_ w: WidthClass) -> CGFloat { w.pick(16, 24, 32) }
}

// MARK: - Radii (dimens.xml)

enum Radius {
    static let card: CGFloat = 16
    static let homeThumbnail: CGFloat = 12
    static let thumbnail: CGFloat = 8
    static let chip: CGFloat = 4
    static let filterChip: CGFloat = 20
    static let dialog: CGFloat = 20
    static let meChip: CGFloat = 28
    static let pill: CGFloat = 999
}

// MARK: - Type scale (dimens.xml / styles.xml sizes; Dynamic Type via relative text styles)

enum TypeScale {
    /// .title3 20 — Android headline 20 bold; scales to .title2 (~22–24) on .large so a tablet
    /// headline doesn't sit at phone size in a much bigger layout.
    static func headline(_ w: WidthClass) -> Font {
        Font.system(w.pick(.title3, .title3, .title2), weight: .bold)
    }
    static let sectionTitle = Font.system(.headline, weight: .semibold) // .headline 17 — Android 18
    static let subtitle = Font.system(.callout)                          // .callout 16 — Android 16
    /// .subheadline 15 — Android body 14; scales to .callout (~16) on .large.
    static func body(_ w: WidthClass) -> Font {
        Font.system(w.pick(.subheadline, .subheadline, .callout))
    }
    static let caption = Font.system(.caption)                           // .caption 12 — Android 12
    static let badge = Font.system(.caption2, weight: .bold)             // .caption2 11 bold — Android 10 bold
    static let itemTitle = Font.system(.subheadline, weight: .medium)    // .subheadline 15 medium — Android 15 medium
    static let itemMeta = Font.system(.footnote)                         // .footnote 13 — Android 13
    static let seeAll = Font.system(.subheadline, weight: .medium)       // .subheadline 15 medium — Android 14 medium
}

// MARK: - Shell layout (Android layout/ BottomNavigationView ↔ layout-sw600dp/layout-sw720dp
// NavigationRailView -- task-7b)

/// The pure rail-vs-bottom-bar decision `MainShellView` branches on. Compact width keeps
/// Android's `BottomNavigationView` (bottom tab bar); regular/large switch to Android's
/// `NavigationRailView` (leading rail) -- branched on `WidthClass`, never on device idiom, per
/// spec §6.
nonisolated enum ShellLayout: Equatable {
    case bottomBar, rail

    init(_ widthClass: WidthClass) {
        self = widthClass == .compact ? .bottomBar : .rail
    }
}

/// `NavigationRailView` sizing (`layout-sw600dp/dimens.xml`: navigation_rail_width 80dp,
/// navigation_rail_icon_size 28dp, label unset -> Material default ~12sp;
/// `layout-sw720dp/dimens.xml`: 96dp / 32dp / 14sp explicit). `.compact`'s values are never read
/// (the rail doesn't render below 600pt) but `WidthClass.pick` needs a third case, so they repeat
/// the regular value harmlessly.
nonisolated enum NavigationRailMetrics {
    static func width(_ w: WidthClass) -> CGFloat { w.pick(80, 80, 96) }
    static func iconSize(_ w: WidthClass) -> CGFloat { w.pick(28, 28, 32) }
    /// No system Dynamic Type style sits at exactly 14pt, so 14sp maps to the nearest built-in
    /// style above `.caption` (12pt, used for the 12sp/unset case) -- `.footnote` (13pt) -- same
    /// "nearest style, not exact px" approach as `TypeScale.headline`/`.body` above.
    static func label(_ w: WidthClass) -> Font { Font.system(w.pick(.caption, .caption, .footnote)) }
}

// MARK: - Touch targets

enum Size {
    static func button(_ w: WidthClass) -> CGFloat { w.pick(56, 56, 64) }
    static func buttonMinWidth(_ w: WidthClass) -> CGFloat { w.pick(120, 140, 160) }
    static func iconXL(_ w: WidthClass) -> CGFloat { w.pick(96, 96, 128) }
    static func stateBodyMaxWidth(_ w: WidthClass) -> CGFloat { w.pick(300, 400, 480) }
}
