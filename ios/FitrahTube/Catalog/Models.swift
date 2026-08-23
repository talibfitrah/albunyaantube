import Foundation

nonisolated enum ContentType: String, Sendable {
    case video = "VIDEO"
    case channel = "CHANNEL"
    case playlist = "PLAYLIST"
}

nonisolated struct ContentItem: Identifiable, Hashable, Sendable {
    let id: String
    let type: ContentType
    let title: String
    let category: String?
    let description: String?
    let thumbnailURL: URL?
    let durationSeconds: Int?
    let uploadedDaysAgo: Int?
    let viewCount: Int64?
    let channelTitle: String?
    let subscribers: Int64?
    let videoCount: Int?
    let itemCount: Int?
}

nonisolated struct CursorPage<Item: Sendable & Hashable>: Sendable, Hashable {
    let items: [Item]
    let nextCursor: String?
    var hasMore: Bool { nextCursor != nil }
}

nonisolated struct HomeSection: Identifiable, Hashable, Sendable {
    let id: String // categoryId
    let name: String
    let localizedNames: [String: String]?
    let icon: String?
    let items: [ContentItem]
}

/// `displayName(for:)` lives in `Formatting.swift` (Task 3).
nonisolated struct Category: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let slug: String
    let parentId: String?
    let displayOrder: Int?
    let localizedNames: [String: String]?
    let icon: String?

    init(id: String, name: String, slug: String, parentId: String?,
         displayOrder: Int? = nil, localizedNames: [String: String]? = nil, icon: String? = nil) {
        self.id = id
        self.name = name
        self.slug = slug
        self.parentId = parentId
        self.displayOrder = displayOrder
        self.localizedNames = localizedNames
        self.icon = icon
    }
}

nonisolated struct FilterState: Hashable, Sendable {
    var categoryId: String?
    var categoryName: String?
    var length: String?
    var date: String?
    var sort: String?

    init(categoryId: String? = nil, categoryName: String? = nil, length: String? = nil,
         date: String? = nil, sort: String? = nil) {
        self.categoryId = categoryId
        self.categoryName = categoryName
        self.length = length
        self.date = date
        self.sort = sort
    }
}

nonisolated enum ListType: String, Sendable {
    case videos = "VIDEOS"
    case channels = "CHANNELS"
    case playlists = "PLAYLISTS"
    case all = "ALL"
}
