import Foundation
import SwiftData

/// The sync layer's bookkeeping, in SwiftData rather than `UserDefaults` (ruling F3): the cursor
/// and the rows it advances past have to commit in ONE `ModelContext.save()`, or a crash between
/// the two leaves a cursor that has moved past rows that were never written.
///
/// Both live on the same `ModelContainer` as the three synced entities, arriving with schema V5.
/// Same column conventions as `FavoriteVideo` -- see that file for the `deleted` trap.

/// Android's `sync_state` table: one cursor per (entity type, user), Room's composite primary key.
@Model final class SyncState {
    #Unique<SyncState>([\.entityType, \.userId])

    /// `SyncEntityType.rawValue` -- "subscriptions" | "playlists" | "favorites". A raw `String`
    /// because a `@Model` property is a stored attribute, not the enum's business; the enum owns
    /// the values and the wire names.
    var entityType: String
    var userId: String
    /// Epoch millis; 0 = never synced.
    var lastCursor: Int
    /// The compound cursor's second half -- the last document id at `lastCursor`, so a page break
    /// inside a group of rows sharing one timestamp resumes without skipping or repeating.
    var lastDocId: String?
    var lastSyncAt: Date

    init(entityType: String, userId: String, lastCursor: Int = 0,
         lastDocId: String? = nil, lastSyncAt: Date = Date(timeIntervalSince1970: 0)) {
        self.entityType = entityType
        self.userId = userId
        self.lastCursor = lastCursor
        self.lastDocId = lastDocId
        self.lastSyncAt = lastSyncAt
    }
}

/// Android's single-row `account_binding` table: which account this device's local data belongs to,
/// and whether the one-time anonymous-data merge for it has run.
@Model final class AccountBinding {
    #Unique<AccountBinding>([\.userId])

    var userId: String
    var boundAt: Date
    var initialMergeDone: Bool

    init(userId: String, boundAt: Date = Date(), initialMergeDone: Bool = false) {
        self.userId = userId
        self.boundAt = boundAt
        self.initialMergeDone = initialMergeDone
    }
}
