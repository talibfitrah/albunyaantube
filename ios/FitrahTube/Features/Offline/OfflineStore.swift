import Foundation
import Observation
import SwiftData

/// The Saved library's persistence — the `SwiftDataFavoritesStore` idiom, minus the protocol
/// (nothing fakes this store; tests hand it an in-memory container). Task 4's manager writes
/// through it; Task 6's Saved screen renders `items` verbatim.
@MainActor @Observable final class OfflineStore {
    private let context: ModelContext
    /// All rows, alphabetical by title (`DownloadsFragment` sort parity).
    private(set) var items: [OfflineItem] = []

    init(modelContainer: ModelContainer) {
        context = ModelContext(modelContainer)
        refresh()
    }

    func item(videoId: String) -> OfflineItem? {
        first(#Predicate { $0.videoId == videoId })
    }

    func item(id: String) -> OfflineItem? {
        first(#Predicate { $0.id == id })
    }

    /// Upserts on `videoId` (`#Unique`): a re-save at a different quality replaces the row.
    func insert(_ item: OfflineItem) throws {
        context.insert(item)
        try saveOrRollback()
    }

    /// Persist in-place mutations on fetched rows (status/bytes/paths).
    func save() throws {
        try saveOrRollback()
    }

    /// Removes the ROW only. The saved file is the manager's job (Task 4's `delete` removes
    /// files then the row) — this store never touches `FileManager`.
    func delete(_ item: OfflineItem) throws {
        context.delete(item)
        try saveOrRollback()
    }

    private func first(_ predicate: Predicate<OfflineItem>) -> OfflineItem? {
        var descriptor = FetchDescriptor<OfflineItem>(predicate: predicate)
        descriptor.fetchLimit = 1
        descriptor.includePendingChanges = false
        return (try? context.fetch(descriptor))?.first
    }

    /// Failed saves roll back and re-read, so `items` can't keep showing a mutation that no
    /// longer exists — the gate wave-4 V9 lesson, verbatim from `SwiftDataFavoritesStore`.
    private func saveOrRollback() throws {
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
        var descriptor = FetchDescriptor<OfflineItem>(sortBy: [SortDescriptor(\.title)])
        descriptor.includePendingChanges = false
        items = (try? context.fetch(descriptor)) ?? []
    }
}
