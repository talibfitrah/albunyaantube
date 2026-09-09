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
        /// "APPROVED" | "AWAITING" -- an AWAITING row is an imported, unreviewed channel: hidden
        /// from `items`, still `isSubscribed`. A null server value means "APPROVED"
        /// (`SyncManager.kt:379`).
        var approvalStatus: String = "APPROVED"
        /// "USER_IMPORT" for rows the YouTube import wrote; nil for a manual subscribe.
        var source: String?
        var importedAt: Date?

        init(channelId: String, title: String, avatarUrl: String?,
             followedAt: Date = Date(), userId: String = "", updatedAt: Date = Date(timeIntervalSince1970: 0),
             isRemoved: Bool = false, dirty: Bool = false, channelUrl: String = "",
             approvalStatus: String = "APPROVED", source: String? = nil, importedAt: Date? = nil) {
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
        didSet { refresh() }
    }

    private(set) var items: [SubscribedChannel] = []

    init(modelContainer: ModelContainer) {
        context = ModelContext(modelContainer)
        refresh()
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
            refresh()
            throw error
        }
        refresh()
    }

    private func refresh() {
        let uid = currentUserId
        // V5: an AWAITING row is an imported channel the admins have not reviewed. It must not
        // render as an ordinary chip and must not count against the 30-channel cap through
        // `items.count` above. `isSubscribed` stays UNFILTERED (matching `isFavorite`), so a
        // re-add of an awaiting channel finds the existing row instead of duplicating it.
        let awaiting = "AWAITING"
        var descriptor = FetchDescriptor<SubscribedChannel>(
            predicate: #Predicate { $0.userId == uid && $0.isRemoved == false && $0.approvalStatus != awaiting },
            sortBy: [SortDescriptor(\.followedAt, order: .reverse)]
        )
        descriptor.includePendingChanges = false
        items = (try? context.fetch(descriptor)) ?? []
    }
}
