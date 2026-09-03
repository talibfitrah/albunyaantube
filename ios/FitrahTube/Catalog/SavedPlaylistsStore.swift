import Foundation
import Observation
import SwiftData

/// Plan C Task 4: the playlist screen's Save toggle, mirroring `SwiftDataFavoritesStore` --
/// per-user scoping, tombstones (`isRemoved`) instead of deletes, `dirty` for the sync layer,
/// save-or-rollback so a failed write never lingers in the context.
@MainActor protocol SavedPlaylistsStore: AnyObject, Observable, UserScoped {
    var items: [SavedPlaylist] { get }
    func isSaved(_ playlistId: String) -> Bool
    func toggle(id: String, title: String?, thumbnailURL: URL?, itemCount: Int?) throws
}

nonisolated enum SavedPlaylistsError: Error, Equatable {
    /// `PlaylistDetailFragment.kt:787`: `^[A-Za-z0-9_-]{3,128}$`, refused before it reaches the store.
    case invalidPlaylistId
}

/// Same column conventions as `FavoriteVideo` (`isRemoved`, not `deleted` -- see that file for why).
@Model final class SavedPlaylist {
    #Unique<SavedPlaylist>([\.playlistId, \.userId])

    var playlistId: String
    var title: String
    var thumbnailUrl: String?
    var itemCount: Int
    var addedAt: Date
    var userId: String
    var updatedAt: Date
    var isRemoved: Bool
    var dirty: Bool

    init(playlistId: String, title: String, thumbnailUrl: String?, itemCount: Int,
         addedAt: Date = Date(), userId: String = "", updatedAt: Date = Date(timeIntervalSince1970: 0),
         isRemoved: Bool = false, dirty: Bool = false) {
        self.playlistId = playlistId
        self.title = title
        self.thumbnailUrl = thumbnailUrl
        self.itemCount = itemCount
        self.addedAt = addedAt
        self.userId = userId
        self.updatedAt = updatedAt
        self.isRemoved = isRemoved
        self.dirty = dirty
    }
}

@MainActor @Observable final class SwiftDataSavedPlaylistsStore: SavedPlaylistsStore {
    private let context: ModelContext

    var currentUserId: String = "" {
        didSet { refresh() }
    }

    private(set) var items: [SavedPlaylist] = []

    init(modelContainer: ModelContainer) {
        context = ModelContext(modelContainer)
        refresh()
    }

    nonisolated static func isValid(_ playlistId: String) -> Bool {
        playlistId.wholeMatch(of: /[A-Za-z0-9_-]{3,128}/) != nil
    }

    nonisolated static func validate(_ playlistId: String) throws {
        guard isValid(playlistId) else { throw SavedPlaylistsError.invalidPlaylistId }
    }

    func isSaved(_ playlistId: String) -> Bool {
        let uid = currentUserId
        let descriptor = FetchDescriptor<SavedPlaylist>(
            predicate: #Predicate { $0.playlistId == playlistId && $0.userId == uid && $0.isRemoved == false }
        )
        return ((try? context.fetchCount(descriptor)) ?? 0) > 0
    }

    func toggle(id: String, title: String?, thumbnailURL: URL?, itemCount: Int?) throws {
        try Self.validate(id)
        let uid = currentUserId
        let descriptor = FetchDescriptor<SavedPlaylist>(predicate: #Predicate { $0.playlistId == id && $0.userId == uid })
        if let existing = try context.fetch(descriptor).first {
            existing.dirty = true // never `updatedAt` -- the server timestamp (gate wave-2 W12)
            if existing.isRemoved {
                existing.isRemoved = false
                existing.title = title ?? id
                existing.thumbnailUrl = thumbnailURL?.absoluteString
                existing.itemCount = itemCount ?? 0
            } else {
                existing.isRemoved = true
            }
        } else {
            context.insert(SavedPlaylist(playlistId: id, title: title ?? id, thumbnailUrl: thumbnailURL?.absoluteString,
                                         itemCount: itemCount ?? 0, userId: uid, dirty: true))
        }
        do {
            try context.save()
        } catch {
            context.rollback()
            refresh()
            throw error
        }
        refresh()
    }

    private func refresh() {
        let uid = currentUserId
        var descriptor = FetchDescriptor<SavedPlaylist>(
            predicate: #Predicate { $0.userId == uid && $0.isRemoved == false },
            sortBy: [SortDescriptor(\.addedAt, order: .reverse)]
        )
        descriptor.includePendingChanges = false // saved rows only (gate wave-4 V9)
        items = (try? context.fetch(descriptor)) ?? []
    }
}
