import SwiftUI

/// Stand-in for every screen `Route` can point to before its real implementation lands
/// (task-7 brief): shows the case name and every argument as a labelled list, so phase-1
/// navigation -- tab roots, pushes, deep links -- is visually verifiable before those screens exist.
struct PhaseTwoPlaceholderView: View {
    let route: Route

    var body: some View {
        List {
            Section {
                if arguments.isEmpty {
                    Text("(no arguments)").foregroundStyle(.secondary)
                } else {
                    ForEach(arguments, id: \.label) { argument in
                        LabeledContent(argument.label, value: argument.value)
                    }
                }
            } header: {
                Text(caseName)
            }
        }
        .navigationTitle(caseName)
    }

    private var caseName: String {
        switch route {
        case .player: "player"
        case .shorts: "shorts"
        case .channel: "channel"
        case .playlist: "playlist"
        case .search: "search"
        case .categories: "categories"
        case .subcategories: "subcategories"
        case .featured: "featured"
        case .favorites: "favorites"
        case .settings: "settings"
        case .about: "about"
        }
    }

    private var arguments: [(label: String, value: String)] {
        switch route {
        case .player(let args):
            [
                ("videoId", args.videoId),
                ("playlistId", args.playlistId ?? "nil"),
                ("title", args.title ?? "nil"),
                ("channelName", args.channelName ?? "nil"),
                ("thumbnailURL", args.thumbnailURL?.absoluteString ?? "nil"),
                ("description", args.description ?? "nil"),
                ("durationSeconds", args.durationSeconds.map(String.init) ?? "nil"),
                ("viewCount", args.viewCount.map(String.init) ?? "nil"),
                ("channelId", args.channelId ?? "nil"),
            ]
        case .shorts(let id):
            [("id", id)]
        case .channel(let id, let name, let avatarURL):
            [("id", id), ("name", name ?? "nil"), ("avatarURL", avatarURL?.absoluteString ?? "nil")]
        case .playlist(let id, let title, let category, let count):
            [("id", id), ("title", title ?? "nil"), ("category", category ?? "nil"), ("count", count.map(String.init) ?? "nil")]
        case .subcategories(let parentId, let parentName):
            [("parentId", parentId), ("parentName", parentName)]
        case .featured(let categoryId, let categoryName):
            [("categoryId", categoryId ?? "nil"), ("categoryName", categoryName ?? "nil")]
        case .search, .categories, .favorites, .settings, .about:
            []
        }
    }
}

#Preview {
    NavigationStack {
        PhaseTwoPlaceholderView(route: .player(PlayerArgs(videoId: "abc123")))
    }
}
