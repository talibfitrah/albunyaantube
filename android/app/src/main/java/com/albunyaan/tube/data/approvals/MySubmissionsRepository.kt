package com.albunyaan.tube.data.approvals

import com.albunyaan.tube.data.approvals.dto.*
import javax.inject.Inject
import javax.inject.Singleton

class RateLimitError(val retryAfterSeconds: Long) : RuntimeException("rate-limited")

/**
 * Submission was adjudicated server-side (APPROVED / REJECTED) between the moment the
 * user opened the sheet and the moment they hit save/delete. Surfaced as a typed
 * exception so consumers can branch on it without matching English error-message
 * substrings — that string match was fragile to translation and to any future wrapping
 * Throwable that shadowed the original `message`.
 */
class AlreadyReviewedError : RuntimeException("Already reviewed")

@Singleton
class MySubmissionsRepository @Inject constructor(
    private val api: ApprovalApi,
) {
    suspend fun fetchMySubmissions(status: String? = null): Result<List<PendingApprovalDto>> = runCatching {
        val resp = api.mySubmissions(status = status, cursor = null, limit = 100)
        if (!resp.isSuccessful) error("HTTP ${resp.code()}")
        resp.body()?.items ?: emptyList()
    }

    suspend fun submitChannel(
        youtubeId: String,
        categoryIds: List<String>,
        name: String? = null,
        thumbnailUrl: String? = null,
        submitterNote: String? = null,
    ): Result<Unit> = submit {
        api.submitChannel(SubmitChannelRequest(youtubeId, categoryIds, name, thumbnailUrl, submitterNote))
    }

    suspend fun submitPlaylist(
        youtubeId: String,
        categoryIds: List<String>,
        title: String? = null,
        thumbnailUrl: String? = null,
        submitterNote: String? = null,
    ): Result<Unit> = submit {
        api.submitPlaylist(SubmitPlaylistRequest(youtubeId, categoryIds, title, thumbnailUrl, submitterNote))
    }

    suspend fun submitVideo(
        youtubeId: String,
        categoryIds: List<String>,
        title: String? = null,
        thumbnailUrl: String? = null,
        submitterNote: String? = null,
    ): Result<Unit> = submit {
        api.submitVideo(SubmitVideoRequest(youtubeId, categoryIds, title, thumbnailUrl, submitterNote))
    }

    /**
     * Update the free-text "why I'm suggesting this" note on a row the caller submitted.
     * Backend rejects (409) once the row has been adjudicated (APPROVED/REJECTED).
     */
    suspend fun editSubmitterNote(type: String, id: String, submitterNote: String?): Result<Unit> = runCatching {
        val body = SubmitterNoteUpdateRequest(submitterNote)
        val resp = when (type.uppercase()) {
            "CHANNEL"  -> api.editChannelSubmitterNote(id, body)
            "PLAYLIST" -> api.editPlaylistSubmitterNote(id, body)
            "VIDEO"    -> api.editVideoSubmitterNote(id, body)
            else       -> error("Unknown submission type: $type")
        }
        when {
            resp.isSuccessful -> Unit
            resp.code() == 403 -> error("Not your submission")
            resp.code() == 409 -> throw AlreadyReviewedError()
            else -> error("HTTP ${resp.code()}")
        }
    }

    /**
     * Delete a submission the caller created — only allowed while PENDING or REQUEST_CHANGES.
     */
    suspend fun deleteSubmission(type: String, id: String): Result<Unit> = runCatching {
        val resp = when (type.uppercase()) {
            "CHANNEL"  -> api.deleteChannelSubmission(id)
            "PLAYLIST" -> api.deletePlaylistSubmission(id)
            "VIDEO"    -> api.deleteVideoSubmission(id)
            else       -> error("Unknown submission type: $type")
        }
        when {
            resp.isSuccessful -> Unit
            resp.code() == 403 -> error("Not your submission")
            resp.code() == 409 -> throw AlreadyReviewedError()
            else -> error("HTTP ${resp.code()}")
        }
    }

    private suspend inline fun submit(crossinline call: suspend () -> retrofit2.Response<*>): Result<Unit> {
        return runCatching {
            val resp = call()
            when {
                resp.isSuccessful -> Unit
                resp.code() == 429 -> throw RateLimitError(resp.headers()["Retry-After"]?.toLongOrNull() ?: 0L)
                resp.code() == 409 -> error("Already exists")
                else -> error("HTTP ${resp.code()}")
            }
        }
    }
}
