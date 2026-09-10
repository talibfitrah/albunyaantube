import Foundation

nonisolated enum ImportPhase: Sendable, Equatable { case resolving, writing, done }

nonisolated struct ImportSummary: Sendable, Equatable {
    var added: Int
    var sentForReview: Int
    /// `alreadyPresent` + everything the backend refused or could not resolve.
    var skipped: Int
    var alreadyPresent: Int
    /// How many of the fresh candidates this run actually got through. A COMPLETE run ends with
    /// `processed == total`; anything short of that is a run a 429, a cancel or a network failure
    /// cut off. Review I1: the "this was partial" signal used to exist ONLY in the transient DONE
    /// `progress` emission, so a screen that keeps the summary and drops the last callback would
    /// tell a user whose connection died after chunk 1 "200 added" and nothing else. The plan's
    /// Task 28 interface block is amended for this field.
    var processed: Int
    /// The DENOMINATOR `processed` is short of: the FRESH count this run set out to write
    /// (`candidates.count - alreadyPresent`). Task 28 re-review: without it the partial predicate
    /// needed a number only the screen's caller held, so the summary could not answer "was this
    /// run complete?" on its own — and the screen would have had to keep the candidate list alive
    /// past the run to ask. `processed < total` is the whole predicate now.
    var total: Int
    var rateLimited: Bool
}

/// The three strings an imported row is stamped with, spelled ONCE (R9-P3 #17). `source` is F13's
/// alignment with the backend registry's own provenance value — the two used to describe the same
/// thing differently.
nonisolated enum ImportProvenance {
    static let source = "USER_IMPORT"
    /// The registry already knows this item and approved it: an ordinary row, visible immediately.
    static let approved = "APPROVED"
    /// Submitted for approval by this import. Hidden from every `items` list (V5's fail-closed
    /// `== "APPROVED"` filter) but still `isSubscribed`/`isSaved`/`isFavorite`, so a later manual
    /// toggle finds the existing row instead of duplicating it.
    static let awaiting = "AWAITING"
}

/// B9's orchestration (`YouTubeImportRepository.kt:66-160`), as a value: dedupe against the local
/// rows, chunk at `ImportClient.batchSize`, write per the APPROVED/PENDING matrix, stop on 429.
///
/// **Every write goes through a STORE**, never a raw `ModelContext`: the stores are what set
/// `dirty` and call back into `AppContainer.pushDirtySoon`, so an import's rows are pushed by the
/// sync manager exactly as a manual toggle's are — and coalesced, which is what keeps a 200-row
/// chunk from queueing 200 pushes.
///
/// **Cancellable at every await.** A cancel that lands while a chunk is in flight writes NONE of
/// that chunk; the chunks already committed stay and dedupe on the retry, which is the same state
/// a 429 leaves.
@MainActor struct ImportPipeline {

    private let client: ImportClient
    private let favorites: any FavoritesStore
    private let subscriptions: any SubscriptionsStore
    private let playlists: any SavedPlaylistsStore
    private let now: () -> Date

    init(client: ImportClient, favorites: any FavoritesStore, subscriptions: any SubscriptionsStore,
         playlists: any SavedPlaylistsStore, now: @escaping () -> Date) {
        self.client = client
        self.favorites = favorites
        self.subscriptions = subscriptions
        self.playlists = playlists
        self.now = now
    }

    /// `progress` is called with the ACTUAL processed count, never the total (cubic-P3): a 429- or
    /// cancel-truncated run must not be painted as complete.
    func run(_ candidates: [ImportCandidate],
             progress: @MainActor (ImportPhase, Int, Int) -> Void) async -> ImportSummary {

        // 1. Dedupe against the local rows. Deleted-agnostic AND status-agnostic: a soft-deleted
        //    row and an AWAITING one both mean "the user already has this", so re-sending it would
        //    spend the daily item budget to write a row that already exists.
        var alreadyPresent = 0
        var fresh: [ImportCandidate] = []
        for candidate in candidates {
            if containsAny(candidate) { alreadyPresent += 1 } else { fresh.append(candidate) }
        }

        let total = fresh.count
        var added = 0, sentForReview = 0, rejectedOrError = 0, processed = 0
        var rateLimited = false
        progress(.resolving, 0, total)

        let chunks = stride(from: 0, to: total, by: ImportClient.batchSize).map {
            Array(fresh[$0..<min($0 + ImportClient.batchSize, total)])
        }

        for chunk in chunks {
            if Task.isCancelled { break }
            progress(.resolving, processed, total)

            let results: [ImportResult]
            do {
                results = try await client.resolve(chunk)
            } catch {
                // F10: a 429 means the per-user daily import budget is exhausted mid-run. Stop —
                // the chunks already written persist and dedupe on retry — and report partial
                // success plus the cap, instead of failing the whole import. Any OTHER failure
                // stops the same way but claims no cap: the DONE emission's short count is what
                // says the run was partial.
                if case .rateLimited = error { rateLimited = true }
                break
            }
            // The cancel could have landed while that resolve was in flight. Writing here anyway
            // is what a half-imported chunk would look like.
            if Task.isCancelled { break }

            let byId = Dictionary(chunk.map { ($0.youtubeId, $0) }, uniquingKeysWith: { first, _ in first })
            let chunkBase = processed
            progress(.writing, processed, total)

            for result in results {
                let candidate = byId[result.youtubeId]
                switch result.disposition {
                case .approved:
                    if writeApproved(result, candidate) { added += 1 } else { rejectedOrError += 1 }
                case .pending:
                    // Counted as "sent for review" only when a row was actually WRITTEN. A
                    // youtubeId absent from the request chunk has no candidate metadata to write,
                    // so the count must skip it too or the summary over-reports.
                    if let candidate, writePending(candidate) { sentForReview += 1 } else { rejectedOrError += 1 }
                case .rejected, .error:
                    rejectedOrError += 1
                }
                // Clamped to this chunk's candidates: a backend echoing more rows than it was sent
                // must never push the count past `total`.
                processed = min(processed + 1, chunkBase + chunk.count)
                progress(.writing, processed, total)
            }
            // Review M3: count CANDIDATES, not results. `ImportClient` drops a row whose `type`
            // this build cannot name, and that candidate was still processed — counting results
            // would make a fully successful run read as partial and corrupt the exact signal
            // `processed` exists to carry.
            processed = chunkBase + chunk.count
        }

        progress(.done, processed, total)
        return ImportSummary(added: added, sentForReview: sentForReview,
                             skipped: alreadyPresent + rejectedOrError,
                             alreadyPresent: alreadyPresent, processed: processed, total: total,
                             rateLimited: rateLimited)
    }

    // MARK: - Dedupe

    private func containsAny(_ candidate: ImportCandidate) -> Bool {
        switch candidate.type {
        case .channel: subscriptions.containsAny(candidate.youtubeId)
        case .playlist: playlists.containsAny(candidate.youtubeId)
        case .video: favorites.containsAny(candidate.youtubeId)
        }
    }

    // MARK: - The write matrix

    /// APPROVED: the registry's CANONICAL metadata wins over the candidate's, because the whole
    /// point of the round trip is that a curator has already named and thumbnailed this item.
    /// A write that throws (a malformed id, a failed save) counts as rejectedOrError, never added.
    private func writeApproved(_ result: ImportResult, _ candidate: ImportCandidate?) -> Bool {
        let content = result.content
        let at = now()
        do {
            switch result.type {
            case .channel:
                // `name` first: `ContentItemMapper.fromChannel` sets it and leaves `title` null.
                try subscriptions.importChannel(
                    id: result.youtubeId,
                    title: content?.name ?? content?.title ?? candidate?.title ?? result.youtubeId,
                    avatarUrl: content?.thumbnailUrl ?? candidate?.thumbnailUrl,
                    approvalStatus: ImportProvenance.approved, at: at)
            case .playlist:
                try playlists.importPlaylist(
                    id: result.youtubeId,
                    title: content?.title ?? content?.name ?? candidate?.title ?? result.youtubeId,
                    thumbnailUrl: content?.thumbnailUrl ?? candidate?.thumbnailUrl,
                    uploaderName: content?.channelTitle,
                    approvalStatus: ImportProvenance.approved, at: at)
            case .video:
                try favorites.importVideo(
                    id: result.youtubeId,
                    title: content?.title ?? candidate?.title ?? result.youtubeId,
                    // NEVER `candidate.channelId` — that is a "UC…" id, not a name.
                    channelName: content?.channelTitle ?? "",
                    thumbnailUrl: content?.thumbnailUrl ?? candidate?.thumbnailUrl,
                    durationSeconds: content?.durationSeconds ?? 0,
                    approvalStatus: ImportProvenance.approved, at: at)
            }
            return true
        } catch {
            return false
        }
    }

    /// PENDING: the backend returns no content for it, so the candidate's own metadata is all
    /// there is. The row is AWAITING until an admin reviews the submission this just created.
    private func writePending(_ candidate: ImportCandidate) -> Bool {
        let at = now()
        do {
            switch candidate.type {
            case .channel:
                try subscriptions.importChannel(id: candidate.youtubeId, title: candidate.title,
                                                avatarUrl: candidate.thumbnailUrl,
                                                approvalStatus: ImportProvenance.awaiting, at: at)
            case .playlist:
                try playlists.importPlaylist(id: candidate.youtubeId, title: candidate.title,
                                             thumbnailUrl: candidate.thumbnailUrl, uploaderName: nil,
                                             approvalStatus: ImportProvenance.awaiting, at: at)
            case .video:
                try favorites.importVideo(id: candidate.youtubeId, title: candidate.title,
                                          // Deliberately blank: the candidate carries the uploader's
                                          // ID, and an id rendered where a name belongs is worse
                                          // than a blank (`YouTubeImportRepository.kt:233,288`).
                                          channelName: "", thumbnailUrl: candidate.thumbnailUrl,
                                          durationSeconds: 0,
                                          approvalStatus: ImportProvenance.awaiting, at: at)
            }
            return true
        } catch {
            return false
        }
    }
}
