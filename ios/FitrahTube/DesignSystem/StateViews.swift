import SwiftUI

/// Android empty_state.xml / error_state.xml share one layout: icon, optional bold headline,
/// body ≤ 300 pt, optional button. Icon tint and heading are the only two things that vary
/// between an empty state (brand icon, no heading) and an error state (red icon, heading).
struct EmptyStateView: View {
    let systemImage: String
    var iconColor: Color = .brand
    var title: String? = nil
    let message: String
    var action: (title: String, run: () -> Void)? = nil
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            VStack(spacing: Spacing.md(widthClass)) {
                Image(systemName: systemImage)
                    .font(.system(size: Size.iconXL(widthClass)))
                    .foregroundStyle(iconColor)
                    .accessibilityHidden(true)
                if let title {
                    Text(title)
                        .font(TypeScale.headline(widthClass))
                        .foregroundStyle(Color.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                }
                StateMessage(text: message)
            }
            .accessibilityElement(children: .combine)
            if let action {
                StateButton(title: action.title, action: action.run)
            }
        }
        .padding(Spacing.lg(widthClass))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Android error_state.xml: red icon, optional heading, message, retry button.
struct ErrorStateView: View {
    var title: String? = nil
    let message: String
    let retry: () -> Void

    var body: some View {
        EmptyStateView(
            systemImage: "exclamationmark.triangle.fill",
            iconColor: .accentRed,
            title: title,
            message: message,
            action: (String(localized: "retry"), retry)
        )
    }
}

private struct StateMessage: View {
    let text: String
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        Text(text)
            .font(TypeScale.body(widthClass))
            .foregroundStyle(Color.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: Size.stateBodyMaxWidth(widthClass))
    }
}

private struct StateButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        Button(action: action) {
            Text(title)
                .foregroundStyle(Color.onBrand)
                .frame(minWidth: Size.buttonMinWidth(widthClass), minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .controlSize(widthClass == .large ? .extraLarge : .large)
        .tint(.brand)
    }
}

/// Android skeleton_content_item.xml: 120×90 thumbnail block + two text bars, shimmering; static
/// under Reduce Motion. Drives off the shared `Shimmer` (gate wave-2 W9) -- the TimelineView tick,
/// the phase and the Reduce Motion branch used to be re-implemented here, subtly out of step with
/// the copy `SkeletonGrid`/`SkeletonCarousel` use.
struct SkeletonListView: View {
    var rows: Int = 6
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        Shimmer { fill in
            VStack(spacing: Spacing.md(widthClass)) {
                ForEach(0..<rows, id: \.self) { _ in
                    HStack(spacing: Spacing.sm) {
                        RoundedRectangle(cornerRadius: Radius.thumbnail)
                            .fill(fill)
                            .frame(width: 120, height: 90)
                        VStack(alignment: .leading, spacing: Spacing.sm) {
                            RoundedRectangle(cornerRadius: Radius.chip).fill(fill).frame(height: 16)
                            RoundedRectangle(cornerRadius: Radius.chip).fill(fill).frame(width: 140, height: 12)
                        }
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(Spacing.md(widthClass))
            .accessibilityLabel(String(localized: "loading"))
        }
    }
}

#Preview("Empty") {
    EmptyStateView(systemImage: "tray", title: "No downloads yet", message: "Downloaded videos will appear here")
}

#Preview("Error") {
    ErrorStateView(message: "Couldn't load content.") {}
}

#Preview("Error with title") {
    ErrorStateView(title: "Couldn't load content", message: "Check your connection and try again.") {}
}

#Preview("Skeleton") {
    SkeletonListView()
}
