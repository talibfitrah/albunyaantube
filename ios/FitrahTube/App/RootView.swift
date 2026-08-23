import SwiftUI

/// Phase-0 placeholder: proves DI → FitrahAPI → backend end to end. Replaced by the shell in phase 1.
struct RootView: View {
    @Environment(\.container) private var container
    @State private var status = "Loading categories…"

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
    }
}

#Preview { RootView() }
