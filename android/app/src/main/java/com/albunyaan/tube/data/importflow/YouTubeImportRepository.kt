package com.albunyaan.tube.data.importflow

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.currentUid
import com.albunyaan.tube.data.importflow.ImportProgress.Phase
import com.albunyaan.tube.data.importflow.dto.ImportItemDto
import com.albunyaan.tube.data.importflow.dto.ImportResolveRequestDto
import com.albunyaan.tube.data.importflow.dto.ImportResultDto
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import com.albunyaan.tube.data.youtube.CandidateType
import com.albunyaan.tube.data.youtube.ImportCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B9: Orchestrates the YouTube-import flow end-to-end.
 *
 * Algorithm:
 *  1. Dedup against Room — candidates whose youtubeId already exists (any
 *     approval_status, including AWAITING/soft-deleted) are counted as [skipped]
 *     and never sent to the backend.
 *  2. Remaining "fresh" candidates are chunked into batches of ≤[BATCH_SIZE] and
 *     sent to [ImportApi.resolve].
 *  3. Each [ImportResultDto] is written to the appropriate Me-list table via the
 *     existing repositories (so the SyncManager dirty-flag fires normally):
 *       - APPROVED  → written with approvalStatus="APPROVED"; canonical metadata
 *                     from result.content when present, else candidate metadata.
 *       - PENDING   → written with approvalStatus="AWAITING"; candidate metadata.
 *       - REJECTED / ERROR / unknown → NOT written; counted as [skipped].
 *  4. Progress is emitted via [progress] as a [StateFlow<ImportProgress>].
 *
 * Hilt-injected singleton; the [ImportApi] dependency is provided by the DI module (B15).
 */
@Singleton
class YouTubeImportRepository @Inject constructor(
    private val importApi: ImportApi,
    private val subscriptionRepository: SubscriptionRepository,
    private val favoritesRepository: FavoritesRepository,
    private val accountRepository: AccountRepository,
) {

    companion object {
        const val BATCH_SIZE = 200
        // F13: align the per-user row's source tag with the backend registry's
        // "USER_IMPORT" provenance value (they described the same thing differently).
        const val SOURCE_USER_IMPORT = "USER_IMPORT"
    }

    private val _progress = MutableStateFlow(ImportProgress(Phase.RESOLVING, 0, 0))
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    /**
     * Run the full import for the given [selected] candidates.
     *
     * This is a suspend function that runs sequentially. Call it from a
     * CoroutineScope (the ImportViewModel in B10 will do this via viewModelScope).
     *
     * @return [ImportSummary] with final counts once all candidates are processed.
     */
    suspend fun import(selected: List<ImportCandidate>): ImportSummary {
        val uid = accountRepository.currentUid()
        val now = System.currentTimeMillis()

        // ── 1. Dedup vs local Room ────────────────────────────────────────────
        // getByIdAny includes soft-deleted and any approval_status — "already
        // present" means the user already has this item in their list in any state.
        var alreadyPresent = 0
        val fresh = mutableListOf<ImportCandidate>()
        for (candidate in selected) {
            val exists = existsInRoom(uid, candidate)
            if (exists) {
                alreadyPresent++
            } else {
                fresh.add(candidate)
            }
        }

        val total = fresh.size
        _progress.value = ImportProgress(Phase.RESOLVING, 0, total)

        // ── 2 & 3. Chunk → resolve → write ───────────────────────────────────
        var added = 0
        var sentForReview = 0
        var rejectedOrError = 0
        var processed = 0

        val chunks = fresh.chunked(BATCH_SIZE)
        var rateLimited = false
        for (chunk in chunks) {
            _progress.value = ImportProgress(Phase.RESOLVING, processed, total)

            val request = ImportResolveRequestDto(
                items = chunk.map { candidate ->
                    ImportItemDto(
                        type = candidate.type.name,          // enum name → "CHANNEL"/"PLAYLIST"/"VIDEO"
                        youtubeId = candidate.youtubeId,
                        title = candidate.title,
                        thumbnailUrl = candidate.thumbnailUrl,
                        channelId = candidate.channelId,
                    )
                }
            )

            // F10: a 429 means the per-user daily import budget is exhausted mid-run.
            // Stop here — chunks already written persist (and dedup on retry) — and
            // report partial success plus the cap, instead of failing the whole import.
            val response = try {
                importApi.resolve(request)
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 429) { rateLimited = true; break }
                throw e
            }

            // Build a map so we can look up the original candidate for fallback metadata
            val candidateByYoutubeId = chunk.associateBy { it.youtubeId }

            _progress.value = ImportProgress(Phase.WRITING, processed, total)

            for (result in response.results) {
                val candidate = candidateByYoutubeId[result.youtubeId]
                when (result.disposition) {
                    "APPROVED" -> {
                        writeApproved(uid, now, result, candidate)
                        added++
                    }
                    "PENDING" -> {
                        if (candidate != null) {
                            writePending(uid, now, candidate)
                        }
                        sentForReview++
                    }
                    else -> {
                        // "REJECTED", "ERROR", or any unknown disposition — do not write
                        rejectedOrError++
                    }
                }
                processed++
                _progress.value = ImportProgress(Phase.WRITING, processed, total)
            }
        }

        // cubic-P3: report the ACTUAL processed count — a 429 break leaves processed < total,
        // so emitting (total, total) would paint a partial run as fully complete.
        _progress.value = ImportProgress(Phase.DONE, processed, total)

        val skipped = alreadyPresent + rejectedOrError
        return ImportSummary(
            added = added,
            sentForReview = sentForReview,
            skipped = skipped,
            alreadyPresent = alreadyPresent,
            rateLimited = rateLimited,
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns true if the candidate's youtubeId is already present in Room for
     * the current user (any approval_status, any deleted state).
     */
    private suspend fun existsInRoom(uid: String, candidate: ImportCandidate): Boolean {
        return when (candidate.type) {
            CandidateType.CHANNEL  -> subscriptionRepository.channelExistsAny(uid, candidate.youtubeId)
            CandidateType.PLAYLIST -> subscriptionRepository.playlistExistsAny(uid, candidate.youtubeId)
            CandidateType.VIDEO    -> favoritesRepository.favoriteExistsAny(uid, candidate.youtubeId)
        }
    }

    /**
     * Write an APPROVED result to the correct Me-list table.
     *
     * Prefers canonical metadata from [result.content] when present (it is the
     * authoritative server-side record). Falls back to candidate metadata if
     * content is null (should not happen for APPROVED but defensive).
     */
    private suspend fun writeApproved(
        uid: String,
        now: Long,
        result: ImportResultDto,
        candidate: ImportCandidate?,
    ) {
        val content = result.content
        when (result.type) {
            "CHANNEL" -> {
                val channel = SubscribedChannel(
                    channelId = result.youtubeId,
                    channelUrl = "https://www.youtube.com/channel/${result.youtubeId}",
                    name = content?.name ?: content?.title ?: candidate?.title ?: result.youtubeId,
                    avatarUrl = content?.thumbnailUrl ?: candidate?.thumbnailUrl,
                    subscribedAt = now,
                    user_id = uid,
                    dirty = true,
                    deleted = false,
                    approvalStatus = "APPROVED",
                    source = SOURCE_USER_IMPORT,
                    importedAt = now,
                )
                subscriptionRepository.subscribe(channel)
            }
            "PLAYLIST" -> {
                val playlist = SavedPlaylist(
                    playlistId = result.youtubeId,
                    playlistUrl = "https://www.youtube.com/playlist?list=${result.youtubeId}",
                    name = content?.title ?: content?.name ?: candidate?.title ?: result.youtubeId,
                    thumbnailUrl = content?.thumbnailUrl ?: candidate?.thumbnailUrl,
                    uploaderName = content?.channelTitle,
                    savedAt = now,
                    user_id = uid,
                    dirty = true,
                    deleted = false,
                    approvalStatus = "APPROVED",
                    source = SOURCE_USER_IMPORT,
                    importedAt = now,
                )
                subscriptionRepository.savePlaylist(playlist)
            }
            "VIDEO" -> {
                favoritesRepository.addImportedFavorite(
                    uid = uid,
                    videoId = result.youtubeId,
                    title = content?.title ?: candidate?.title ?: result.youtubeId,
                    channelName = content?.channelTitle ?: "",  // never the channelId — it's a "UC…" id, not a name
                    thumbnailUrl = content?.thumbnailUrl ?: candidate?.thumbnailUrl,
                    durationSeconds = content?.durationSeconds ?: 0,
                    approvalStatus = "APPROVED",
                    source = SOURCE_USER_IMPORT,
                    importedAt = now,
                )
            }
        }
    }

    /**
     * Write a PENDING result to the correct Me-list table with approvalStatus="AWAITING".
     * Uses candidate metadata (backend returns no content for PENDING).
     */
    private suspend fun writePending(uid: String, now: Long, candidate: ImportCandidate) {
        when (candidate.type) {
            CandidateType.CHANNEL -> {
                val channel = SubscribedChannel(
                    channelId = candidate.youtubeId,
                    channelUrl = "https://www.youtube.com/channel/${candidate.youtubeId}",
                    name = candidate.title,
                    avatarUrl = candidate.thumbnailUrl,
                    subscribedAt = now,
                    user_id = uid,
                    dirty = true,
                    deleted = false,
                    approvalStatus = "AWAITING",
                    source = SOURCE_USER_IMPORT,
                    importedAt = now,
                )
                subscriptionRepository.subscribe(channel)
            }
            CandidateType.PLAYLIST -> {
                val playlist = SavedPlaylist(
                    playlistId = candidate.youtubeId,
                    playlistUrl = "https://www.youtube.com/playlist?list=${candidate.youtubeId}",
                    name = candidate.title,
                    thumbnailUrl = candidate.thumbnailUrl,
                    uploaderName = null,
                    savedAt = now,
                    user_id = uid,
                    dirty = true,
                    deleted = false,
                    approvalStatus = "AWAITING",
                    source = SOURCE_USER_IMPORT,
                    importedAt = now,
                )
                subscriptionRepository.savePlaylist(playlist)
            }
            CandidateType.VIDEO -> {
                favoritesRepository.addImportedFavorite(
                    uid = uid,
                    videoId = candidate.youtubeId,
                    title = candidate.title,
                    channelName = "",  // no channel name on the PENDING path; the candidate carries only the id
                    thumbnailUrl = candidate.thumbnailUrl,
                    durationSeconds = 0,
                    approvalStatus = "AWAITING",
                    source = SOURCE_USER_IMPORT,
                    importedAt = now,
                )
            }
        }
    }
}
