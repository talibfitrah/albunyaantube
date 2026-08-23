import SwiftUI

/// Android empty_state.xml: 96 pt brand icon, 20 bold headline, body ≤ 300 pt, optional 56 pt button.
struct EmptyStateView: View {
    let systemImage: String
    let title: String
    let message: String
    var action: (title: String, run: () -> Void)? = nil
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            VStack(spacing: Spacing.md(widthClass)) {
                Image(systemName: systemImage)
                    .font(.system(size: Size.iconXL(widthClass)))
                    .foregroundStyle(Color.brand)
                    .accessibilityHidden(true)
                Text(title).font(TypeScale.headline(widthClass)).foregroundStyle(Color.textPrimary)
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
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: Size.iconXL(widthClass)))
                .foregroundStyle(Color.accentRed)
                .accessibilityHidden(true)
            if let title {
                Text(title)
                    .font(TypeScale.headline(widthClass))
                    .foregroundStyle(Color.textPrimary)
                    .accessibilityAddTraits(.isHeader)
            }
            StateMessage(text: message)
            StateButton(title: String(localized: "retry"), action: retry)
        }
        .padding(Spacing.lg(widthClass))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                .frame(minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .controlSize(widthClass == .large ? .extraLarge : .large)
        .tint(.brand)
    }
}

/// Android skeleton_content_item.xml: 120×90 thumbnail block + two text bars, shimmering; static under Reduce Motion.
struct SkeletonListView: View {
    var rows: Int = 6
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        TimelineView(.animation(minimumInterval: 1, paused: reduceMotion)) { context in
            let phase = Int(context.date.timeIntervalSinceReferenceDate) % 2 == 0
            VStack(spacing: Spacing.md(widthClass)) {
                ForEach(0..<rows, id: \.self) { _ in
                    HStack(spacing: Spacing.sm) {
                        RoundedRectangle(cornerRadius: Radius.thumbnail)
                            .fill(fill(phase))
                            .frame(width: 120, height: 90)
                        VStack(alignment: .leading, spacing: Spacing.sm) {
                            RoundedRectangle(cornerRadius: Radius.chip).fill(fill(phase)).frame(height: 16)
                            RoundedRectangle(cornerRadius: Radius.chip).fill(fill(phase)).frame(width: 140, height: 12)
                        }
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(Spacing.md(widthClass))
            .accessibilityLabel(String(localized: "loading"))
            .animation(reduceMotion ? nil : .easeInOut(duration: 1), value: phase)
        }
    }

    private func fill(_ phase: Bool) -> Color { (phase && !reduceMotion) ? .skeletonShimmer : .skeleton }
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
