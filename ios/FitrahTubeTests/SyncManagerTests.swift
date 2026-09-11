import Foundation
import SwiftData
import Synchronization
import Testing
@testable import FitrahTube

/// Phase 4 Task 23, Step 1 — `SyncManager`'s I/O order and its transactions. The exclusion is
/// `SyncMutexTests`' subject and the branch table is `SyncDecisionTests`'; what is pinned HERE is
/// everything that can only go wrong once a decision meets a `ModelContext`: the account-switch
/// write being all-or-nothing, the cursor and the rows it advances past landing in ONE save, the
/// drain order, and where `updatedAt` gets its value.
///
/// Fakes only: `ScriptedSyncClient` at Task 22's seam (`Support/TestDoubles.swift`) and an
/// in-memory `ModelContainer`. No Firebase, no network, no clock.
@Suite(.perTest)
struct SyncManagerTests {

    // MARK: - Fixtures (approved ids only)

    private static let uid = "uid-a"
    private static let other = "uid-b"
    private static let channelId = "UCmMcOjsVehVlEOteyrhjI2Q"
    private static let playlistId = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
    private static let videoId = "xc7keR2piUM"

    private static func subscription(_ id: String = channelId, deleted: Bool = false,
                                     updatedAt: Int = 2_000, name: String = "Alafasy",
                                     approvalStatus: String? = nil) -> SubscriptionSyncDTO {
        SubscriptionSyncDTO(entityId: id, deleted: deleted, updatedAt: updatedAt,
                            channelUrl: "https://www.youtube.com/channel/\(id)", name: name,
                            avatarUrl: nil, subscribedAt: 1_000, approvalStatus: approvalStatus,
                            source: nil, importedAt: nil)
    }

    private static func favorite(_ id: String = videoId, deleted: Bool = false,
                                 updatedAt: Int = 2_000, title: String = "Lecture") -> FavoriteSyncDTO {
        FavoriteSyncDTO(entityId: id, deleted: deleted, updatedAt: updatedAt, title: title,
                        channelName: "Alafasy", thumbnailUrl: nil, durationSeconds: 600,
                        addedAt: 1_000, approvalStatus: nil, source: nil, importedAt: nil)
    }

    private static func playlist(_ id: String = playlistId, deleted: Bool = false,
                                 updatedAt: Int = 2_000, name: String = "Series") -> PlaylistSyncDTO {
        PlaylistSyncDTO(entityId: id, deleted: deleted, updatedAt: updatedAt,
                        playlistUrl: "https://www.youtube.com/playlist?list=\(id)", name: name,
                        thumbnailUrl: nil, uploaderName: nil, savedAt: 1_000,
                        approvalStatus: nil, source: nil, importedAt: nil)
    }

    private func manager(_ client: ScriptedSyncClient, container: ModelContainer,
                         sleep: @escaping @Sendable (Duration) async -> Void = { _ in }) -> SyncManager {
        SyncManager(client: client, modelContainer: container,
                    backoff: SyncBackoff(random: { $0.lowerBound }), sleep: sleep)
    }

    private func container() -> ModelContainer { AppContainer.makeModelContainer(inMemory: true) }

    /// Through a FRESH context every time: what actually persisted, never in-memory state.
    private func fetch<T: PersistentModel>(_ container: ModelContainer, _ type: T.Type = T.self) -> [T] {
        (try? ModelContext(container).fetch(FetchDescriptor<T>())) ?? []
    }

    // MARK: - bind: the merge, in order

    /// `SyncManager.kt:136-147`: tag this device's anonymous rows to the uid, THEN pull, THEN push,
    /// THEN mark the merge done. Every step is ordered against the next by a failure that has
    /// happened: tagging after the pull loses the anon row to a server row of the same id; marking
    /// the merge done before the push means a crash mid-drain never re-enters the merge.
    ///
    /// The client's hook is the recording spy — it reads the store at the exact moment each call is
    /// in flight, which is the only way "before" and "after" are distinguishable at all.
    @Test func bindWithNoBindingTagsAnonRowsThenPullsThenPushesThenMarksTheMergeDone() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: "", dirty: true))
        try context.save()

        let taggedAtPull = Mutex<String?>(nil)
        let mergeDoneAtPush = Mutex<Bool?>(nil)
        let client = ScriptedSyncClient(
            pulls: [.page(.empty)], puts: [.reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000))],
            hook: { call, _ in
                let owner = await MainActor.run {
                    (try? ModelContext(container).fetch(FetchDescriptor<FavoriteVideo>()))?.first?.userId
                }
                let merged = await MainActor.run {
                    (try? ModelContext(container).fetch(FetchDescriptor<AccountBinding>()))?.first?.initialMergeDone
                }
                if call == .pull { taggedAtPull.withLock { $0 = owner } }
                if case .put = call { mergeDoneAtPush.withLock { $0 = merged } }
            })
        let manager = self.manager(client, container: container)

        await manager.bind(uid: Self.uid)

        #expect(taggedAtPull.withLock { $0 } == Self.uid, "the pull ran before the anon rows were tagged")
        #expect(mergeDoneAtPush.withLock { $0 } == false, "the merge was marked done before the push drained")
        #expect(client.calls == [.pull, .put(.favorites, Self.videoId)])
        let binding = try #require(fetch(container, AccountBinding.self).first)
        #expect(binding.userId == Self.uid)
        #expect(binding.initialMergeDone)
    }

    /// Same account, merge already finished: the merge is NOT re-entered (no `beginBinding`
    /// reset, no second `markMergeDone`), a plain delta pull runs, then a dirty drain. The guest
    /// rows written while signed out ARE claimed first (Part B gate, stage 5 M1 — Task 23 pinned
    /// the opposite): a favorite made between two sessions of the same account was invisible
    /// while signed in, reappeared on the next sign-out, and was never pushed. Guest rows belong to
    /// the previous account (R-final5's premise), and here the previous account is this one.
    @Test func bindWithTheSameUidAndTheMergeDoneClaimsGuestRowsThenPullsThenPushes() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(AccountBinding(userId: Self.uid, initialMergeDone: true))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: "", dirty: true))
        try context.save()
        let client = ScriptedSyncClient(pulls: [.page(.empty)],
                                        puts: [.reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000))])
        let manager = self.manager(client, container: container)

        await manager.bind(uid: Self.uid)

        #expect(client.calls == [.pull, .put(.favorites, Self.videoId)])
        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(row.userId == Self.uid, "the guest row was left under the anon sentinel")
        let binding = try #require(fetch(container, AccountBinding.self).first)
        #expect(binding.initialMergeDone, "a finished merge was re-entered")
    }

    /// A prior merge that crashed mid-way left `initialMergeDone == false`. Re-enter it rather than
    /// start pulling over half-merged rows.
    @Test func bindWithTheSameUidAndAnUnfinishedMergeMergesAgain() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(AccountBinding(userId: Self.uid, initialMergeDone: false))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: "", dirty: true))
        try context.save()
        let client = ScriptedSyncClient(
            pulls: [.page(.empty)], puts: [.reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000))])
        let manager = self.manager(client, container: container)

        await manager.bind(uid: Self.uid)

        #expect(fetch(container, FavoriteVideo.self).first?.userId == Self.uid)
        #expect(fetch(container, AccountBinding.self).first?.initialMergeDone == true)
    }

    // MARK: - bind: the account switch

    /// The switch, in the order `SyncManager.kt:92-122` records two bugs for. The anon rows are
    /// tagged to the PREVIOUS uid and wiped WITH it: tagging them to the NEW uid is exactly how
    /// user A's local library was transferred into user B's account (R-final5 / R-final6), and
    /// doing the tagging outside the transaction left a crash window that re-merged A's data into B.
    ///
    /// **Task 23 review M2:** and it commits in exactly ONE save. Atomicity here IS "one context,
    /// one save, `rollback()` on the way out", so a second save inside `switchAccount` re-opens
    /// precisely the half-applied window the rollback test exists to close — and that test would
    /// still pass, because it only asserts what survives a throw placed before the first save. The
    /// count is read INSIDE the pull, i.e. after the switch and after the merge's anon tag (which
    /// finds nothing to tag here — the switch wiped the anon row — and a no-op `save()` posts no
    /// `didSave`), and before the drain's own writes.
    @Test func bindWithADifferentUidTagsAnonRowsToThePreviousUidAndWipesThemWithIt() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(AccountBinding(userId: Self.uid, initialMergeDone: true))
        context.insert(SyncState(entityType: "favorites", userId: Self.uid, lastCursor: 99))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "A's", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid))
        context.insert(SavedPlaylist(playlistId: Self.playlistId, title: "A's", thumbnailUrl: nil,
                                     itemCount: 3, userId: Self.uid))
        context.insert(SubscribedChannel(channelId: Self.channelId, title: "A's", avatarUrl: nil,
                                         userId: ""))          // the anon row, A's by residence
        try context.save()
        // Scoped to THIS container: every other suite runs in parallel and saves into its own.
        let saves = Mutex<Int>(0), atPull = Mutex<Int>(-1)
        let token = NotificationCenter.default.addObserver(
            forName: ModelContext.didSave, object: nil, queue: nil) { note in
                guard let saved = note.object as? ModelContext, saved.container === container else { return }
                saves.withLock { $0 += 1 }
            }
        defer { NotificationCenter.default.removeObserver(token) }
        let client = ScriptedSyncClient(pulls: [.page(.empty)],
                                        hook: { _, _ in atPull.withLock { $0 = saves.withLock { $0 } } })
        let manager = self.manager(client, container: container)

        await manager.bind(uid: Self.other)

        #expect(atPull.withLock { $0 } == 1, "the account switch did not commit in ONE save")
        #expect(fetch(container, FavoriteVideo.self).isEmpty)
        #expect(fetch(container, SavedPlaylist.self).isEmpty)
        #expect(fetch(container, SubscribedChannel.self).isEmpty,
                "the anonymous row survived the wipe and is now available to re-tag to uid-b")
        #expect(fetch(container, SyncState.self).isEmpty)
        let binding = try #require(fetch(container, AccountBinding.self).first)
        #expect(binding.userId == Self.other)
        #expect(fetch(container, AccountBinding.self).count == 1)
    }

    /// **CF-A-16, the stop condition.** SwiftData offers no transaction primitive stronger than
    /// "one context, one save, `rollback()` on the way out", so this test IS the specification: a
    /// throw injected between the `SyncState` clear and the `AccountBinding` insert must leave the
    /// STORE — read back through a FRESH context, not the throwing one — byte-identical to the
    /// snapshot taken before `bind` was called.
    ///
    /// That injection point is the hard one because `#Unique<AccountBinding>([\.userId])` upserts
    /// at SAVE time, not at insert time, so the surviving row is decided by whether the save
    /// happened at all.
    @Test func aThrowMidAccountSwitchLeavesTheStoreByteIdenticalInAFreshContext() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(AccountBinding(userId: Self.uid, initialMergeDone: true))
        context.insert(SyncState(entityType: "favorites", userId: Self.uid, lastCursor: 99,
                                 lastDocId: "doc-9"))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "A's", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid))
        context.insert(SavedPlaylist(playlistId: Self.playlistId, title: "A's", thumbnailUrl: nil,
                                     itemCount: 3, userId: Self.uid))
        context.insert(SubscribedChannel(channelId: Self.channelId, title: "A's", avatarUrl: nil,
                                         userId: ""))
        try context.save()
        let before = Self.snapshot(container)

        let client = ScriptedSyncClient(pulls: [.page(.empty)])
        let manager = self.manager(client, container: container)

        await SyncManager.$injectedSwitchFailure.withValue({ throw SwitchFailure.injected }) {
            await manager.bind(uid: Self.other)
        }

        #expect(Self.snapshot(container) == before,
                "the account switch is not all-or-nothing: part of it survived the throw")
        #expect(client.calls.isEmpty, "the merge ran on top of a half-applied switch")
    }

    enum SwitchFailure: Error { case injected }

    /// Every stored column of every entity, read through a fresh context and sorted — "byte
    /// identical" made checkable. A column added without being listed here weakens the assertion,
    /// which is why the strings spell each one.
    @MainActor private static func snapshot(_ container: ModelContainer) -> [String] {
        let context = ModelContext(container)
        func rows<T: PersistentModel>(_ type: T.Type, _ describe: (T) -> String) -> [String] {
            ((try? context.fetch(FetchDescriptor<T>())) ?? []).map(describe).sorted()
        }
        return rows(FavoriteVideo.self) {
            "F|\($0.videoId)|\($0.title)|\($0.channelName)|\($0.thumbnailUrl ?? "-")|\($0.durationSeconds)|\($0.addedAt.timeIntervalSince1970)|\($0.userId)|\($0.updatedAt.timeIntervalSince1970)|\($0.isRemoved)|\($0.dirty)|\($0.approvalStatus)|\($0.source ?? "-")|\($0.importedAt?.timeIntervalSince1970 ?? -1)"
        } + rows(SavedPlaylist.self) {
            "P|\($0.playlistId)|\($0.title)|\($0.thumbnailUrl ?? "-")|\($0.itemCount)|\($0.addedAt.timeIntervalSince1970)|\($0.userId)|\($0.updatedAt.timeIntervalSince1970)|\($0.isRemoved)|\($0.dirty)|\($0.playlistUrl)|\($0.uploaderName ?? "-")|\($0.approvalStatus)|\($0.source ?? "-")|\($0.importedAt?.timeIntervalSince1970 ?? -1)"
        } + rows(SubscribedChannel.self) {
            "C|\($0.channelId)|\($0.title)|\($0.avatarUrl ?? "-")|\($0.followedAt.timeIntervalSince1970)|\($0.userId)|\($0.updatedAt.timeIntervalSince1970)|\($0.isRemoved)|\($0.dirty)|\($0.channelUrl)|\($0.approvalStatus)|\($0.source ?? "-")|\($0.importedAt?.timeIntervalSince1970 ?? -1)"
        } + rows(SyncState.self) {
            "S|\($0.entityType)|\($0.userId)|\($0.lastCursor)|\($0.lastDocId ?? "-")|\($0.lastSyncAt.timeIntervalSince1970)"
        } + rows(AccountBinding.self) {
            "B|\($0.userId)|\($0.boundAt.timeIntervalSince1970)|\($0.initialMergeDone)"
        }
    }

    // MARK: - Pull: one save per page

    /// Ruling F3 / SYNC-CURSOR-PERSIST-01. Two saves leave a window in which a crash keeps a cursor
    /// that has already moved past rows which were never written — and those rows are then never
    /// fetched again, because the cursor says they were.
    @Test func theRowsAndTheCursorTheyAdvancePastCommitInOneSave() async throws {
        let container = self.container()
        let saves = Mutex<Int>(0)
        // Scoped to THIS container: the suite runs in parallel with every other, and an unfiltered
        // `didSave` observer counts ~18 saves for this one page — all of them somebody else's.
        let token = NotificationCenter.default.addObserver(
            forName: ModelContext.didSave, object: nil, queue: nil) { note in
                guard let context = note.object as? ModelContext, context.container === container else { return }
                saves.withLock { $0 += 1 }
            }
        defer { NotificationCenter.default.removeObserver(token) }

        let client = ScriptedSyncClient(pulls: [
            .page(.page(subscriptions: [Self.subscription()], favorites: [Self.favorite()],
                        subscriptionsCursor: 2_000, subscriptionsCursorId: "doc-1"))
        ])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        #expect(saves.withLock { $0 } == 1, "the rows and the cursor were committed separately")
        #expect(fetch(container, SubscribedChannel.self).count == 1)
        let state = try #require(fetch(container, SyncState.self).first)
        #expect(state.entityType == "subscriptions")
        #expect(state.lastCursor == 2_000)
        #expect(state.lastDocId == "doc-1")
    }

    /// The `dirty` flag alone is the conflict signal: local writes never bump `updatedAt` (it is
    /// server-stamped on push success), so an unsynced local edit must survive a pull that would
    /// otherwise overwrite it, whatever the timestamps say.
    @Test func aDirtyLocalRowSurvivesAPullThatWouldOverwriteIt() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "my edit", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     dirty: true))
        try context.save()
        let client = ScriptedSyncClient(pulls: [
            .page(.page(favorites: [Self.favorite(updatedAt: 9_999, title: "server copy")]))
        ])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(row.title == "my edit")
        #expect(row.dirty)
    }

    /// A newer tombstone applies through the `RowAction` the decision returned — never through
    /// `SyncCodec.apply()`, which would take `isRemoved` from the DTO and rewrite every snapshot
    /// column with it, bypassing the monotonicity guard the decision was computed under. Each DTO
    /// carries a different title on purpose: if `apply()` ran, the title changes.
    ///
    /// All THREE types, because the routing is written out per type and a mutation pass found the
    /// subscriptions and playlists branches unpinned when only the favorites one was covered.
    @Test func aNewerTombstoneIsAppliedThroughTheRowActionAndNotThroughApply() async throws {
        let container = self.container()
        let context = ModelContext(container)
        let stale = Date(timeIntervalSince1970: 1)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "local title", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     updatedAt: stale, dirty: true))
        context.insert(SubscribedChannel(channelId: Self.channelId, title: "local title", avatarUrl: nil,
                                         userId: Self.uid, updatedAt: stale, dirty: true))
        context.insert(SavedPlaylist(playlistId: Self.playlistId, title: "local title", thumbnailUrl: nil,
                                     itemCount: 3, userId: Self.uid, updatedAt: stale, dirty: true))
        try context.save()
        let client = ScriptedSyncClient(pulls: [
            .page(.page(subscriptions: [Self.subscription(deleted: true, updatedAt: 8_000, name: "wire title")],
                        playlists: [Self.playlist(deleted: true, updatedAt: 8_000, name: "wire title")],
                        favorites: [Self.favorite(deleted: true, updatedAt: 8_000, title: "wire title")]))
        ])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        func check(_ row: any SyncableRow, _ title: String, _ label: String) {
            #expect(row.isRemoved, "\(label): not tombstoned")
            #expect(!row.dirty, "\(label): a tombstone clears dirty itself (`FavoriteVideoDao.kt:168`)")
            #expect(row.updatedAt == SyncCodec.date(millis: 8_000), "\(label): wrong timestamp")
            #expect(title == "local title", "\(label): the tombstone went through SyncCodec.apply()")
        }
        let favorite = try #require(fetch(container, FavoriteVideo.self).first)
        check(favorite, favorite.title, "favorite")
        let channel = try #require(fetch(container, SubscribedChannel.self).first)
        check(channel, channel.title, "subscription")
        let playlist = try #require(fetch(container, SavedPlaylist.self).first)
        check(playlist, playlist.title, "playlist")
    }

    /// A tombstone older than the local row is stale and must never resurrect it.
    @Test func aStaleTombstoneLeavesTheLocalRowAlone() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "local", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     updatedAt: SyncCodec.date(millis: 9_000)))
        try context.save()
        let client = ScriptedSyncClient(pulls: [
            .page(.page(favorites: [Self.favorite(deleted: true, updatedAt: 100)]))
        ])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(!row.isRemoved)
        #expect(row.updatedAt == SyncCodec.date(millis: 9_000))
    }

    /// The loop stops rather than spinning, and says which type stalled with the pair on both
    /// sides of the page — `SyncDecisions.page` compares whole dictionaries and can only report
    /// THAT something stalled, which is undiagnosable from a support ticket.
    @Test func aStalledCursorStopsTheLoopAndLogsTheTypeWithBothCursors() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(SyncState(entityType: "playlists", userId: Self.uid, lastCursor: 4_242,
                                 lastDocId: "doc-7"))
        try context.save()
        // The server mints back exactly the cursor it was queried with: no progress is possible.
        let client = ScriptedSyncClient(pulls: [
            .page(.page(playlistsCursor: 4_242, playlistsCursorId: "doc-7")),
            .page(.empty)
        ])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        #expect(client.calls == [.pull], "the loop kept requesting a page that cannot advance")
        let stall = try #require(await manager.incidents.first { $0.contains("stalled") })
        #expect(stall.contains("playlists"))
        #expect(stall.contains("4242"))
        #expect(stall.contains("doc-7"))
    }

    /// `SyncClient` drops a stored `lastDocId` the server would 400 on, silently — and a pull that
    /// has quietly lost its same-millisecond tiebreaker looks exactly like a healthy one.
    @Test func aStoredCursorIdTheServerWouldRejectIsReportedNotJustDropped() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(SyncState(entityType: "favorites", userId: Self.uid, lastCursor: 10,
                                 lastDocId: "__reserved__"))
        try context.save()
        let client = ScriptedSyncClient(pulls: [.page(.empty)])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        let dropped = try #require(await manager.incidents.first { $0.contains("lastDocId") })
        #expect(dropped.contains("favorites"))
        #expect(dropped.contains("__reserved__"))
    }

    /// `SyncPage.items` is a REQUIRED key (Moshi parity, Task 21's deliberate choice), so a server
    /// page that omits it fails the whole three-type decode. That is a transport failure for this
    /// run — never a signal that the server has no rows, and never a wipe.
    @Test func aPullThatFailsToDecodeWipesNothingAndKeepsTheCursor() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "local", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid))
        context.insert(SyncState(entityType: "favorites", userId: Self.uid, lastCursor: 77))
        try context.save()
        let decodeFailure = DecodingError.keyNotFound(
            SyncCodingKey.items, .init(codingPath: [], debugDescription: "items"))
        let client = ScriptedSyncClient(pulls: [.failure(decodeFailure), .failure(decodeFailure),
                                                .failure(decodeFailure)])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        #expect(client.calls.count == 3, "the bounded ladder is not three attempts")
        #expect(fetch(container, FavoriteVideo.self).count == 1)
        #expect(fetch(container, SyncState.self).first?.lastCursor == 77)
    }

    struct SyncCodingKey: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
        static let items = SyncCodingKey(stringValue: "items")
    }

    /// A revoked or blocked account answers 401/403 forever. Without a classifier in front of the
    /// ladder that is an unbounded loop against a server that will never say yes.
    @Test func aTerminalPullStopsTheRunWithoutRetrying() async throws {
        let container = self.container()
        let client = ScriptedSyncClient(pulls: [.failure(SyncClientError.pullStatus(403))])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        #expect(client.calls == [.pull], "a terminal pull entered the retry ladder")
        #expect(await manager.incidents.contains { $0.contains("terminal") })
    }

    /// **Task 23 review I1.** The addendum's PERMANENT arm is "400 invalid cursor -> DROP cursor
    /// with the log line, no retry". Logging and keeping it wedges the pull FOREVER: the next run
    /// sends the same id, gets the same 400, logs the same line and stops, with no self-heal and no
    /// symptom beyond an account that quietly stops receiving server changes. The pre-request
    /// validator cannot cover this — a 400 is precisely the case where the client's mirror of
    /// `SyncController.isValidCursorId` and the server have drifted apart (Task 22 M1).
    ///
    /// The whole request is rejected with one status, so the wire says nothing about WHICH type's
    /// tiebreaker was bad: all three go. That costs a re-fetch of the boundary millisecond, which
    /// `applyPage` upserts; keeping one costs the account.
    @Test func aServerRejectedCursorIdIsDroppedSoTheNextPullIsNotWedged() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(SyncState(entityType: "favorites", userId: Self.uid, lastCursor: 1_700,
                                 lastDocId: "doc-400"))
        try context.save()
        // "doc-400" passes `isValidCursorId`, so the pre-request drop cannot fire and the id really
        // does reach the wire — the server is the one rejecting it.
        let client = ScriptedSyncClient(pulls: [.failure(SyncClientError.pullStatus(400)),
                                                .page(.empty)])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        #expect(client.calls == [.pull], "a permanent pull entered the retry ladder")
        let dropped = try #require(await manager.incidents.first { $0.contains("rejected lastDocId") })
        #expect(dropped.contains("favorites"))
        #expect(dropped.contains("doc-400"))
        let state = try #require(fetch(container, SyncState.self).first)
        #expect(state.lastDocId == nil, "the rejected id is still stored; the next pull wedges again")
        #expect(state.lastCursor == 1_700, "the millisecond went with the id, re-fetching whole pages")

        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        #expect(client.pullIds == [["favorites": "doc-400"], [:]],
                "run 1 must send the stored id and run 2 must send none")
    }

    // MARK: - Push

    /// The fixed drain order, and the URL synthesis that has to happen before the first byte leaves.
    /// The stores never set `channelUrl`/`playlistUrl` (Android fills them in at subscribe time);
    /// an empty one meets the backend's `@NotBlank` as a 400 -> `.permanentFailure` -> the row's
    /// dirt dropped and the user's edit silently gone. A push with an empty URL is a test failure,
    /// not a wire event.
    @Test func theDrainRunsSubscriptionsThenPlaylistsThenFavoritesWithSynthesisedURLs() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(SubscribedChannel(channelId: Self.channelId, title: "Alafasy", avatarUrl: nil,
                                         userId: Self.uid, dirty: true))
        context.insert(SavedPlaylist(playlistId: Self.playlistId, title: "Series", thumbnailUrl: nil,
                                     itemCount: 3, userId: Self.uid, dirty: true))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     dirty: true))
        try context.save()
        let echo = SyncRowEcho(deleted: false, updatedAt: 5_000)
        let client = ScriptedSyncClient(puts: [.reply(200, echo), .reply(200, echo), .reply(200, echo)])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        #expect(client.calls == [.put(.subscriptions, Self.channelId),
                                 .put(.playlists, Self.playlistId),
                                 .put(.favorites, Self.videoId)])
        let bodies = client.bodies.map { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
        #expect(bodies[0]?["channelUrl"] as? String == "https://www.youtube.com/channel/\(Self.channelId)")
        #expect(bodies[1]?["playlistUrl"] as? String == "https://www.youtube.com/playlist?list=\(Self.playlistId)")
        // Synthesised once and persisted, not re-derived on every push.
        #expect(fetch(container, SubscribedChannel.self).first?.channelUrl
                == "https://www.youtube.com/channel/\(Self.channelId)")
    }

    /// `clearDirty` is where `updatedAt` gets its value, and the value is the SERVER's. A device
    /// clock ahead of the server writes a future timestamp that makes the monotonicity guard reject
    /// every later server update to that row, permanently (gate wave-2 W12).
    @Test func aPushedRowTakesItsUpdatedAtFromTheServerEchoAndNeverALocalClock() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     dirty: true))
        try context.save()
        let client = ScriptedSyncClient(puts: [.reply(200, SyncRowEcho(deleted: false, updatedAt: 1_700_000_000_000))])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(!row.dirty)
        #expect(row.updatedAt == SyncCodec.date(millis: 1_700_000_000_000))
    }

    /// SYNC-ECHO-01. A PUT answering `deleted: true` means the server's projection knows a parent
    /// was archived; the row is tombstoned locally instead of merely cleared, or it stays alive on
    /// this device until the next pull cycle.
    @Test func aPutAnsweringAnArchiveEchoTombstonesTheRowInsteadOfClearingIt() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(SubscribedChannel(channelId: Self.channelId, title: "Alafasy", avatarUrl: nil,
                                         userId: Self.uid, dirty: true))
        try context.save()
        let client = ScriptedSyncClient(puts: [.reply(200, SyncRowEcho(deleted: true, updatedAt: 6_000))])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        let row = try #require(fetch(container, SubscribedChannel.self).first)
        #expect(row.isRemoved)
        #expect(!row.dirty)
        #expect(row.updatedAt == SyncCodec.date(millis: 6_000))
    }

    /// A tombstoned row is a DELETE. Part B gate, Cubic round 1 P1: the echo's `updatedAt` — the
    /// server's tombstone time — IS stamped (Task 22 had dropped the body, leaving the last PUT's
    /// stamp, which the pull of the server's own tombstone then beat). Never a local clock.
    @Test func aTombstonedRowIsDeletedAndStampsTheServersTombstoneTime() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     updatedAt: SyncCodec.date(millis: 3_000), isRemoved: true,
                                     dirty: true))
        try context.save()
        let client = ScriptedSyncClient(deletes: [200])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        #expect(client.calls == [.delete(.favorites, Self.videoId)])
        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(!row.dirty)
        #expect(row.updatedAt == SyncCodec.date(millis: ScriptedSyncClient.deleteEchoUpdatedAt))
    }

    /// The P1 in full: favorite → unfavorite (DELETE echo T2) → re-favorite → the next pull brings
    /// the server's tombstone at T2. With the stamp the re-add carries T2 and survives
    /// (`.skipStaleTombstone`); without it the row still carried the PUT's T1 and was wiped.
    @Test func aReAddMadeAfterADeleteSurvivesThePullOfThatDeletesTombstone() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     updatedAt: SyncCodec.date(millis: 3_000), isRemoved: true, dirty: true))
        try context.save()
        let tombstoneTime = ScriptedSyncClient.deleteEchoUpdatedAt
        let client = ScriptedSyncClient(
            pulls: [.page(.page(favorites: [Self.favorite(deleted: true, updatedAt: tombstoneTime)]))],
            deletes: [200])
        let manager = self.manager(client, container: container)
        await manager.assumeBound(uid: Self.uid)
        await manager.pushDirty(uid: Self.uid)

        // The user re-favorites before the next pull (a store write: resurrect + dirty, no stamp).
        let again = ModelContext(container)
        let readd = try #require(try again.fetch(FetchDescriptor<FavoriteVideo>()).first)
        readd.isRemoved = false
        readd.dirty = true
        try again.save()

        await manager.pullAll(uid: Self.uid)

        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(row.isRemoved == false, "the pull of the delete's own tombstone wiped the re-add")
        #expect(row.dirty, "the re-add lost its dirt and will never be pushed")
    }

    /// Cubic round 1 P2: a guest toggle of an id the account already holds meets the `#Unique`
    /// pair the moment it is retagged. MEASURED (revert-and-run, Part B gate): SwiftData resolves
    /// the clash at save by clobbering, not by throwing — this case was green with the plain retag
    /// too — so what the explicit delete buys is a DETERMINISTIC winner (the account's row, the one
    /// the server knows) instead of whichever row SwiftData keeps. This pins the outcome, not the
    /// mechanism: one row, the account's, its dirt untouched, nothing pushed.
    @Test func aGuestDuplicateOfARowTheAccountAlreadyHoldsIsDroppedNotRetagged() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(AccountBinding(userId: Self.uid, initialMergeDone: true))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: "", dirty: true))
        try context.save()
        let client = ScriptedSyncClient(pulls: [.page(.empty)])
        let manager = self.manager(client, container: container)

        await manager.bind(uid: Self.uid)

        let rows = fetch(container, FavoriteVideo.self)
        #expect(rows.map(\.userId) == [Self.uid], "rows: \(rows.map { ($0.userId, $0.dirty) })")
        #expect(client.calls == [.pull], "the duplicate was retagged and pushed")
        #expect(await manager.incidents.filter { $0.contains("tag anon rows") }.isEmpty,
                "the retag save failed on the unique constraint")
    }

    /// Cubic round 1 P2: the archive echo tombstones a row the stores are rendering; they must
    /// hear about it like they hear about a pulled page.
    @Test func anArchiveEchoTombstoneReloadsTheStores() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid, dirty: true))
        try context.save()
        let reloads = Mutex(0)
        let client = ScriptedSyncClient(puts: [.reply(200, SyncRowEcho(deleted: true, updatedAt: 5_000))])
        let manager = SyncManager(client: client, modelContainer: container,
                                  backoff: SyncBackoff(random: { $0.lowerBound }), sleep: { _ in },
                                  onWrite: { reloads.withLock { $0 += 1 } })
        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(row.isRemoved)
        #expect(reloads.withLock { $0 } == 1)
    }

    /// Cubic round 1 P3: the classifier's 404 arm is the DELETE's. A PUT that 404s carries no echo
    /// to clear the row with and would be re-sent on every drain forever; it is permanent, and
    /// said out loud.
    @Test func aPutThatAnswers404IsPermanentNotSilentlyRetriedForever() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid, dirty: true))
        try context.save()
        let client = ScriptedSyncClient(puts: [.reply(404, nil)])
        let manager = self.manager(client, container: container)
        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(!row.dirty, "the row stays dirty and re-PUTs on every drain")
        #expect(await manager.incidents.contains { $0.contains("404") })
    }

    /// Cubic R5 P1 #19: the drain is resilient, not all-or-nothing. One 5xx on a subscription used
    /// to abort the whole cycle, leaving every other dirty row in every other type stuck until the
    /// next trigger. The failing row keeps its dirt; the queue makes forward progress.
    @Test func aTransientFailureLeavesThatRowDirtyAndTheDrainCarriesOn() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(SubscribedChannel(channelId: Self.channelId, title: "Alafasy", avatarUrl: nil,
                                         userId: Self.uid, dirty: true))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     dirty: true))
        try context.save()
        let client = ScriptedSyncClient(puts: [.reply(503, nil),
                                               .reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000))])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        #expect(client.calls == [.put(.subscriptions, Self.channelId), .put(.favorites, Self.videoId)])
        #expect(fetch(container, SubscribedChannel.self).first?.dirty == true)
        #expect(fetch(container, FavoriteVideo.self).first?.dirty == false)
    }

    /// 401/403 is not transient and does break the drain — the session is going away and every
    /// later request in this cycle would answer the same way.
    @Test func anAuthFailureBreaksTheDrain() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(SubscribedChannel(channelId: Self.channelId, title: "Alafasy", avatarUrl: nil,
                                         userId: Self.uid, dirty: true))
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     dirty: true))
        try context.save()
        let client = ScriptedSyncClient(puts: [.reply(401, nil)])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        #expect(client.calls == [.put(.subscriptions, Self.channelId)])
        #expect(fetch(container, FavoriteVideo.self).first?.dirty == true)
    }

    /// 400/409/422 is the payload's fault and the same bytes will fail the same way. The dirt is
    /// dropped with a warning rather than left to block every later pull forever.
    @Test func aPermanentFailureDropsTheDirtFlagAndSaysSo() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     dirty: true))
        try context.save()
        let client = ScriptedSyncClient(puts: [.reply(422, nil)])
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        #expect(fetch(container, FavoriteVideo.self).first?.dirty == false)
        #expect(await manager.incidents.contains { $0.contains("permanently rejected") })
    }

    /// `SyncClient.put` maps BOTH "no body" and "UNDECODABLE body" to a transient failure, and an
    /// undecodable body is not transient at all — it fails identically forever. So the ladder is
    /// bounded: after it the row keeps its dirt and the run reports it, instead of re-pushing until
    /// the process dies.
    @Test func theRetryLadderIsBoundedAndTheRowKeepsItsDirt() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     dirty: true))
        try context.save()
        // Every attempt answers 200 with no decodable body — Task 22's `dto == nil` leg.
        let client = ScriptedSyncClient(puts: Array(repeating: .reply(200, nil), count: 10))
        let manager = self.manager(client, container: container)

        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)
        for _ in 0..<500 where client.calls.count < 4 { await Task.yield() }

        #expect(client.calls.count == 4, "the ladder is unbounded: 1 drain + 3 retries is the cap")
        #expect(fetch(container, FavoriteVideo.self).first?.dirty == true)
        #expect(await manager.incidents.contains { $0.contains("ladder exhausted") })
        #expect(await manager.incidents.contains { $0.contains("no decodable body") })
    }

    /// **Task 23 review M3.** Every `SyncStore` write outside the two throwing transactions used to
    /// swallow its save with a bare `try?`. A `clearDirty` that did not commit is indistinguishable
    /// from one that did — and the row stays dirty, so it is re-pushed on every trigger, forever,
    /// with nothing in the ring to say why. One `save` helper, one `note()`.
    ///
    /// A `ModelContext.save()` cannot be made to fail on demand any more than the account switch
    /// could, so this uses `injectedSwitchFailure`'s twin at the same DEBUG seam.
    @Test func aSaveThatThrowsIsReportedInsteadOfSwallowed() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid,
                                     dirty: true))
        try context.save()
        let client = ScriptedSyncClient(puts: [.reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000))])
        let manager = self.manager(client, container: container)

        await SyncManager.$injectedSaveFailure.withValue({ throw SaveFailure.injected }) {
            await manager.assumeBound(uid: Self.uid)
            await manager.pushDirty(uid: Self.uid)
        }

        #expect(client.calls == [.put(.favorites, Self.videoId)])
        #expect(await manager.incidents.contains { $0.contains("clear dirty") },
                "the clearDirty save failed silently; the row re-pushes forever and nothing says so")
    }

    // MARK: - Part B gate: the identity fence, the lost-toggle guard, the page cap, the write hook

    /// Stage 3 I-2 / Codex 5. A toggle that lands while the row's PUT is on the wire flips
    /// `isRemoved` and re-dirties the row; the echo of the OLD bytes must not clear that newer
    /// edit, or the row disagrees with the server and nothing ever pushes it again.
    @Test func aToggleLandingWhileItsPutIsInFlightKeepsItsDirt() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid, dirty: true))
        try context.save()
        let client = ScriptedSyncClient(
            puts: [.reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000))],
            hook: { call, _ in
                guard case .put = call else { return }
                // The user unfavorites while the PUT is in flight: a second, newer local edit.
                await MainActor.run {
                    let mid = ModelContext(container)
                    if let row = try? mid.fetch(FetchDescriptor<FavoriteVideo>()).first {
                        row.isRemoved = true
                        row.dirty = true
                        try? mid.save()
                    }
                }
            })
        let manager = self.manager(client, container: container)
        await manager.assumeBound(uid: Self.uid)

        await manager.pushDirty(uid: Self.uid)

        let row = try #require(fetch(container, FavoriteVideo.self).first)
        #expect(row.isRemoved, "the mid-flight toggle was lost")
        #expect(row.dirty, "the echo of the OLD bytes cleared the NEWER edit's dirt")
    }

    /// Stage 3 I-4 / stage 5 C1 / stage 4 S2. `AuthorizedTransport` mints the bearer per request
    /// from whoever is current, so a drain for A that is still walking its rows when B binds
    /// would sign A's remaining PUTs with B's token. The bind takes the identity BEFORE it takes
    /// the lock, and the drain stops at its next row — and the echo of the request already on the
    /// wire clears nothing.
    @Test func aBindForAnotherAccountStopsTheDrainInFlightAtItsNextRow() async throws {
        let container = self.container()
        let context = ModelContext(container)
        for id in ["xc7keR2piUM", "xc7keR2piUN"] {
            context.insert(FavoriteVideo(videoId: id, title: "Lecture", channelName: "Alafasy",
                                         thumbnailUrl: nil, durationSeconds: 600, userId: Self.uid, dirty: true))
        }
        try context.save()
        let putGate = Gate()
        let client = ScriptedSyncClient(
            pulls: [.page(.empty)],
            puts: [.reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000)),
                   .reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000))],
            hook: { call, index in if case .put = call, index == 1 { await putGate.block() } })
        let manager = self.manager(client, container: container)
        await manager.assumeBound(uid: Self.uid)

        let drain = Task { await manager.pushDirty(uid: Self.uid) }
        await putGate.waitUntilBlocked()
        // B signs in while A's first PUT is on the wire. The bind parks on the exclusion, but its
        // identity is already taken.
        let bind = Task { await manager.bind(uid: Self.other) }
        for _ in 0..<200 { await Task.yield() }
        await putGate.release()
        await drain.value
        await bind.value

        let puts = client.calls.filter { if case .put = $0 { true } else { false } }
        #expect(puts.count == 1, "A's second row was pushed after B took the identity: \(client.calls)")
        let rows = fetch(container, FavoriteVideo.self)
        #expect(rows.allSatisfy { $0.dirty }, "an echo received after the identity changed cleared a row")
    }

    /// Stage 4 S6 / Codex 10. `.advance` accepts any cursor pair that differs from the last, so a
    /// server that keeps minting a moving cursor holds the ONE exclusion forever. The cap ends
    /// the run, keeps the cursor, and says so.
    @Test func aPullThatNeverExhaustsStopsAtThePageCapAndKeepsItsCursor() async throws {
        let container = self.container()
        let pages: [ScriptedSyncClient.PullReply] = (1...5).map { n in
            .page(.page(subscriptions: [Self.subscription("UCmMcOjsVehVlEOteyrhjI\(n)Q", updatedAt: n * 1_000)],
                        subscriptionsCursor: n * 1_000, subscriptionsCursorId: "doc-\(n)"))
        }
        let client = ScriptedSyncClient(pulls: pages)
        let manager = SyncManager(client: client, modelContainer: container,
                                  backoff: SyncBackoff(random: { $0.lowerBound }), sleep: { _ in },
                                  maxPullPages: 3)
        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        #expect(client.calls == [.pull, .pull, .pull])
        #expect(await manager.incidents.contains { $0.contains("page cap") })
        let state = try #require(fetch(container, SyncState.self).first)
        #expect(state.lastCursor == 3_000, "the cap threw away the pages it had already written")
    }

    /// Stage 5 I5. A pull that ended on a TERMINAL verdict unbinds — and the merge that wrapped
    /// it must stop there too: no push under a bearer the server just refused, and no
    /// `initialMergeDone` stamped for an account that is gone.
    @Test func aTerminalPullEndsTheMergeWithoutAPushOrAMergeDoneStamp() async throws {
        let container = self.container()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
                                     thumbnailUrl: nil, durationSeconds: 600, userId: "", dirty: true))
        try context.save()
        let client = ScriptedSyncClient(pulls: [.failure(SyncClientError.pullStatus(403))],
                                        puts: [.reply(200, SyncRowEcho(deleted: false, updatedAt: 5_000))])
        let manager = self.manager(client, container: container)

        await manager.bind(uid: Self.uid)

        #expect(client.calls == [.pull], "the merge pushed after a terminal pull: \(client.calls)")
        let binding = try #require(fetch(container, AccountBinding.self).first)
        #expect(binding.initialMergeDone == false, "a terminal account was stamped merge-done")
    }

    /// Stage 9 round 2 P1 + its sibling: `c79cc4b7` cleared the fence on a terminal pull. The
    /// session keeps its own bound-uid latch and never rebinds the same identity, so every later
    /// trigger was refused for the rest of the process; and a `bind(B)` already queued behind the
    /// terminal pull (it takes the identity BEFORE the lock) was refused too. The fence stays up.
    @Test func aTerminalPullDoesNotKillSyncForTheRestOfTheProcess() async throws {
        let container = self.container()
        let client = ScriptedSyncClient(pulls: [.failure(SyncClientError.pullStatus(403)), .page(.empty)])
        let manager = self.manager(client, container: container)
        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)          // terminal
        await manager.syncNow(uid: Self.uid)          // the session's next foreground

        #expect(client.calls == [.pull, .pull], "the manager dropped the identity on its own: \(client.calls)")
    }

    @Test func aTerminalPullForTheOldAccountDoesNotRefuseTheBindAlreadyQueuedForTheNew() async throws {
        let container = self.container()
        let pullGate = Gate()
        let client = ScriptedSyncClient(
            pulls: [.failure(SyncClientError.pullStatus(403)), .page(.empty)],
            hook: { call, index in if call == .pull, index == 1 { await pullGate.block() } })
        let manager = self.manager(client, container: container)
        await manager.assumeBound(uid: Self.uid)

        let old = Task { await manager.pullAll(uid: Self.uid) }
        await pullGate.waitUntilBlocked()
        let new = Task { await manager.bind(uid: Self.other) }   // queued behind A's pull
        for _ in 0..<200 { await Task.yield() }
        await pullGate.release()
        await old.value
        await new.value

        #expect(client.calls == [.pull, .pull], "B's bind was refused after A's terminal pull: \(client.calls)")
        let binding = try #require(fetch(container, AccountBinding.self).first)
        #expect(binding.userId == Self.other)
        #expect(binding.initialMergeDone, "B's merge never ran")
    }

    /// Stage 4 S5. A server id becomes a `#Unique` key, a route, a synthesised URL pushed back
    /// and an accessibility identifier — so it passes the same validator every LOCAL writer
    /// applies, or it is skipped and noted. Same for a cursor id, validated on the way IN.
    @Test func serverRowsAndCursorIdsThatFailTheLocalIdRulesAreSkippedAndNoted() async throws {
        let container = self.container()
        let client = ScriptedSyncClient(pulls: [
            .page(.page(subscriptions: [Self.subscription("../etc"), Self.subscription()],
                        favorites: [Self.favorite("not-eleven-chars-long")],
                        subscriptionsCursor: 2_000, subscriptionsCursorId: "__x__"))
        ])
        let manager = self.manager(client, container: container)
        await manager.assumeBound(uid: Self.uid)

        await manager.pullAll(uid: Self.uid)

        #expect(fetch(container, SubscribedChannel.self).map(\.channelId) == [Self.channelId])
        #expect(fetch(container, FavoriteVideo.self).isEmpty)
        let state = try #require(fetch(container, SyncState.self).first)
        #expect(state.lastCursor == 2_000)
        #expect(state.lastDocId == nil, "an invalid cursor id was persisted")
        let incidents = await manager.incidents.filter { $0.contains("invalid id") }
        #expect(incidents.count == 3, "two rows and one cursor id: \(incidents)")
        #expect(incidents.filter { $0.contains("cursor id") }.count == 1)
    }

    /// Stage 5 I3. The stores keep their own contexts and re-read only on their own writes, so a
    /// pull that restored a whole library rendered NOTHING until the next toggle or relaunch. The
    /// manager now says "I wrote" after every committed write — tagging, switching, every page.
    @Test func everyCommittedSyncWriteReloadsTheStores() async throws {
        let container = self.container()
        let reloads = Mutex(0)
        let client = ScriptedSyncClient(pulls: [
            .page(.page(subscriptions: [Self.subscription()], subscriptionsCursor: 2_000)),
            .page(.empty)
        ])
        let manager = SyncManager(client: client, modelContainer: container,
                                  backoff: SyncBackoff(random: { $0.lowerBound }), sleep: { _ in },
                                  onWrite: { reloads.withLock { $0 += 1 } })

        await manager.bind(uid: Self.uid)

        // tagAnonRows + two applied pages (the empty one still commits its cursor read).
        #expect(reloads.withLock { $0 } == 3, "reloads: \(reloads.withLock { $0 })")
    }

    /// Stage 3 I-3, measured rather than assumed. The stores hold long-lived contexts; a row the
    /// sync tombstoned through ITS context must not leave a stale registered object in the
    /// store's, or the next toggle takes the wrong branch (unsubscribe on an already-removed row)
    /// and the button does nothing.
    @MainActor @Test func aToggleAfterAPulledTombstoneResurrectsTheRowInsteadOfReTombstoningIt() async throws {
        let container = self.container()
        let store = SwiftDataSubscriptionsStore(modelContainer: container)
        store.currentUserId = Self.uid
        try store.toggle(id: Self.channelId, name: "Alafasy", avatarURL: nil)
        #expect(store.isSubscribed(Self.channelId))
        // The push echo clears the dirt (through the manager's own context), then a later pull
        // tombstones the row (again through its own context).
        _ = SyncStore.clearDirty(container, uid: Self.uid, type: .subscriptions, id: Self.channelId,
                                 serverUpdatedAt: 1_000, pushedRemoved: false)
        _ = try SyncStore.applyPage(container, uid: Self.uid, body: .page(
            subscriptions: [Self.subscription(deleted: true, updatedAt: 2_000)]))
        store.reload()
        #expect(store.isSubscribed(Self.channelId) == false, "the tombstone did not land")

        try store.toggle(id: Self.channelId, name: "Alafasy", avatarURL: nil)

        #expect(store.isSubscribed(Self.channelId), "the toggle read a stale isRemoved and un-subscribed a removed row")
        let row = try #require(fetch(container, SubscribedChannel.self).first)
        #expect(row.isRemoved == false)
        #expect(row.dirty)
    }

    enum SaveFailure: Error { case injected }
}
