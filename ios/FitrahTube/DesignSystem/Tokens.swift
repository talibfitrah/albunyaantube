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
    static let skeletonShimmer = Color(light: 0xF5F5F5, dark: 0x383838)
    static let navInactive = Color(light: 0x757575, dark: 0xB0B0B0)
    static let divider = Color(light: 0x1A000000, dark: 0x1AFFFFFF)
    static let liveBadge = Color(light: 0xF44336, dark: 0xF44336)
    static let upcomingBadge = Color(light: 0x2196F3, dark: 0x2196F3)
    static let heroOverlay = Color(light: 0x40000000, dark: 0x66000000)
    static let settingsIconBackground = Color(light: 0xF0F0F0, dark: 0x2A3530)
    static let submissionPending = Color(light: 0xFFA000, dark: 0xFFA000)
    static let submissionApproved = Color(light: 0x43A047, dark: 0x43A047)
    static let submissionRejected = Color(light: 0xE53935, dark: 0xE53935)
    static let submissionChanges = Color(light: 0x1E88E5, dark: 0x1E88E5)
}

// MARK: - Width class (Android layout/ ↔ layout-sw600dp/ ↔ layout-sw720dp/)

enum WidthClass: Equatable {
    case compact, regular, large

    init(width: CGFloat) {
        switch width {
        case ..<600: self = .compact
        case ..<1000: self = .regular
        default: self = .large
        }
    }
}

extension EnvironmentValues {
    /// Set by the root view from its width (Android layout / sw600dp / sw720dp buckets).
    @Entry var widthClass: WidthClass = .compact
}

// MARK: - Spacing (dimens.xml + sw600dp/sw720dp overrides), points

enum Spacing {
    static let xxs: CGFloat = 2
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let xxl: CGFloat = 48

    static func md(_ w: WidthClass) -> CGFloat { pick(w, 16, 20, 24) }
    static func lg(_ w: WidthClass) -> CGFloat { pick(w, 24, 32, 40) }
    static func xl(_ w: WidthClass) -> CGFloat { pick(w, 32, 48, 64) }
    static func xxxl(_ w: WidthClass) -> CGFloat { pick(w, 96, 112, 128) }

    private static func pick(_ w: WidthClass, _ c: CGFloat, _ r: CGFloat, _ l: CGFloat) -> CGFloat {
        switch w { case .compact: c; case .regular: r; case .large: l }
    }
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
    static let headline = Font.system(.title3, weight: .bold)          // .title3 20 — Android headline 20 bold
    static let sectionTitle = Font.system(.headline, weight: .semibold) // .headline 17 — Android 18
    static let subtitle = Font.system(.callout)                          // .callout 16 — Android 16
    static let body = Font.system(.subheadline)                          // .subheadline 15 — Android 14
    static let caption = Font.system(.caption)                           // .caption 12 — Android 12
    static let badge = Font.system(.caption2, weight: .bold)             // .caption2 11 bold — Android 10 bold
    static let itemTitle = Font.system(.subheadline, weight: .medium)    // .subheadline 15 medium — Android 15 medium
    static let itemMeta = Font.system(.footnote)                         // .footnote 13 — Android 13
    static let seeAll = Font.system(.subheadline, weight: .medium)       // .subheadline 15 medium — Android 14 medium
}

// MARK: - Touch targets

enum Size {
    static let button: CGFloat = 56
}
