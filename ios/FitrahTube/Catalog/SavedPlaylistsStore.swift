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

    /// Phase 4 Task 28, import dedupe (`SubscriptionRepository.playlistExistsAny`): deleted- AND
    /// status-agnostic, `SubscriptionsStore.containsAny`'s twin.
    func containsAny(_ playlistId: String) -> Bool

    /// Phase 4 Task 28: the import path's write. No cap exists for playlists, so unlike
    /// `SubscriptionsStore.importChannel` this bypasses nothing — it exists to stamp
    /// `playlistUrl` / `approvalStatus` / `source` / `importedAt`, which `toggle` has no reason to.
    func importPlaylist(id: String, title: String, thumbnailUrl: String?, uploaderName: String?,
                        approvalStatus: String, at: Date) throws

    /// Phase 4 Task 30 (fork F14): the AWAITING rows — imported ids an admin has not reviewed yet.
    /// `items` deliberately EXCLUDES them (the fail-closed `== "APPROVED"` filter), so this cannot
    /// be derived from it; it is a second fetch over the same uid, sorted the same way.
    ///
    /// Stored and refreshed alongside `items`, never computed on read. An import writes rows that
    /// `items` does not contain, so NOTHING observable would change and the Me tab's Pending tab
    /// could never appear — the count has to be part of the same observation `items` is.
    var awaitingItems: [SavedPlaylist] { get }
}

nonisolated enum SavedPlaylistsError: Error, Equatable {
    /// `PlaylistDetailFragment.kt:787`: `^[A-Za-z0-9_-]{3,128}$`, refused before it reaches the store.
    case invalidPlaylistId
}

extension FavoritesSchemaV2 {
    /// The V2 shape, frozen as `52549ec7` declared it -- and unchanged at V3 and V4, which alias
    /// it. This is what a store written by any pre-V5 build actually holds. NEVER edit it: a
    /// column added here changes what "V2"/"V3"/"V4" hash to, and every such store becomes an
    /// unknown model version that `makeModelContainer`'s recovery path deletes
    /// (`FavoriteVideo.swift`).
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
}

extension FavoritesSchemaV5 {
    /// The LIVE shape (`SavedPlaylist` at file scope). Same column conventions as `FavoriteVideo`
    /// (`isRemoved`, not `deleted` -- see that file for why).
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
        /// V5, same rules as `SubscribedChannel`'s: defaulted or optional so the stage stays
        /// lightweight, and `playlistUrl` is stored data, never a navigable affordance.
        /// `itemCount` above STAYS: no sync DTO carries it, and it is what the count chip renders.
        var playlistUrl: String = ""
        var uploaderName: String?
        var approvalStatus: String = ImportProvenance.approved
        var source: String?
        var importedAt: Date?

        init(playlistId: String, title: String, thumbnailUrl: String?, itemCount: Int,
             addedAt: Date = Date(), userId: String = "", updatedAt: Date = Date(timeIntervalSince1970: 0),
             isRemoved: Bool = false, dirty: Bool = false, playlistUrl: String = "",
             uploaderName: String? = nil, approvalStatus: String = ImportProvenance.approved,
             source: String? = nil, importedAt: Date? = nil) {
            self.playlistId = playlistId
            self.title = title
            self.thumbnailUrl = thumbnailUrl
            self.itemCount = itemCount
            self.addedAt = addedAt
            self.userId = userId
            self.updatedAt = updatedAt
            self.isRemoved = isRemoved
            self.dirty = dirty
            self.playlistUrl = playlistUrl
            self.uploaderName = uploaderName
            self.approvalStatus = approvalStatus
            self.source = source
            self.importedAt = importedAt
        }
    }
}

@MainActor @Observable final class SwiftDataSavedPlaylistsStore: SavedPlaylistsStore {
    private let context: ModelContext

    var currentUserId: String = "" {
        didSet { reload() }
    }

    private(set) var items: [SavedPlaylist] = []

    /// Task 30: the AWAITING rows, in the same order `items` uses. Refreshed by the same
    /// `reload()`, so one fetch pair keeps the two lists consistent by construction.
    private(set) var awaitingItems: [SavedPlaylist] = []


    /// Phase 4 Task 24: "this store just dirtied a row for that uid" -- the playlist sibling of
    /// `SubscriptionRepository.kt:153`.
    /// The ROW's uid, not the session's -- see `SwiftDataFavoritesStore.onDirty`.
    private let onDirty: ((String) -> Void)?

    init(modelContainer: ModelContainer, onDirty: ((String) -> Void)? = nil) {
        context = ModelContext(modelContainer)
        self.onDirty = onDirty
        reload()
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
            reload()
            throw error
        }
        reload()
        // Task 24: after the save, so a rolled-back write pushes nothing.
        onDirty?(uid)
    }

    func containsAny(_ playlistId: String) -> Bool {
        let uid = currentUserId
        // No `isRemoved`/`approvalStatus` clause: that IS the point (`isSaved` has both).
        let descriptor = FetchDescriptor<SavedPlaylist>(
            predicate: #Predicate { $0.playlistId == playlistId && $0.userId == uid }
        )
        return ((try? context.fetchCount(descriptor)) ?? 0) > 0
    }

    /// `itemCount` is deliberately left at 0 (or whatever a resurrected row already had): no
    /// import DTO carries it, and inventing one would put a wrong count chip on the row.
    func importPlaylist(id: String, title: String, thumbnailUrl: String?, uploaderName: String?,
                        approvalStatus: String, at: Date) throws {
        try Self.validate(id)
        let uid = currentUserId
        let descriptor = FetchDescriptor<SavedPlaylist>(predicate: #Predicate { $0.playlistId == id && $0.userId == uid })
        if let existing = try context.fetch(descriptor).first {
            existing.isRemoved = false
            existing.dirty = true // never `updatedAt` -- the server timestamp (gate wave-2 W12)
            existing.title = title
            existing.thumbnailUrl = thumbnailUrl
            existing.uploaderName = uploaderName
            existing.playlistUrl = SyncURL.playlist(id)
            existing.approvalStatus = approvalStatus
            existing.source = ImportProvenance.source
            existing.importedAt = at
        } else {
            context.insert(SavedPlaylist(playlistId: id, title: title, thumbnailUrl: thumbnailUrl,
                                         itemCount: 0, addedAt: at, userId: uid, dirty: true,
                                         playlistUrl: SyncURL.playlist(id), uploaderName: uploaderName,
                                         approvalStatus: approvalStatus,
                                         source: ImportProvenance.source, importedAt: at))
        }
        do {
            try context.save()
        } catch {
            context.rollback()
            reload()
            throw error
        }
        reload()
        onDirty?(uid)
    }

    /// `UserScoped.reload()`: every read, and the sync manager's write hook.
    func reload() {
        let uid = currentUserId
        // V5, same rule as `SwiftDataSubscriptionsStore.reload()`: awaiting (imported,
        // unreviewed) rows are hidden from `items`; `isSaved` stays unfiltered.
        // Task 20 review M3, ruled: FAIL CLOSED — `== "APPROVED"`, not `!= "AWAITING"`. See
        // `SwiftDataSubscriptionsStore.reload()` for the full note.
        let approved = ImportProvenance.approved
        var descriptor = FetchDescriptor<SavedPlaylist>(
            predicate: #Predicate { $0.userId == uid && $0.isRemoved == false && $0.approvalStatus == approved },
            sortBy: [SortDescriptor(\.addedAt, order: .reverse)]
        )
        descriptor.includePendingChanges = false // saved rows only (gate wave-4 V9)
        items = (try? context.fetch(descriptor)) ?? []
        // Task 30 (fork F14). Deliberately a SECOND fetch, not a filter over `items`: the
        // descriptor above is fail-closed on `== "APPROVED"`, so an awaiting row was never in
        // `items` to filter out of. Same uid, same tombstone rule, same order.
        let awaiting = ImportProvenance.awaiting
        var pending = FetchDescriptor<SavedPlaylist>(
            predicate: #Predicate { $0.userId == uid && $0.isRemoved == false && $0.approvalStatus == awaiting },
            sortBy: [SortDescriptor(\.addedAt, order: .reverse)]
        )
        pending.includePendingChanges = false
        awaitingItems = (try? context.fetch(pending)) ?? []
    }
}
