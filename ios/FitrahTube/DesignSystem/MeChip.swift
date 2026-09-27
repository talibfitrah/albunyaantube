import SwiftUI

/// Spec §7's missing component: r28 (`Radius.meChip`), 1 pt outline, 32 pt avatar.
/// `HomeChannelItem` is the closest existing shape but is a vertical tile, not a chip.
///
/// Selection is `label + trait`, never colour alone (spec §14): the brand fill is the sighted
/// signal, `.isSelected` is the VoiceOver one.
struct MeChip: View {
    let title: String
    let avatarURL: URL?
    let isSelected: Bool
    let action: () -> Void

    @Environment(\.widthClass) private var widthClass

    /// The avatar scales with Dynamic Type — a flat 32 pt next to text that grew to
    /// `.accessibility3` reads as a bug, and the chip's own height follows the label anyway.
    @ScaledMetric(relativeTo: .subheadline) private var avatarSize: CGFloat = 32

    var body: some View {
        Button(action: action) {
            HStack(spacing: Spacing.sm) {
                RemoteImage(url: avatarURL)
                    .frame(width: avatarSize, height: avatarSize)
                    .clipShape(Circle())
                    .accessibilityHidden(true)
                Text(title)
                    .font(TypeScale.seeAll)
                    .lineLimit(1)
            }
            .foregroundStyle(isSelected ? Color.onBrand : Color.textPrimary)
            .padding(.vertical, Spacing.sm)
            .padding(.horizontal, Spacing.md(widthClass))
            // ≥44 pt target: the 32 pt avatar plus 8 pt of vertical padding either side is 48 pt,
            // and `minHeight` keeps it there if the avatar ever shrinks.
            .frame(minHeight: 44)
            .background(isSelected ? Color.brand : Color.homeCard,
                        in: RoundedRectangle(cornerRadius: Radius.meChip))
            .overlay(
                RoundedRectangle(cornerRadius: Radius.meChip)
                    .strokeBorder(isSelected ? Color.brand : Color.divider, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }
}

#if DEBUG
#Preview {
    HStack {
        MeChip(title: "Channel One", avatarURL: nil, isSelected: false) {}
        MeChip(title: "Playlist One", avatarURL: nil, isSelected: true) {}
    }
    .padding()
    .background(Color.background)
}
#endif
