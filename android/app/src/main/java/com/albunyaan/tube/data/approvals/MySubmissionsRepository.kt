package com.albunyaan.tube.data.approvals

import com.albunyaan.tube.data.approvals.dto.*
import javax.inject.Inject
import javax.inject.Singleton

class RateLimitError(val retryAfterSeconds: Long) : RuntimeException("rate-limited")

@Singleton
class MySubmissionsRepository @Inject constructor(
    private val api: ApprovalApi,
) {
    suspend fun fetchMySubmissions(status: String? = null): Result<List<PendingApprovalDto>> = runCatching {
        val resp = api.mySubmissions(status = status, cursor = null, limit = 100)
        if (!resp.isSuccessful) error("HTTP ${resp.code()}")
        resp.body()?.items ?: emptyList()
    }

    suspend fun submitChannel(youtubeId: String, categoryIds: List<String>): Result<Unit> = submit {
        api.submitChannel(SubmitChannelRequest(youtubeId, categoryIds))
    }

    suspend fun submitPlaylist(youtubeId: String, categoryIds: List<String>): Result<Unit> = submit {
        api.submitPlaylist(SubmitPlaylistRequest(youtubeId, categoryIds))
    }

    suspend fun submitVideo(youtubeId: String, categoryIds: List<String>): Result<Unit> = submit {
        api.submitVideo(SubmitVideoRequest(youtubeId, categoryIds))
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
