import SwiftUI

/// Phase-0 placeholder: proves DI → FitrahAPI → backend end to end. Replaced by the shell in phase 1.
struct RootView: View {
    @Environment(\.container) private var container
    @State private var status = "Loading categories…"
    @State private var widthClass: WidthClass = .compact

    var body: some View {
        VStack(spacing: 16) {
            Text("FitrahTube").font(.largeTitle.bold())
            Text(status).font(.body)
        }
        .task {
            do {
                let count = try await container.catalog.categories().count
                status = "\(count) categories"
            } catch {
                status = "Backend unreachable: \(error.localizedDescription)"
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Measures the full-size container, not the content — see WidthClass.
        .onGeometryChange(for: WidthClass.self) { WidthClass(width: $0.size.width) } action: { widthClass = $0 }
        .environment(\.widthClass, widthClass)
    }
}

#Preview { RootView() }
