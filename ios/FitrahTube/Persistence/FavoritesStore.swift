import Foundation
import Observation
import SwiftData

/// Android's `FavoriteVideoDao` + `FavoritesRepository`/`FavoritesViewModel` --
/// `docs/superpowers/plans/2026-08-23-ios-phase1-research/favorites-settings-about.md:20-63`.
/// Every read/write is scoped to `currentUserId`; `""` is the anon/signed-out sentinel until
/// phase 4 wires real auth. `isFavorite` mirrors the DAO's `EXISTS(... deleted = 0)` check --
/// unlike `items`, it does not filter by `approvalStatus`, so an AWAITING (imported, unreviewed)
/// favorite still reads as favorited even though it's hidden from the list.
@MainActor protocol FavoritesStore: AnyObject, Observable {
    var items: [FavoriteVideo] { get }
    func isFavorite(_ videoId: String) -> Bool
    func toggle(_ item: ContentItem) throws
    func clearAll() throws
}

@MainActor @Observable final class SwiftDataFavoritesStore: FavoritesStore {
    private let context: ModelContext

    /// Phase 4 sets this from real auth state. Re-scopes `items` on every change --
    /// contract's "any favorites publisher must be a function of the current uid, re-subscribed
    /// when auth state changes" (favorites-settings-about.md:34).
    var currentUserId: String = "" {
        didSet { refresh() }
    }

    private(set) var items: [FavoriteVideo] = []

    init(modelContainer: ModelContainer) {
        context = ModelContext(modelContainer)
        refresh()
    }

    func isFavorite(_ videoId: String) -> Bool {
        let uid = currentUserId
        let descriptor = FetchDescriptor<FavoriteVideo>(
            predicate: #Predicate { $0.videoId == videoId && $0.userId == uid && $0.isRemoved == false }
        )
        return ((try? context.fetchCount(descriptor)) ?? 0) > 0
    }

    func toggle(_ item: ContentItem) throws {
        let uid = currentUserId
        let videoId = item.id
        let descriptor = FetchDescriptor<FavoriteVideo>(
            predicate: #Predicate { $0.videoId == videoId && $0.userId == uid }
        )
        if let existing = try context.fetch(descriptor).first {
            // `dirty` only -- never `updatedAt` (gate wave-2 W12). `updatedAt` is the *server*
            // timestamp and phase 4's monotonicity guard (`AND updated_at < :ts`,
            // favorites-settings-about.md:20-33,60); Android's DAO leaves it alone on every local
            // mutation for exactly that reason. Stamping it locally on a device whose clock runs
            // ahead of the server writes a future timestamp that makes the guard reject every
            // later server update to that row, permanently.
            existing.dirty = true
            if existing.isRemoved {
                // Resurrect: re-add of a soft-deleted row refreshes the snapshot fields too
                // (DAO's `resurrectAndUpsert`, favorites-settings-about.md:41-42).
                existing.isRemoved = false
                existing.title = item.title
                existing.channelName = item.channelTitle ?? ""
                existing.thumbnailUrl = item.thumbnailURL?.absoluteString
                existing.durationSeconds = item.durationSeconds ?? 0
            } else {
                existing.isRemoved = true
            }
        } else {
            context.insert(FavoriteVideo(
                videoId: videoId, title: item.title, channelName: item.channelTitle ?? "",
                thumbnailUrl: item.thumbnailURL?.absoluteString, durationSeconds: item.durationSeconds ?? 0,
                userId: uid, dirty: true
            ))
        }
        try saveOrRollback()
        refresh()
    }

    func clearAll() throws {
        let uid = currentUserId
        let descriptor = FetchDescriptor<FavoriteVideo>(
            predicate: #Predicate { $0.userId == uid && $0.isRemoved == false }
        )
        for favorite in try context.fetch(descriptor) {
            favorite.isRemoved = true
            favorite.dirty = true // not `updatedAt` -- see `toggle` (gate wave-2 W12)
        }
        try saveOrRollback()
        refresh()
    }

    /// Gate wave-4 V9: both writers mutate model objects *before* saving, so a failed save used to
    /// leave those mutations pending in the context -- and the next successful save of any
    /// unrelated operation then committed the toggle the user was told had failed. Rolling back
    /// discards them at the point of failure; `refresh()` re-reads so `items` can't keep showing a
    /// mutation that no longer exists. The error still propagates -- the caller decides what the
    /// user sees.
    private func saveOrRollback() throws {
        do {
            try context.save()
        } catch {
            context.rollback()
            refresh()
            throw error
        }
    }

    private func refresh() {
        let uid = currentUserId
        let approved = "APPROVED"
        var descriptor = FetchDescriptor<FavoriteVideo>(
            predicate: #Predicate { $0.userId == uid && $0.isRemoved == false && $0.approvalStatus == approved },
            sortBy: [SortDescriptor(\.addedAt, order: .reverse)]
        )
        // Saved rows only (gate wave-4 V9). `fetch` merges the context's pending changes, and a
        // rolled-back insert stays *registered* in the context (verified: after `rollback()`,
        // `hasChanges` is false and `insertedModelsArray` is empty, yet a plain `fetch` still
        // returns the row while `fetchCount` returns 0) -- so a failed toggle would keep showing
        // the favorite the user was just told had failed. Every writer here saves before it
        // refreshes, so on the success path this returns exactly the same rows either way, and it
        // now matches `isFavorite`, whose `fetchCount` never saw pending changes to begin with.
        descriptor.includePendingChanges = false
        items = (try? context.fetch(descriptor)) ?? []
    }
}
