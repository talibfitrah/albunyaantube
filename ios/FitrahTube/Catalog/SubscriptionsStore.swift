import Foundation
import Observation
import SwiftData

/// Plan C Task 5: the channel screen's Subscribe toggle -- `SavedPlaylistsStore`'s twin over
/// Android's `followed_channels` table (`FollowedChannel.kt:18-23`), with RULING 27's guest-local
/// 30-channel cap (`SubscriptionLimitGuard.kt:73`).
@MainActor protocol SubscriptionsStore: AnyObject, Observable, UserScoped {
    var items: [SubscribedChannel] { get }
    func isSubscribed(_ channelId: String) -> Bool
    func toggle(id: String, name: String?, avatarURL: URL?) throws

    /// Phase 4 Task 28, import dedupe (`SubscriptionRepository.channelExistsAny`): deleted- AND
    /// status-agnostic. A soft-deleted row and an AWAITING one both mean "the user already has
    /// this channel in some state", so neither is re-sent to the backend.
    func containsAny(_ channelId: String) -> Bool

    /// Phase 4 Task 28. **CF-A-11: this write BYPASSES the 30-channel cap**
    /// (`SubscriptionRepository.kt:121-136`). The YouTube import is the ONE intentional exception —
    /// it imports a user's whole subscription list, and refusing the 31st would make the feature
    /// meaningless. Every other caller goes through `toggle`, which enforces RULING 27's cap;
    /// `ImportPipeline` is the only production caller of this method and must stay so.
    func importChannel(id: String, title: String, avatarUrl: String?,
                       approvalStatus: String, at: Date) throws

    /// Phase 4 Task 30 (fork F14): the AWAITING rows — imported ids an admin has not reviewed yet.
    /// `items` deliberately EXCLUDES them (the fail-closed `== ImportProvenance.approved` filter above), so this
    /// cannot be derived from it; it is a second fetch over the same uid, sorted the same way.
    ///
    /// Stored and refreshed alongside `items`, never computed on read. An import writes rows that
    /// `items` does not contain, so NOTHING observable would change and the Me tab's Pending tab
    /// could never appear — the count has to be part of the same observation `items` is.
    var awaitingItems: [SubscribedChannel] { get }
}

nonisolated enum SubscriptionsError: Error, Equatable {
    /// `ChannelDetailFragment.kt:516`: `^[A-Za-z0-9_-]{3,64}$`, refused before it reaches the store.
    case invalidChannelId
    /// The 31st subscribe; the screen shows `me_subscription_cap_reached`. Unsubscribing is never capped.
    case capReached
}

extension FavoritesSchemaV3 {
    /// The V3 shape, frozen as `976d2e6f` declared it -- and unchanged at V4, which aliases it.
    /// This is what a store written by any pre-V5 build actually holds. NEVER edit it: a column
    /// added here changes what "V3"/"V4" hash to, and every such store becomes an unknown model
    /// version that `makeModelContainer`'s recovery path deletes (`FavoriteVideo.swift`).
    @Model final class SubscribedChannel {
        #Unique<SubscribedChannel>([\.channelId, \.userId])

        var channelId: String
        var title: String
        var avatarUrl: String?
        var followedAt: Date
        var userId: String
        var updatedAt: Date
        var isRemoved: Bool
        var dirty: Bool

        init(channelId: String, title: String, avatarUrl: String?,
             followedAt: Date = Date(), userId: String = "", updatedAt: Date = Date(timeIntervalSince1970: 0),
             isRemoved: Bool = false, dirty: Bool = false) {
            self.channelId = channelId
            self.title = title
            self.avatarUrl = avatarUrl
            self.followedAt = followedAt
            self.userId = userId
            self.updatedAt = updatedAt
            self.isRemoved = isRemoved
            self.dirty = dirty
        }
    }
}

extension FavoritesSchemaV5 {
    /// The LIVE shape (`SubscribedChannel` at file scope). Same column conventions as
    /// `FavoriteVideo` (`isRemoved`, not `deleted` -- see that file for why).
    @Model final class SubscribedChannel {
        #Unique<SubscribedChannel>([\.channelId, \.userId])

        var channelId: String
        var title: String
        var avatarUrl: String?
        var followedAt: Date
        var userId: String
        var updatedAt: Date
        var isRemoved: Bool
        var dirty: Bool
        /// V5. Inline defaults (and optionals) are what make the V4 -> V5 stage *lightweight*: a
        /// non-optional column with no default has nothing to write into the existing rows.
        /// `channelUrl` is Room's `channelUrl`, which the sync wire requires; it is STORED data,
        /// never a navigable affordance (owner directive: no link or redirect to YouTube, anywhere).
        var channelUrl: String = ""
        /// ImportProvenance.approved | "AWAITING" -- an AWAITING row is an imported, unreviewed channel: hidden
        /// from `items`, still `isSubscribed`. A null server value means ImportProvenance.approved
        /// (`SyncManager.kt:379`).
        var approvalStatus: String = ImportProvenance.approved
        /// "USER_IMPORT" for rows the YouTube import wrote; nil for a manual subscribe.
        var source: String?
        var importedAt: Date?

        init(channelId: String, title: String, avatarUrl: String?,
             followedAt: Date = Date(), userId: String = "", updatedAt: Date = Date(timeIntervalSince1970: 0),
             isRemoved: Bool = false, dirty: Bool = false, channelUrl: String = "",
             approvalStatus: String = ImportProvenance.approved, source: String? = nil, importedAt: Date? = nil) {
            self.channelId = channelId
            self.title = title
            self.avatarUrl = avatarUrl
            self.followedAt = followedAt
            self.userId = userId
            self.updatedAt = updatedAt
            self.isRemoved = isRemoved
            self.dirty = dirty
            self.channelUrl = channelUrl
            self.approvalStatus = approvalStatus
            self.source = source
            self.importedAt = importedAt
        }
    }
}

@MainActor @Observable final class SwiftDataSubscriptionsStore: SubscriptionsStore {
    static let cap = 30
    private let context: ModelContext

    var currentUserId: String = "" {
        didSet { reload() }
    }

    private(set) var items: [SubscribedChannel] = []

    /// Task 30: the AWAITING rows, in the same order `items` uses. Refreshed by the same
    /// `reload()`, so one fetch pair keeps the two lists consistent by construction.
    private(set) var awaitingItems: [SubscribedChannel] = []


    /// Phase 4 Task 24: "this store just dirtied a row for that uid" (`SubscriptionRepository.kt:135`).
    /// The ROW's uid, not the session's -- see `SwiftDataFavoritesStore.onDirty`.
    private let onDirty: ((String) -> Void)?

    init(modelContainer: ModelContainer, onDirty: ((String) -> Void)? = nil) {
        context = ModelContext(modelContainer)
        self.onDirty = onDirty
        reload()
    }

    nonisolated static func isValid(_ channelId: String) -> Bool {
        channelId.wholeMatch(of: /[A-Za-z0-9_-]{3,64}/) != nil
    }

    nonisolated static func validate(_ channelId: String) throws {
        guard isValid(channelId) else { throw SubscriptionsError.invalidChannelId }
    }

    func isSubscribed(_ channelId: String) -> Bool {
        let uid = currentUserId
        let descriptor = FetchDescriptor<SubscribedChannel>(
            predicate: #Predicate { $0.channelId == channelId && $0.userId == uid && $0.isRemoved == false }
        )
        return ((try? context.fetchCount(descriptor)) ?? 0) > 0
    }

    func toggle(id: String, name: String?, avatarURL: URL?) throws {
        try Self.validate(id)
        let uid = currentUserId
        let descriptor = FetchDescriptor<SubscribedChannel>(predicate: #Predicate { $0.channelId == id && $0.userId == uid })
        let existing = try context.fetch(descriptor).first
        if let existing, !existing.isRemoved {
            existing.isRemoved = true
            existing.dirty = true // never `updatedAt` -- the server timestamp (gate wave-2 W12)
        } else {
            guard items.count < Self.cap else { throw SubscriptionsError.capReached }
            if let existing {
                existing.isRemoved = false
                existing.dirty = true
                existing.title = name ?? id
                existing.avatarUrl = avatarURL?.absoluteString
                existing.followedAt = Date()
            } else {
                context.insert(SubscribedChannel(channelId: id, title: name ?? id, avatarUrl: avatarURL?.absoluteString, userId: uid, dirty: true))
            }
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

    func containsAny(_ channelId: String) -> Bool {
        let uid = currentUserId
        // No `isRemoved`/`approvalStatus` clause: that IS the point (`isSubscribed` has both).
        let descriptor = FetchDescriptor<SubscribedChannel>(
            predicate: #Predicate { $0.channelId == channelId && $0.userId == uid }
        )
        return ((try? context.fetchCount(descriptor)) ?? 0) > 0
    }

    /// CF-A-11's cap bypass — see the protocol's note. Everything else matches `toggle`: validate,
    /// resurrect-or-insert, save-or-rollback, then push.
    func importChannel(id: String, title: String, avatarUrl: String?,
                       approvalStatus: String, at: Date) throws {
        try Self.validate(id)
        let uid = currentUserId
        let descriptor = FetchDescriptor<SubscribedChannel>(predicate: #Predicate { $0.channelId == id && $0.userId == uid })
        if let existing = try context.fetch(descriptor).first {
            existing.isRemoved = false
            existing.dirty = true // never `updatedAt` -- the server timestamp (gate wave-2 W12)
            existing.title = title
            existing.avatarUrl = avatarUrl
            existing.channelUrl = SyncURL.channel(id)
            existing.approvalStatus = approvalStatus
            existing.source = ImportProvenance.source
            existing.importedAt = at
        } else {
            context.insert(SubscribedChannel(channelId: id, title: title, avatarUrl: avatarUrl,
                                             followedAt: at, userId: uid, dirty: true,
                                             channelUrl: SyncURL.channel(id),
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
        // V5: an AWAITING row is an imported channel the admins have not reviewed. It must not
        // render as an ordinary chip and must not count against the 30-channel cap through
        // `items.count` above. `isSubscribed` stays UNFILTERED (matching `isFavorite`), so a
        // re-add of an awaiting channel finds the existing row instead of duplicating it.
        //
        // Task 20 review M3, ruled: FAIL CLOSED. `!= "AWAITING"` failed OPEN — a REJECTED row, or
        // any status a later backend adds, rendered as an ordinary chip and counted against the
        // cap. `== ImportProvenance.approved` matches `SwiftDataFavoritesStore` and shows only what has actually
        // been approved. Pre-existing rows are safe: V5's column default is ImportProvenance.approved, so the
        // lightweight V4 -> V5 stage fills every migrated row with it.
        let approved = ImportProvenance.approved
        var descriptor = FetchDescriptor<SubscribedChannel>(
            predicate: #Predicate { $0.userId == uid && $0.isRemoved == false && $0.approvalStatus == approved },
            sortBy: [SortDescriptor(\.followedAt, order: .reverse)]
        )
        descriptor.includePendingChanges = false
        items = (try? context.fetch(descriptor)) ?? []
        // Task 30 (fork F14). Deliberately a SECOND fetch, not a filter over `items`: the
        // descriptor above is fail-closed on `== ImportProvenance.approved`, so an awaiting row was never in
        // `items` to filter out of. Same uid, same tombstone rule, same order.
        let awaiting = ImportProvenance.awaiting
        var pending = FetchDescriptor<SubscribedChannel>(
            predicate: #Predicate { $0.userId == uid && $0.isRemoved == false && $0.approvalStatus == awaiting },
            sortBy: [SortDescriptor(\.followedAt, order: .reverse)]
        )
        pending.includePendingChanges = false
        awaitingItems = (try? context.fetch(pending)) ?? []
    }
}
